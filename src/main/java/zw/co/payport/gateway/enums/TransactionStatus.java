package zw.co.payport.gateway.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * PayPort Transaction State Machine
 *
 * INITIATED → PAYMENT_PENDING → PAYMENT_CONFIRMED → FULFILMENT_PENDING → COMPLETED
 *
 * Failure paths:
 * PAYMENT_PENDING    → PAYMENT_FAILED | PAYMENT_TIMEOUT
 * PAYMENT_TIMEOUT    → PAYMENT_CONFIRMED | PAYMENT_FAILED (after reconciliation)
 * FULFILMENT_PENDING → FULFILMENT_FAILED
 * FULFILMENT_FAILED  → FULFILMENT_RETRY → COMPLETED | FULFILMENT_FAILED
 * FULFILMENT_FAILED  → MANUAL_FULFILMENT → COMPLETED | REFUND_PENDING
 * COMPLETED          → REFUND_PENDING → REFUNDED
 * INITIATED          → CANCELLED
 *
 * CRITICAL RULE: FULFILMENT_PENDING can ONLY be entered from PAYMENT_CONFIRMED.
 * CRITICAL RULE: Terminal states (REFUNDED, CANCELLED) cannot be left. PAYMENT_FAILED can only start a new
 * payment attempt on the same intent (PAYMENT_FAILED -> PAYMENT_PENDING), never anything else.
 */
public enum TransactionStatus {

    INITIATED,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    PAYMENT_FAILED,
    PAYMENT_TIMEOUT,
    FULFILMENT_PENDING,
    FULFILMENT_FAILED,
    FULFILMENT_RETRY,
    MANUAL_FULFILMENT,
    COMPLETED,
    REFUND_PENDING,
    REFUNDED,
    CANCELLED;

    /**
     * Returns the set of states this status can transition TO.
     * Any transition not in this set is ILLEGAL and must throw an exception.
     */
    public Set<TransactionStatus> getAllowedTransitions() {
        return switch (this) {
            case INITIATED         -> EnumSet.of(PAYMENT_PENDING, CANCELLED);
            case PAYMENT_PENDING   -> EnumSet.of(PAYMENT_CONFIRMED, PAYMENT_FAILED, PAYMENT_TIMEOUT);
            case PAYMENT_CONFIRMED -> EnumSet.of(FULFILMENT_PENDING);
            case PAYMENT_TIMEOUT   -> EnumSet.of(PAYMENT_CONFIRMED, PAYMENT_FAILED);
            case FULFILMENT_PENDING-> EnumSet.of(COMPLETED, FULFILMENT_FAILED);
            case FULFILMENT_FAILED -> EnumSet.of(FULFILMENT_RETRY, MANUAL_FULFILMENT, REFUND_PENDING);
            case FULFILMENT_RETRY  -> EnumSet.of(COMPLETED, FULFILMENT_FAILED);
            case MANUAL_FULFILMENT -> EnumSet.of(COMPLETED, REFUND_PENDING);
            case COMPLETED         -> EnumSet.of(REFUND_PENDING);
            case REFUND_PENDING    -> EnumSet.of(REFUNDED);
            // Terminal states - no transitions allowed
            // A definitely failed payment may be attempted again on the same intent (new attempt, new provider reference).
            case PAYMENT_FAILED    -> EnumSet.of(PAYMENT_PENDING);
            case REFUNDED, CANCELLED -> EnumSet.noneOf(TransactionStatus.class);
        };
    }

    public boolean canTransitionTo(TransactionStatus next) {
        return getAllowedTransitions().contains(next);
    }

    public boolean isTerminal() {
        return this == PAYMENT_FAILED || this == REFUNDED || this == CANCELLED;
    }

    public boolean isSuccessful() {
        return this == COMPLETED;
    }

    public boolean requiresAction() {
        return this == FULFILMENT_FAILED || this == MANUAL_FULFILMENT
                || this == PAYMENT_TIMEOUT || this == REFUND_PENDING;
    }
}
