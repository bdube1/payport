package zw.co.payport.gateway.psp.ledger;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;

/**
 * Creates ledger accounts (and their balance rows) on first use, in their
 * own short transaction. If two postings race to create the same account,
 * one insert loses on the unique code and simply re-reads the winner,
 * without poisoning the caller's posting transaction.
 */
@Component
public class AccountProvisioner {

    private final LedgerAccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final TransactionTemplate newTx;

    public AccountProvisioner(LedgerAccountRepository accounts, AccountBalanceRepository balances,
                              PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.balances = balances;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LedgerAccount getOrCreate(AccountKey key) {
        return accounts.findByCode(key.code()).orElseGet(() -> create(key));
    }

    private LedgerAccount create(AccountKey key) {
        try {
            return newTx.execute(s -> {
                LedgerAccount a = new LedgerAccount();
                a.setCode(key.code());
                a.setCategory(key.category());
                a.setAccountType(key.category().type());
                a.setOwnerRef(key.ownerRef());
                a.setCurrency(key.currency());
                a.setAllowNegative(key.category().allowNegative());
                a.setStatus("OPEN");
                accounts.saveAndFlush(a);

                AccountBalance b = new AccountBalance();
                b.setAccountId(a.getId());
                b.setBalanceMinor(0);
                b.setDebitsMinor(0);
                b.setCreditsMinor(0);
                balances.saveAndFlush(b);
                return a;
            });
        } catch (DataIntegrityViolationException raced) {
            return accounts.findByCode(key.code())
                    .orElseThrow(() -> new IllegalStateException("Ledger account " + key.code() + " could not be created", raced));
        }
    }
}
