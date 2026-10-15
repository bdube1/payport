package zw.co.payport.gateway.psp.ledger;

/**
 * Ledger persistence lives in these repositories:
 * {@link LedgerAccountRepository}, {@link JournalEntryRepository},
 * {@link JournalLineRepository}, {@link AccountBalanceRepository}, {@link FundHoldRepository}.
 *
 * Only {@link LedgerService} writes journal rows. Other modules call the
 * service; they never touch these repositories for writes.
 */
public final class LedgerRepositories {
    private LedgerRepositories() {}
}
