package zw.co.payport.gateway.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import zw.co.payport.gateway.service.TransactionAccessService.Access;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** R3: who may see a transaction's full details. */
class TransactionAccessServiceTest {

    private static final String TOKEN = "s3cr3t-token-value";
    private static final String HASH = TransactionAccessService.sha256Hex(TOKEN);
    private static final String PHONE = "+263771234567";

    @Test
    void validTokenGivesFullAccess() {
        assertEquals(Access.FULL, TransactionAccessService.decide(HASH, PHONE, TOKEN, null, false));
        assertEquals(Access.FULL, TransactionAccessService.decide(HASH, PHONE, TOKEN, null, true));
    }

    @Test
    void wrongOrMissingTokenGivesPublicViewUntilEnforced() {
        assertEquals(Access.PUBLIC, TransactionAccessService.decide(HASH, PHONE, "guess", null, false));
        assertEquals(Access.PUBLIC, TransactionAccessService.decide(HASH, PHONE, null, null, false));
        assertEquals(Access.NONE, TransactionAccessService.decide(HASH, PHONE, "guess", null, true));
        assertEquals(Access.NONE, TransactionAccessService.decide(HASH, PHONE, null, null, true));
    }

    @Test
    void legacyTransactionWithoutStoredHashNeverMatchesAToken() {
        assertEquals(Access.PUBLIC, TransactionAccessService.decide(null, PHONE, TOKEN, null, false));
    }

    @Test
    void staffGetFullAccess() {
        assertEquals(Access.FULL, TransactionAccessService.decide(HASH, PHONE, null, auth("0712000000", "ROLE_OPS"), true));
        assertEquals(Access.FULL, TransactionAccessService.decide(HASH, PHONE, null, auth("0712000000", "ROLE_FINANCE"), true));
    }

    @Test
    void owningCustomerGetsFullAccessOtherCustomersDoNot() {
        assertEquals(Access.FULL, TransactionAccessService.decide(HASH, PHONE, null, auth("0771234567", "ROLE_CUSTOMER"), true));
        assertEquals(Access.NONE, TransactionAccessService.decide(HASH, PHONE, null, auth("0779999999", "ROLE_CUSTOMER"), true));
    }

    @Test
    void merchantRolesAreNotStaff() {
        assertEquals(Access.PUBLIC, TransactionAccessService.decide(HASH, PHONE, null, auth("x", "ROLE_MERCHANT"), false));
    }

    @Test
    void anonymousIsTreatedAsUnauthenticated() {
        Authentication anon = auth("anonymousUser", "ROLE_ANONYMOUS");
        assertEquals(Access.PUBLIC, TransactionAccessService.decide(HASH, PHONE, null, anon, false));
    }

    @Test
    void phoneComparisonUsesNationalNumber() {
        assertTrue(TransactionAccessService.samePhone("+263771234567", "0771234567"));
        assertTrue(TransactionAccessService.samePhone("263 77 123 4567", "+263771234567"));
        assertFalse(TransactionAccessService.samePhone("+263771234567", "+263771234568"));
        assertFalse(TransactionAccessService.samePhone(null, "+263771234567"));
        assertFalse(TransactionAccessService.samePhone("1234", "1234"));
    }

    @Test
    void hashIsSha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TransactionAccessService.sha256Hex("abc"));
    }

    /** Implements the real Spring Security Authentication contract (no @Override so it also compiles against API stubs). */
    private static Authentication auth(String name, String... roles) {
        return new Authentication() {
            public Collection<? extends GrantedAuthority> getAuthorities() {
                return java.util.Arrays.stream(roles).map(r -> (GrantedAuthority) () -> r).toList();
            }
            public Object getCredentials() { return null; }
            public Object getDetails() { return null; }
            public Object getPrincipal() { return name; }
            public boolean isAuthenticated() { return true; }
            public void setAuthenticated(boolean b) { }
            public String getName() { return name; }
        };
    }
}
