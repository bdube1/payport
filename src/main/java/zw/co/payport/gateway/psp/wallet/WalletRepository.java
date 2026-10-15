package zw.co.payport.gateway.psp.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByWalletRef(String walletRef);
    Optional<Wallet> findByOwnerTypeAndOwnerRefAndCurrency(String ownerType, String ownerRef, String currency);
    List<Wallet> findByOwnerTypeAndOwnerRef(String ownerType, String ownerRef);
}
