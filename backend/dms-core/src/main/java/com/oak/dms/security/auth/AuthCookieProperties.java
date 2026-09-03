package com.oak.dms.security.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the app.auth.cookie.* configuration keys controlling how the
 * session cookies dms-core sets on login are shaped. Kept configurable
 * (rather than hardcoded) because these need different values in local
 * dev vs. a real deployment - most importantly {@code domain}, which
 * must stay unset (host-only cookie) unless dms-core and the frontend are
 * ever split across subdomains of one parent domain.
 * <p>
 * Cookie <em>names</em> ({@code dms_access_token} / {@code dms_refresh_token})
 * are NOT configurable here - they're hardcoded in {@link AuthCookieService}
 * because they must match {@code lib/auth/cookie-names.ts} on the frontend
 * exactly, and {@code proxy.ts} reads the access-token cookie by that
 * literal name to gate routes.
 */
@Component
@ConfigurationProperties(prefix = "app.auth.cookie")
@Getter
@Setter
public class AuthCookieProperties {

    /**
     * Whether cookies are marked Secure (sent only over HTTPS - or, per
     * the fetch/cookie spec, over plain HTTP to "localhost", which
     * browsers treat as a secure context). True is the safe default and
     * works for both a real deployment and local dev against
     * http://localhost. Only set this false if you must run the stack on
     * plain HTTP against a non-localhost hostname, which should never
     * happen outside a throwaway sandbox.
     */
    private boolean secure = true;

    /**
     * Lax is deliberate, not a placeholder: it's what stops a page on
     * another site from making a state-changing cross-site request that
     * carries these cookies (browsers withhold Lax/Strict cookies on
     * cross-site fetch/XHR, only attaching them on top-level, same-site
     * navigation), which is why CSRF protection is safely disabled in
     * SecurityConfig. Only move this to "None" if the frontend and
     * dms-core are ever deployed on genuinely different sites - and if
     * you do, you must add an explicit CSRF token scheme, because "None"
     * removes the protection this relies on.
     */
    private String sameSite = "Lax";

    /** Empty/null = host-only cookie (recommended). Set only for a shared-subdomain deployment. */
    private String domain;

    private String path = "/";
}
