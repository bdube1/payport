package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * goAML-structured XML for an FIU report (the FIU's portal is goAML: RBZ Guideline 01-2025 s3.106).
 *
 * Element names follow the UNODC goAML report structure (report, rentity_id, rentity_branch,
 * submission_code, report_code, entity_reference, submission_date, currency_code_local,
 * reporting_person, reason, action, transaction, report_indicators). The FIU issues its goAML XSD to
 * registered reporting entities: validate against it during goAML onboarding and adjust the
 * template's field mapping if it differs. Every template field is also carried in an
 * <additional_information> block so nothing in the approved draft is lost.
 */
final class GoamlXml {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private GoamlXml() {}

    static String render(JsonNode template, Map<String, Object> f, String reportType, String rentityId, LocalDateTime now) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<report>\n");
        el(x, 1, "rentity_id", rentityId == null || rentityId.isBlank() ? "TO-BE-ISSUED-BY-FIU" : rentityId);
        el(x, 1, "rentity_branch", f.get("rentity_branch"));
        el(x, 1, "submission_code", template.path("goaml").path("submissionCode").asText("E"));
        el(x, 1, "report_code", template.path("goaml").path("reportCode").asText(reportType));
        el(x, 1, "entity_reference", f.get("entity_reference"));
        el(x, 1, "submission_date", now.format(TS));
        el(x, 1, "currency_code_local", "USD");
        x.append("  <reporting_person>\n");
        String name = String.valueOf(f.getOrDefault("officer_name", ""));
        int sp = name.lastIndexOf(' ');
        el(x, 2, "first_name", sp > 0 ? name.substring(0, sp) : name);
        el(x, 2, "last_name", sp > 0 ? name.substring(sp + 1) : "");
        el(x, 2, "email", f.get("officer_email"));
        el(x, 2, "occupation", f.get("officer_position"));
        x.append("  </reporting_person>\n");
        if ("STR".equals(reportType)) {
            el(x, 1, "reason", f.get("narrative"));
            el(x, 1, "action", f.get("action_taken"));
            Object txs = f.get("transactions");
            if (txs instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> t)) continue;
                    x.append("  <transaction>\n");
                    el(x, 2, "transactionnumber", t.get("reference"));
                    el(x, 2, "transaction_location", "Payport " + (t.get("channel") == null ? "" : t.get("channel")));
                    el(x, 2, "date_transaction", t.get("created_at"));
                    el(x, 2, "transmode_code", t.get("payment_provider_code") != null ? t.get("payment_provider_code") : t.get("payment_method_code"));
                    el(x, 2, "amount_local", minor(t.get("amount_cents")));
                    el(x, 2, "currency_code", t.get("currency"));
                    el(x, 2, "comments", "status " + t.get("status") + (t.get("recipient_identifier") == null ? "" : "; recipient " + t.get("recipient_identifier")));
                    x.append("  </transaction>\n");
                }
            }
            x.append("  <report_indicators>\n");
            Object alerts = f.get("indicators");
            if (alerts instanceof List<?> list) for (Object o : list) if (o instanceof Map<?, ?> a) el(x, 2, "indicator", a.get("rule_code"));
            Object flags = f.get("red_flags");
            if (flags != null) el(x, 2, "indicator", flags);
            x.append("  </report_indicators>\n");
        } else {
            Object lines = f.get("lines");
            if (lines instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> l) || !(l.get("transactions") instanceof List<?> txs)) continue;
                    for (Object to : txs) {
                        if (!(to instanceof Map<?, ?> t)) continue;
                        x.append("  <transaction>\n");
                        el(x, 2, "transactionnumber", t.get("reference"));
                        el(x, 2, "date_transaction", t.get("completed_at"));
                        el(x, 2, "amount_local", minor(t.get("amount_cents")));
                        el(x, 2, "currency_code", t.get("currency"));
                        el(x, 2, "comments", "customer " + l.get("customerPhone") + ", aggregate " + minor(l.get("aggregateMinor")) + " " + l.get("currency") + " on " + l.get("day"));
                        x.append("  </transaction>\n");
                    }
                }
            }
        }
        x.append("  <additional_information>\n");
        for (Map.Entry<String, Object> e : f.entrySet()) {
            if (e.getValue() instanceof List<?> || e.getValue() instanceof Map<?, ?>) continue;
            x.append("    <field name=\"").append(esc(e.getKey())).append("\">").append(esc(e.getValue() == null ? "" : String.valueOf(e.getValue()))).append("</field>\n");
        }
        x.append("  </additional_information>\n</report>\n");
        return x.toString();
    }

    private static String minor(Object o) {
        if (!(o instanceof Number n)) return "";
        long v = n.longValue();
        return String.format("%d.%02d", v / 100, Math.abs(v % 100));
    }

    private static void el(StringBuilder x, int depth, String name, Object value) {
        x.append("  ".repeat(depth)).append('<').append(name).append('>').append(esc(value == null ? "" : String.valueOf(value)))
                .append("</").append(name).append(">\n");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
