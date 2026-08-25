package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.repository.MerchantRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MerchantRiskScoringService {

    private static final Set<String> FREE_EMAIL_DOMAINS = Set.of(
            "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "icloud.com", "aol.com"
    );

    private final MerchantRepository merchantRepository;

    public record RiskScoreResult(int score, List<String> flags) {}

    public RiskScoreResult score(String businessName, String registrationNumber, String contactEmail,
                                  String contactPhone, String businessCategory) {
        int score = 0;
        List<String> flags = new ArrayList<>();

        String emailDomain = contactEmail != null && contactEmail.contains("@")
                ? contactEmail.substring(contactEmail.indexOf('@') + 1).toLowerCase().trim() : "";
        if (FREE_EMAIL_DOMAINS.contains(emailDomain)) {
            score += 20;
            flags.add("free_email_domain");
        }

        if (businessName == null || businessName.trim().length() < 4) {
            score += 15;
            flags.add("very_short_business_name");
        }

        if (businessCategory == null || businessCategory.isBlank()) {
            score += 10;
            flags.add("no_business_category");
        }

        if (registrationNumber != null && registrationNumber.chars().distinct().count() <= 2) {
            score += 20;
            flags.add("suspicious_registration_number_pattern");
        }

        boolean matchesRejectedApplicant = merchantRepository.findAll().stream()
                .filter(m -> "REJECTED".equals(m.getStatus()))
                .anyMatch(m -> m.getContactEmail().equalsIgnoreCase(contactEmail) || m.getContactPhone().equals(contactPhone));
        if (matchesRejectedApplicant) {
            score += 30;
            flags.add("contact_info_matches_previously_rejected_application");
        }

        return new RiskScoreResult(Math.min(score, 100), flags);
    }
}
