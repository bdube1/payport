package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.entity.QueryAttachment;
import zw.co.payport.gateway.repository.QueryAttachmentRepository;

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
public class QueryAttachmentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private final QueryAttachmentRepository queryAttachmentRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${payport.upload.query-attachments-directory}")
    private String attachmentsDirectory;

    public QueryAttachment upload(Long queryId, MultipartFile file, String uploadedByType, Long uploadedById) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File is too large -- 10MB maximum");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment";
        String extension = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only PDF, PNG, and JPG files are accepted");
        }

        Path uploadPath = Paths.get(attachmentsDirectory, "query-" + queryId);
        Files.createDirectories(uploadPath);

        String storedFilename = randomToken() + "." + extension;
        Path filePath = uploadPath.resolve(storedFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        QueryAttachment attachment = QueryAttachment.builder()
                .queryId(queryId)
                .originalFilename(originalFilename)
                .storedFilename(filePath.toString())
                .contentType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .uploadedByType(uploadedByType)
                .uploadedById(uploadedById)
                .build();
        queryAttachmentRepository.save(attachment);

        log.info("[QUERY_ATTACHMENT] Uploaded for query {} by {} {}: {}", queryId, uploadedByType, uploadedById, storedFilename);
        return attachment;
    }

    public List<QueryAttachment> listForQuery(Long queryId) {
        return queryAttachmentRepository.findByQueryIdOrderByCreatedAtDesc(queryId);
    }

    public QueryAttachment getOrThrow(Long attachmentId) {
        return queryAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new RuntimeException("Attachment not found: " + attachmentId));
    }

    private String randomToken() {
        byte[] bytes = new byte[9];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
