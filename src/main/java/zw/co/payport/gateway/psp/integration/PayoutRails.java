package zw.co.payport.gateway.psp.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of payout rails plus the sandbox simulator and the "manual bank file" rail. */
@Component
public class PayoutRails {

    private final List<PayoutRail> rails;

    public PayoutRails(List<PayoutRail> rails) {
        this.rails = rails;
    }

    public Optional<PayoutRail> byCode(String code) {
        return rails.stream().filter(r -> r.code().equalsIgnoreCase(code)).findFirst();
    }

    public PayoutRail require(String code) {
        return byCode(code).orElseThrow(() -> PspException.unavailable("RAIL_NOT_CONFIGURED", "Payout rail " + code + " is not configured"));
    }

    public List<String> codes() {
        return rails.stream().map(PayoutRail::code).toList();
    }

    /**
     * "Bank file" rail: nothing is sent electronically. Finance downloads the
     * batch file, uploads it to internet banking, then confirms each payout
     * with the bank's reference and proof. Always available.
     */
    @Component
    public static class ManualBankFileRail implements PayoutRail {
        @Override public String code() { return "BANK_FILE"; }
        @Override public boolean supports(String method, String currency) { return true; }
        @Override public PayoutResult submit(PayoutRequest r) { return new PayoutResult("ACCEPTED", "FILE:" + r.instructionRef(), "Included in bank upload file"); }
        @Override public PayoutResult status(String ref, String railRef) { return PayoutResult.unknown("Confirm from the bank statement"); }
    }

    /**
     * Sandbox simulator for ZIPIT / RTGS / wallet B2C. Enabled only when
     * payport.psp.integrations.sandbox=true. Account numbers ending in 0000
     * are rejected, ending in 9999 return UNKNOWN (to exercise recovery paths).
     */
    @Component
    public static class SandboxRail implements PayoutRail {
        private static final Logger log = LoggerFactory.getLogger(SandboxRail.class);
        private final Map<String, PayoutResult> sent = new ConcurrentHashMap<>();
        @Value("${payport.psp.integrations.sandbox:false}")
        private boolean enabled;

        @Override public String code() { return "SANDBOX"; }
        @Override public boolean supports(String method, String currency) { return enabled; }

        @Override
        public PayoutResult submit(PayoutRequest r) {
            if (!enabled) return new PayoutResult("REJECTED", null, "Sandbox rail disabled");
            return sent.computeIfAbsent(r.instructionRef(), k -> {
                String acct = r.accountNumber() != null ? r.accountNumber() : String.valueOf(r.mobileNumber());
                if (acct.endsWith("0000")) return new PayoutResult("REJECTED", null, "Simulated: account closed");
                if (acct.endsWith("9999")) return PayoutResult.unknown("Simulated: rail timeout");
                log.info("[SANDBOX-RAIL] Paid {} {} to {}", r.amountMinor(), r.currency(), r.beneficiaryName());
                return new PayoutResult("COMPLETED", "SBX-" + k, "Simulated payout completed");
            });
        }

        @Override
        public PayoutResult status(String ref, String railRef) {
            PayoutResult r = sent.get(ref);
            return r == null ? PayoutResult.unknown("Not found") : r;
        }
    }
}
