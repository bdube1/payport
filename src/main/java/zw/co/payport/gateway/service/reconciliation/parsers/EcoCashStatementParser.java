package zw.co.payport.gateway.service.reconciliation.parsers;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses EcoCash's "Account Statement" export (as supplied for
 * CLICKANDPAY). Format confirmed directly against a real sample file:
 * multiple sheets (one per currency -- ZWG/USD), each with a title row,
 * a header row ("Transactor, Name, Transfer Date, Transfer Id,
 * Correlator, Mobile Number, Service Name, Transfer Value, Post
 * Balance, Transaction Type, Cr/Dr, Currency"), then one row per entry.
 *
 * Only Cr/Dr = "CR" rows are genuine customer payments. "DR" rows seen
 * in the sample (Service Name "Automatic Liquidation") are periodic
 * settlement sweeps out of the merchant wallet, not individual
 * transactions, and are deliberately excluded from the parsed line list
 * -- they'd never correspond to a single Payport Transaction anyway.
 *
 * The sample file's "Transfer Date" column is date-only, no time
 * component -- timestamps here default to midnight on that date. This
 * is a real precision limit inherited from the source file, not a
 * parsing bug: it narrows how tightly the fuzzy-match fallback can use
 * time proximity for this provider specifically.
 */
@Slf4j
@Component
public class EcoCashStatementParser implements StatementParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String providerCode() { return "ECOCASH"; }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file)) {

            for (Sheet sheet : workbook) {
                int headerRowIndex = findHeaderRow(sheet);
                if (headerRowIndex == -1) {
                    log.warn("[ECOCASH-PARSE] No header row found in sheet '{}', skipping", sheet.getSheetName());
                    continue;
                }

                for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String crDr = cellString(row, 10);
                    if (!"CR".equalsIgnoreCase(crDr)) continue; // DR = liquidation sweep, not a transaction

                    String transferId = cellString(row, 3);
                    String correlator = cellString(row, 4);
                    String mobile = cellString(row, 5);
                    String transferValue = cellString(row, 7);
                    String currency = cellString(row, 11);
                    String transferDate = cellString(row, 2);

                    if (transferValue == null || transferValue.isBlank()) continue;

                    Long amountCents = parseAmountToCents(transferValue);
                    if (amountCents == null) {
                        log.warn("[ECOCASH-PARSE] Could not parse amount '{}' at row {}, skipping line", transferValue, r);
                        continue;
                    }

                    LocalDateTime timestamp = parseDate(transferDate);

                    List<String> candidates = new ArrayList<>();
                    if (transferId != null && !transferId.isBlank()) candidates.add(transferId);
                    if (correlator != null && !correlator.isBlank()) candidates.add(correlator);

                    lines.add(ProviderStatementLine.builder()
                            .candidateReferences(candidates)
                            .phone(mobile)
                            .amountCents(amountCents)
                            .currency(currency != null ? currency : "USD")
                            .timestamp(timestamp)
                            .isTransactionLine(true)
                            .rawRow(rowToString(row))
                            .build());
                }
            }
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse EcoCash statement: " + e.getMessage(), e);
        }
        return lines;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String first = cellString(row, 0);
            if ("Transactor".equalsIgnoreCase(first)) return r;
        }
        return -1;
    }

    private String cellString(Row row, int col) {
        return StatementCells.text(row, col);
    }

    private Long parseAmountToCents(String value) {
        return StatementCells.cents(value);
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(dateStr.trim(), DATE_FMT);
            return date.atStartOfDay();
        } catch (Exception e) {
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
