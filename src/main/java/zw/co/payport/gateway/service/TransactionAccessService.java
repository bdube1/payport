package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.repository.TransactionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/**
 * Who may see (and act on) a transaction through the public /api/v1/transactions endpoints.
 *
 * Transaction references follow PP-{CHANNEL}-{YYYYMMDD}-{6 digits}, so they can be guessed.
 * Knowing a reference must therefore not be enough to read personal data or a ZESA token
 * (R3). Full access requires one of:
 *   - the per-transaction access token issued at initiation (X-Transaction-Token header),
 *   - a logged-in consumer whose phone number is the transaction's customer phone,
 *   - a logged-in staff user (OPS / ADMIN / FINANCE).
 * Everyone else gets the public view (TransactionResponse.publicView), or - once
 * payport.security.transaction-token.enforce=true, after the frontends send the token -
 * no access at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionAccessService {

    public static final String TOKEN_HEADER = "X-Transaction-Token";

    public enum Access { FULL, PUBLIC, NONE }

    private static final Set<String> STAFF_AUTHORITIES = Set.of("ROLE_OPS", "ROLE_ADMIN", "ROLE_FINANCE");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransactionRepository transactionRepository;

    /**
     * false (default): callers without access get the redacted public view, so the
     * existing consumer app keeps working while it is updated to send the token.
     * true: callers without access get 404, as if the transaction did not exist.
     */
    @Value("${payport.security.transaction-token.enforce:false}")
    private boolean enforce;

    /** Creates a new token for the transaction, stores only its hash, and returns the raw token once. */
    @Transactional
    public String issueToken(String reference) {
        Transaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("Transaction not found after initiation: " + reference));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tx.setAccessTokenHash(sha256Hex(token));
        transactionRepository.save(tx);
        return token;
    }

    public Access accessFor(String reference, String presentedToken) {
        Transaction tx = transactionRepository.findByReference(reference).orElse(null);
        if (tx == null) {
            return enforce ? Access.NONE : Access.PUBLIC; // the caller then gets the usual not-found from the orchestrator
        }
        return decide(tx.getAccessTokenHash(), tx.getCustomerPhone(), presentedToken,
                SecurityContextHolder.getContext().getAuthentication(), enforce);
    }

    /** Pure decision, separated for unit testing. */
    static Access decide(String storedHash, String customerPhone, String presentedToken, Authentication auth, boolean enforce) {
        if (presentedToken != null && !presentedToken.isBlank() && storedHash != null
                && MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
                                         sha256Hex(presentedToken.trim()).getBytes(StandardCharsets.US_ASCII))) {
            return Access.FULL;
        }
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            boolean staff = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF_AUTHORITIES::contains);
            if (staff) return Access.FULL;
            boolean customer = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("ROLE_CUSTOMER"::equals);
            if (customer && samePhone(auth.getName(), customerPhone)) return Access.FULL;
        }
        return enforce ? Access.NONE : Access.PUBLIC;
    }

    /** Compares the national significant number (last 9 digits), so +263771234567 equals 0771234567. */
    static boolean samePhone(String a, String b) {
        String da = digits(a), db = digits(b);
        if (da.length() < 9 || db.length() < 9) return false;
        return da.substring(da.length() - 9).equals(db.substring(db.length() - 9));
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    static String sha256Hex(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
