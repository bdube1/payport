package zw.co.payport.gateway.notifications;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

/** The mobile app registers its Expo push token after login (and removes it on logout). */
@RestController
@RequestMapping("/api/v1/profile/push-token")
public class PushTokenController {

    private final PushNotificationService push;
    private final UserRepository users;

    public PushTokenController(PushNotificationService push, UserRepository users) {
        this.push = push;
        this.users = users;
    }

    public record Body(String token, String platform) {}

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@RequestBody Body b, HttpServletRequest req) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.status(401).body(ApiResponse.error("UNAUTHORIZED", "Log in to receive notifications", rid(req)));
        try {
            push.register(userId, b == null ? null : b.token(), b == null ? null : b.platform());
            return ResponseEntity.ok(ApiResponse.success(Map.of("registered", true), rid(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_PUSH_TOKEN", e.getMessage(), rid(req)));
        }
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> unregister(@RequestBody Body b, HttpServletRequest req) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.status(401).body(ApiResponse.error("UNAUTHORIZED", "Not logged in", rid(req)));
        push.unregister(userId, b == null ? null : b.token());
        return ResponseEntity.ok(ApiResponse.success(Map.of("registered", false), rid(req)));
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        return users.findByPhone(auth.getName()).map(u -> u.getId()).orElse(null);
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
