package zw.co.payport.gateway.psp.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, Long> {
    Optional<BankStatementLine> findByCashAccountAndLineHash(String cashAccount, String lineHash);
    List<BankStatementLine> findByImportRefOrderByValueDateAscIdAsc(String importRef);
    List<BankStatementLine> findByMatchStatusOrderByValueDateAsc(String matchStatus);
    List<BankStatementLine> findByMatchStatusAndValueDateBefore(String matchStatus, LocalDate before);
    List<BankStatementLine> findByCashAccountOrderByValueDateDescIdDesc(String cashAccount);
}
