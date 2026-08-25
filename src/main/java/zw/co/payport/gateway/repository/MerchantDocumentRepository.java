package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.MerchantDocument;

import java.util.List;

@Repository
public interface MerchantDocumentRepository extends JpaRepository<MerchantDocument, Long> {
    List<MerchantDocument> findByMerchantIdOrderByUploadedAtDesc(Long merchantId);
}
