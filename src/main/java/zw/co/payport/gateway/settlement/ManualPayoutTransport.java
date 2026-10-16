package zw.co.payport.gateway.settlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default transport until the sponsor bank's channel is known: the instructed file waits for an
 * operator to upload it, and the bank's results are imported by hand. A bank transport is added as
 * a @Primary PayoutTransport bean.
 */
@Slf4j
@Component
public class ManualPayoutTransport implements PayoutTransport {

    @Override
    public boolean automatic() {
        return false;
    }

    @Override
    public void send(SettlementBatchService.PayoutFile file) {
        log.warn("[SETTLEMENT] {} instructed ({}); no bank transport configured -- an operator must upload it and import the bank's results",
                file.fileName(), file.sha256());
    }

    @Override
    public List<BankResultFile> fetchResults() {
        return List.of();
    }
}
