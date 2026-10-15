package zw.co.payport.gateway.psp.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface RequestNonceRepository extends JpaRepository<RequestNonce, Long> {
    @Modifying
    @Query("DELETE FROM RequestNonce n WHERE n.createdAt < :before")
    int purgeBefore(@Param("before") LocalDateTime before);
}
