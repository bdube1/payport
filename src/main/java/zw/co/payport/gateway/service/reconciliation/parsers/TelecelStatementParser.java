package zw.co.payport.gateway.service.reconciliation.parsers;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Telecel's "ClickyNPay Top Up Transactions" export. Format
 * confirmed against a real sample: single sheet, header row 1
 * ("agent_code, dest_mobile, agent_id, airwide_tid, amount,
 * agent_opening_balance, agent_closing_balance, result_description,
 * request_time, response_time, response_code").
 *
 * "agent_id" is the reference Payport's own TelecelAirtimeAdaptor
 * generates as agentTransId when requesting a top-up -- the strongest
 * candidate for Payport's fulfilmentReference. "airwide_tid" (Telecel's
 * own internal numeric ID) is included as a secondary candidate.
 *
 * request_time/response_time are genuine Excel date-time cells in this
 * export (confirmed directly, not text strings like NetOne's date
 * column) -- read via POI's native date-cell handling rather than
 * string parsing.
 *
 * "amount" is stored as a negative number (a debit against the agent's
 * own float balance) -- absolute value used for matching against
 * Payport's amountCents, which is always positive.
 */
@Slf4j
@Component
public class TelecelStatementParser implements StatementParser {

    @Override
    public String providerCode() { return "TELECEL"; }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerRowIndex = findHeaderRow(sheet);
            if (headerRowIndex == -1) {
                throw new StatementParseException("Could not find header row (expected 'agent_code' in column A)");
            }

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Integer responseCode = cellInt(row, 10);
                // 0 = success per the sample's result_description
                // ("Transaction Successful") pairing with response_code 0.
                // A failed top-up never became money that moved on
                // either side, so it isn't a reconciliation candidate.
                if (responseCode == null || responseCode != 0) continue;

                String destMobile = cellString(row, 1);
                String agentId = cellString(row, 2);
                String airwideTid = cellString(row, 3);
                Long signedCents = StatementCells.cents(cellString(row, 4));
                LocalDateTime requestTime = cellDateTime(row, 8);

                if (signedCents == null) continue;
                long amountCents = Math.abs(signedCents); // the export shows the agent wallet debit as a negative amount

                List<String> candidates = new ArrayList<>();
                if (agentId != null && !agentId.isBlank()) candidates.add(agentId);
                if (airwideTid != null && !airwideTid.isBlank()) candidates.add(airwideTid);

                lines.add(ProviderStatementLine.builder()
                        .candidateReferences(candidates)
                        .phone(destMobile)
                        .amountCents(amountCents)
                        .currency("USD")
                        .timestamp(requestTime)
                        .isTransactionLine(true)
                        .rawRow(rowToString(row))
                        .build());
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse Telecel statement: " + e.getMessage(), e);
        }
        return lines;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String first = cellString(row, 0);
            if ("agent_code".equalsIgnoreCase(first)) return r;
        }
        return -1;
    }

    private String cellString(Row row, int col) {
        return StatementCells.text(row, col);
    }

    private Integer cellInt(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
            if (cell.getCellType() == CellType.STRING) return Integer.parseInt(cell.getStringCellValue().trim());
        } catch (Exception ignored) {}
        return null;
    }

    private Double cellDouble(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
            if (cell.getCellType() == CellType.STRING) return Double.parseDouble(cell.getStringCellValue().trim());
        } catch (Exception ignored) {}
        return null;
    }

    private LocalDateTime cellDateTime(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue();
            }
        } catch (Exception e) {
            log.warn("[TELECEL-PARSE] Could not read date cell at column {}: {}", col, e.getMessage());
        }
        return null;
    }

    private String rowToString(Row row) {
        StringBuilder sb = new StringBuilder();
        for (Cell cell : row) {
            sb.append(cellString(row, cell.getColumnIndex())).append(" | ");
        }
        return sb.toString();
    }
}
