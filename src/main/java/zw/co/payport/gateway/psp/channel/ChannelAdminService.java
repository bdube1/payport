package zw.co.payport.gateway.psp.channel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.audit.AuditService;

import java.security.SecureRandom;
import java.util.*;

/** Issues and manages partner API credentials and POS terminals. Secrets are shown once and stored encrypted. */
@Service
public class ChannelAdminService {

    public static final Set<String> SCOPES = Set.of("payments:write", "payments:read", "balance:read", "statement:read", "qr:write");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record IssuedSecret(String id, String secret, String note) {}

    private final PartnerClientRepository partners;
    private final TerminalRepository terminals;
    private final LegacyGateway legacy;
    private final AuditService audit;

    public ChannelAdminService(PartnerClientRepository partners, TerminalRepository terminals, LegacyGateway legacy, AuditService audit) {
        this.partners = partners;
        this.terminals = terminals;
        this.legacy = legacy;
        this.audit = audit;
    }

    @Transactional
    public IssuedSecret createPartner(String name, Long merchantId, Set<String> scopes, String allowedIps, String mtlsCertSha256,
                                      int rateLimitPerMinute, Actor actor) {
        if (!SCOPES.containsAll(scopes)) throw PspException.badRequest("INVALID_SCOPE", "Scopes must be from " + SCOPES);
        if (merchantId != null && legacy.merchant(merchantId).isEmpty()) throw PspException.notFound("Merchant", merchantId);
        PartnerClient c = new PartnerClient();
        c.setKeyId(Refs.newRef("pk"));
        c.setName(name);
        c.setMerchantId(merchantId);
        String secret = secret();
        c.setSecret(secret);
        c.setScopes(String.join(",", new TreeSet<>(scopes)));
        c.setAllowedIps(allowedIps);
        c.setMtlsCertSha256(mtlsCertSha256 == null ? null : mtlsCertSha256.replace(":", "").toLowerCase());
        c.setStatus("ACTIVE");
        c.setRateLimitPerMinute(rateLimitPerMinute <= 0 ? 120 : rateLimitPerMinute);
        c.setCreatedBy(actor.id());
        partners.save(c);
        audit.record(actor, "PARTNER_CREATED", "PARTNER", c.getKeyId(), Map.of("name", name, "scopes", c.getScopes(),
                "mtls", c.getMtlsCertSha256() != null));
        return new IssuedSecret(c.getKeyId(), secret, "Store this secret now; it cannot be shown again.");
    }

    @Transactional
    public IssuedSecret rotatePartnerSecret(String keyId, Actor actor) {
        PartnerClient c = partners.findByKeyId(keyId).orElseThrow(() -> PspException.notFound("Partner", keyId));
        String secret = secret();
        c.setSecret(secret);
        partners.save(c);
        audit.record(actor, "PARTNER_SECRET_ROTATED", "PARTNER", keyId, Map.of());
        return new IssuedSecret(keyId, secret, "Old secret stops working immediately.");
    }

    @Transactional
    public void setPartnerStatus(String keyId, String status, Actor actor) {
        PartnerClient c = partners.findByKeyId(keyId).orElseThrow(() -> PspException.notFound("Partner", keyId));
        if (!Set.of("ACTIVE", "SUSPENDED", "REVOKED").contains(status)) throw PspException.badRequest("INVALID_STATUS", "ACTIVE, SUSPENDED or REVOKED");
        c.setStatus(status);
        partners.save(c);
        audit.record(actor, "PARTNER_STATUS", "PARTNER", keyId, Map.of("status", status));
    }

    @Transactional
    public IssuedSecret registerTerminal(long merchantId, String type, String serial, String model, String location, Actor actor) {
        if (legacy.merchant(merchantId).isEmpty()) throw PspException.notFound("Merchant", merchantId);
        String t = type == null ? "MPOS" : type.toUpperCase();
        if (!Set.of("POS", "MPOS", "SOFTPOS").contains(t)) throw PspException.badRequest("INVALID_TYPE", "POS, MPOS or SOFTPOS");
        Terminal term = new Terminal();
        String id;
        do { id = "T" + zw.co.payport.gateway.psp.common.Refs.digits(7); } while (terminals.findByTerminalId(id).isPresent());
        term.setTerminalId(id);
        term.setMerchantId(merchantId);
        term.setTerminalType(t);
        term.setSerialNumber(serial);
        term.setModel(model);
        term.setLocationLabel(location);
        String secret = secret();
        term.setSecret(secret);
        term.setStatus("ACTIVE");
        term.setAssignedBy(actor.id());
        terminals.save(term);
        audit.record(actor, "TERMINAL_REGISTERED", "TERMINAL", id, Map.of("merchantId", merchantId, "type", t));
        return new IssuedSecret(id, secret, "Provision this secret into the terminal; it cannot be shown again.");
    }

    @Transactional
    public void setTerminalStatus(String terminalId, String status, Actor actor) {
        Terminal t = terminals.findByTerminalId(terminalId).orElseThrow(() -> PspException.notFound("Terminal", terminalId));
        if (!Set.of("ACTIVE", "SUSPENDED", "RETIRED").contains(status)) throw PspException.badRequest("INVALID_STATUS", "ACTIVE, SUSPENDED or RETIRED");
        t.setStatus(status);
        terminals.save(t);
        audit.record(actor, "TERMINAL_STATUS", "TERMINAL", terminalId, Map.of("status", status));
    }

    public List<PartnerClient> partners() { return partners.findAll(); }
    public List<Terminal> terminals(long merchantId) { return terminals.findByMerchantId(merchantId); }

    @Transactional
    public void heartbeat(String terminalId, String appVersion) {
        terminals.findByTerminalId(terminalId).ifPresent(t -> {
            t.setLastSeenAt(java.time.LocalDateTime.now());
            t.setAppVersion(appVersion);
            terminals.save(t);
        });
    }

    private static String secret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
