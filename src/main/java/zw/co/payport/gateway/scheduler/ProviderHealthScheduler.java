package zw.co.payport.gateway.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.entity.ProviderHealth;
import zw.co.payport.gateway.platform.HealthLookups;
import zw.co.payport.gateway.repository.ProviderHealthRepository;
import zw.co.payport.gateway.service.adaptor.econet.EconetAirtimeAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsClient;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsResponse;
import zw.co.payport.gateway.service.adaptor.esolutions.bulawayo.BulawayoBillAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.moonlight.MoonlightAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.nyaradzo.NyaradzoAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.zesa.ZesaTokenAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.zol.ZolPaymentAdaptor;
import zw.co.payport.gateway.service.adaptor.onemoney.OneMoneyAdaptor;
import zw.co.payport.gateway.service.adaptor.telecel.TelecelAirtimeAdaptor;
import zw.co.payport.gateway.service.adaptor.telecel.TelecelSoapClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Provider health, checked every minute by sending each provider a real request (owner, 2026-10-25):
 * <ul>
 *   <li><b>UP</b> (Operational): the provider answered.</li>
 *   <li><b>DOWN</b>: no answer (connection refused, timed out, or the provider's gateway returned an error page).</li>
 *   <li><b>DEGRADED</b>: an account balance or account lookup was answered, but with an error instead of the balance or
 *       the account (the provider is there, the service is not working).</li>
 * </ul>
 * What each provider is asked:
 * EcoCash and OneMoney - a request with a test reference that they reject (a rejection is an answer);
 * NetOne, Econet, Telecel and eSolutions - our own account balance enquiry;
 * ZESA, City of Bulawayo, Nyaradzo, Moonlight and ZOL - a lookup of the account set in Provider Tools.
 */
@Slf4j
@Component
public class ProviderHealthScheduler {

    public static final String UP = "UP", DOWN = "DOWN", DEGRADED = "DEGRADED", NOT_CONFIGURED = "NOT_CONFIGURED";
    private static final long CHECK_LIMIT_SECONDS = 60;

    private final ProviderHealthRepository healthRepository;
    private final RestTemplate restTemplate;
    private final HealthLookups lookups;
    private final ObjectProvider<OneMoneyAdaptor> oneMoney;
    private final ObjectProvider<EconetAirtimeAdaptor> econet;
    private final ObjectProvider<TelecelAirtimeAdaptor> telecel;
    private final ObjectProvider<ESolutionsClient> esolutions;
    private final ObjectProvider<ZesaTokenAdaptor> zesa;
    private final ObjectProvider<BulawayoBillAdaptor> bulawayo;
    private final ObjectProvider<NyaradzoAdaptor> nyaradzo;
    private final ObjectProvider<MoonlightAdaptor> moonlight;
    private final ObjectProvider<ZolPaymentAdaptor> zol;
    private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "provider-health");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, String> lastStatus = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${payport.netone.base-url:}")
    private String netoneBaseUrl;
    @Value("${payport.netone.access-code:}")
    private String netoneAccessCode;
    @Value("${payport.netone.access-password:}")
    private String netoneAccessPassword;
    @Value("${payport.ecocash.base-url:}")
    private String ecocashBaseUrl;
    @Value("${payport.ecocash.username:}")
    private String ecocashUsername;
    @Value("${payport.ecocash.password:}")
    private String ecocashPassword;
    @Value("${payport.econet.company-msisdn:}")
    private String econetCompanyMsisdn;
    @Value("${payport.onemoney.base-url:}")
    private String onemoneyBaseUrl;
    @Value("${payport.telecel.agent-code:}")
    private String telecelAgentCode;

    public ProviderHealthScheduler(ProviderHealthRepository healthRepository, RestTemplate restTemplate, HealthLookups lookups,
                                   ObjectProvider<OneMoneyAdaptor> oneMoney, ObjectProvider<EconetAirtimeAdaptor> econet,
                                   ObjectProvider<TelecelAirtimeAdaptor> telecel, ObjectProvider<ESolutionsClient> esolutions,
                                   ObjectProvider<ZesaTokenAdaptor> zesa, ObjectProvider<BulawayoBillAdaptor> bulawayo,
                                   ObjectProvider<NyaradzoAdaptor> nyaradzo, ObjectProvider<MoonlightAdaptor> moonlight,
                                   ObjectProvider<ZolPaymentAdaptor> zol) {
        this.healthRepository = healthRepository;
        this.restTemplate = restTemplate;
        this.lookups = lookups;
        this.oneMoney = oneMoney;
        this.econet = econet;
        this.telecel = telecel;
        this.esolutions = esolutions;
        this.zesa = zesa;
        this.bulawayo = bulawayo;
        this.nyaradzo = nyaradzo;
        this.moonlight = moonlight;
        this.zol = zol;
    }

    /** One check's answer. */
    public record Result(String status, String detail) {
        static Result up(String d) { return new Result(UP, d); }
        static Result down(String d) { return new Result(DOWN, d); }
        static Result degraded(String d) { return new Result(DEGRADED, d); }
        static Result notConfigured(String d) { return new Result(NOT_CONFIGURED, d); }
    }

    private record Check(String code, String type, java.util.concurrent.Callable<Result> run) {}

    private List<Check> checks() {
        List<Check> c = new ArrayList<>();
        c.add(new Check("ECOCASH", "PAYMENT", this::checkEcoCash));
        c.add(new Check("ONEMONEY", "PAYMENT", this::checkOneMoney));
        c.add(new Check("NETONE", "FULFILMENT", this::checkNetOne));
        c.add(new Check("ECONET", "FULFILMENT", this::checkEconet));
        c.add(new Check("TELECEL", "FULFILMENT", this::checkTelecel));
        c.add(new Check("ESOLUTIONS", "BILLER", this::checkESolutions));
        c.add(new Check("ZESA", "BILLER", () -> lookup("ZESA", n -> zesa.getObject().getCustomerInfo(n, "USD"))));
        c.add(new Check("BULAWAYO", "BILLER", () -> lookup("BULAWAYO", n -> bulawayo.getObject().getCustomerInfo(n, "USD"))));
        c.add(new Check("NYARADZO", "BILLER", () -> lookup("NYARADZO", n -> nyaradzo.getObject().getCustomerInfo(n, null))));
        c.add(new Check("MOONLIGHT", "BILLER", () -> lookup("MOONLIGHT", n -> moonlight.getObject().getCustomerInfo(n, null))));
        c.add(new Check("ZOL", "BILLER", () -> lookup("ZOL", n -> zol.getObject().getCustomerInfo(n))));
        return c;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void checkAllProviders() {
        runAll();
    }

    /** Runs every check now (side by side) and returns what each one found. */
    public synchronized Map<String, Result> runAll() {
        Map<String, CompletableFuture<Result>> running = new LinkedHashMap<>();
        for (Check c : checks()) running.put(c.code(), CompletableFuture.supplyAsync(() -> safely(c), pool));
        Map<String, Result> out = new LinkedHashMap<>();
        for (Check c : checks()) {
            Result r;
            try {
                r = running.get(c.code()).get(CHECK_LIMIT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                r = Result.down("No response within " + CHECK_LIMIT_SECONDS + "s");
            } catch (Exception e) {
                r = Result.down(message(e));
            }
            persist(c.code(), c.type(), r);
            out.put(c.code(), r);
        }
        return out;
    }

    private Result safely(Check c) {
        try {
            return c.run().call();
        } catch (Exception e) {
            return answered(e) ? Result.degraded("Answered with an error: " + message(e)) : Result.down("No response: " + message(e));
        }
    }

    // ─── payments: a request they reject is still an answer ──────────────────

    Result checkEcoCash() {
        if (blank(ecocashBaseUrl)) return Result.notConfigured("Not configured");
        String encoded = java.util.Base64.getEncoder().encodeToString((ecocashUsername + ":" + ecocashPassword).getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.set("Content-Type", "application/json");
        Map<String, Object> testPayload = new HashMap<>();
        String ref = "HEALTH-" + System.currentTimeMillis();
        testPayload.put("clientCorrelator", ref);
        testPayload.put("notifyUrl", "https://localhost/health");
        testPayload.put("referenceCode", ref);
        testPayload.put("tranType", "MER");
        testPayload.put("endUserId", "771111111");
        try {
            restTemplate.exchange(ecocashBaseUrl + "/transactions/amount", HttpMethod.POST, new HttpEntity<>(testPayload, headers), String.class);
            return Result.up("Responded");
        } catch (RestClientResponseException e) {
            return Result.up("Responded (HTTP " + e.getStatusCode().value() + " to the test request)");
        }
    }

    Result checkOneMoney() {
        if (blank(onemoneyBaseUrl)) return Result.notConfigured("Not configured");
        try {
            oneMoney.getObject().queryC2BTransaction("HEALTH" + System.currentTimeMillis());
            return Result.up("Responded");
        } catch (Exception e) {
            if (answered(e)) return Result.up("Responded (rejected the test request: " + message(e) + ")");
            return Result.down("No response: " + message(e));
        }
    }

    // ─── airtime and bills: our own account balance ──────────────────────────

    Result checkNetOne() {
        if (blank(netoneBaseUrl) || blank(netoneAccessCode) || blank(netoneAccessPassword)) return Result.notConfigured("Not configured");
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-access-code", netoneAccessCode);
        headers.set("x-access-password", netoneAccessPassword);
        headers.set("x-agent-reference", "HEALTH-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        headers.set("Accept", "application/json");
        try {
            restTemplate.exchange(netoneBaseUrl + "/wallet-balance", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            return Result.up("Balance enquiry answered");
        } catch (RestClientResponseException e) {
            return Result.degraded("Balance enquiry answered HTTP " + e.getStatusCode().value());
        }
    }

    Result checkEconet() throws Exception {
        if (blank(econetCompanyMsisdn)) return Result.notConfigured("Set ECONET_COMPANY_MSISDN");
        EconetAirtimeAdaptor.BalanceResult b = econet.getObject().getAccountBalance(econetCompanyMsisdn, "USD");
        if (b != null && b.getCompanyAccountAmount() != null) return Result.up("Balance enquiry answered");
        return Result.degraded("Balance enquiry answered without a balance");
    }

    Result checkTelecel() {
        if (blank(telecelAgentCode)) return Result.notConfigured("Not configured");
        TelecelSoapClient.TelecelBalanceResult b = telecel.getObject().getBalance("USD");
        if (b == null) return Result.down("No response");
        if (b.isSuccess()) return Result.up("Balance enquiry answered");
        // The SOAP client reports a failed call (no answer) as ERROR; any other code came from Telecel.
        if ("ERROR".equals(b.getResultCode())) return Result.down("No response: " + b.getResultDescription());
        return Result.degraded("Balance enquiry answered: " + b.getResultDescription());
    }

    Result checkESolutions() throws Exception {
        ESolutionsResponse r = esolutions.getObject().balanceEnquiry("USD");
        if (r == null) return Result.down("No response");
        return r.isSuccess() ? Result.up("Balance enquiry answered") : Result.degraded("Balance enquiry answered: " + r.getFailureMessage());
    }

    @FunctionalInterface
    interface Lookup { Object run(String number) throws Exception; }

    Result lookup(String provider, Lookup l) {
        String number = lookups.number(provider);
        if (blank(number)) return Result.notConfigured("No lookup number set in Provider Tools");
        try {
            Object info = l.run(number);
            return info == null ? Result.degraded("Lookup of " + number + " returned nothing") : Result.up("Lookup of " + number + " answered");
        } catch (Exception e) {
            return answered(e) ? Result.degraded("Lookup of " + number + " answered with an error: " + message(e))
                    : Result.down("No response to the lookup of " + number + ": " + message(e));
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * Whether the provider answered: false for connection failures and timeouts, and for an error page from the
     * provider's gateway (eSolutions reports those as "currently unavailable"); true for everything else.
     */
    static boolean answered(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException) return true;
            if (t instanceof java.io.IOException || t instanceof org.springframework.web.client.ResourceAccessException
                    || t instanceof java.util.concurrent.TimeoutException) return false;
            String m = t.getMessage();
            if (m != null && (m.contains("currently unavailable") || m.contains("Connection refused") || m.contains("timed out")
                    || m.contains("UnknownHost") || m.contains("No route to host"))) return false;
            if (t.getCause() == t) break;
        }
        return !(e instanceof NullPointerException || e instanceof org.springframework.beans.BeansException);
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 180 ? m.substring(0, 180) : m;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void persist(String code, String type, Result r) {
        String previous = lastStatus.put(code, r.status());
        if (!r.status().equals(previous)) {
            if (UP.equals(r.status())) log.info("[HEALTH] {} → UP", code);
            else log.warn("[HEALTH] {} → {} - {}", code, r.status(), r.detail());
        }
        try {
            ProviderHealth health = healthRepository.findById(code)
                    .orElseGet(() -> ProviderHealth.builder().providerCode(code).providerType(type).manualOverride(false).build());
            if (Boolean.TRUE.equals(health.getManualOverride())) return;
            health.setProviderType(type);
            health.setStatus(r.status());
            health.setDetail(r.detail() == null || r.detail().length() <= 250 ? r.detail() : r.detail().substring(0, 250));
            health.setLastCheckedAt(LocalDateTime.now());
            health.setSuccessRate10m(UP.equals(r.status()) ? BigDecimal.valueOf(100) : DOWN.equals(r.status()) ? BigDecimal.ZERO : null);
            healthRepository.save(health);
        } catch (Exception e) {
            log.error("Failed to persist health for {}: {}", code, e.getMessage());
        }
    }
}
