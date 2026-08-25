package zw.co.payport.gateway.service.reconciliation;

import java.io.InputStream;
import java.util.List;

/**
 * One implementation per provider (EcoCash, OneMoney, NetOne, Telecel,
 * Innbucks, Omari) -- each provider's statement export has its own
 * column layout and even file format (Excel vs PDF), isolated here the
 * same way each payment/fulfilment adaptor is isolated behind its own
 * class elsewhere in this codebase. Adding a seventh provider later
 * means writing one new class, not touching the matching engine.
 */
public interface StatementParser {

    /** Which provider this parser handles -- must match a value used
     *  consistently across ReconciliationRun.provider and the ops UI. */
    String providerCode();

    List<ProviderStatementLine> parse(InputStream file) throws StatementParseException;

    class StatementParseException extends Exception {
        public StatementParseException(String message) { super(message); }
        public StatementParseException(String message, Throwable cause) { super(message, cause); }
    }
}
