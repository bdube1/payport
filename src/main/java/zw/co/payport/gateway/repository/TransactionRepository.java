package zw.co.payport.gateway.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReference(String reference);

    /**
     * Loads the transaction with a row lock (SELECT ... FOR UPDATE) held until the surrounding
     * transaction ends. Every payment lifecycle change goes through this, so callbacks, the
     * resolver, the timeout watchdog and the submit flow are serialised per payment.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.reference = :ref")
    Optional<Transaction> lockByReference(@Param("ref") String reference);
    boolean existsByReference(String reference);
    List<Transaction> findByStatusIn(List<TransactionStatus> statuses);

    // Used by ZimSwitch callback controller to look up a transaction by checkoutId
    Optional<Transaction> findByPaymentCorrelator(String paymentCorrelator);

    // Used by statement reconciliation to load the candidate set for a
    // given provider/period before matching -- COMPLETED transactions
    // are the ones that should genuinely appear on a provider's
    // settlement statement. Payment-side providers (EcoCash, OneMoney,
    // Omari) are looked up by paymentProviderCode; fulfilment-side
    // providers (NetOne, Telecel, Econet) by fulfilmentProviderCode --
    // see ReconciliationService for which is used per provider.
    @Query("SELECT t FROM Transaction t WHERE t.paymentProviderCode = :providerCode "
            + "AND t.status = 'COMPLETED' AND t.createdAt BETWEEN :from AND :to")
    List<Transaction> findCompletedByPaymentProviderAndPeriod(
            @Param("providerCode") String providerCode,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT t FROM Transaction t WHERE t.fulfilmentProviderCode = :providerCode "
            + "AND t.status = 'COMPLETED' AND t.createdAt BETWEEN :from AND :to")
    List<Transaction> findCompletedByFulfilmentProviderAndPeriod(
            @Param("providerCode") String providerCode,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT t FROM Transaction t WHERE t.status = :status AND t.createdAt < :cutoff")
    List<Transaction> findTimedOutTransactions(
            @Param("status") TransactionStatus status,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * Find PAYMENT_PENDING transactions that have been pending longer than the timeout threshold
     * Uses payment_submitted_at to determine when the payment was initiated
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = :status AND t.paymentSubmittedAt < :cutoff")
    List<Transaction> findByStatusAndPaymentSubmittedAtBefore(
            @Param("status") TransactionStatus status,
            @Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status = :status")
    long countByStatus(@Param("status") TransactionStatus status);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.createdAt >= :since AND t.status = 'COMPLETED'")
    long countCompletedSince(@Param("since") LocalDateTime since);

    @Query("SELECT SUM(t.amountCents) FROM Transaction t WHERE t.createdAt >= :since AND t.status = 'COMPLETED'")
    Long sumAmountCentsSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.createdAt >= :since AND t.status = :status")
    long countByStatusAndCreatedAtAfter(
            @Param("status") TransactionStatus status,
            @Param("since") LocalDateTime since);

    @Query("SELECT t FROM Transaction t WHERE " +
            "(:reference IS NULL OR t.reference LIKE %:reference%) AND " +
            "(:status IS NULL OR t.status = :status) AND " +
            "(:customerPhone IS NULL OR t.customerPhone LIKE %:customerPhone%) AND " +
            "(:recipientIdentifier IS NULL OR t.recipientIdentifier LIKE %:recipientIdentifier%) AND " +
            "(:productTypeCode IS NULL OR t.productType.code = :productTypeCode) AND " +
            "(:providerReference IS NULL OR t.providerReference LIKE %:providerReference%) AND " +
            "(:paymentCorrelator IS NULL OR t.paymentCorrelator LIKE %:paymentCorrelator%) AND " +
            "(:dateFrom IS NULL OR t.createdAt >= :dateFrom) AND " +
            "(:dateTo IS NULL OR t.createdAt <= :dateTo)")
    Page<Transaction> searchTransactions(
            @Param("reference") String reference,
            @Param("status") TransactionStatus status,
            @Param("customerPhone") String customerPhone,
            @Param("recipientIdentifier") String recipientIdentifier,
            @Param("productTypeCode") String productTypeCode,
            @Param("providerReference") String providerReference,
            @Param("paymentCorrelator") String paymentCorrelator,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable);

    // Same filters as searchTransactions, plus excluding merchant checkout
    // activity (product codes MERCHANT_CHECKOUT / MERCHANT_CHECKOUT_ZWG) --
    // Payport's own airtime/data/bills business, cleanly separate from
    // anything a merchant's customer paid for. A dedicated method rather
    // than adding a flag to the existing query above, so the
    // already-working general search stays untouched.
    @Query("SELECT t FROM Transaction t WHERE " +
            "t.productType.code NOT IN ('MERCHANT_CHECKOUT', 'MERCHANT_CHECKOUT_ZWG') AND " +
            "(:reference IS NULL OR t.reference LIKE %:reference%) AND " +
            "(:status IS NULL OR t.status = :status) AND " +
            "(:customerPhone IS NULL OR t.customerPhone LIKE %:customerPhone%) AND " +
            "(:recipientIdentifier IS NULL OR t.recipientIdentifier LIKE %:recipientIdentifier%) AND " +
            "(:productTypeCode IS NULL OR t.productType.code = :productTypeCode) AND " +
            "(:dateFrom IS NULL OR t.createdAt >= :dateFrom) AND " +
            "(:dateTo IS NULL OR t.createdAt <= :dateTo)")
    Page<Transaction> searchPayportOnlyTransactions(
            @Param("reference") String reference,
            @Param("status") TransactionStatus status,
            @Param("customerPhone") String customerPhone,
            @Param("recipientIdentifier") String recipientIdentifier,
            @Param("productTypeCode") String productTypeCode,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable);

    // For customer statement
    @Query("SELECT t FROM Transaction t WHERE t.customerPhone = :phone ORDER BY t.createdAt DESC")
    Page<Transaction> findByCustomerPhone(
            @Param("phone") String phone,
            Pageable pageable);

    // For transaction history by user
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId ORDER BY t.createdAt DESC")
    Page<Transaction> findByUserId(
            @Param("userId") Long userId,
            Pageable pageable);

    @Modifying
    @Query("UPDATE Transaction t SET t.status = :newStatus, t.updatedAt = :now " +
            "WHERE t.reference = :ref AND t.status = :expectedStatus")
    int updateStatusOptimistic(
            @Param("ref") String reference,
            @Param("expectedStatus") TransactionStatus expectedStatus,
            @Param("newStatus") TransactionStatus newStatus,
            @Param("now") LocalDateTime now);

    // Used by ProviderHealthScheduler to count recent failures per provider
    @Query("SELECT COUNT(t) FROM Transaction t WHERE " +
            "(t.recipientNetwork = :providerCode OR " +
            " t.fulfilmentProviderCode = :providerCode OR " +
            " t.paymentProviderCode = :providerCode) AND " +
            "t.status IN (" +
            " zw.co.payport.gateway.enums.TransactionStatus.FULFILMENT_FAILED," +
            " zw.co.payport.gateway.enums.TransactionStatus.PAYMENT_FAILED," +
            " zw.co.payport.gateway.enums.TransactionStatus.PAYMENT_TIMEOUT) AND " +
            "t.createdAt >= :since")
    long countRecentFailuresByProvider(
            @Param("providerCode") String providerCode,
            @Param("since") LocalDateTime since);
}
