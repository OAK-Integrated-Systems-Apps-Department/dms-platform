package com.oak.dms.security;

import com.oak.dms.security.auth.CookieBearerTokenResolver;
import com.oak.dms.security.auth.JwtAuthConverter;
import com.oak.dms.security.auth.TenantContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    private final CookieBearerTokenResolver cookieBearerTokenResolver;

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
            "/api/v1/auth/reset-password/validate"
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
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(cookieBearerTokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)))
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

