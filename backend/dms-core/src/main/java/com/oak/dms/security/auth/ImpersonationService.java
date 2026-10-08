package com.oak.dms.security.auth;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.InvalidUserStateException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Starts and ends an "Act as User" impersonation session - see
 * {@link ImpersonationContextFilter} for how the resulting grant is
 * actually applied on every subsequent request, and {@link ImpersonationTokenCodec}
 * for the token shape.
 * <p>
 * Authorization split across two layers, deliberately: {@code @PreAuthorize}
 * on {@code ImpersonationController#start} is the caller's own permission
 * check (a caller without {@code otua.manage-users.impersonate} never
 * reaches this class at all), while everything in {@link #start} below is
 * about the TARGET - same tenant, not the caller themself, active, and not
 * already impersonating anyone. The filter re-checks the admin's own
 * permission again on every subsequent request (see its own doc comment for
 * why that's not redundant).
 */
@Service
@RequiredArgsConstructor
public class ImpersonationService {

    private final UserRepository userRepository;
    private final ImpersonationTokenCodec tokenCodec;
    private final AuthCookieService cookieService;
    private final AuditService auditService;

    @Value("${app.impersonation.session-minutes}")
    private long sessionMinutes;

    /**
     * Issues a new impersonation grant for {@code targetUserId} and writes
     * it as a cookie on {@code response}. Every check here runs against the
     * REAL caller's identity - {@link TenantContext} has not been touched
     * yet at this point in the request, since {@link ImpersonationContextFilter}
     * only rebinds it on requests that already carry a valid grant, and this
     * one doesn't yet.
     */
    @Transactional
    public void start(UUID targetUserId, HttpServletResponse response) {
        UUID adminId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();

        if (TenantContext.getImpersonatorIdOrNull() != null) {
            // Reachable only if the admin's own current request is itself
            // already running under someone else's impersonation grant -
            // i.e. they're an impersonation TARGET right now, not the
            // impersonator. An admin who is themselves impersonating someone
            // else and wants to switch targets must stop() first; there is
            // no scenario where stacking two grants is meaningful.
            throw new InvalidUserStateException("Already impersonating another user - stop the current session first");
        }

        if (targetUserId.equals(adminId)) {
            throw new InvalidUserStateException("Cannot impersonate yourself");
        }

        User target = userRepository.findByUserIdAndTenantIdAndDeletedFalse(targetUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));

        if (target.getStatus() != User.UserStatus.active) {
            throw new InvalidUserStateException("Cannot impersonate an inactive user");
        }

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(sessionMinutes));
        String token = tokenCodec.issue(adminId, targetUserId, tenantId, issuedAt, expiresAt);
        cookieService.writeImpersonationCookie(response, token, sessionMinutes * 60);

        // Actor is still the real admin here - this runs before the grant
        // just issued has taken effect on any request, including this one.
        auditService.event(AuditActionCatalog.IMPERSONATION_STARTED)
                .objectType(AuditObjectTypes.USER)
                .objectId(target.getUserId())
                .resourceName(target.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    /**
     * Ends the impersonation session active on the current request, if any.
     * {@link TenantContext} is, by the time a controller method runs,
     * already rebound to the TARGET for this exact request (that's what
     * makes the ended event's actor the target and its impersonated_by the
     * admin, symmetrically with {@link #start}'s actor being the admin) -
     * clearing the cookie is what stops that rebind from happening on the
     * NEXT request.
     */
    @Transactional
    public void stop(HttpServletResponse response) {
        UUID impersonatorId = TenantContext.getImpersonatorIdOrNull();
        if (impersonatorId == null) {
            throw new InvalidUserStateException("Not currently impersonating anyone");
        }

        cookieService.clearImpersonationCookie(response);

        auditService.event(AuditActionCatalog.IMPERSONATION_ENDED)
                .objectType(AuditObjectTypes.USER)
                .objectId(TenantContext.getUserId())
                .resourceName(TenantContext.getDisplayNameOrNull())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }
}
