package zw.co.payport.gateway.service.reconciliation.parsers;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ZimSwitch card switch report (owner's sample, 2026-10-16): one sheet, header row
 * "Issuer, TerminalId, Device, DateTime, MessageType, Type, STAN, RRN, PAN, RspCode, SwitchFees,
 * AcquirerFees, IssuerFees, CashAmt, CashCurr, TranAmt, TranCurr, NetAmt".
 *
 * Only RspCode 0 (approved) financial messages (MessageType 200) are payments. References offered to
 * the matcher: RRN and STAN (the card network's identifiers); PayPort's own reference is not in
 * this report, so many lines match by amount and time (ReconEngine's second pass). PANs are
 * masked in the source and are not stored beyond the raw row.
 */
@Slf4j
@Component
public class ZimSwitchStatementParser implements StatementParser {

    @Override
    public String providerCode() {
        return "ZIMSWITCH";
    }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file)) {
            Sheet sheet = wb.getSheetAt(0);
            int header = -1;
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
                if ("Issuer".equalsIgnoreCase(StatementCells.text(sheet.getRow(r), 0))
                        && "RRN".equalsIgnoreCase(StatementCells.text(sheet.getRow(r), 7))) { header = r; break; }
            }
            if (header < 0) throw new StatementParseException("Could not find the ZimSwitch header row (Issuer ... RRN ...)");
            for (int r = header + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || StatementCells.text(row, 0) == null) continue;
                if (!"0".equals(StatementCells.text(row, 9))) continue;           // RspCode: 0 = approved
                String mti = StatementCells.text(row, 4);
                if (mti != null && !"200".equals(mti)) continue;                  // financial transactions only
                Long cents = StatementCells.cents(StatementCells.text(row, 15)); // TranAmt
                if (cents == null) {
                    log.warn("[ZIMSWITCH-PARSE] Bad amount at row {}", r);
                    continue;
                }
                List<String> refs = new ArrayList<>();
                String rrn = StatementCells.text(row, 7), stan = StatementCells.text(row, 6);
                if (rrn != null) refs.add(rrn);
                if (stan != null) refs.add(stan);
                StringBuilder raw = new StringBuilder();
                for (int c = 0; c <= 17; c++) {
                    if (c == 8) { raw.append("PAN(masked) | "); continue; }
                    raw.append(StatementCells.text(row, c)).append(" | ");
                }
                lines.add(ProviderStatementLine.builder()
                        .candidateReferences(refs)
                        .amountCents(cents)
                        .currency(StatementCells.text(row, 16) != null ? StatementCells.text(row, 16) : "USD")
                        .timestamp(StatementCells.dateTime(row, 3))
                        .isTransactionLine(true)
                        .rawRow(raw.toString())
                        .build());
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse ZimSwitch report: " + e.getMessage(), e);
        }
        return lines;
    }
}
