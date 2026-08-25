package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.entity.MerchantDocument;
import zw.co.payport.gateway.repository.MerchantDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantDocumentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "CERTIFICATE_OF_INCORPORATION", "PROOF_OF_ADDRESS", "DIRECTOR_ID", "TAX_CLEARANCE", "BANK_CONFIRMATION", "OTHER"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private final MerchantDocumentRepository merchantDocumentRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${payport.upload.kyc-directory}")
    private String kycDirectory;

    public MerchantDocument upload(Long merchantId, String documentType, MultipartFile file) throws IOException {
        if (documentType == null || !ALLOWED_TYPES.contains(documentType.toUpperCase())) {
            throw new IllegalArgumentException("documentType must be one of " + ALLOWED_TYPES);
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File is too large -- 10MB maximum");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String extension = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only PDF, PNG, and JPG files are accepted");
        }

        Path uploadPath = Paths.get(kycDirectory, "merchant-" + merchantId);
        Files.createDirectories(uploadPath);

        String storedFilename = documentType.toUpperCase() + "-" + randomToken() + "." + extension;
        Path filePath = uploadPath.resolve(storedFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        MerchantDocument doc = MerchantDocument.builder()
                .merchantId(merchantId)
                .documentType(documentType.toUpperCase())
                .filePath(filePath.toString())
                .originalFilename(originalFilename)
                .build();
        merchantDocumentRepository.save(doc);

        log.info("[MERCHANT_DOC] Uploaded {} for merchant {}: {}", documentType, merchantId, storedFilename);
        return doc;
    }

    public List<MerchantDocument> listForMerchant(Long merchantId) {
        return merchantDocumentRepository.findByMerchantIdOrderByUploadedAtDesc(merchantId);
    }

    public MerchantDocument getOrThrow(Long documentId) {
        return merchantDocumentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
    }

    private String randomToken() {
        byte[] bytes = new byte[9];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
