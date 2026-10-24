package zw.co.payport.gateway.wallets;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Raw wallet report: every wallet operation with holder, charge, tax and channel; filterable; JSON, Excel or PDF. */
@RestController
public class WalletReport {

    static final int SCREEN_LIMIT = 1_000;
    static final int EXPORT_LIMIT = 50_000;

    static final String[][] COLUMNS = {
            {"created_at", "Date"}, {"op_ref", "Operation"}, {"wallet_ref", "Wallet"}, {"holder", "Holder"}, {"phone", "Phone"},
            {"currency", "Currency"}, {"kind", "Type"}, {"direction", "Dr/Cr"}, {"amount", "Amount"}, {"charge", "Charge"}, {"tax", "Tax"},
            {"channel", "Channel"}, {"counterparty", "Counterparty"}, {"reference", "Reference"}, {"wallet_status", "Wallet status"},
            {"tier", "Tier"}, {"entry_ref", "Ledger entry"}};

    private final JdbcTemplate jdbc;

    public WalletReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(String from, String to, String currency, String kind, String direction, String channel,
                         String status, String q, String minAmount, String maxAmount) {}

    public List<Map<String, Object>> rows(Filter f, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(limit);
        args.add(Math.max(0, offset));
        List<Map<String, Object>> raw = jdbc.queryForList(base() + where + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?", args.toArray());
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("created_at", r.get("created_at"));
            m.put("op_ref", r.get("op_ref"));
            m.put("wallet_ref", r.get("wallet_ref"));
            m.put("holder", r.get("holder"));
            m.put("phone", r.get("phone"));
            m.put("currency", r.get("currency"));
            m.put("kind", r.get("kind"));
            m.put("direction", r.get("direction"));
            m.put("amount_minor", num(r.get("amount_minor")));
            m.put("charge_minor", num(r.get("charge_minor")));
            m.put("tax_minor", num(r.get("tax_minor")));
            m.put("channel", r.get("channel"));
            m.put("counterparty", r.get("counterparty"));
            m.put("reference", r.get("reference"));
            m.put("wallet_status", r.get("wallet_status"));
            m.put("tier", r.get("tier"));
            m.put("entry_ref", r.get("entry_ref"));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> report(Filter f, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", jdbc.queryForObject("SELECT COUNT(*) " + from() + where, Long.class, args.toArray()));
        out.put("summary", jdbc.queryForList("SELECT o.currency, o.kind, o.direction, COUNT(*) AS count, COALESCE(SUM(o.amount_minor), 0) AS amount_minor, "
                + "COALESCE(SUM(" + charge() + "), 0) AS charge_minor, COALESCE(SUM(" + tax() + "), 0) AS tax_minor "
                + from() + where + " GROUP BY o.currency, o.kind, o.direction ORDER BY o.currency, o.kind, o.direction", args.toArray()));
        out.put("rows", rows(f, Math.min(Math.max(limit, 1), SCREEN_LIMIT), offset));
        return out;
    }

    private static String charge() {
        return "CASE WHEN o.direction = 'DR' THEN COALESCE(pu.charge_minor, wpa.charge_minor, 0) ELSE 0 END";
    }

    private static String tax() {
        return "CASE WHEN o.direction = 'DR' THEN COALESCE(pu.tax_minor, wpa.tax_minor, 0) ELSE 0 END";
    }

    private static String from() {
        return "FROM wallet_operations o JOIN wallets w ON w.id = o.wallet_id LEFT JOIN users u ON u.id = w.user_id "
                + "LEFT JOIN party_usage pu ON pu.party_type = 'WALLET' AND pu.op_ref = o.op_ref "
                + "LEFT JOIN wallet_payment_authorizations wpa ON o.kind = 'PAYMENT' AND wpa.reference = o.reference ";
    }

    private static String base() {
        return "SELECT o.id, o.created_at, o.op_ref, w.wallet_ref, u.full_name AS holder, w.phone, o.currency, o.kind, o.direction, o.amount_minor, "
                + charge() + " AS charge_minor, " + tax() + " AS tax_minor, pu.channel, o.counterparty, o.reference, w.status AS wallet_status, w.tier, o.entry_ref "
                + from();
    }

    private static String where(Filter f, List<Object> args) {
        StringBuilder w = new StringBuilder("WHERE 1=1");
        if (present(f.from())) { w.append(" AND o.created_at >= ?"); args.add(Timestamp.valueOf(date(f.from()).atStartOfDay())); }
        if (present(f.to())) { w.append(" AND o.created_at < ?"); args.add(Timestamp.valueOf(date(f.to()).plusDays(1).atStartOfDay())); }
        if (present(f.currency())) { w.append(" AND o.currency = ?"); args.add(f.currency().trim().toUpperCase(Locale.ROOT)); }
        if (present(f.kind())) { w.append(" AND o.kind = ?"); args.add(f.kind().trim().toUpperCase(Locale.ROOT)); }
        if (present(f.direction())) { w.append(" AND o.direction = ?"); args.add(f.direction().trim().toUpperCase(Locale.ROOT)); }
        if (present(f.channel())) { w.append(" AND pu.channel = ?"); args.add(zw.co.payport.gateway.rules.PartyRules.channel(f.channel())); }
        if (present(f.status())) { w.append(" AND w.status = ?"); args.add(f.status().trim().toUpperCase(Locale.ROOT)); }
        if (present(f.minAmount())) { w.append(" AND o.amount_minor >= ?"); args.add(minor(f.minAmount())); }
        if (present(f.maxAmount())) { w.append(" AND o.amount_minor <= ?"); args.add(minor(f.maxAmount())); }
        if (present(f.q())) {
            String like = "%" + f.q().trim() + "%";
            String phone = WalletService.phoneKey(f.q());
            w.append(" AND (w.wallet_ref LIKE ? OR w.phone LIKE ? OR u.full_name LIKE ? OR o.op_ref LIKE ? OR o.reference LIKE ? OR o.counterparty LIKE ?");
            for (int i = 0; i < 6; i++) args.add(like);
            if (phone != null) { w.append(" OR REPLACE(REPLACE(w.phone, '+', ''), ' ', '') LIKE ?"); args.add("%" + phone); }
            w.append(")");
        }
        return w.toString();
    }

    static LocalDate date(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Enter dates like 2026-10-24");
        }
    }

    static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    static long minor(String major) {
        try {
            return new BigDecimal(major.trim()).movePointRight(2).longValueExact();
        } catch (Exception e) {
            throw new IllegalArgumentException("Enter amounts like 10.50");
        }
    }

    static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    static String cell(Map<String, Object> r, String key) {
        return switch (key) {
            case "amount" -> money(r.get("amount_minor"));
            case "charge" -> money(r.get("charge_minor"));
            case "tax" -> money(r.get("tax_minor"));
            case "created_at" -> r.get("created_at") instanceof Timestamp t ? t.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : r.get("created_at") instanceof LocalDateTime l ? l.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : String.valueOf(r.get("created_at"));
            default -> r.get(key) == null ? "" : String.valueOf(r.get(key));
        };
    }

    static String money(Object minor) {
        return BigDecimal.valueOf(num(minor), 2).toPlainString();
    }

    // ─── exports ─────────────────────────────────────────────────────────────

    public byte[] xlsx(List<Map<String, Object>> rows) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Wallet report");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            CellStyle amount = wb.createCellStyle();
            amount.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            Row h = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(COLUMNS[i][1]);
                c.setCellStyle(head);
            }
            int n = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(n++);
                for (int i = 0; i < COLUMNS.length; i++) {
                    String key = COLUMNS[i][0];
                    Cell c = row.createCell(i);
                    if (key.equals("amount") || key.equals("charge") || key.equals("tax")) {
                        c.setCellValue(num(r.get(key + "_minor")) / 100.0);
                        c.setCellStyle(amount);
                    } else {
                        c.setCellValue(cell(r, key));
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not build the Excel file", e);
        }
    }

