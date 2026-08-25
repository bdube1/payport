package zw.co.payport.gateway.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import zw.co.payport.gateway.entity.Merchant;

/**
 * A request to /api/v1/merchant/** can be authenticated by either
 * ApiKeyAuthenticationFilter (a merchant's server, secret key) or
 * MerchantJwtAuthenticationFilter (a merchant's dashboard user, browser
 * session) -- two different principal types. Controllers shouldn't each
 * duplicate the logic for telling them apart; this is the one place that
 * does it.
 */
public class MerchantAuthResolver {

    private MerchantAuthResolver() {}

    public static Merchant currentMerchant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            if (auth.getPrincipal() instanceof ApiKeyAuthenticationFilter.MerchantPrincipal p) {
                return p.merchant();
            }
            if (auth.getPrincipal() instanceof MerchantJwtAuthenticationFilter.MerchantUserPrincipal p) {
                return p.merchant();
            }
        }
        throw new RuntimeException("Not authenticated as a merchant");
    }

    /** Null when authenticated by API key -- a server calling the API isn't "a person" with a role. */
    public static zw.co.payport.gateway.entity.MerchantUser currentMerchantUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MerchantJwtAuthenticationFilter.MerchantUserPrincipal p) {
            return p.merchantUser();
        }
        return null;
    }
}
