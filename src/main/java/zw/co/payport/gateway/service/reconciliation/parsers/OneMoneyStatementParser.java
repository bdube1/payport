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
 * Parses OneMoney's "Received Records" export. Format confirmed against
 * a real sample: single sheet named "Received Records", header row 1
 * ("Trading No., Original Trading No., Merchant Order No., Trading
 * Time, Transaction Type, Trading Amount, Payer Name, Operator Name,
 * Status, Settlement Status, [currency], [sign]").
 *
 * "Merchant Order No." is the reference Payport's own OneMoney adaptor
 * generates and sends as the C2B order reference -- this maps directly
 * to Payport's paymentCorrelator field (see Transaction entity), the
 * strongest reference candidate of any provider in this set.
 *
 * Deliberately does NOT filter by the Status/Settlement Status numeric
 * codes: the sheet is explicitly named "Received Records", meaning
 * OneMoney has already filtered this export to successful, received
 * payments before generating it. Adding a status filter here on top of
 * that, without a confirmed mapping for what each numeric code means,
 * would risk silently dropping genuine rows based on a guess. If
 * OneMoney is ever asked for an unfiltered "all records" export
 * instead, this parser will need a real status filter added, informed
 * by that export's actual documented codes -- not before.
 */
@Slf4j
@Component
public class OneMoneyStatementParser implements StatementParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    @Override
    public String providerCode() { return "ONEMONEY"; }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerRowIndex = findHeaderRow(sheet);
            if (headerRowIndex == -1) {
                throw new StatementParseException("Could not find header row (expected 'Trading No.' in column A)");
            }

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Status 30 = success (OneMoney DPS spec: 10 init, 20 in progress, 30 success, 40 failure, 50 closed);
                // the last column is the direction ("+" received, "-" refund/reversal out).
                String status = cellString(row, 8);
                if (status != null && !"30".equals(status)) continue;
                String direction = cellString(row, 11);
                if (direction != null && !"+".equals(direction)) continue;

                String tradingNo = cellString(row, 0);
                String merchantOrderNo = cellString(row, 2);
                String tradingTime = cellString(row, 3);
                String tradingAmount = cellString(row, 5);
                String currency = cellString(row, 10);

                if (tradingAmount == null || tradingAmount.isBlank()) continue;
                Long amountCents = parseAmountToCents(tradingAmount);
                if (amountCents == null) {
                    log.warn("[ONEMONEY-PARSE] Could not parse amount '{}' at row {}, skipping line", tradingAmount, r);
                    continue;
                }

                List<String> candidates = new ArrayList<>();
                if (merchantOrderNo != null && !merchantOrderNo.isBlank()) candidates.add(merchantOrderNo);
                if (tradingNo != null && !tradingNo.isBlank()) candidates.add(tradingNo);

                lines.add(ProviderStatementLine.builder()
                        .candidateReferences(candidates)
                        .phone(null) // Payer Name is masked in the export (e.g. "GETRUDE ****"), no usable phone number
                        .amountCents(amountCents)
                        .currency(currency != null && !currency.isBlank() ? currency : "USD")
                        .timestamp(parseDate(tradingTime))
                        .isTransactionLine(true)
                        .rawRow(rowToString(row))
                        .build());
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse OneMoney statement: " + e.getMessage(), e);
        }
        return lines;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String first = cellString(row, 0);
            if (first != null && first.startsWith("Trading No")) return r;
        }
        return -1;
    }

    private String cellString(Row row, int col) {
        return StatementCells.text(row, col);
    }

    private Long parseAmountToCents(String value) {
        return StatementCells.cents(value);
    }

    private LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim(), DATE_FMT);
        } catch (Exception e) {
            log.warn("[ONEMONEY-PARSE] Could not parse date '{}': {}", raw, e.getMessage());
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
