package zw.co.payport.gateway.psp.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletTariffRepository extends JpaRepository<WalletTariff, Long> {
    List<WalletTariff> findByOperationAndCurrencyAndStatus(String operation, String currency, String status);
    List<WalletTariff> findAllByOrderByOperationAscCurrencyAscBandMinMinorAsc();
}
