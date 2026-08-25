package zw.co.payport.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import zw.co.payport.gateway.repository.UserRepository;

import java.util.List;

/**
 * Security Configuration
 *
 * JWT Bearer token authentication for all protected endpoints.
 * No Basic Auth - unified authentication across customer and ops portals.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   ApiKeyAuthenticationFilter apiKeyAuthFilter,
                                                   MerchantJwtAuthenticationFilter merchantJwtAuthFilter,
                                                   RateLimitFilter rateLimitFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints - always public (can't require login to log in)
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // Merchants Module, Phase 0 -- exact paths only, not a
                        // wildcard. Future authenticated merchant-dashboard
                        // routes will live under this same /merchant-portal/
                        // prefix, and a wildcard here would accidentally expose
                        // those too the moment they're added.
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant-portal/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant-portal/login").permitAll()

                        // Public application tracking -- a reference number is
                        // effectively a long random password (see
                        // MerchantService.generateApplicationReference), so no
                        // separate login is required to check status, upload a
                        // requested document, or acknowledge a review note.
                        // Deliberately its own /track/ sub-path, not folded into
                        // /merchant-portal/** generally, for the same reason the
                        // comment above gives -- exact paths, no accidental
                        // wildcard exposure.
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchant-portal/track/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant-portal/track/*/documents").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant-portal/track/*/review-notes/*/resolve").permitAll()

                        // Merchants Module, Phase 1 -- the hosted checkout page
                        // itself has no API key (it's the end customer's
                        // browser), so these two are genuinely public. The
                        // /api/v1/merchant/** developer API is deliberately
                        // NOT listed here -- it's authenticated by
                        // ApiKeyAuthenticationFilter and gated by
                        // @PreAuthorize("hasRole('MERCHANT')") on its
                        // controller, falling through to the authenticated()
                        // catch-all below like everything else not explicitly
                        // permitted.
                        .requestMatchers(HttpMethod.GET, "/api/v1/checkout/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/checkout/*/pay").permitAll()

                        // Payment Links -- a customer visiting a shareable
                        // link has no more auth than a hosted checkout page
                        // customer does. Same public-endpoint reasoning.
                        .requestMatchers(HttpMethod.GET, "/api/v1/pay/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pay/*/start").permitAll()

                        // Guest checkout - customers can pay without an account
                        .requestMatchers("/api/v1/transactions/initiate").permitAll()
                        .requestMatchers("/api/v1/transactions/pay").permitAll()
                        .requestMatchers("/api/v1/transactions/*").permitAll()
                        .requestMatchers("/api/v1/transactions/*/timeline").permitAll()
                        .requestMatchers("/api/v1/transactions/*/confirm-omari-otp").permitAll()

                        // Payment provider callbacks - reachable without a user JWT
                        // (server-to-server). See CustomerMessageTranslator/CallbackController
                        // for how each provider's payload is validated.
                        .requestMatchers("/api/v1/callbacks/**").permitAll()

                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
                        .requestMatchers("/").permitAll()

                        // Public catalogue / pre-payment lookups
                        .requestMatchers(HttpMethod.POST, "/api/v1/assistant/chat").permitAll()
                        .requestMatchers("/api/v1/products/**").permitAll()
                        .requestMatchers("/api/v1/bills/customer-info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bundles/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bundles/*/code/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bundles/*/categories").permitAll()

                        // Everything else requires a valid login. Staff-only endpoints are
                        // further restricted via @PreAuthorize on their controllers.
                        .anyRequest().authenticated()
                )
                // Rate limit login/register before anything else, then JWT auth
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(merchantJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        return http.build();
    }

    /**
     * CORS, defined once here as the actual source of truth. This used to
     * also live in AppConfig's WebMvcConfigurer -- removed from there,
     * since Spring Security's filter chain runs before Spring MVC ever
     * sees a request, which is exactly why an MVC-only CORS config was
     * silently never being applied to the OPTIONS preflight at all: the
     * .cors() call above is what actually makes Security let it through
     * before the browser's real request ever gets sent.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "https://payport.africa",
                "https://www.payport.africa",
                "https://app.payport.africa",
                "https://admin.payport.africa",
                "https://merchant.payport.africa",
                // Local dev only -- these origins can never exist on the public internet.
                "http://localhost:4321",
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:3002"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        // Callback URLs are open to payment providers -- server-to-server,
        // no browser origin involved, so no restriction needed. Registered
        // as its own pattern; UrlBasedCorsConfigurationSource picks the
        // most specific match automatically, so this correctly overrides
        // the broader /api/** rule above for this one path.
        CorsConfiguration callbackConfig = new CorsConfiguration();
        callbackConfig.setAllowedOrigins(List.of("*"));
        callbackConfig.setAllowedMethods(List.of("POST"));
        source.registerCorsConfiguration("/api/v1/callbacks/**", callbackConfig);

        return source;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return phone -> {
            // Normalize phone number
            String normalized = phone.replaceAll("[^0-9+]", "");
            if (!normalized.startsWith("+263") && normalized.startsWith("0")) {
                normalized = "+263" + normalized.substring(1);
            }

            final String finalPhone = normalized;

            return userRepository.findByPhone(finalPhone)
                    .map(user -> org.springframework.security.core.userdetails.User.builder()
                            .username(user.getPhone())
                            .password(user.getPinHash())
                            .roles(user.getRole())
                            .disabled(!user.getIsActive())
                            .build())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + finalPhone));
        };
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}