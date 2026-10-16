package zw.co.payport.gateway.settlement;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * PayPort's interim payout instruction CSV (until the sponsor bank's format is known):
 *
 *   instruction_ref,merchant_id,currency,amount,destination,narrative
 *
 * instruction_ref is unique per item and stable across re-runs, so the bank can reject a
 * repeated instruction. amount is in major units with two decimals.
 */
@Component
public class GenericCsvPayoutAdapter implements PayoutFileAdapter {

    @Override
    public String format() {
        return "PAYPORT_CSV_V1";
    }

    @Override
    public String extension() {
        return "csv";
    }

    @Override
    public String render(String batchRef, String currency, List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("instruction_ref,merchant_id,currency,amount,destination,narrative\n");
        for (Map<String, Object> i : items) {
            long net = ((Number) i.get("net_minor")).longValue();
            sb.append(i.get("instruction_ref")).append(',')
              .append(i.get("merchant_id")).append(',')
              .append(currency).append(',')
              .append(BigDecimal.valueOf(net, 2).toPlainString()).append(',')
              .append(csv((String) i.get("destination"))).append(',')
              .append(csv("PayPort settlement " + batchRef)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
