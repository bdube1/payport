package zw.co.payport.gateway.psp.channel;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EMVCo Merchant-Presented QR (MPM) payloads. Pure and unit-tested.
 *
 * Builds and parses the TLV string a merchant displays (sticker = static,
 * till/POS screen = dynamic with amount). PayPort's own merchant account
 * information goes in template 26 with GUID "zw.co.payport". If ZimSwitch
 * or RBZ publish a national QR standard (template id / GUID), add it as a
 * second merchant account template so other wallets can pay the same code.
 */
public final class EmvQr {

    public static final String GUID = "zw.co.payport";

    public record Payload(boolean dynamic, String merchantRef, String mcc, String currency, Long amountMinor,
                          String merchantName, String city, String reference) {}

    private EmvQr() {}

    public static String build(Payload p) {
        StringBuilder sb = new StringBuilder();
        tlv(sb, "00", "01");
        tlv(sb, "01", p.dynamic() ? "12" : "11");
        StringBuilder mai = new StringBuilder();
        tlv(mai, "00", GUID);
        tlv(mai, "01", p.merchantRef());
        tlv(sb, "26", mai.toString());
        tlv(sb, "52", p.mcc() == null ? "0000" : p.mcc());
        tlv(sb, "53", numericCurrency(p.currency()));
        if (p.amountMinor() != null && p.amountMinor() > 0) tlv(sb, "54", amount(p.amountMinor()));
        tlv(sb, "58", "ZW");
        tlv(sb, "59", clip(ascii(p.merchantName()), 25));
        tlv(sb, "60", clip(ascii(p.city() == null ? "HARARE" : p.city()), 15));
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
        if (!GUID.equals(mai.get("00"))) throw new IllegalArgumentException("Not a PayPort merchant QR");
        String ref = top.containsKey("62") ? tlvs(top.get("62")).get("05") : null;
        String amt = top.get("54");
        Long minor = amt == null ? null : new java.math.BigDecimal(amt).movePointRight(2).longValueExact();
        return new Payload("12".equals(top.get("01")), mai.get("01"), top.get("52"), alphaCurrency(top.get("53")), minor,
                top.get("59"), top.get("60"), ref);
    }

    static Map<String, String> tlvs(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (i + 4 > s.length()) throw new IllegalArgumentException("Truncated TLV");
            String tag = s.substring(i, i + 2);
            int len = Integer.parseInt(s.substring(i + 2, i + 4));
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

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), as required by EMVCo. */
    public static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }

    static String numericCurrency(String c) {
        return switch (c == null ? "USD" : c.toUpperCase()) {
            case "USD" -> "840";
            case "ZWG", "ZIG" -> "924";
            default -> throw new IllegalArgumentException("Unsupported QR currency " + c);
        };
    }

    static String alphaCurrency(String n) {
        return switch (n == null ? "" : n) {
            case "840" -> "USD";
            case "924" -> "ZWG";
            default -> throw new IllegalArgumentException("Unsupported QR currency code " + n);
        };
    }

    static String amount(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    private static String ascii(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("[^\\x20-\\x7E]", "");
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
