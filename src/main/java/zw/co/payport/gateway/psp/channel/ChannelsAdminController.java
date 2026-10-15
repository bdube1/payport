package zw.co.payport.gateway.psp.channel;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Partner API credentials and POS terminal fleet. */
@RestController
@RequestMapping("/api/v1/psp/admin/channels")
@PreAuthorize("hasAnyRole('ADMIN','OPS')")
public class ChannelsAdminController {

    public record PartnerBody(String name, Long merchantId, Set<String> scopes, String allowedIps, String mtlsCertSha256, int rateLimitPerMinute) {}
    public record StatusBody(String status) {}
    public record TerminalBody(long merchantId, String type, String serialNumber, String model, String location) {}

    private final ChannelAdminService channels;

    public ChannelsAdminController(ChannelAdminService channels) {
        this.channels = channels;
    }

    @PostMapping("/partners")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ChannelAdminService.IssuedSecret>> partner(@RequestBody PartnerBody b, HttpServletRequest req) {
        return Api.created(channels.createPartner(b.name(), b.merchantId(), b.scopes(), b.allowedIps(), b.mtlsCertSha256(),
                b.rateLimitPerMinute(), Actor.current()), req);
    }

    @GetMapping("/partners")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> partners(HttpServletRequest req) {
        return Api.ok(channels.partners().stream().map(p -> Map.<String, Object>of("keyId", p.getKeyId(), "name", p.getName(),
                "merchantId", String.valueOf(p.getMerchantId()), "scopes", p.getScopes(), "status", p.getStatus(),
                "mtls", p.getMtlsCertSha256() != null, "lastUsedAt", String.valueOf(p.getLastUsedAt()))).collect(Collectors.toList()), req);
    }

    @PostMapping("/partners/{keyId}/rotate-secret")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ChannelAdminService.IssuedSecret>> rotate(@PathVariable String keyId, HttpServletRequest req) {
        return Api.ok(channels.rotatePartnerSecret(keyId, Actor.current()), req);
    }

    @PostMapping("/partners/{keyId}/status")
    public ResponseEntity<ApiResponse<String>> partnerStatus(@PathVariable String keyId, @RequestBody StatusBody b, HttpServletRequest req) {
        channels.setPartnerStatus(keyId, b.status(), Actor.current());
        return Api.ok(b.status(), req);
    }

    @PostMapping("/terminals")
    public ResponseEntity<ApiResponse<ChannelAdminService.IssuedSecret>> terminal(@RequestBody TerminalBody b, HttpServletRequest req) {
        return Api.created(channels.registerTerminal(b.merchantId(), b.type(), b.serialNumber(), b.model(), b.location(), Actor.current()), req);
    }

    @PostMapping("/terminals/{terminalId}/status")
    public ResponseEntity<ApiResponse<String>> terminalStatus(@PathVariable String terminalId, @RequestBody StatusBody b, HttpServletRequest req) {
        channels.setTerminalStatus(terminalId, b.status(), Actor.current());
        return Api.ok(b.status(), req);
    }
}
