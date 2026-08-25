package zw.co.payport.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.repository.UserRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Runs entirely locally — mocks JwtService and UserRepository, no database,
 * no network, no VPN needed. This is the highest-stakes piece of this whole
 * batch of changes (it sits in front of every authenticated request), so
 * it's worth having real coverage of the actual decision table before this
 * ever reaches the server, not just a manual read-through.
 *
 * What this can't tell you: whether the mobile app actually sends deviceId
 * correctly, or whether a real login round-trip works end to end. That part
 * genuinely does need a real run from the server. This only proves the
 * *decision logic* — given a token and a phone number, does it correctly
 * decide to allow or reject? — is right.
 */
class JwtAuthenticationFilterDeviceCheckTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService, userRepository);
    }

    private User userWithActiveDevice(String deviceId) {
        User u = new User();
        u.setPhone(PHONE);
        u.setActiveDeviceId(deviceId);
        return u;
    }

    @Test
    void allowsRequest_whenTokenHasNoDeviceClaim() {
        // Old tokens issued before this feature shipped — must keep working.
        when(jwtService.extractDeviceId("old-token")).thenReturn(null);

        assertTrue(filter.isDeviceAllowed("old-token", PHONE));
        verifyNoInteractions(userRepository); // shouldn't even need to hit the DB
    }

    @Test
    void allowsRequest_whenUserHasNoActiveDeviceOnRecord() {
        // User has never logged in from a device-aware client — nothing to enforce.
        when(jwtService.extractDeviceId("token")).thenReturn("device-A");
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(userWithActiveDevice(null)));

        assertTrue(filter.isDeviceAllowed("token", PHONE));
    }

    @Test
    void allowsRequest_whenDeviceMatches() {
        when(jwtService.extractDeviceId("token")).thenReturn("device-A");
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(userWithActiveDevice("device-A")));

        assertTrue(filter.isDeviceAllowed("token", PHONE));
    }

    @Test
    void rejectsRequest_whenDeviceDoesNotMatch() {
        // The actual point of this feature: device-A's old token, but device-B
        // logged in more recently and is now the account's active session.
        when(jwtService.extractDeviceId("device-A-token")).thenReturn("device-A");
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(userWithActiveDevice("device-B")));

        assertFalse(filter.isDeviceAllowed("device-A-token", PHONE));
    }

    @Test
    void allowsRequest_whenUserLookupFails() {
        // Fail open, deliberately — a DB hiccup here should never lock everyone out.
        when(jwtService.extractDeviceId("token")).thenReturn("device-A");
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

        assertTrue(filter.isDeviceAllowed("token", PHONE));
    }

    @Test
    void allowsRequest_whenSomethingThrowsUnexpectedly() {
        when(jwtService.extractDeviceId(anyString())).thenThrow(new RuntimeException("parsing blew up"));

        assertTrue(filter.isDeviceAllowed("token", PHONE),
                "A bug in this check itself must never be able to lock out every user");
    }
}
