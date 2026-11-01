package zw.co.payport.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeping the store clean (owner, 1 Nov 2026). Pornographic or sexual material, and other prohibited material, is not
 * allowed anywhere in a store. When it is found in a product, the store's details or a picture, the item is taken
 * down, the store is disabled (only Payport can switch it back on) and Payport customer care gets a report.
 *
 * Words are always checked here (a list of clear terms). With the AI key set, the language model also checks wording
 * and every picture uploaded; without it, pictures are not checked automatically and stores can still be disabled by
 * hand in the ops portal. Reviews with offensive language are refused before they are published.
 */
@Service
public class Moderation {

    private static final Logger log = LoggerFactory.getLogger(Moderation.class);

    /** Clear terms for prohibited material (sexual content, and other material Payport does not allow). */
    static final List<String> PROHIBITED = List.of(
            "porn", "porno", "pornographic", "xxx", "nude", "nudes", "sex video", "sex tape", "sextape", "erotic", "escort", "onlyfans",
            "adult content", "adult video", "explicit video", "sexual services", "hookup", "strip club", "camgirl", "sex toy", "sex toys", "dildo", "vibrator",
            "child abuse", "cocaine", "heroin", "crystal meth", "mbanje", "dagga", "marijuana for sale", "guns for sale", "firearm", "ammunition", "fake id", "counterfeit money");

    /** Offensive words a review or chat message may not contain. */
    static final List<String> OFFENSIVE = List.of(
            "fuck", "fucking", "fucker", "shit", "bullshit", "bitch", "bastard", "asshole", "dick", "pussy", "cunt", "whore", "slut", "nigger", "idiot",
            "moron", "mhata", "benzi", "pfutseki", "voetsek");

    private final JdbcTemplate jdbc;
    private final Ai ai;
    private final Psp psp;

    public Moderation(JdbcTemplate jdbc, Ai ai, Psp psp) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.psp = psp;
    }

    static boolean hasWord(String text, List<String> words) {
        if (text == null || text.isBlank()) return false;
        String low = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ") + " ";
        for (String w : words) if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(low).find()) return true;
        return false;
    }

    /** Why this wording is not allowed in a store, or null. */
    public String prohibitedText(String text) {
        if (hasWord(text, PROHIBITED)) return "Prohibited material in the wording";
        if (ai.textIs(text, "Is this text from an online shop listing about pornography, sexual content or services, illegal drugs, weapons or other illegal goods?"))
            return "Prohibited material in the wording (AI check)";
        return null;
    }

    /** Why this picture is not allowed, or null (only checked when the AI key is set). */
    public String prohibitedPicture(byte[] bytes, String contentType) {
        if (ai.pictureIs(bytes, contentType, "Does this picture show nudity, sexual or pornographic content, or graphic violence or gore?"))
            return "Prohibited picture (AI check)";
        return null;
    }

    /** Why a review cannot be published, or null. */
    public String offensiveReview(String text) {
        if (hasWord(text, OFFENSIVE) || hasWord(text, PROHIBITED)) return "Please remove offensive language from your review.";
        if (ai.textIs(text, "Does this customer review contain swearing, insults, hate speech, sexual content or threats?"))
            return "Please remove offensive language from your review.";
        return null;
    }

    /**
     * Prohibited material found: the product (if any) is hidden, the store is disabled until Payport switches it back
     * on, and Payport customer care gets a report.
     */
    public void takeDown(long merchantId, Long productId, String kind, String reason) {
        if (productId != null) jdbc.update("UPDATE products SET status = 'HIDDEN' WHERE id = ? AND merchant_id = ?", productId, merchantId);
        List<Map<String, Object>> st = jdbc.queryForList("SELECT * FROM stores WHERE merchant_id = ?", merchantId);
        Long storeId = st.isEmpty() ? null : ((Number) st.get(0).get("id")).longValue();
        if (storeId != null) jdbc.update("UPDATE stores SET suspended_reason = ?, suspended_by = 'automatic check', suspended_at = ? WHERE id = ? AND suspended_reason IS NULL",
                Shop.clip(reason, 200), Shop.now(), storeId);
        jdbc.update("INSERT INTO moderation_events (merchant_id, store_id, product_id, kind, reason, action, created_at) VALUES (?,?,?,?,?,?,?)",
                merchantId, storeId, productId, kind, Shop.clip(reason, 200), productId != null ? "PRODUCT_HIDDEN_STORE_DISABLED" : "STORE_DISABLED", Shop.now());
        log.warn("[COMMERCE] prohibited material from merchant {} ({}): store disabled", merchantId, reason);
        if (st.isEmpty()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("about", "SELLER");
        body.put("reason", "Prohibited material (automatic check): " + reason);
        body.put("store", st.get(0).get("name"));
        body.put("storeSlug", st.get(0).get("slug"));
        body.put("details", "The store was disabled automatically" + (productId == null ? "." : "; product " + productId + " was hidden."));
        try {
            psp.report(body);
        } catch (RuntimeException e) {
            log.info("[COMMERCE] moderation report not sent: {}", e.getMessage());
        }
    }
}
