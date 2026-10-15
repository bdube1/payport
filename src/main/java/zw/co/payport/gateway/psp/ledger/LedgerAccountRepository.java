package zw.co.payport.gateway.psp.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {
    Optional<LedgerAccount> findByCode(String code);
    List<LedgerAccount> findByCodeIn(Collection<String> codes);
    List<LedgerAccount> findByOwnerRefAndCategoryIn(String ownerRef, Collection<AccountCategory> categories);
    List<LedgerAccount> findByCategory(AccountCategory category);
    List<LedgerAccount> findByCategoryAndCurrency(AccountCategory category, String currency);
}
