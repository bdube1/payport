package zw.co.payport.gateway.channels;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/channels")
public class ChannelController {

    private final ChannelService channels;

    public ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(channels.list(), rid(req)));
    }

    public record SwitchBody(boolean enabled, String reason) {}

    @PostMapping("/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> set(@PathVariable String code, @RequestBody SwitchBody b, HttpServletRequest req) {
        try {
            String by = "staff:" + SecurityContextHolder.getContext().getAuthentication().getName();
            return ResponseEntity.ok(ApiResponse.success(channels.set(code, b.enabled(), b.reason(), by), rid(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("CHANNEL_CHANGE_REFUSED", e.getMessage(), rid(req)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("CHANNEL_CHANGE_REFUSED", e.getMessage(), rid(req)));
        }
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
