package zw.co.payport.gateway.service.reconciliation.parsers;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Omari's wallet statement PDF. Confirmed directly against a
 * real sample -- PDF text extraction does not preserve the source
 * table's columns cleanly; each logical entry actually spans two or
 * more physical text lines once extracted:
 *
 *   12/01/2026 12/01/2026 1c6cbcbd647b Merchant Settlement MU000663 10.00 10.00
 *   08:24:51 08:24:51 CLICK AND PAY *HD0CCMpaygo.co.zw
 *   AfZW:CustomerNumber:263776764741:Name:Takudzw
 *   a Kuchera
 *
 * This parser reads the PDF as a state machine: an "entry-start" line
 * is recognised by date+date+ref+KNOWN-DESCRIPTION+terminal+amount+
 * balance; every line after it, up to the next entry-start, is treated
 * as that entry's continuation (time + customer detail) and
 * concatenated before extracting the phone number.
 *
 * Only "Merchant Settlement" entries are individual customer
 * transactions and are returned as transaction lines. "Adjustment
 * Debit ... LIQUIDATION" entries are periodic sweeps of the whole
 * wallet balance elsewhere, exactly like EcoCash's "Automatic
 * Liquidation" rows -- excluded from the returned list, same reasoning.
 *
 * Neither candidate reference here (the hash-like "Ref" column, or the
 * short code in "CLICK AND PAY *XXXXXX") obviously matches Payport's
 * own reference formats in the way NetOne's AgentRefernece or
 * OneMoney's Merchant Order No. do -- both are tried anyway since it
 * costs nothing to check, but the realistic match path for Omari is
 * phone + amount + time proximity, not a confirmed exact reference.
 * Customer name reconstruction from the wrapped text (e.g. "Takudzw" +
 * "a Kuchera") is deliberately not attempted precisely -- it isn't used
 * for matching, only kept best-effort in the raw row for audit context.
 */
@Slf4j
@Component
public class OmariStatementParser implements StatementParser {

    private static final Pattern ENTRY_START = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{4})\\s+\\d{2}/\\d{2}/\\d{4}\\s+(\\S+)\\s+(Merchant Settlement|Adjustment Debit)\\s+(\\S+)\\s+\\(?([\\d,]+\\.\\d{2})\\)?\\s+([\\d,]+\\.\\d{2})\\s*$"
    );
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern CUSTOMER_NUMBER = Pattern.compile("CustomerNumber:(\\d+)");
    private static final Pattern SHORT_CODE = Pattern.compile("\\*([A-Z0-9]+)paygo");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public String providerCode() { return "OMARI"; }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> lines = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document);
            String[] rawLines = fullText.split("\\r?\\n");

            int entriesRecognised = 0;
            int i = 0;
            while (i < rawLines.length) {
                Matcher entryMatch = ENTRY_START.matcher(rawLines[i].trim());
                if (!entryMatch.matches()) {
                    i++;
                    continue;
                }
                entriesRecognised++;

                String dateStr = entryMatch.group(1);
                String ref = entryMatch.group(2);
                String description = entryMatch.group(3);
                String amountStr = entryMatch.group(5);

                // Gather continuation lines until the next entry-start
                // (or end of document) so time + customer detail can be
                // extracted from text that wraps across several lines.
                StringBuilder continuation = new StringBuilder();
                int j = i + 1;
                while (j < rawLines.length && !ENTRY_START.matcher(rawLines[j].trim()).matches()) {
                    continuation.append(rawLines[j]).append(" ");
                    j++;
                }
                String continuationText = continuation.toString();

                if ("Merchant Settlement".equals(description)) {
                    LocalDateTime timestamp = buildTimestamp(dateStr, continuationText);
                    Long amountCents = parseAmountToCents(amountStr);
                    String phone = extractFirst(CUSTOMER_NUMBER, continuationText);
                    String shortCode = extractFirst(SHORT_CODE, continuationText);

                    if (amountCents != null) {
                        List<String> candidates = new ArrayList<>();
                        if (ref != null && !ref.isBlank()) candidates.add(ref);
                        if (shortCode != null && !shortCode.isBlank()) candidates.add(shortCode);

                        lines.add(ProviderStatementLine.builder()
                                .candidateReferences(candidates)
                                .phone(phone)
                                .amountCents(amountCents)
                                .currency("USD")
                                .timestamp(timestamp)
                                .isTransactionLine(true)
                                .rawRow(rawLines[i].trim() + " || " + continuationText.trim())
                                .build());
                    } else {
                        log.warn("[OMARI-PARSE] Could not parse amount '{}' for entry ref {}, skipping line", amountStr, ref);
                    }
                }
                // "Adjustment Debit" entries are liquidation sweeps --
                // deliberately not added to the returned list.

                i = j;
            }

            // Zero recognised entries of any kind (not even a
            // liquidation-sweep row) means this text almost certainly
            // isn't an Omari statement export at all -- rather than
            // silently return an empty list and leave the ops user
            // wondering why nothing came through, fail clearly. This
            // is also what makes the StatementParseException catch
            // below genuinely reachable, not dead code: previously
            // nothing in this try block could ever throw it.
            if (entriesRecognised == 0) {
                throw new StatementParseException(
                        "Could not recognise any statement entries in this PDF -- check this is a genuine Omari statement export, not a different file.");
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Failed to parse Omari statement PDF: " + e.getMessage(), e);
        }
        return lines;
    }

    private LocalDateTime buildTimestamp(String dateStr, String continuationText) {
        try {
            LocalDate date = LocalDate.parse(dateStr.trim(), DATE_FMT);
            Matcher timeMatch = TIME_PATTERN.matcher(continuationText.trim());
            if (timeMatch.find()) {
                LocalTime time = LocalTime.parse(timeMatch.group(1));
                return LocalDateTime.of(date, time);
            }
            return date.atStartOfDay();
        } catch (Exception e) {
            log.warn("[OMARI-PARSE] Could not build timestamp from date='{}': {}", dateStr, e.getMessage());
            return null;
        }
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private Long parseAmountToCents(String value) {
        try {
            double d = Double.parseDouble(value.replace(",", "").trim());
            return Math.round(d * 100);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
