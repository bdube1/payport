package zw.co.payport.gateway.psp.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    Optional<AccountBalance> findByAccountId(Long accountId);

    /**
     * SELECT ... FOR UPDATE on every account an entry touches, always in
     * ascending account id order so two concurrent postings can never
     * deadlock on each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AccountBalance b WHERE b.accountId IN :ids ORDER BY b.accountId ASC")
    List<AccountBalance> lockForUpdate(@Param("ids") Collection<Long> accountIds);
}
