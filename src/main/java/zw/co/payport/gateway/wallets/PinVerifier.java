package zw.co.payport.gateway.wallets;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.UserRepository;

import java.time.LocalDateTime;

/**
 * Re-checks the customer's PIN before money leaves a wallet (payment, transfer, conversion,
 * cash-out code). Same lockout as login: five wrong PINs lock the account for 15 minutes. The
 * failure count is committed even though the caller's operation is refused.
 */
@Component
public class PinVerifier {

    static final int MAX_ATTEMPTS = 5;

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;

    public PinVerifier(UserRepository users, BCryptPasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = Web.PinRejectedException.class)
    public User verify(String phone, String pin) {
        User u = users.findByPhone(phone).orElseThrow(() -> new SecurityException("Account not found"));
        if (!Boolean.TRUE.equals(u.getIsActive())) throw new SecurityException("Account is deactivated");
        if (u.getLockedUntil() != null && LocalDateTime.now().isBefore(u.getLockedUntil())) {
            throw new Web.PinRejectedException("Too many wrong PINs. Try again after " + u.getLockedUntil().toLocalTime().withNano(0));
        }
        if (pin == null || pin.isBlank() || !encoder.matches(pin, u.getPinHash())) {
            int n = (u.getFailedPinAttempts() == null ? 0 : u.getFailedPinAttempts()) + 1;
            u.setFailedPinAttempts(n);
            if (n >= MAX_ATTEMPTS) u.setLockedUntil(LocalDateTime.now().plusMinutes(15));
            users.save(u);
            zw.co.payport.gateway.observability.SecurityEvents.count(n >= MAX_ATTEMPTS ? zw.co.payport.gateway.observability.SecurityEvents.ACCOUNT_LOCKED : zw.co.payport.gateway.observability.SecurityEvents.PIN_REJECTED, "customer");
            throw new Web.PinRejectedException(n >= MAX_ATTEMPTS ? "Too many wrong PINs. Your account is locked for 15 minutes." : "Wrong PIN");
        }
        if (u.getFailedPinAttempts() != null && u.getFailedPinAttempts() > 0) {
            u.setFailedPinAttempts(0);
            users.save(u);
        }
        return u;
    }
}
