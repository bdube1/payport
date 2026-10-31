package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhooks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private String url;

    /** Raw, not hashed -- the merchant needs the actual value to verify signatures on their own server. */
    /** Signing secret, encrypted at rest ("enc:v1:...", see SecretBox); legacy rows hold plaintext. */
    @Column(nullable = false, length = 500)
    private String secret;

    /** v1 = X-Payport-Signature only; v2 adds Payport-Signature (timestamped) and Payport-Event-Id. */
    @Column(nullable = false)
    @Builder.Default
    private String apiVersion = "v1";

    private String disabledReason;

    /** ACTIVE | DISABLED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
