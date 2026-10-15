package zw.co.payport.gateway.psp.fx;

import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

@Component
public class FxRateApprovalHandler implements ApprovalHandler {

    private final FxRateRepository rates;

    public FxRateApprovalHandler(FxRateRepository rates) {
        this.rates = rates;
    }

    @Override public String actionType() { return FxRateService.APPROVE_RATE; }
    @Override public String requiredRole() { return "FINANCE"; }

    @Override
    public String execute(String rateRef, String payload, String requestedBy, String approvedBy) {
        FxRate r = rates.findByRateRef(rateRef).orElseThrow(() -> PspException.notFound("FX rate", rateRef));
        if (!"PENDING_APPROVAL".equals(r.getStatus())) throw PspException.conflict("NOT_PENDING", "Rate is " + r.getStatus());
        r.setStatus("ACTIVE");
        r.setApprovedBy(approvedBy);
        rates.save(r);
        return "USD/ZWG mid " + r.getMid() + " active from " + r.getAsOf();
    }
}
