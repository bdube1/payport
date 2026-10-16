package zw.co.payport.gateway.settlement;

import java.util.List;
import java.util.Map;

/**
 * Renders a settlement batch as the sponsor bank's payout instruction format. The bank's
 * specification is not available yet (Phase 3 dependency), so the default adapter writes a
 * documented CSV; a bank-specific adapter replaces it without touching the batch logic.
 * Rendering must be deterministic: the same batch always gives the same file.
 */
public interface PayoutFileAdapter {

    String format();

    String extension();

    String render(String batchRef, String currency, List<Map<String, Object>> items);
}
