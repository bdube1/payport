package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.PlatformSetting;
import zw.co.payport.gateway.repository.PlatformSettingRepository;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    private static final String DEFAULT_COMMISSION_KEY = "default_commission_bps";
    private static final int FALLBACK_BPS = 250; // only used if the platform_settings row is somehow missing entirely

    private final PlatformSettingRepository platformSettingRepository;

    public record CommissionResult(int bpsApplied, long commissionAmountCents, long netAmountCents) {}

    /** A merchant's own commission_rate_bps overrides the platform default when set; otherwise the platform default applies. */
    public int resolveEffectiveBps(Merchant merchant) {
        if (merchant.getCommissionRateBps() != null) {
            return merchant.getCommissionRateBps();
        }
        return getPlatformDefaultBps();
    }

    public int getPlatformDefaultBps() {
        return platformSettingRepository.findById(DEFAULT_COMMISSION_KEY)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.getValue());
                    } catch (NumberFormatException e) {
                        log.warn("[COMMISSION] platform_settings.default_commission_bps is not a valid integer: '{}', using fallback", s.getValue());
                        return FALLBACK_BPS;
                    }
                })
                .orElse(FALLBACK_BPS);
    }

    public void setPlatformDefaultBps(int bps) {
        validateBps(bps);
        PlatformSetting setting = platformSettingRepository.findById(DEFAULT_COMMISSION_KEY)
                .orElse(PlatformSetting.builder().key(DEFAULT_COMMISSION_KEY).build());
        setting.setValue(String.valueOf(bps));
        setting.setUpdatedAt(LocalDateTime.now());
        platformSettingRepository.save(setting);
        log.info("[COMMISSION] Platform default commission set to {} bps", bps);
    }

    public void validateBps(int bps) {
        if (bps < 0 || bps > 10000) {
            throw new RuntimeException("Commission rate must be between 0 and 10000 basis points (0%-100%)");
        }
    }

    /** Frozen at the moment of calling -- see the comment on CheckoutSession.commissionBpsApplied for why. */
    public CommissionResult calculate(Merchant merchant, long grossAmountCents) {
        int bps = resolveEffectiveBps(merchant);
        long commission = Math.round(grossAmountCents * (bps / 10000.0));
        long net = grossAmountCents - commission;
        return new CommissionResult(bps, commission, net);
    }
}
