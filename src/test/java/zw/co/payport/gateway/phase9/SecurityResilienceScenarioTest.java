package zw.co.payport.gateway.phase9;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.config.JwtService;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.wallets.WalletPaymentService;
import zw.co.payport.gateway.wallets.WalletService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9: security, scale and resilience evidence on MySQL 8.
 *  - authorization matrix: every admin route refuses anonymous callers and customers; every merchant
 *    route refuses customers (generated from the running application's route table, so a new
 *    route without a guard fails the build);
 *  - security headers on API responses; Prometheus scrape only with the token; liveness/readiness;
 *  - concurrency: a wallet with money for 10 transfers receives 30 at once, exactly 10 succeed and it
 *    never goes negative; one payment confirmed by 10 simultaneous wallet payments is taken once;
 *  - latency: p95 of an authenticated read under 8 concurrent clients, reported and bounded.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityResilienceScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static final String METRICS_TOKEN = "scrape-token-for-tests";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "phase9-test-secret-phase9-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
        r.add("payport.ussd.secret", () -> "ussd-test-secret");
        r.add("payport.metrics.token", () -> METRICS_TOKEN);
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Autowired MarketRegistry markets;
    @Autowired WalletService wallets;
    @Autowired WalletPaymentService walletPayments;
    @Autowired LedgerClient ledger;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Value("${local.server.port}") int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    static final String PIN = "1357";
    static final String PIN_HASH = new BCryptPasswordEncoder().encode(PIN);

    record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    Res call(String method, String path, String bearer, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode n;
        try { n = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()); } catch (Exception e) { n = JSON.createObjectNode(); }
        return new Res(r.statusCode(), n, r);
    }

    String mobile() {
        return String.format("+26378%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    String user(String role, boolean pin) {
        String phone = mobile();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) VALUES (?, ?, ?, ?, TRUE, TRUE, ?, FALSE)",
                role + " user", phone, pin ? PIN_HASH : "x", role, "CUSTOMER".equals(role) ? null : role.toLowerCase() + SEQ.incrementAndGet() + "@p9.test");
        return phone;
    }

    String token(String phone) {
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    // ─── authorization matrix ───────────────────────────────────────────────

    record Route(String method, String path, String query, String body, boolean multipart, String guard) {}

    static String sample(Class<?> t) {
        if (t == java.time.LocalDate.class) return "2026-10-16";
        if (t == java.time.LocalDateTime.class) return "2026-10-16T10:00:00";
        if (t == boolean.class || t == Boolean.class) return "true";
        if (t.isEnum() && t.getEnumConstants().length > 0) return ((Enum<?>) t.getEnumConstants()[0]).name();
        return "1";
    }

    List<Route> routes(String prefix) {
        List<Route> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod h = e.getValue();
            StringBuilder q = new StringBuilder();
            String body = null;
            Map<String, String> pathValues = new java.util.HashMap<>();
            for (MethodParameter p : h.getMethodParameters()) {
                p.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
                RequestParam rp = p.getParameterAnnotation(RequestParam.class);
                if (rp != null && rp.required() && ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())) {
                    String name = !rp.name().isEmpty() ? rp.name() : !rp.value().isEmpty() ? rp.value() : p.getParameterName();
                    if (name != null) q.append(q.isEmpty() ? "?" : "&").append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append("=").append(sample(p.getParameterType()));
                }
                PathVariable pv = p.getParameterAnnotation(PathVariable.class);
                if (pv != null) {
                    String name = !pv.name().isEmpty() ? pv.name() : !pv.value().isEmpty() ? pv.value() : p.getParameterName();
                    if (name != null) pathValues.put(name, sample(p.getParameterType()));
                }
                if (p.hasParameterAnnotation(RequestBody.class)) {
                    Class<?> t = p.getParameterType();
                    body = java.util.Collection.class.isAssignableFrom(t) || t.isArray() ? "[]" : "{}";
                }
            }
            PreAuthorize pa = h.getMethodAnnotation(PreAuthorize.class);
            if (pa == null) pa = h.getBeanType().getAnnotation(PreAuthorize.class);
            boolean multipart = info.getConsumesCondition().getConsumableMediaTypes().stream().anyMatch(m -> "multipart".equals(m.getType()));
            List<String> methods = new ArrayList<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) methods.add("GET");
            for (String path : info.getPatternValues()) {
                if (!path.startsWith(prefix)) continue;
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\{([^}:]+)(:[^}]*)?}").matcher(path);
                StringBuilder concrete = new StringBuilder();
                while (mm.find()) mm.appendReplacement(concrete, pathValues.getOrDefault(mm.group(1), "1"));
                mm.appendTail(concrete);
                for (String m : methods) out.add(new Route(m, concrete.toString(), q.toString(), body, multipart, pa == null ? null : pa.value()));
            }
        }
        return out;
    }

    /** The handler ran (or would have): anything but a refusal or a request the framework rejected before the method. */
    static boolean reached(int status) {
        return !(status == 400 || status == 401 || status == 403 || status == 405 || status == 415);
    }

    @Test
    void authorizationMatrix_adminRoutesRefuseAnonymousAndCustomers_merchantRoutesRefuseCustomers() throws Exception {
        String customer = token(user("CUSTOMER", true));
        List<Route> admin = routes("/api/v1/admin/");
        assertTrue(admin.size() > 100, "admin routes found: " + admin.size());
        List<String> holes = new ArrayList<>();
        for (Route r : admin) {
            // Statically: a role guard that does not admit customers.
            if (r.guard() == null || !r.guard().contains("Role") || r.guard().contains("CUSTOMER") || r.guard().contains("permitAll")
                    || r.guard().contains("isAuthenticated")) {
                holes.add("guard " + r.method() + " " + r.path() + " -> " + r.guard());
            }
            int anon = call(r.method(), r.path() + r.query(), null, r.body(), Map.of()).status();
            if (anon != 401 && anon != 403) holes.add("anonymous " + r.method() + " " + r.path() + " -> " + anon);
            if (r.multipart()) continue;     // content negotiation answers before authorisation; the checks above still apply
            int cust = call(r.method(), r.path() + r.query(), customer, r.body(), Map.of()).status();
            if (reached(cust)) holes.add("customer " + r.method() + " " + r.path() + " -> " + cust);
        }
        for (Route r : routes("/api/v1/merchant/")) {
            if (r.multipart()) continue;
            int cust = call(r.method(), r.path() + r.query(), customer, r.body(), Map.of()).status();
            if (reached(cust)) holes.add("customer on merchant " + r.method() + " " + r.path() + " -> " + cust);
        }
        assertTrue(holes.isEmpty(), "authorization holes:\n" + String.join("\n", new TreeSet<>(holes)));
    }

    // ─── headers, metrics, probes ───────────────────────────────────────────

    @Test
    void securityHeaders_metricsTokenProtected_probesUp() throws Exception {
        HttpResponse<String> r = call("GET", "/api/v1/products", null, null, Map.of()).raw();
        var h = r.headers();
        assertTrue(h.firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"), h.map().toString());
        assertEquals("no-referrer", h.firstValue("Referrer-Policy").orElse(""));
        assertEquals("nosniff", h.firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", h.firstValue("X-Frame-Options").orElse(""));
        assertTrue(h.firstValue("Permissions-Policy").orElse("").contains("camera=()"));

        assertEquals(401, call("GET", "/actuator/prometheus", null, null, Map.of()).status());
        assertEquals(401, call("GET", "/actuator/prometheus", "wrong", null, Map.of()).status());
        Res scrape = call("GET", "/actuator/prometheus", METRICS_TOKEN, null, Map.of("Accept", "text/plain"));
        assertEquals(200, scrape.status());
        assertTrue(scrape.raw().body().contains("http_server_requests"), "request metrics exported");

        assertEquals(200, call("GET", "/actuator/health/liveness", null, null, Map.of()).status());
        assertEquals(200, call("GET", "/actuator/health/readiness", null, null, Map.of()).status());
    }

    // ─── concurrency ─────────────────────────────────────────────────────────

    void walletsOn() {
        // Test setup only (the portal path is covered in Phase8CompletionScenarioTest): licence answer recorded directly.
        jdbc.update("UPDATE service_lines SET licence_status = 'NOT_REQUIRED', decided_by = 'test / test', decided_at = NOW(6), enabled = TRUE WHERE code = 'WALLETS'");
    }

    @Test
    void walletUnderConcurrentSpends_neverOverdrawn_andAPaymentIsTakenOnce() throws Exception {
        walletsOn();
        String ann = user("CUSTOMER", true), ben = user("CUSTOMER", true);
        long annId = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, ann);
        long benId = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, ben);
        WalletService.Wallet a = wallets.open(annId, ann, "USD", "test");
        wallets.open(benId, ben, "USD", "test");
        wallets.creditTopUp("PP-WEB-20261016-" + String.format("%06d", SEQ.incrementAndGet() % 1_000_000), a.ref(), "USD", 1_000, "ECOCASH", "test");

        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), refused = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    wallets.transfer(annId, ben, "USD", 100, null, "test");
                    ok.incrementAndGet();
                } catch (RuntimeException e) {
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        assertEquals(10, ok.get(), "money for exactly 10 transfers");
        assertEquals(20, refused.get());
        assertEquals(0, wallets.balance(a), "never negative");
        assertEquals(1_000, wallets.balance(wallets.forUser(benId, "USD").orElseThrow()));

        // One payment, ten simultaneous confirmations: taken once.
        wallets.creditTopUp("PP-WEB-20261016-" + String.format("%06d", SEQ.incrementAndGet() % 1_000_000), a.ref(), "USD", 2_000, "ECOCASH", "test");
        jdbc.update("UPDATE payment_methods SET is_active = TRUE WHERE code = 'WALLET'");
        String ref = String.format("PP-WEB-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        String init = JSON.writeValueAsString(Map.of("reference", ref, "productTypeCode", "AIRTIME_ECONET", "recipientIdentifier", "0771234567",
                "amountCents", 500, "currency", "USD", "customerPhone", ann, "channel", "WEB"));
        assertEquals(200, call("POST", "/api/v1/transactions/initiate", null, init, Map.of()).status());
        CountDownLatch go2 = new CountDownLatch(1);
        List<Future<?>> ps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ps.add(pool.submit(() -> {
                go2.await();
                try { walletPayments.payTransaction(ann, ref, PIN); } catch (Exception ignored) { }
                return null;
            }));
        }
        go2.countDown();
        for (Future<?> f : ps) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wallet_operations WHERE kind = 'PAYMENT' AND reference = ?", Integer.class, ref));
        assertEquals(1_500, wallets.balance(a), "taken once");
        assertEquals("PAYMENT_CONFIRMED", jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, ref));
        assertTrue(ledger.integrityReport().isEmpty(), ledger.integrityReport().toString());
    }

    @Test
    void latency_p95OfAnAuthenticatedRead_under8ConcurrentClients() throws Exception {
        walletsOn();
        String phone = user("CUSTOMER", true);
        String t = token(phone);
        long uid = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        wallets.open(uid, phone, "USD", "test");
        for (int i = 0; i < 20; i++) call("GET", "/api/v1/wallet", t, null, Map.of());   // warm-up
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            fs.add(pool.submit(() -> {
                long s = System.nanoTime();
                int st = call("GET", "/api/v1/wallet", t, null, Map.of()).status();
                times.add((System.nanoTime() - s) / 1_000_000);
                assertEquals(200, st);
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2), p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1), p99 = sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1);
        System.out.println("::notice title=latency::GET /api/v1/wallet, 400 requests, 8 clients: p50=" + p50 + "ms p95=" + p95 + "ms p99=" + p99 + "ms");
        assertTrue(p95 < 1_500, "p95 " + p95 + "ms (CI runner bound; the production SLO is in docs/phase9/SLO.md)");
    }
}
