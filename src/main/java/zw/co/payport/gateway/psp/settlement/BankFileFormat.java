package zw.co.payport.gateway.psp.settlement;

import java.util.List;

/**
 * Bulk payment file. Generic CSV that most Zimbabwean internet-banking
 * portals accept with a column mapping; replace with the exact layout of
 * your trust bank (CBZ, Stanbic, FBC, Steward...) once confirmed.
 */
public final class BankFileFormat {

    public static final String[] HEADER = {"Reference", "Beneficiary Name", "Bank", "Bank Code", "Branch Code",
            "Account / Wallet", "Amount", "Currency", "Narrative"};

    private BankFileFormat() {}

    public static String csv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(escape(r[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    public static String amount(long minor) {
        return (minor / 100) + "." + String.format("%02d", Math.abs(minor % 100));
    }

    /** RFC 4180 quoting, plus neutralising spreadsheet formula injection. */
    static String escape(String v) {
        if (v == null || "null".equals(v)) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
