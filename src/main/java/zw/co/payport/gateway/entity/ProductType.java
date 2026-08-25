package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "product_types")
public class ProductType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    private String category;  // AIRTIME | DATA | BILLS

    @Column(name = "provider_code", nullable = false, length = 50)
    private String providerCode;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "min_amount_cents", nullable = false)
    private Long minAmountCents;  // CENTS - e.g. $0.50 = 50

    @Column(name = "max_amount_cents", nullable = false)
    private Long maxAmountCents;

    @Column(nullable = false, length = 3, columnDefinition = "VARCHAR(3)")
    private String currency;

    @Column(name = "logo_url", length = 255)
    private String logoUrl;  // URL to product/biller logo

    @Column(name = "fulfilment_config", columnDefinition = "TEXT")
    private String fulfilmentConfig;  // JSON

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (currency == null)  currency  = "USD";
        if (isActive == null)  isActive  = false;
    }

    public void validateAmount(long amountCents) {
        if (amountCents < this.minAmountCents || amountCents > this.maxAmountCents) {
            throw new IllegalArgumentException(
                    String.format("Amount %d cents is outside allowed range [%d, %d] for product %s",
                            amountCents, this.minAmountCents, this.maxAmountCents, this.code));
        }
    }
}