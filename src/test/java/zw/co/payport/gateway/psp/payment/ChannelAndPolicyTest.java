package zw.co.payport.gateway.psp.payment;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.psp.channel.EmvQr;
import zw.co.payport.gateway.psp.channel.RequestSigning;
import zw.co.payport.gateway.psp.cutover.CutoverMath;
import zw.co.payport.gateway.psp.cutover.LedgerCutoverService;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.routing.RouteScorer;
import zw.co.payport.gateway.psp.settlement.BankFileFormat;
import zw.co.payport.gateway.psp.wallet.TariffMath;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChannelAndPolicyTest {

    // ── Timeout is never failure ──────────────────────────────────────────

    @Test
    void pollingBacksOffAndNeverDecidesFailureByAge() {
        assertEquals(Duration.ofSeconds(15), PollingPolicy.nextDelay(Duration.ofMinutes(1)));
        assertEquals(Duration.ofMinutes(1), PollingPolicy.nextDelay(Duration.ofMinutes(10)));
        assertEquals(Duration.ofMinutes(10), PollingPolicy.nextDelay(Duration.ofHours(5)));
        assertEquals(Duration.ofHours(1), PollingPolicy.nextDelay(Duration.ofHours(30)));
        assertFalse(PollingPolicy.shouldEscalate(Duration.ofMinutes(29)));
        assertTrue(PollingPolicy.shouldEscalate(Duration.ofMinutes(30)));
        assertTrue(PollingPolicy.shouldStopAutoPolling(Duration.ofHours(72)));
        assertTrue(PollingPolicy.inboxRetryDelay(20).toSeconds() <= 1800);
    }

    // ── EMVCo QR ─────────────────────────────────────────────────────────

    @Test
    void qrRoundTripsAndChecksCrc() {
        String qr = EmvQr.build(new EmvQr.Payload(true, "M42", "5411", "ZWG", 12_345L, "Tendai's Café", "Bulawayo", "INV-77"));
        EmvQr.Payload p = EmvQr.parse(qr);
        assertTrue(p.dynamic());
        assertEquals("M42", p.merchantRef());
        assertEquals("ZWG", p.currency());
        assertEquals(Long.valueOf(12_345L), p.amountMinor());
        assertEquals("INV-77", p.reference());
        assertEquals("Tendai's Cafe", p.merchantName());
        String tampered = qr.replace("123.45", "923.45");
        assertThrows(IllegalArgumentException.class, () -> EmvQr.parse(tampered));
    }

    @Test
    void crcMatchesKnownVector() {
        // CRC-16/CCITT-FALSE check value for "123456789" is 0x29B1.
        assertEquals("29B1", EmvQr.crc16("123456789"));
    }

    // ── Request signing ──────────────────────────────────────────────────

    @Test
    void signatureVerifiesAndDetectsTampering() {
        byte[] body = "{\"amountMinor\":500}".getBytes(StandardCharsets.UTF_8);
        String c = RequestSigning.canonical("POST", "/api/v1/partner/payments", null, 1_760_000_000L, "n-123456789012345", body);
        String sig = RequestSigning.sign("s3cret", c);
        assertTrue(RequestSigning.verify("s3cret", c, sig));
        String c2 = RequestSigning.canonical("POST", "/api/v1/partner/payments", null, 1_760_000_000L, "n-123456789012345",
                "{\"amountMinor\":900}".getBytes(StandardCharsets.UTF_8));
        assertFalse(RequestSigning.verify("s3cret", c2, sig));
        assertFalse(RequestSigning.verify("other", c, sig));
        assertTrue(RequestSigning.timestampFresh(1000, 1200));
        assertFalse(RequestSigning.timestampFresh(1000, 1400));
    }

    // ── Routing ──────────────────────────────────────────────────────────

    @Test
    void walletRoutingFollowsNetworkAndSkipsDownProviders() {
        List<RouteScorer.Rule> rules = List.of(
                new RouteScorer.Rule("ECOCASH", 1, 0, 0, 100, 0, "ECONET"),
                new RouteScorer.Rule("ONEMONEY", 1, 0, 0, 100, 0, "NETONE"),
                new RouteScorer.Rule("OMARI", 2, 0, 0, 50, 0, null));
        List<RouteScorer.Candidate> r = RouteScorer.rank(rules, 1_000, "NETONE", Map.of("ONEMONEY", "UP"), Map.of());
        assertEquals("ONEMONEY", r.get(0).provider());
        assertFalse(r.stream().filter(c -> c.provider().equals("ECOCASH")).findFirst().orElseThrow().eligible());
        List<RouteScorer.Candidate> down = RouteScorer.rank(rules, 1_000, "NETONE", Map.of("ONEMONEY", "DOWN"), Map.of());
        assertEquals("OMARI", down.stream().filter(RouteScorer.Candidate::eligible).findFirst().orElseThrow().provider());
    }

    @Test
    void cheaperHealthyRouteWinsWithinPriority() {
        List<RouteScorer.Rule> rules = List.of(
                new RouteScorer.Rule("ZIPIT", 1, 0, 500_000, 0, 150, null),
                new RouteScorer.Rule("RTGS", 2, 0, 0, 0, 500, null));
        assertEquals("ZIPIT", RouteScorer.rank(rules, 100_000, null, Map.of(), Map.of()).get(0).provider());
        assertEquals("RTGS", RouteScorer.rank(rules, 900_000, null, Map.of(), Map.of()).stream()
                .filter(RouteScorer.Candidate::eligible).findFirst().orElseThrow().provider());
    }

    // ── Tariffs, bank files, cutover ─────────────────────────────────────

    @Test
    void tariffBandsAndTransferTax() {
        List<TariffMath.Band> bands = List.of(
                new TariffMath.Band(1, 1_000, 10, 0, 5, 0),
                new TariffMath.Band(1_001, 0, 0, 100, 0, 50));
        assertEquals(10, TariffMath.charges(bands, 500).customerFeeMinor());
        assertEquals(50, TariffMath.charges(bands, 5_000).customerFeeMinor());
        assertEquals(25, TariffMath.charges(bands, 5_000).agentCommissionMinor());
        assertEquals(0, TariffMath.transferTax(500, 200, 1_000, 0));
        assertEquals(40, TariffMath.transferTax(2_000, 200, 1_000, 0));
        assertEquals(30, TariffMath.transferTax(2_000, 200, 1_000, 30));
    }

    @Test
    void bankFileEscapesAndNeutralisesFormulas() {
        String csv = BankFileFormat.csv(List.<String[]>of(new String[]{"sti_1", "=HYPERLINK(\"x\")", "CBZ", "", "", "123", "10.50", "USD", "a,b"}));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""));
        assertTrue(csv.contains("\"a,b\""));
        assertEquals("1234.05", BankFileFormat.amount(123_405));
    }

    @Test
    void cutoverSkipsPaymentsAlreadyInTheLedger() {
        List<LegacyGateway.UnsettledSession> s = List.of(
                new LegacyGateway.UnsettledSession(1, "PP-API-1", "USD", 1_000),
                new LegacyGateway.UnsettledSession(2, "PP-API-2", "USD", 2_000),
                new LegacyGateway.UnsettledSession(3, "PP-API-3", "ZWG", 5_000));
        List<LedgerCutoverService.CurrencyPreview> p = CutoverMath.preview(s, ref -> ref.equals("PP-API-2"));
        assertEquals(2, p.size());
        LedgerCutoverService.CurrencyPreview usd = p.stream().filter(x -> x.currency().equals("USD")).findFirst().orElseThrow();
        assertEquals(1_000, usd.openingBalanceMinor());
        assertEquals(List.of(1L), usd.sessionIds());
    }
}
