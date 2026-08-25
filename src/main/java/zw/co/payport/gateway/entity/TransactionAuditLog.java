package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.payport.gateway.enums.TransactionStatus;

import java.time.LocalDateTime;

/**
 * Immutable audit log entry.
 *
 * CRITICAL RULES:
 * 1. NEVER update or delete rows in this table.
 * 2. Every state transition MUST produce an audit log entry.
 * 3. Raw API payloads are stored in payload_snapshot for full traceability.
 * 4. actor field: "SYSTEM", "ECOCASH_CALLBACK", "ONEMONEY_CALLBACK", "OPS:username"
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "transaction_audit_log", indexes = {
        @Index(name = "idx_audit_reference", columnList = "transaction_reference"),
        @Index(name = "idx_audit_created",   columnList = "created_at")
})
public class TransactionAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, length = 24)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private TransactionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private TransactionStatus toStatus;

    // Who caused this state change:
    // "SYSTEM" for automated transitions
    // "ECOCASH_CALLBACK" / "ONEMONEY_CALLBACK" for provider callbacks
    // "OPS:john.doe" for manual ops actions
    @Column(nullable = false, length = 100)
    private String actor;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // Raw API response or event payload stored verbatim for debugging and reconciliation
    @Column(name = "payload_snapshot", columnDefinition = "TEXT")
    private String payloadSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
