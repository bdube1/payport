package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payment options in the store (owner, 31 Oct 2026): the methods the merchant accepts, as Payport Payments says (kept
 * here so the store still shows them when the PSP is briefly down), plus pay on delivery when the store allows it.
 */
@Service
public class Options {

    private static final Logger log = LoggerFactory.getLogger(Options.class);

    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("WALLET", "Payport Wallet");
        LABELS.put("ECOCASH", "EcoCash");
        LABELS.put("ONEMONEY", "OneMoney");
        LABELS.put("OMARI", "Omari");
        LABELS.put("INNBUCKS", "InnBucks");
        LABELS.put("ZIMSWITCH", "Card");
    }

    private final JdbcTemplate jdbc;
    private final Psp psp;

    public Options(JdbcTemplate jdbc, Psp psp) {
        this.jdbc = jdbc;
        this.psp = psp;
    }

    /** From Payport Payments' answer: [{code, enabled}, ...]. */
    public void remember(long merchantId, JsonNode methods) {
        if (methods == null || !methods.isArray()) return;
        List<String> on = new ArrayList<>();
        for (JsonNode m : methods) if (m.path("enabled").asBoolean(false) && LABELS.containsKey(m.path("code").asText())) on.add(m.path("code").asText());
        jdbc.update("UPDATE merchants SET methods = ?, methods_at = ? WHERE merchant_id = ?", String.join(",", on), Shop.now(), merchantId);
    }

    /** Accepted method codes; empty when not known yet (the customer then chooses on the payment page). */
    public List<String> accepted(long merchantId) {
        List<String> r = jdbc.queryForList("SELECT methods FROM merchants WHERE merchant_id = ?", String.class, merchantId);
        if (r.isEmpty() || r.get(0) == null || r.get(0).isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String c : r.get(0).split(",")) if (LABELS.containsKey(c)) out.add(c);
        return out;
    }

    public List<Map<String, Object>> choices(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : accepted(merchantId)) out.add(Map.of("code", c, "name", LABELS.get(c)));
        return out;
    }

    /** Asks Payport Payments again (methods are switched on and off in the ops portal; read every 2 minutes). */
    public List<String> refresh(long merchantId) {
        try {
            remember(merchantId, psp.merchant(merchantId).path("methods"));
        } catch (RuntimeException e) {
            log.info("[COMMERCE] payment methods for merchant {} not refreshed: {}", merchantId, e.getMessage());
        }
        return accepted(merchantId);
    }

    @Scheduled(fixedDelayString = "${commerce.methods-ms:120000}", initialDelay = 30_000)
    public void refreshAll() {
        for (Long id : jdbc.queryForList("SELECT merchant_id FROM merchants WHERE psp_status = 'ACTIVE'", Long.class)) refresh(id);
    }
}
