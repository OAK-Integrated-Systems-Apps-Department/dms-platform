package com.oak.dms.security.auth;

import com.oak.dms.security.rbac.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs once per request, immediately after {@link TenantContextFilter} has
 * bound the REAL, authenticated caller's identity. If a valid
 * {@code dms_impersonation} cookie is present, rebinds {@link TenantContext}
 * from that real identity to the impersonation target's - every downstream
 * service, {@code @authz} check, and audit event then operates as if the
 * target had made the request themselves, while {@link TenantContext#getImpersonatorIdOrNull()}
 * remembers who really did.
 * <p>
 * Deliberately re-checks BOTH that the admin still holds
 * {@link PermissionCodes#OTUA_USERS_IMPERSONATE} AND that the target is
 * still an active member of the same tenant, on every single request - not
 * just once when the grant was issued. A permission revoked, or a target
 * deactivated, mid-session takes effect on the very next request rather
 * than only once the (separately time-boxed) grant expires. Cheap: both are
 * indexed lookups already keyed off values this filter needs anyway.
 * <p>
 * Never touches the caller's Spring Security {@code Authentication}/JWT
 * authorities - only {@link TenantContext}. {@link AuthorizationService}'s
 * own full-access check re-resolves the target's platform roles live
 * (rather than trusting anything cached in the grant) exactly where it's
 * needed instead - see that class's own doc comment.
 * <p>
 * Fails closed and silently on any problem with the grant itself (missing,
 * forged, expired, foreign to this admin, wrong tenant): the request simply
 * proceeds as the real, authenticated admin, exactly as if no impersonation
 * cookie had been sent - same "never let a malformed token crash or subtly
 * corrupt the request" posture as {@link AuditCorrelationTokenCodec}. A
 * grant that verifies but whose current state no longer permits it (the
 * admin's permission was revoked, or the target was deactivated) also
 * clears the cookie outright, so the browser doesn't keep re-sending a
 * grant that can only ever fail from here on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImpersonationContextFilter extends OncePerRequestFilter {

    private final ImpersonationTokenCodec tokenCodec;
    private final AuthCookieService cookieService;
    private final AuthorizationService authorizationService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        applyImpersonationIfPresent(request, response);
        chain.doFilter(request, response);
    }

    private void applyImpersonationIfPresent(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request);
        if (token == null) {
            return;
        }

        // No real identity bound at all (e.g. a public endpoint, or a
        // request whose JWT didn't resolve to a provisioned local user) -
        // nothing to rebind FROM, so there's no meaningful way to apply an
        // impersonation grant here regardless of whether it's otherwise valid.
        UUID realUserId;
        UUID realTenantId;
        try {
            realUserId = TenantContext.getUserId();
            realTenantId = TenantContext.getTenantId();
        } catch (IllegalStateException e) {
            return;
        }
        // Captured now, before the rebind below overwrites it - this is the
        // ONLY point in the request where TenantContext still reflects the
        // real admin's own identity, not the impersonation target's.
        String realDisplayName = TenantContext.getDisplayNameOrNull();

        Optional<ImpersonationTokenCodec.Grant> maybeGrant = tokenCodec.verify(token);
        if (maybeGrant.isEmpty()) {
            return;
        }
        ImpersonationTokenCodec.Grant grant = maybeGrant.get();

        if (!grant.impersonatorId().equals(realUserId) || !grant.tenantId().equals(realTenantId)) {
            // A stale grant from a different admin's login on this same
            // browser, or (defense in depth) a tenant mismatch that
            // shouldn't be reachable in practice - either way, foreign to
            // this exact session, so treated identically to "no grant".
            log.warn("Ignoring impersonation grant issued to a different admin/tenant than the current session");
            return;
        }

        if (!authorizationService.hasPermission(PermissionCodes.OTUA_USERS_IMPERSONATE)) {
            log.info("Admin {} no longer holds {} - ending their active impersonation grant",
                    realUserId, PermissionCodes.OTUA_USERS_IMPERSONATE);
            cookieService.clearImpersonationCookie(response);
            return;
        }

        Optional<User> maybeTarget = userRepository
                .findByUserIdAndTenantIdAndDeletedFalse(grant.targetUserId(), grant.tenantId());
        if (maybeTarget.isEmpty() || maybeTarget.get().getStatus() != User.UserStatus.active) {
            log.info("Impersonation target {} is no longer an active member of tenant {} - ending the grant",
                    grant.targetUserId(), grant.tenantId());
            cookieService.clearImpersonationCookie(response);
            return;
        }

        User target = maybeTarget.get();
        TenantContext.set(grant.tenantId(), target.getUserId(), target.getDisplayName(), target.getKeycloakId());
        TenantContext.setImpersonator(grant.impersonatorId(), realDisplayName);
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (AuthCookieService.IMPERSONATION_COOKIE.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
