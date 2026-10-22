package zw.co.payport.gateway.recon;

import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * PayPort's own statement CSV, for providers without a dedicated parser and for tests:
 *
 *   reference,provider_reference,amount,currency,timestamp[,type]
 *
 * amount is in major units ("12.50"); timestamp is ISO-8601 local time ("2026-10-16T09:15:00");
 * type defaults to PAYMENT; any other type (FEE, SWEEP, ...) is stored but not matched.
 */
public class GenericCsvStatementParser implements StatementParser {

    private final String provider;

    public GenericCsvStatementParser(String provider) {
        this.provider = provider;
    }

    @Override
    public String providerCode() {
        return provider;
    }

    @Override
    public List<ProviderStatementLine> parse(InputStream file) throws StatementParseException {
        List<ProviderStatementLine> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(file, StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);
                if (n == 1 && c[0].trim().equalsIgnoreCase("reference")) continue;
                if (c.length < 5) throw new StatementParseException("line " + n + ": expected at least 5 columns");
                long cents;
                try {
                    cents = new BigDecimal(c[2].trim()).movePointRight(2).longValueExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    throw new StatementParseException("line " + n + ": bad amount '" + c[2] + "'");
                }
                LocalDateTime ts;
                try {
                    ts = LocalDateTime.parse(c[4].trim());
                } catch (Exception e) {
                    throw new StatementParseException("line " + n + ": bad timestamp '" + c[4] + "'");
                }
                String type = c.length > 5 && !c[5].isBlank() ? c[5].trim().toUpperCase() : "PAYMENT";
                List<String> refs = new ArrayList<>();
                if (!c[0].isBlank()) refs.add(c[0].trim());
                if (!c[1].isBlank()) refs.add(c[1].trim());
                out.add(ProviderStatementLine.builder()
                        .candidateReferences(refs)
                        .amountCents(cents)
                        .currency(c[3].trim().isEmpty() ? null : c[3].trim().toUpperCase())
                        .timestamp(ts)
                        .isTransactionLine("PAYMENT".equals(type))
                        .rawRow(line)
                        .build());
            }
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("could not read statement: " + e.getMessage(), e);
        }
        return out;
    }
}
