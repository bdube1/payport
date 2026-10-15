package zw.co.payport.gateway.psp.integration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service-provider interfaces for the integration layer that the PSP core
 * depends on. Each has a sandbox simulator (for development and UAT) and a
 * live implementation slot. Live implementations need the provider's API
 * specification and credentials; where a public specification exists
 * (UN / OFAC sanctions lists, WhatsApp Cloud API, Africa's Talking USSD/SMS)
 * the live client is implemented in this module.
 */
public final class IntegrationSpis {
    private IntegrationSpis() {}

    /** National ID verification against the Registrar General (or an approved KYC bureau). */
    public interface IdentityVerifier {
        String code();
        IdCheckResult verify(IdCheckRequest request);
    }

    public record IdCheckRequest(String nationalId, String firstName, String lastName, LocalDate dateOfBirth) {}

    /** result: MATCH | PARTIAL_MATCH | NO_MATCH | NOT_FOUND | UNAVAILABLE */
    public record IdCheckResult(String result, String registeredName, LocalDate registeredDob, String providerRef, String detail) {}

    /** Credit bureau (e.g. FCB) for merchant / agent due diligence. */
    public interface CreditBureau {
        String code();
        BureauReport report(String nationalIdOrRegNo, String name);
    }

    /** riskBand: LOW | MEDIUM | HIGH | ADVERSE | UNAVAILABLE */
    public record BureauReport(String riskBand, Integer score, int adverseListings, String providerRef, String detail) {}

    /** A sanctions or PEP list feed. */
    public interface SanctionsListSource {
        String listCode();
        List<SanctionsEntry> fetch() throws Exception;
    }

    public record SanctionsEntry(String listCode, String externalId, String entryType, String primaryName,
                                 List<String> aliases, String nationality, String dateOfBirth, String programme) {}

    /** Official / interbank FX rates (RBZ). */
    public interface FxRateSource {
        String code();
        List<FxQuote> fetch() throws Exception;
    }

    public record FxQuote(String base, String quote, java.math.BigDecimal bid, java.math.BigDecimal ask,
                          java.math.BigDecimal mid, LocalDateTime asOf, String source) {}

    /** Trust / settlement bank account access (balance + statement) for safeguarding reconciliation. */
    public interface BankAccountFeed {
        String cashAccount();
        Long balanceMinor() throws Exception;
        List<StatementEntry> statement(LocalDate from, LocalDate to) throws Exception;
    }

    public record StatementEntry(LocalDate valueDate, String bankReference, String narrative, String direction,
                                 long amountMinor, Long runningBalanceMinor) {}

    /** Outbound messaging channels used by the notifications module. */
    public interface MessageChannel {
        String channel(); // SMS | EMAIL | WHATSAPP
        String send(String to, String subjectOrTemplate, String body) throws Exception;
    }
}
