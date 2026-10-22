package zw.co.payport.gateway.service.reconciliation.parsers;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Shared cell reading for provider statement spreadsheets.
 *  - Numeric cells come back as Excel shows them (a phone 785043887 stays "785043887", not
 *    "7.85043887E8"; an id 20688945 stays "20688945", not "2.0688945E7").
 *  - Amounts are converted exactly with BigDecimal (no binary floating point).
 */
final class StatementCells {

    private static final DataFormatter FORMATTER = new DataFormatter(java.util.Locale.ENGLISH);

    private StatementCells() {}

    static String text(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String s = switch (t) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toString()
                    : new BigDecimal(String.valueOf(cell.getNumericCellValue())).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case BLANK -> "";
            default -> FORMATTER.formatCellValue(cell);
        };
        s = s == null ? null : s.trim();
        return s == null || s.isEmpty() ? null : s;
    }

    /** Major units ("12.50", "1,234.5", "-56") to minor units, exactly; null if not a number. */
    static Long cents(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", "").replace("$", "").trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    static LocalDateTime dateTime(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue();
        String s = text(row, col);
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }
}
