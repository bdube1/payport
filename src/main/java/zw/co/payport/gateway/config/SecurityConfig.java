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
    private final zw.co.payport.gateway.roles.StaffRoles staffRoles;

    @org.springframework.beans.factory.annotation.Value("${payport.environment:production}")
    private String environment = "production";

    @org.springframework.beans.factory.annotation.Value("${payport.cors.extra-origins:}")
    private String extraOrigins = "";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   ApiKeyAuthenticationFilter apiKeyAuthFilter,
                                                   MerchantJwtAuthenticationFilter merchantJwtAuthFilter,
                                                   zw.co.payport.gateway.agents.AgentJwtAuthenticationFilter agentJwtAuthFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   MetricsTokenFilter metricsTokenFilter) throws Exception {
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
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant-portal/forgot-password", "/api/v1/merchant-portal/reset-password").permitAll()
                        // Agent portal: public login, password reset, application and its tracking
                        .requestMatchers(HttpMethod.POST, "/api/v1/agent-portal/login", "/api/v1/agent-portal/forgot-password",
                                "/api/v1/agent-portal/reset-password", "/api/v1/agent-portal/apply").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/agent-portal/track/*").permitAll()

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
                        // Phase 8: QR for an open session, QR resolution, USSD aggregator (HMAC-signed), FX quotes
                        .requestMatchers(HttpMethod.GET, "/api/v1/checkout/*/qr").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/qr/resolve").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ussd").permitAll()
                        // Network USSD gateways (access key or allowed IP, UssdNetworkService)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ussd/econet", "/api/v1/ussd/netone", "/api/v1/ussd/telecel").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/fx/quote").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/markets").permitAll()
                        // Pay a merchant by its six-digit code (guests too): look up the name, open a session
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchants/by-code/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/charges/quote").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/codes/scan").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/session-timeouts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchants/by-code/*/payments").permitAll()
                        // Phase 8: POS terminals sign every request (HMAC + nonce, PosService); WhatsApp Cloud API
                        // webhook (X-Hub-Signature-256, WhatsAppService). No user login on either.
                        .requestMatchers(HttpMethod.POST, "/api/v1/pos/sales").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/pos/sales/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pos/sales/*/card").permitAll()
                        // PayPort Commerce, server to server: the service key is checked by CommerceFacade (closed without one).
                        .requestMatchers(HttpMethod.GET, "/api/v1/commerce/events", "/api/v1/commerce/checkouts/*/*", "/api/v1/commerce/merchants/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/commerce/checkouts", "/api/v1/commerce/refunds", "/api/v1/commerce/messages").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/whatsapp/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/whatsapp/webhook").permitAll()

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
                        .requestMatchers(HttpMethod.GET, "/api/v1/transactions/*/outcome").permitAll()
                        .requestMatchers("/api/v1/transactions/*/confirm-omari-otp").permitAll()

                        // Payment provider callbacks - reachable without a user JWT
                        // (server-to-server). See CustomerMessageTranslator/CallbackController
                        // for how each provider's payload is validated.
                        .requestMatchers("/api/v1/callbacks/**").permitAll()

                        .requestMatchers("/actuator/health").permitAll()
                        // Phase 9: liveness/readiness probes; Prometheus scrape is checked by MetricsTokenFilter.
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/monitoring/targets").permitAll()   // scrape token (MetricsTokenFilter)
                        .requestMatchers("/actuator/info").permitAll()
                        .requestMatchers("/").permitAll()

                        // Public catalogue / pre-payment lookups
                        .requestMatchers(HttpMethod.POST, "/api/v1/assistant/chat").permitAll()
                        .requestMatchers("/api/v1/products/**").permitAll()
                        .requestMatchers("/api/v1/bills/customer-info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/billers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/billers/*/lookup").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bundles/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bundles/*/code/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bundles/*/categories").permitAll()

                        // Phase 9 defence in depth: staff consoles and merchant APIs are closed by URL as well as by
                        // each controller's @PreAuthorize, so a route added without a guard is still not open to
                        // customers (three admin controllers were found unguarded by the authorization matrix test).
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/roles/me").hasRole("STAFF")
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "OPS", "FINANCE", "COMPLIANCE", "OPS_MANAGER")
                        .requestMatchers("/api/v1/merchant/**").hasAnyRole("MERCHANT", "MERCHANT_PENDING")
                        .requestMatchers("/api/v1/agent/**").hasRole("AGENT")

                        // Everything else requires a valid login. Staff-only endpoints are
                        // further restricted via @PreAuthorize on their controllers.
                        .anyRequest().authenticated()
                )
                // Rate limit login/register before anything else, then JWT auth
                .addFilterBefore(metricsTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(merchantJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(agentJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Roles & permissions: configured roles only reach the staff APIs their permissions allow.
                .addFilterAfter(new zw.co.payport.gateway.roles.PermissionFilter(staffRoles, userRepository), UsernamePasswordAuthenticationFilter.class)
                // Phase 9: an API serves JSON only, so the strictest policy applies: nothing may be loaded,
                // framed or referred from a response. HSTS and nosniff are Spring Security defaults.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=()"))
                        .httpStrictTransportSecurity(h -> h.includeSubDomains(true).maxAgeInSeconds(31536000))
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
        List<String> origins = new java.util.ArrayList<>(List.of(
                "https://payport.africa",
                "https://www.payport.africa",
                "https://app.payport.africa",
                "https://admin.payport.africa",
                "https://merchant.payport.africa"));
        // Phase 9 (R36): localhost origins only outside production. A production deployment that needs
        // another browser origin lists it in payport.cors.extra-origins (comma-separated, https only).
        // Outside production (staging on a bare IP, no TLS yet) http extras are accepted too.
        boolean production = zw.co.payport.gateway.platform.DeploymentEnvironment.isProduction(environment);
        if (!production) {
            // Staging portals (owner, 2026-10-25): ops, consumer, merchant/agent.
            origins.addAll(List.of("https://admin-staging.payport.africa", "https://app-staging.payport.africa",
                    "https://merchant-staging.payport.africa"));
            origins.addAll(List.of("http://localhost:4321", "http://localhost:3000", "http://localhost:3001", "http://localhost:3002",
                    "http://localhost:5173", "http://localhost:8081"));
        }
        for (String o : extraOrigins.split(",")) {
            String t = o.trim();
            if (t.startsWith("https://") || (!production && t.startsWith("http://"))) origins.add(t);
        }
        config.setAllowedOrigins(origins);
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
                            .authorities(staffRoles.authorities(user))
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