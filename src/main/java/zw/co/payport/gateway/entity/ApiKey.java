package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;

    /** TEST | LIVE */
    @Column(nullable = false)
    private String environment;

    @Column(nullable = false, unique = true)
    private String publicKey;

    /** bcrypt hash -- the raw secret is shown to the merchant exactly once, at creation. */
    @Column(nullable = false)
    private String secretKeyHash;

    private String label;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;

    /** Comma-separated scopes, or "*" for full access (keys created before Phase 4). See ApiKeyScopes. */
    @Column(nullable = false)
    @Builder.Default
    private String scopes = "*";

    private LocalDateTime expiresAt;

    /** The key this one replaced (rotation). */
    private Long rotatedFromId;
}
