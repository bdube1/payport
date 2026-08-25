package zw.co.payport.gateway.service.reconciliation.parsers;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses NetOne's recharge export. Format confirmed against a real
 * sample: single sheet, header row 1 ("RechargeDate, MobileNumber,
 * AgentRefernece, BrandName, RechargeStatus, Amount" -- "AgentRefernece"
 * is the provider's own spelling, not a typo introduced here).
 *
 * AgentRefernece is the strongest candidate reference field for this
 * provider -- it's the kind of value Payport's own NetOne adaptor
 * generates and sends when requesting a recharge (see
 * NetOneAirtimeAdaptor), so it's tried first against Payport's
 * fulfilmentReference.
 *
 * RechargeDate is a Java Date.toString()-style string, e.g.
 * "Wed Aug 19 23:59:58 SAST 2026" -- the timezone abbreviation token is
 * stripped before parsing rather than hardcoded as literal text, since
 * relying on "SAST" specifically would silently break if a future
 * export ever shows a different abbreviation.
 */
@Slf4j
@Component
public class NetOneStatementParser implements StatementParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy");

    @Override
    public String providerCode() { return "NETONE"; }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerRowIndex = findHeaderRow(sheet);
            if (headerRowIndex == -1) {
                throw new StatementParseException("Could not find header row (expected 'RechargeDate' in column A)");
            }

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String status = cellString(row, 4);
                // Only successful recharges represent money actually
                // moved/delivered -- failed attempts never became a
                // real transaction on either side to reconcile.
                if (status == null || !status.equalsIgnoreCase("Success")) continue;

                String rechargeDate = cellString(row, 0);
                String mobile = cellString(row, 1);
                String agentReference = cellString(row, 2);
                String amount = cellString(row, 5);

                if (amount == null || amount.isBlank()) continue;
                Long amountCents = parseAmountToCents(amount);
                if (amountCents == null) {
                    log.warn("[NETONE-PARSE] Could not parse amount '{}' at row {}, skipping line", amount, r);
                    continue;
                }

                List<String> candidates = new ArrayList<>();
                if (agentReference != null && !agentReference.isBlank()) candidates.add(agentReference);

                lines.add(ProviderStatementLine.builder()
                        .candidateReferences(candidates)
                        .phone(mobile)
                        .amountCents(amountCents)
                        .currency("USD") // NetOne's USD Pinless/bundle products in the sample; no separate currency column
                        .timestamp(parseDate(rechargeDate))
                        .isTransactionLine(true)
                        .rawRow(rowToString(row))
                        .build());
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse NetOne statement: " + e.getMessage(), e);
        }
        return lines;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String first = cellString(row, 0);
            if ("RechargeDate".equalsIgnoreCase(first)) return r;
        }
        return -1;
    }

    private String cellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private Long parseAmountToCents(String value) {
        try {
            double d = Double.parseDouble(value.replace(",", "").trim());
            return Math.round(d * 100);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // "Wed Aug 19 23:59:58 SAST 2026" -> strip the zone token
            // (whichever 4-letter/variable abbreviation it is) rather
            // than assume it's always "SAST".
            String[] parts = raw.trim().split("\\s+");
            if (parts.length != 6) return null;
            String rebuilt = parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[5];
            return LocalDateTime.parse(rebuilt, DATE_FMT);
        } catch (Exception e) {
            log.warn("[NETONE-PARSE] Could not parse date '{}': {}", raw, e.getMessage());
            return null;
        }
    }

    private String rowToString(Row row) {
        StringBuilder sb = new StringBuilder();
        for (Cell cell : row) {
            sb.append(cellString(row, cell.getColumnIndex())).append(" | ");
        }
        return sb.toString();
    }
}
