package zw.co.payport.gateway.products;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EMVCo Merchant-Presented QR (MPM) payloads, harvested from psp-foundation (Phase 8). Pure.
 *
 * Template 26 carries Payport's merchant account information (GUID "zw.co.payport" + the
 * checkout session reference). Country (tag 58) and the ISO 4217 numeric currency (tag 53) come
 * from the market configuration, never from code. If the national switch publishes a QR scheme
 * (template id / GUID), it is added as a second merchant account template so other wallets can pay
 * the same code. CRC: CRC-16/CCITT-FALSE as EMVCo requires.
 */
public final class EmvQr {

    public static final String GUID = "zw.co.payport";

    public record Payload(boolean dynamic, String merchantRef, String mcc, String currencyNumeric, Long amountMinor,
                          String countryCode, String merchantName, String city, String reference) {}

    private EmvQr() {}

    public static String build(Payload p) {
        StringBuilder sb = new StringBuilder();
        tlv(sb, "00", "01");
        tlv(sb, "01", p.dynamic() ? "12" : "11");
        StringBuilder mai = new StringBuilder();
        tlv(mai, "00", GUID);
        tlv(mai, "01", clip(p.merchantRef(), 60));
        tlv(sb, "26", mai.toString());
        tlv(sb, "52", p.mcc() == null || !p.mcc().matches("[0-9]{4}") ? "0000" : p.mcc());
        if (p.currencyNumeric() == null || !p.currencyNumeric().matches("[0-9]{3}")) throw new IllegalArgumentException("ISO numeric currency code required");
        tlv(sb, "53", p.currencyNumeric());
        if (p.amountMinor() != null && p.amountMinor() > 0) tlv(sb, "54", amount(p.amountMinor()));
        if (p.countryCode() == null || !p.countryCode().matches("[A-Z]{2}")) throw new IllegalArgumentException("country code required");
        tlv(sb, "58", p.countryCode());
        tlv(sb, "59", clip(ascii(p.merchantName()), 25));
        tlv(sb, "60", clip(ascii(p.city() == null || p.city().isBlank() ? "-" : p.city()), 15));
        if (p.reference() != null && !p.reference().isBlank()) {
            StringBuilder add = new StringBuilder();
            tlv(add, "05", clip(p.reference(), 25));
            tlv(sb, "62", add.toString());
        }
        sb.append("6304");
        sb.append(crc16(sb.toString()));
        return sb.toString();
    }

    /** Parses and validates (CRC, mandatory fields). Throws IllegalArgumentException on anything malformed. */
    public static Payload parse(String qr) {
        if (qr == null || qr.length() < 20) throw new IllegalArgumentException("Not a QR payload");
        String body = qr.substring(0, qr.length() - 4);
        if (!body.endsWith("6304")) throw new IllegalArgumentException("Missing CRC field");
        if (!crc16(body).equalsIgnoreCase(qr.substring(qr.length() - 4))) throw new IllegalArgumentException("QR checksum does not match");
        Map<String, String> top = tlvs(body.substring(0, body.length() - 4));
        if (!"01".equals(top.get("00"))) throw new IllegalArgumentException("Unsupported payload format");
        Map<String, String> mai = tlvs(top.getOrDefault("26", ""));
        if (!GUID.equals(mai.get("00"))) throw new IllegalArgumentException("Not a Payport merchant QR");
        String ref = top.containsKey("62") ? tlvs(top.get("62")).get("05") : null;
        String amt = top.get("54");
        Long minor = amt == null ? null : new java.math.BigDecimal(amt).movePointRight(2).longValueExact();
        return new Payload("12".equals(top.get("01")), mai.get("01"), top.get("52"), top.get("53"), minor, top.get("58"), top.get("59"), top.get("60"), ref);
    }

    static Map<String, String> tlvs(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (i + 4 > s.length()) throw new IllegalArgumentException("Truncated TLV");
            String tag = s.substring(i, i + 2);
            int len;
            try { len = Integer.parseInt(s.substring(i + 2, i + 4)); } catch (NumberFormatException e) { throw new IllegalArgumentException("Bad TLV length at tag " + tag); }
            if (i + 4 + len > s.length()) throw new IllegalArgumentException("TLV length overflow at tag " + tag);
            m.put(tag, s.substring(i + 4, i + 4 + len));
            i += 4 + len;
        }
        return m;
    }

    private static void tlv(StringBuilder sb, String tag, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("Field " + tag + " too long");
        sb.append(tag).append(String.format("%02d", value.length())).append(value);
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). */
    public static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }

    static String amount(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    private static String ascii(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("[^\\x20-\\x7E]", "");
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
