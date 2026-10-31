package zw.co.payport.gateway.agents;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.platform.PortalCredentials;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.service.EmailService;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent portal logins (agent_users). Their own token type ("agent_user"), never accepted on merchant,
 * customer or staff routes. Logins are created by Payport (onboarding, or approval of an application)
 * with a temporary password that must be changed at first login; forgotten passwords are reset with an
 * emailed link (only the link's hash is stored); ops can reset a password to a new temporary one.
 */
@Slf4j
@Service
public class AgentAuthService {

    static final int MAX_FAILED = 5;

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final EmailService email;
    private final ComplianceAudit audit;
    private final String jwtSecret;
    private final long jwtExpirationMs;
    private final String portalUrl;

    public AgentAuthService(JdbcTemplate jdbc, BCryptPasswordEncoder encoder, EmailService email, ComplianceAudit audit,
                            @Value("${payport.security.jwt-secret}") String jwtSecret,
                            @Value("${payport.security.jwt-expiration-ms:3600000}") long jwtExpirationMs,
                            @Value("${payport.portal.merchant-url:https://merchant.payport.africa}") String portalUrl) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.email = email;
        this.audit = audit;
        this.jwtSecret = jwtSecret;
        this.jwtExpirationMs = jwtExpirationMs;
        this.portalUrl = portalUrl.endsWith("/") ? portalUrl.substring(0, portalUrl.length() - 1) : portalUrl;
    }

    public void requireEmailFree(String emailAddress) {
        String e = emailAddress == null ? "" : emailAddress.trim().toLowerCase(Locale.ROOT);
        Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM agent_users WHERE email = ?", Integer.class, e);
        if (taken != null && taken > 0) throw new IllegalStateException("This email address already has an agent portal login");
    }

    /** A portal login for the agent; the temporary password is returned once (and emailed when mail works). */
    public Map<String, Object> createLogin(long agentId, String fullName, String emailAddress, String agentCode, String by) {
        String e = emailAddress == null ? "" : emailAddress.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) throw new IllegalArgumentException("An email address is needed for the agent's portal login");
        Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM agent_users WHERE email = ?", Integer.class, e);
        if (taken != null && taken > 0) throw new IllegalStateException("This email address already has an agent portal login");
        String temp = PortalCredentials.temporaryPassword();
        jdbc.update("INSERT INTO agent_users (agent_id, full_name, email, password_hash, must_change_password, created_by, created_at) VALUES (?,?,?,?,TRUE,?,?)",
                agentId, fullName == null || fullName.isBlank() ? e : fullName.trim(), e, encoder.encode(temp), by, now());
        audit.record(by, "AGENT_LOGIN_CREATED", "agent:" + agentCode, e);
        boolean sent = email.sendPortalMail(e, "Your Payport agent account",
                "Welcome to Payport.\n\nYour agent code is " + agentCode + ".\n\nSign in at " + portalUrl + "/agent/login\nEmail: " + e
                        + "\nTemporary password: " + temp + "\n\nYou will be asked to choose your own password when you first sign in.\n\n-- Payport");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", e);
        out.put("temporaryPassword", temp);
        out.put("emailed", sent);
        return out;
    }

    public List<Map<String, Object>> users(long agentId) {
        return jdbc.queryForList("SELECT id, full_name, email, status, account_locked, must_change_password, last_login_at, created_at FROM agent_users WHERE agent_id = ? ORDER BY id", agentId);
    }

    /** Ops: a new temporary password (shown once), unlocks the login, forces a change at next sign-in. */
    public Map<String, Object> adminReset(long agentId, long userId, String by) {
        Map<String, Object> u = user(userId);
        if (((Number) u.get("agent_id")).longValue() != agentId) throw new IllegalArgumentException("No such login for this agent");
        String temp = PortalCredentials.temporaryPassword();
        jdbc.update("UPDATE agent_users SET password_hash = ?, must_change_password = TRUE, account_locked = FALSE, failed_login_attempts = 0, "
                + "reset_token_hash = NULL, reset_token_expires_at = NULL WHERE id = ?", encoder.encode(temp), userId);
        audit.record(by, "AGENT_PASSWORD_RESET", "agent_user:" + userId, (String) u.get("email"));
        boolean sent = email.sendPortalMail((String) u.get("email"), "Your Payport agent password was reset",
                "Your Payport agent portal password was reset by Payport.\n\nTemporary password: " + temp
                        + "\nSign in at " + portalUrl + "/agent/login and choose a new password.\n\n-- Payport");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", u.get("email"));
        out.put("temporaryPassword", temp);
        out.put("emailed", sent);
        return out;
    }

    public Map<String, Object> login(String emailAddress, String password) {
        String e = emailAddress == null ? "" : emailAddress.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT u.*, a.agent_code, a.name AS agent_name, a.status AS agent_status FROM agent_users u "
                + "JOIN agents a ON a.id = u.agent_id WHERE u.email = ?", e);
        if (rows.isEmpty()) throw new SecurityException("Invalid email or password");
        Map<String, Object> u = rows.get(0);
        long id = ((Number) u.get("id")).longValue();
        if (!"ACTIVE".equals(u.get("status"))) throw new SecurityException("This login has been disabled. Contact Payport.");
        if (truthy(u.get("account_locked"))) throw new SecurityException("This login is locked after too many wrong passwords. Use Forgot password or contact Payport.");
        if ("REJECTED".equals(u.get("agent_status"))) throw new SecurityException("This agent account is not active.");
        if (password == null || !encoder.matches(password, (String) u.get("password_hash"))) {
            int n = ((Number) u.get("failed_login_attempts")).intValue() + 1;
            jdbc.update("UPDATE agent_users SET failed_login_attempts = ?, account_locked = ? WHERE id = ?", n, n >= MAX_FAILED, id);
            throw new SecurityException(n >= MAX_FAILED ? "This login is now locked after too many wrong passwords. Use Forgot password or contact Payport."
                    : "Invalid email or password");
        }
        jdbc.update("UPDATE agent_users SET failed_login_attempts = 0, last_login_at = ? WHERE id = ?", now(), id);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("fullName", u.get("full_name"));
        user.put("email", e);
        user.put("mustChangePassword", truthy(u.get("must_change_password")));
        user.put("agentId", u.get("agent_id"));
        user.put("agentCode", u.get("agent_code"));
        user.put("agentName", u.get("agent_name"));
        user.put("agentStatus", u.get("agent_status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token(id, ((Number) u.get("agent_id")).longValue(), e));
        out.put("user", user);
        return out;
    }

    public void changePassword(long userId, String current, String next) {
        Map<String, Object> u = user(userId);
        if (current == null || !encoder.matches(current, (String) u.get("password_hash"))) throw new IllegalArgumentException("The current password is wrong");
        PortalCredentials.requireStrong(next);
        if (encoder.matches(next, (String) u.get("password_hash"))) throw new IllegalArgumentException("Choose a password different from the current one");
        jdbc.update("UPDATE agent_users SET password_hash = ?, must_change_password = FALSE WHERE id = ?", encoder.encode(next), userId);
    }

    /** Always answers the same way, so it cannot be used to find out which emails have logins. */
    public void forgotPassword(String emailAddress) {
        String e = emailAddress == null ? "" : emailAddress.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, full_name FROM agent_users WHERE email = ? AND status = 'ACTIVE'", e);
        if (rows.isEmpty()) return;
        String token = PortalCredentials.resetToken();
        jdbc.update("UPDATE agent_users SET reset_token_hash = ?, reset_token_expires_at = ? WHERE id = ?",
                PortalCredentials.sha256(token), Timestamp.valueOf(LocalDateTime.now().plusHours(1)), rows.get(0).get("id"));
        email.sendPortalMail(e, "Reset your Payport agent password",
                "A password reset was requested for your Payport agent portal login.\n\nChoose a new password here (the link works for 1 hour):\n"
                        + portalUrl + "/agent/reset-password?token=" + token + "\n\nIf you did not ask for this, ignore this email.\n\n-- Payport");
    }

    public void resetPassword(String token, String next) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Invalid or expired reset link");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, reset_token_expires_at FROM agent_users WHERE reset_token_hash = ?", PortalCredentials.sha256(token.trim()));
        if (rows.isEmpty()) throw new IllegalArgumentException("Invalid or expired reset link");
        Object exp = rows.get(0).get("reset_token_expires_at");
        if (exp == null || zw.co.payport.gateway.wallets.WalletService.ldt(exp).isBefore(LocalDateTime.now())) throw new IllegalArgumentException("Invalid or expired reset link");
        PortalCredentials.requireStrong(next);
        jdbc.update("UPDATE agent_users SET password_hash = ?, must_change_password = FALSE, account_locked = FALSE, failed_login_attempts = 0, "
                + "reset_token_hash = NULL, reset_token_expires_at = NULL WHERE id = ?", encoder.encode(next), rows.get(0).get("id"));
    }

    public Map<String, Object> user(long userId) {
        return jdbc.queryForList("SELECT * FROM agent_users WHERE id = ?", userId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such login"));
    }

    private String token(long userId, long agentId, String emailAddress) {
        var key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .setSubject("agent:" + emailAddress)
                .claim("typ", "agent_user")
                .claim("agentUserId", userId)
                .claim("agentId", agentId)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + jwtExpirationMs))
                .signWith(key)
                .compact();
    }

    static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o) || (o instanceof Number n && n.intValue() != 0);
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
