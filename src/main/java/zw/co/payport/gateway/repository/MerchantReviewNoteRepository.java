package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.MerchantReviewNote;

import java.util.List;

@Repository
public interface MerchantReviewNoteRepository extends JpaRepository<MerchantReviewNote, Long> {
    List<MerchantReviewNote> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<MerchantReviewNote> findByMerchantIdAndStatus(Long merchantId, String status);
}
