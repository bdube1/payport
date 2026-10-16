package zw.co.payport.gateway.iso20022;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Small DOM helpers for schema-validated ISO 20022 documents (namespace-aware, by local name). */
final class Xml {

    private Xml() {}

    static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && name.equals(c.getLocalName())) out.add(c);
        }
        return out;
    }

    static Element child(Element e, String name) {
        List<Element> c = children(e, name);
        return c.isEmpty() ? null : c.get(0);
    }

    /** Follows a path of local names, e.g. path(ntry, "NtryDtls", "TxDtls", "Refs", "EndToEndId"). */
    static Element path(Element e, String... names) {
        Element cur = e;
        for (String n : names) {
            cur = child(cur, n);
            if (cur == null) return null;
        }
        return cur;
    }

    static String text(Element e, String... names) {
        Element t = path(e, names);
        return t == null ? null : t.getTextContent().trim();
    }

    /** Decimal amount in major units to minor units; refuses more decimals than the currency has. */
    static long minor(String amount, int minorUnits) {
        return new BigDecimal(amount.trim()).movePointRight(minorUnits).longValueExact();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> { if (c >= 0x20 || c == '\n' || c == '\t') b.append(c); }
            }
        }
        return b.toString();
    }

    /** ISO 20022 Max&lt;n&gt;Text: trimmed, never longer than n. */
    static String max(String s, int n) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= n ? t : t.substring(0, n);
    }
}
