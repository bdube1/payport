package zw.co.payport.gateway.psp.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * National ID and credit bureau adapters.
 *
 * Zimbabwe's Registrar General and the credit bureaus (e.g. FCB) do not
 * publish open APIs; access is by agreement and their specs come with it.
 * Until then:
 *   - SANDBOX: deterministic simulator for development and UAT
 *   - MANUAL:  returns UNAVAILABLE, which routes the customer to manual
 *              document review by compliance (the system still works, slower)
 * Add the live client as another bean implementing the SPI and set
 * payport.psp.kyc.id-verifier / payport.psp.kyc.credit-bureau to its code.
 */
public final class IdentityAndBureauAdapters {
    private IdentityAndBureauAdapters() {}

    @Component
    public static class Registry {
        private final List<IntegrationSpis.IdentityVerifier> verifiers;
        private final List<IntegrationSpis.CreditBureau> bureaus;
        @Value("${payport.psp.kyc.id-verifier:MANUAL}")
        private String verifierCode = "MANUAL";
        @Value("${payport.psp.kyc.credit-bureau:MANUAL}")
        private String bureauCode = "MANUAL";

        public Registry(List<IntegrationSpis.IdentityVerifier> verifiers, List<IntegrationSpis.CreditBureau> bureaus) {
            this.verifiers = verifiers;
            this.bureaus = bureaus;
        }

        public IntegrationSpis.IdentityVerifier verifier() {
            return verifiers.stream().filter(v -> v.code().equalsIgnoreCase(verifierCode)).findFirst()
                    .orElseGet(() -> verifiers.stream().filter(v -> v.code().equals("MANUAL")).findFirst().orElseThrow());
        }

        public IntegrationSpis.CreditBureau bureau() {
            return bureaus.stream().filter(v -> v.code().equalsIgnoreCase(bureauCode)).findFirst()
                    .orElseGet(() -> bureaus.stream().filter(v -> v.code().equals("MANUAL")).findFirst().orElseThrow());
        }
    }

    @Component
    public static class ManualIdentityVerifier implements IntegrationSpis.IdentityVerifier {
        @Override public String code() { return "MANUAL"; }
        @Override public IntegrationSpis.IdCheckResult verify(IntegrationSpis.IdCheckRequest r) {
            return new IntegrationSpis.IdCheckResult("UNAVAILABLE", null, null, null, "No electronic ID check configured: route to document review");
        }
    }

    /** Zimbabwean national ID format is NN-NNNNNN(N) L NN, e.g. 63-123456 X 42. Sandbox: IDs ending in 00 are NOT_FOUND, 11 are NO_MATCH. */
    @Component
    public static class SandboxIdentityVerifier implements IntegrationSpis.IdentityVerifier {
        @Override public String code() { return "SANDBOX"; }
        @Override public IntegrationSpis.IdCheckResult verify(IntegrationSpis.IdCheckRequest r) {
            String id = r.nationalId() == null ? "" : r.nationalId().replaceAll("[\\s-]", "").toUpperCase();
            if (!id.matches("\\d{8,9}[A-Z]\\d{2}")) return new IntegrationSpis.IdCheckResult("NO_MATCH", null, null, null, "Invalid ID format");
            if (id.endsWith("00")) return new IntegrationSpis.IdCheckResult("NOT_FOUND", null, null, "SBX-" + id, "Simulated: not on register");
            if (id.endsWith("11")) return new IntegrationSpis.IdCheckResult("NO_MATCH", "SIMULATED OTHER PERSON", null, "SBX-" + id, "Simulated: name mismatch");
            return new IntegrationSpis.IdCheckResult("MATCH", (r.firstName() + " " + r.lastName()).toUpperCase(), r.dateOfBirth(), "SBX-" + id, "Simulated match");
        }
    }

    @Component
    public static class ManualCreditBureau implements IntegrationSpis.CreditBureau {
        @Override public String code() { return "MANUAL"; }
        @Override public IntegrationSpis.BureauReport report(String idOrRegNo, String name) {
            return new IntegrationSpis.BureauReport("UNAVAILABLE", null, 0, null, "No bureau integration configured");
        }
    }

    @Component
    public static class SandboxCreditBureau implements IntegrationSpis.CreditBureau {
        @Override public String code() { return "SANDBOX"; }
        @Override public IntegrationSpis.BureauReport report(String idOrRegNo, String name) {
            int h = Math.abs((idOrRegNo + name).hashCode()) % 100;
            String band = h < 60 ? "LOW" : h < 85 ? "MEDIUM" : h < 95 ? "HIGH" : "ADVERSE";
            return new IntegrationSpis.BureauReport(band, 300 + h * 5, "ADVERSE".equals(band) ? 1 : 0, "SBX-BUREAU-" + h, "Simulated");
        }
    }
}
