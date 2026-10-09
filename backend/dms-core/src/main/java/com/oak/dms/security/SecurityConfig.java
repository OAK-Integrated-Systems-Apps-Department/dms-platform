package com.oak.dms.security;

import com.oak.dms.audit.filter.AuditRequestContextFilter;
import com.oak.dms.security.auth.CookieBearerTokenResolver;
import com.oak.dms.security.auth.ImpersonationContextFilter;
import com.oak.dms.security.auth.JwtAuthConverter;
import com.oak.dms.security.auth.TenantContextFilter;
import com.oak.dms.security.rbac.controller.AuditingAccessDeniedHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Wires Spring Security as an OAuth2 resource server against Keycloak:
 * validates the JWT, converts it into Spring authorities via
 * {@link com.oak.dms.security.auth.JwtAuthConverter}, and inserts
 * {@link com.oak.dms.security.auth.TenantContextFilter} immediately after
 * token validation so tenant/user context is available for the rest of the
 * filter chain and every controller method.
 * <p>
 * The JWT itself can arrive either as a normal {@code Authorization:
 * Bearer} header or - for the browser SPA - as the httpOnly session
 * cookie set by {@code AuthController}/{@code AuthCookieService}; see
 * {@link CookieBearerTokenResolver}. Everything else in this class (JWT
 * signature/issuer validation, authority mapping, tenant resolution) is
 * completely unaffected by which of those two carried the token in.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;
    private final TenantContextFilter tenantContextFilter;
    private final ImpersonationContextFilter impersonationContextFilter;
    private final AuditRequestContextFilter auditRequestContextFilter;
    private final CookieBearerTokenResolver cookieBearerTokenResolver;
    private final AuditingAccessDeniedHandler auditingAccessDeniedHandler;

    /**
     * Comma-separated allow-list, e.g. "https://dms.example.com,https://admin.dms.example.com".
     * Defaults to the local Next.js dev server only. This must be an exact
     * list, never a wildcard: CORS is configured with allowCredentials=true
     * below (required so the browser sends the session cookie cross-origin
     * to dms-core), and a wildcard origin combined with allowCredentials
     * would let ANY website make authenticated, cookie-carrying requests
     * against this API on a logged-in user's behalf.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/api/v1/auth/mfa-required",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/reset-password/validate",
            // Cloud malware-scanner verdict webhooks (Azure Event Grid, AWS
            // EventBridge) - they can't present a Keycloak JWT, so
            // ScanCallbackController authenticates them with a shared-secret
            // header instead, and a callback can only trigger a re-check of
            // the provider's own verdict, never set one.
            "/api/v1/scanning/callbacks/*",
            // ocr-worker reading a document's plaintext bytes. Same reasoning
            // as the scan callbacks above: the worker has no Keycloak JWT and
            // no user behind it. It authenticates with TWO factors instead -
            // the shared X-Internal-Token AND a per-job HMAC grant naming one
            // version, one tenant and an expiry (OcrJobTokenCodec). Permitted
            // here only in the sense that Spring Security doesn't demand a
            // JWT; OcrInternalContentController refuses anything that fails
            // either factor, and re-checks the job, the tenant and the scan
            // verdict against the database before serving a byte.
            //
            // This path must not be exposed through nginx/ingress - see
            // docs/ocr/internal-endpoint.md.
            "/internal/v1/ocr/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Safe to leave disabled: dms-core issues its session cookie
                // with SameSite=Lax (see AuthCookieProperties), which
                // already stops browsers from attaching it to cross-site
                // state-changing requests - the same threat a CSRF token
                // exists to cover. If the cookie's SameSite is ever
                // relaxed to "None", a real CSRF token scheme must be
                // added here at the same time.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Mirrors each endpoint's own @PreAuthorize("hasRole('PLATFORM_ADMIN')")
                        // - deliberately duplicated, not a replacement. Spring resolves and
                        // @Valid-validates a @RequestBody before invoking the (AOP-proxied)
                        // controller method, which means @PreAuthorize alone lets a caller
                        // with no business on these endpoints at all see field-level
                        // validation errors (400, naming real request fields) before ever
                        // being told no. Filter-chain authorization runs earlier, in the
                        // security filter itself, before DispatcherServlet routes to the
                        // controller or reads the body at all - so a caller without
                        // PLATFORM_ADMIN gets a clean 403 here first, every time. Limited to
                        // the writes (and the tenant list) that are PLATFORM_ADMIN-only
                        // unconditionally; GET /api/v1/tenants/{id} and PUT .../{id} are
                        // deliberately excluded - both also allow the caller's own tenant
                        // admin via @tenantSecurity.isSameTenant(#tenantId), a per-request
                        // check this layer can't express.
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants", "/api/v1/tenants/provision").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tenants/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/permissions").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/permissions/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/permissions/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/permission-modules").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/permission-modules/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/permission-modules/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/permission-actions").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/permission-actions/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/permission-actions/*").hasRole(PLATFORM_ADMIN_ROLE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/platform/audit-events", "/api/v1/platform/audit-events/*")
                        .hasRole(PLATFORM_ADMIN_ROLE)
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.accessDeniedHandler(auditingAccessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(cookieBearerTokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)))
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(impersonationContextFilter, TenantContextFilter.class)
                .addFilterAfter(auditRequestContextFilter, ImpersonationContextFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // A custom response header is invisible to frontend JS on a
        // cross-origin request unless explicitly exposed - see
        // AuditRequestContextFilter, which sets this on every
        // authenticated response so the frontend can thread a related
        // sequence's correlation_id into a subsequent call.
        // Content-Disposition likewise, so file downloads (e.g. the bulk
        // import template) can keep the server-chosen filename.
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

