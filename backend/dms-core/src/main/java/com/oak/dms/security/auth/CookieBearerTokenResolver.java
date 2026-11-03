package com.oak.dms.security.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Lets the exact same OAuth2-resource-server JWT validation ({@code
 * SecurityConfig}, {@code JwtAuthConverter}, {@code TenantContextFilter})
 * serve both kinds of caller dms-core has:
 * <ul>
 *   <li>the browser SPA, which never sees the raw token - it rides along
 *       in the httpOnly {@code dms_access_token} cookie set by
 *       {@link AuthCookieService};</li>
 *   <li>any other API client (service-to-service calls, Postman, a future
 *       mobile app) that sends a normal {@code Authorization: Bearer ...}
 *       header.</li>
 * </ul>
 * The header is always checked first and wins if present, so a caller
 * that explicitly supplies a token is never silently overridden by a
 * stale cookie. Everything downstream of this resolver is completely
 * unaware of which source the token came from - it's just a JWT string
 * either way.
 */
@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

    /** The editing engine's server-to-server endpoints - see {@link #resolve}. Kept in step with SecurityConfig's public paths. */
    static final String WOPI_PATH_PREFIX = "/internal/v1/wopi/";

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        // The online editor's WOPI endpoints authenticate by their OWN signed token (in the URL, checked by
        // WopiController), not a Keycloak JWT. Collabora also repeats that token as "Authorization: Bearer ...";
        // letting it through here would make Spring try to validate it as a JWT and answer 401 before the controller
        // ever runs. Nothing is lost by ignoring it: the path is public and the controller refuses without a valid
        // access_token (covered by WopiEditingIT).
        if (request.getRequestURI() != null && request.getRequestURI().startsWith(WOPI_PATH_PREFIX)) {
            return null;
        }
        String fromHeader = headerResolver.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieService.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }
}
