package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.PaymentLink;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.PaymentLinkRepository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * A shareable checkout URL a merchant creates from the dashboard, no API
 * integration required -- for merchants without their own developer.
 * Visiting the link creates a real CheckoutSession, so everything else
 * (the hosted page, webhooks, status polling) is the exact same machinery
 * Phase 1 already built; a payment link is just a different way to
 * arrive at a session, not a parallel system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentLinkService {

    private final PaymentLinkRepository paymentLinkRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final CheckoutSessionService checkoutSessionService;
    private final MerchantRepository merchantRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public record CreateLinkRequest(Long amountCents, String currency, String description, Boolean reusable) {}

    public PaymentLink create(Merchant merchant, CreateLinkRequest req) {
        String currency = (req.currency() == null || req.currency().isBlank()) ? merchant.getDefaultCurrency() : req.currency().toUpperCase();
        // Links always take live money: unknown currencies and sandbox-market currencies are refused here.
        if (!checkoutSessionService.isSupportedCurrency(currency)) {
            throw new RuntimeException("Unsupported currency " + currency);
        }
        // Links always take live money, so the currency must be enabled for the merchant.
        checkoutSessionService.requireLiveCurrency(merchant.getId(), currency);
        PaymentLink link = PaymentLink.builder()
                .merchantId(merchant.getId())
                .slug(randomSlug())
                .amountCents(req.amountCents())
                .currency(currency)
                .description(req.description())
                .status("ACTIVE")
                .reusable(req.reusable() == null || req.reusable())
                .build();
        paymentLinkRepository.save(link);
        log.info("[PAYMENT_LINK] Created for merchant {}: {}", merchant.getId(), link.getSlug());
        return link;
    }

    public List<PaymentLink> listForMerchant(Long merchantId) {
        return paymentLinkRepository.findByMerchantId(merchantId);
    }

    public PaymentLink setStatus(Merchant merchant, Long linkId, String status) {
        PaymentLink link = paymentLinkRepository.findById(linkId)
                .orElseThrow(() -> new RuntimeException("Payment link not found: " + linkId));
        if (!link.getMerchantId().equals(merchant.getId())) {
            throw new RuntimeException("Payment link not found: " + linkId);
        }
        link.setStatus(status);
        paymentLinkRepository.save(link);
        return link;
    }

    public PaymentLink getBySlug(String slug) {
        return paymentLinkRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("This payment link doesn't exist"));
    }

    /** Public -- called when a customer visits the link. Creates a fresh CheckoutSession and returns its id. */
    public Long startCheckoutFromLink(String slug, Long customAmountCents) {
        PaymentLink link = getBySlug(slug);
        if (!"ACTIVE".equals(link.getStatus())) {
            throw new RuntimeException("This payment link is no longer active");
        }

        if (Boolean.FALSE.equals(link.getReusable())) {
            boolean alreadyUsed = checkoutSessionRepository.findByMerchantId(link.getMerchantId()).stream()
                    .anyMatch(s -> ("paylink:" + link.getId()).equals(s.getMetadata()) && "PAID".equals(s.getStatus()));
            if (alreadyUsed) {
                throw new RuntimeException("This payment link has already been used");
            }
        }

        Long amount = link.getAmountCents() != null ? link.getAmountCents() : customAmountCents;
        if (amount == null || amount < 1) {
            throw new RuntimeException("An amount is required for this payment link");
        }

        Merchant merchant = merchantRepository.findById(link.getMerchantId())
                .orElseThrow(() -> new RuntimeException("Merchant not found for this payment link"));

        var session = checkoutSessionService.createSession(
                merchant, null,
                new CheckoutSessionService.CreateSessionRequest(
                        null, amount, link.getCurrency(), link.getDescription(),
                        null, null, null, null, "paylink:" + link.getId()
                )
        );
        return session.getId();
    }

    private String randomSlug() {
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase();
    }
}
