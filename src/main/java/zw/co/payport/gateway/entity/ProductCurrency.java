package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Per-product, per-currency configuration.
 *
 * Each active product can have 1-2 rows here (USD and/or ZWG).
 * The ops team can enable/disable a currency for a product without
 * touching the product itself.
 *
 * The consumer portal calls GET /api/v1/products and receives each
 * product with its list of enabled ProductCurrency rows — so only
 * enabled currencies ever reach the frontend.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product_currencies",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_product_currency",
           columnNames = {"product_type_id", "currency"}))
public class ProductCurrency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", nullable = false)
    private ProductType productType;

    /** ISO-4217: USD or ZWG */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Can be toggled by ops without touching the parent product */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /** Override min amount for this currency (cents).  Null = use product default */
    @Column(name = "min_amount_cents")
    private Long minAmountCents;

    /** Override max amount for this currency (cents).  Null = use product default */
    @Column(name = "max_amount_cents")
    private Long maxAmountCents;

    /** Display label shown in frontend e.g. "USD $" or "ZWG ZiG" */
    @Column(length = 20)
    private String label;

    /** Display order: 0 = first */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (isActive == null)     isActive     = false;
        if (displayOrder == null) displayOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Effective min — falls back to parent product if not overridden.
     *  Uses Long throughout and only unboxes at the very end, deliberately —
     *  a ternary mixing a boxed Long with a primitive-returning fallback
     *  will silently NPE if either side is ever null, which is exactly the
     *  kind of thing that only shows up once in production. */
    public long effectiveMin(ProductType product) {
        Long value = minAmountCents != null ? minAmountCents : product.getMinAmountCents();
        return value != null ? value : 50L;
    }

    /** Effective max — same reasoning as effectiveMin. */
    public long effectiveMax(ProductType product) {
        Long value = maxAmountCents != null ? maxAmountCents : product.getMaxAmountCents();
        return value != null ? value : 2000000L;
    }
}
