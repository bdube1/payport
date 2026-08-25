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
import java.util.Locale;

/**
 * Parses Innbucks' "export" statement. Format confirmed against a real
 * sample: single sheet named "export", 30 columns, header row 1.
 *
 * IMPORTANT SCOPE NOTE: as of this writing, Innbucks does not appear
 * anywhere in the Gateway's existing payment adaptor set (EcoCash,
 * OneMoney, Omari, ZimSwitch are the integrated payment providers;
 * Econet/NetOne/Telecel/eSolutions are fulfilment providers). This
 * parser is genuinely useful regardless -- either for when/if an
 * Innbucks integration is added, or to manually cross-reference this
 * statement by hand in the meantime -- but until that integration
 * exists, matching Innbucks lines against Payport's Transaction table
 * will correctly show every line as PROVIDER_ONLY, since there is
 * nothing on Payport's side tagged with an Innbucks payment provider
 * code to match against yet. That is expected, not a parser bug.
 *
 * No column in the sample export offers an obvious reference Payport
 * would have generated and sent (unlike NetOne's AgentRefernece or
 * OneMoney's Merchant Order No.) -- "STAN" and "Payment token" are
 * included as best-effort candidates in case a future integration
 * stores either of them, but the realistic match path for this
 * provider is amount + time proximity, not an exact reference. There
 * is also no phone number in this export (Recipient/Sender Account
 * Number are bank-style account numbers, not phone numbers), so even
 * the fuzzy fallback has one fewer signal to use here than for the
 * mobile-money providers.
 *
 * "Base amount" (before Innbucks' own fee deduction) is used for
 * matching, not "Posted amount" -- Base amount is what the customer
 * was actually charged, the same concept as Payport's own
 * Transaction.amountCents; Posted amount reflects Innbucks' commission
 * being taken out, a different number for a different purpose.
 */
@Slf4j
@Component
public class InnbucksStatementParser implements StatementParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH);

    @Override
    public String providerCode() { return "INNBUCKS"; }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerRowIndex = findHeaderRow(sheet);
            if (headerRowIndex == -1) {
                throw new StatementParseException("Could not find header row (expected 'Transaction date and time' in column A)");
            }

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String status = cellString(row, 5);
                if (status == null || !status.equalsIgnoreCase("COMPLETED")) continue;

                String txDateTime = cellString(row, 0);
                String baseAmount = cellString(row, 10);
                String paymentToken = cellString(row, 19);
                String stan = cellString(row, 23);
                String headTxId = cellString(row, 2);

                if (baseAmount == null || baseAmount.isBlank()) continue;
                Long amountCents = parseAmountToCents(baseAmount);
                if (amountCents == null) {
                    log.warn("[INNBUCKS-PARSE] Could not parse amount '{}' at row {}, skipping line", baseAmount, r);
                    continue;
                }

                List<String> candidates = new ArrayList<>();
                if (stan != null && !stan.isBlank()) candidates.add(stan);
                if (paymentToken != null && !paymentToken.isBlank()) candidates.add(paymentToken);
                if (headTxId != null && !headTxId.isBlank()) candidates.add(headTxId);

                lines.add(ProviderStatementLine.builder()
                        .candidateReferences(candidates)
                        .phone(null) // no phone number field in this export -- see class-level note
                        .amountCents(amountCents)
                        .currency("USD")
                        .timestamp(parseDate(txDateTime))
                        .isTransactionLine(true)
                        .rawRow(rowToString(row))
                        .build());
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse Innbucks statement: " + e.getMessage(), e);
        }
        return lines;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String first = cellString(row, 0);
            if (first != null && first.startsWith("Transaction date")) return r;
        }
        return -1;
    }

    private String cellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            }
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
            return LocalDateTime.parse(raw.trim(), DATE_FMT);
        } catch (Exception e) {
            log.warn("[INNBUCKS-PARSE] Could not parse date '{}': {}", raw, e.getMessage());
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
