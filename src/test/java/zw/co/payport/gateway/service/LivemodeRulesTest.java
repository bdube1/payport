package zw.co.payport.gateway.service;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.entity.ApiKey;
import zw.co.payport.gateway.entity.CheckoutSession;

import static org.junit.jupiter.api.Assertions.*;

/** R1: whether a session moves real money is decided once, at creation, and test money is never settled. */
class LivemodeRulesTest {

    private static ApiKey key(long merchantId, String env) {
        ApiKey k = new ApiKey();
        k.setMerchantId(merchantId);
        k.setEnvironment(env);
        return k;
    }

    @Test
    void noApiKeyMeansLive() {
        // Payment links and dashboard checkouts: previously simulated by accident.
        assertTrue(CheckoutSessionService.decideLivemode(7L, null, null));
    }

    @Test
    void keyEnvironmentDecides() {
        assertTrue(CheckoutSessionService.decideLivemode(7L, 1L, key(7L, "LIVE")));
        assertFalse(CheckoutSessionService.decideLivemode(7L, 1L, key(7L, "TEST")));
    }

    @Test
    void unknownKeyIsRefusedNotGuessed() {
        assertThrows(RuntimeException.class, () -> CheckoutSessionService.decideLivemode(7L, 1L, null));
    }

    @Test
    void anotherMerchantsKeyIsRefused() {
        assertThrows(RuntimeException.class, () -> CheckoutSessionService.decideLivemode(7L, 1L, key(8L, "LIVE")));
    }

    private static CheckoutSession session(Boolean livemode, String status) {
        CheckoutSession s = new CheckoutSession();
        s.setLivemode(livemode);
        s.setStatus(status);
        return s;
    }

    @Test
    void onlyLivePaidSessionsAreSettleable() {
        assertTrue(SettlementService.isSettleable(session(true, "PAID")));
        assertTrue(SettlementService.isSettleable(session(true, "PARTIALLY_REFUNDED")));
        assertTrue(SettlementService.isSettleable(session(true, "REFUNDED")));
        assertFalse(SettlementService.isSettleable(session(false, "PAID")), "simulated payment must never be settled");
        assertFalse(SettlementService.isSettleable(session(null, "PAID")), "unknown mode must never be settled");
        assertFalse(SettlementService.isSettleable(session(true, "PENDING")));
        assertFalse(SettlementService.isSettleable(session(true, "FAILED")));
    }
}
