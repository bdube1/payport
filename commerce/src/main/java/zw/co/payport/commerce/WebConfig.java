package zw.co.payport.commerce;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * The merchant API (/api/commerce/**) needs a Commerce session (Tokens) and is callable from the merchant portal's
 * origin only (CORS). Store pages are public and carry strict security headers: scripts and styles from this site
 * only, no framing, and forms may only go here or to the PSP's checkout.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final String MERCHANT = "commerce.merchant";
    private final Tokens tokens;
    private final Settings settings;

    public WebConfig(Tokens tokens, Settings settings) {
        this.tokens = tokens;
        this.settings = settings;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (settings.portalOrigins.isEmpty()) return;
        registry.addMapping("/api/commerce/**").allowedOrigins(settings.portalOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE").allowedHeaders("Authorization", "Content-Type").maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
                if ("OPTIONS".equals(req.getMethod())) return true;
                String h = req.getHeader("Authorization");
                Tokens.Merchant m = h != null && h.startsWith("Bearer ") ? tokens.read(h.substring(7).trim()) : null;
                if (m == null) {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"success\":false,\"error\":{\"code\":\"SIGN_IN\",\"message\":\"Please sign in again\"}}");
                    return false;
                }
                req.setAttribute(MERCHANT, m);
                return true;
            }
        }).addPathPatterns("/api/commerce/**").excludePathPatterns("/api/commerce/session");
    }

    @Component
    public static class Headers extends OncePerRequestFilter {
        private final Settings settings;

        public Headers(Settings settings) {
            this.settings = settings;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            res.setHeader("X-Frame-Options", "DENY");
            res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            if (!req.getRequestURI().startsWith("/api/")) {
                res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                        + "frame-ancestors 'none'; base-uri 'none'; object-src 'none'; form-action 'self' " + String.join(" ", settings.checkoutOrigins));
            }
            chain.doFilter(req, res);
        }
    }
}