    static final String[] PDF_COLUMNS = {"created_at", "op_ref", "wallet_ref", "holder", "phone", "currency", "kind", "direction", "amount", "charge", "tax", "channel", "counterparty"};
    static final float[] PDF_WIDTHS = {78, 92, 62, 80, 62, 32, 50, 24, 50, 38, 32, 40, 120};

    public byte[] pdf(List<Map<String, Object>> rows, String title) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            float margin = 24, lineH = 11, fontSize = 7;
            int i = 0;
            int page = 0;
            do {
                PDPage p = new PDPage(size);
                doc.addPage(p);
                page++;
                try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                    float y = size.getHeight() - margin;
                    if (page == 1) {
                        text(cs, PDType1Font.HELVETICA_BOLD, 12, margin, y, title);
                        y -= 18;
                    }
                    float x = margin;
                    for (int c = 0; c < PDF_COLUMNS.length; c++) {
                        text(cs, PDType1Font.HELVETICA_BOLD, fontSize, x, y, label(PDF_COLUMNS[c]));
                        x += PDF_WIDTHS[c];
                    }
                    y -= lineH;
                    while (i < rows.size() && y > margin + lineH) {
                        x = margin;
                        for (int c = 0; c < PDF_COLUMNS.length; c++) {
                            text(cs, PDType1Font.HELVETICA, fontSize, x, y, fit(cell(rows.get(i), PDF_COLUMNS[c]), PDF_WIDTHS[c], fontSize));
                            x += PDF_WIDTHS[c];
                        }
                        y -= lineH;
                        i++;
                    }
                    text(cs, PDType1Font.HELVETICA, 6, size.getWidth() - margin - 40, margin - 10, "Page " + page);
                }
            } while (i < rows.size());
            doc.save(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not build the PDF", e);
        }
    }

    static String label(String key) {
        for (String[] c : COLUMNS) if (c[0].equals(key)) return c[1];
        return key;
    }

    static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s) throws java.io.IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /** WinAnsi-safe text cut to the column width. */
    static String fit(String s, float width, float size) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch >= 32 && ch < 127 ? ch : '?');
        String t = b.toString();
        while (!t.isEmpty() && PDType1Font.HELVETICA.getStringWidth(t) / 1000 * size > width - 3) t = t.substring(0, t.length() - 1);
        return t;
    }

    // ─── routes ──────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/wallets/report")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                                   @RequestParam(required = false) String currency, @RequestParam(required = false) String kind,
                                                                   @RequestParam(required = false) String direction, @RequestParam(required = false) String channel,
                                                                   @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                                   @RequestParam(required = false) String minAmount, @RequestParam(required = false) String maxAmount,
                                                                   @RequestParam(defaultValue = "200") int limit, @RequestParam(defaultValue = "0") int offset,
                                                                   HttpServletRequest req) {
        Filter f = new Filter(from, to, currency, kind, direction, channel, status, q, minAmount, maxAmount);
        return Web.run(() -> report(f, limit, offset), req);
    }

    @GetMapping("/api/v1/admin/wallets/report/export")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "xlsx") String format,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String currency, @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String direction, @RequestParam(required = false) String channel,
                                    @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String minAmount, @RequestParam(required = false) String maxAmount,
                                    HttpServletRequest req) {
        Filter f = new Filter(from, to, currency, kind, direction, channel, status, q, minAmount, maxAmount);
        List<Map<String, Object>> rows;
        try {
            rows = rows(f, EXPORT_LIMIT, 0);
        } catch (IllegalArgumentException e) {
            return Web.run(() -> { throw e; }, req);
        }
        String stamp = LocalDate.now().toString();
        if ("pdf".equalsIgnoreCase(format)) {
            String title = "PayPort wallet report" + (present(from) || present(to) ? " " + (present(from) ? from : "") + " to " + (present(to) ? to : stamp) : "")
                    + " (" + rows.size() + " operations)";
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wallet-report-" + stamp + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF).body(pdf(rows, title));
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wallet-report-" + stamp + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(xlsx(rows));
    }
}
