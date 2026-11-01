package zw.co.payport.gateway.tap;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.payport.gateway.entity.Merchant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Payport Tap refuses anything that is not a live one-time token, before any money moves. */
class TapToPayTest {

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final TapToPay tap = new TapToPay(jdbc, null, null, null, null, 10000);

    Merchant merchant(String status) {
        Merchant m = mock(Merchant.class);
        when(m.getStatus()).thenReturn(status);
        when(m.getId()).thenReturn(7L);
        return m;
    }

    @Test
    void refusesNonPayportPayloadsAndBadAmounts() {
        assertThrows(IllegalArgumentException.class, () -> tap.charge(merchant("ACTIVE"), "hello", 100, "USD", null, "1.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> tap.charge(merchant("ACTIVE"), "payport-tap:<script>", 100, "USD", null, "1.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> tap.charge(merchant("ACTIVE"), "payport-tap:AAAAAAAAAAAAAAAAAAAAAAAAAAAA", 0, "USD", null, "1.2.3.4"));
        assertThrows(IllegalStateException.class, () -> tap.charge(merchant("SUSPENDED"), "payport-tap:AAAAAAAAAAAAAAAAAAAAAAAAAAAA", 100, "USD", null, "1.2.3.4"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aUsedOrUnknownTokenIsRefused() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        assertThrows(IllegalArgumentException.class, () -> tap.charge(merchant("ACTIVE"), "payport-tap:AAAAAAAAAAAAAAAAAAAAAAAAAAAA", 100, "USD", null, "1.2.3.4"));
        java.util.Map<String, Object> used = new java.util.HashMap<>();
        used.put("used_at", new java.sql.Timestamp(System.currentTimeMillis()));
        used.put("expires_at", new java.sql.Timestamp(System.currentTimeMillis() + 60000));
        used.put("currency", "USD");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of(used));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> tap.charge(merchant("ACTIVE"), "payport-tap:AAAAAAAAAAAAAAAAAAAAAAAAAAAA", 100, "USD", null, "1.2.3.4"));
        assertTrue(e.getMessage().contains("already been used"));
    }

    @Test
    void tokensAreStoredOnlyAsHashes() {
        assertEquals(64, TapToPay.hash("abc").length());
        assertNotEquals("abc", TapToPay.hash("abc"));
    }
}
