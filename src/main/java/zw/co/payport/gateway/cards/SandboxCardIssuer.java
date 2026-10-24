package zw.co.payport.gateway.cards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Test cards for staging and sandbox deployments (never production): a card is issued at once with a test
 * number derived from its reference, so the card screens, details and block/unblock work end to end before a
 * VISA or ZimSwitch issuing contract is connected. Nothing is stored except the masked number.
 */
public class SandboxCardIssuer implements CardIssuer {

    private final String scheme;

    public SandboxCardIssuer(String scheme) {
        this.scheme = scheme;
    }

    public String scheme() { return scheme; }

    public boolean connected() { return true; }

    static String digits(String seed, int n) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("payport-sandbox-card:" + seed).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; b.length() < n; i++) b.append(Math.floorMod(h[i % h.length] + i * 7, 10));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static int luhn(String partial) {
        int sum = 0;
        boolean dbl = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int d = partial.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    String pan(String ref) {
        String prefix = "ZIMSWITCH".equals(scheme) ? "627000" : "400000";
        String body = prefix + digits(ref, 9);
        return body + luhn(body);
    }

    static String expiry(Map<String, Object> card) {
        Object created = card.get("created_at");
        LocalDate d = created instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate()
                : created instanceof java.time.LocalDateTime l ? l.toLocalDate() : LocalDate.now();
        d = d.plusYears(3);
        return String.format("%02d / %02d", d.getMonthValue(), d.getYear() % 100);
    }

    Issued view(Map<String, Object> card, String status) {
        String ref = (String) card.get("card_ref");
        String pan = pan(ref);
        String last4 = pan.substring(pan.length() - 4);
        return new Issued("SBX-" + ref, status, pan.substring(0, 6) + "••••••" + last4, last4, expiry(card), "Test card");
    }

    public Issued issue(Map<String, Object> card) {
        return view(card, "ACTIVE");
    }

    public Issued setStatus(Map<String, Object> card, String status) {
        return view(card, status);
    }

    public Optional<Issued> fetch(Map<String, Object> card) {
        return Optional.of(view(card, (String) card.get("status")));
    }

    @Override
    public Sensitive reveal(Map<String, Object> card) {
        String ref = (String) card.get("card_ref");
        return new Sensitive(pan(ref), expiry(card), digits(ref + ":cvv", 3));
    }
}
