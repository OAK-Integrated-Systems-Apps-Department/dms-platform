package com.oak.dms.security.auth;

import com.oak.dms.security.auth.dto.StartImpersonationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Act as User" - lets a Tenant Admin holding {@code otua.manage-users.impersonate}
 * operate the application as one of their tenant's other users, for support
 * or troubleshooting, while every audit event generated in the meantime
 * still records who they really are. See {@link ImpersonationService} for
 * the actual start/stop logic, and {@link ImpersonationContextFilter} for
 * how the resulting grant is applied to every subsequent request.
 * <p>
 * Both endpoints require an ordinary authenticated session
 * (SecurityConfig's default {@code anyRequest().authenticated()}) - neither
 * is public. {@code stop} deliberately carries no {@code @PreAuthorize} of
 * its own: ending an active impersonation session must always be possible
 * for whoever is currently in one, regardless of the impersonation target's
 * own permissions - {@link ImpersonationService#stop} itself is what
 * enforces "there must actually be an active session to stop".
 */
@RestController
@RequestMapping("/api/v1/impersonation")
@RequiredArgsConstructor
@Tag(name = "Impersonation", description = "Act as User - operate as another user in the caller's tenant, with the original admin retained for audit purposes")
public class ImpersonationController {

    private final ImpersonationService impersonationService;

    @PostMapping("/start")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_IMPERSONATE)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Start impersonating a user in the caller's tenant",
            description = "The caller must hold otua.manage-users.impersonate. Rejected (409) if the caller is "
                    + "already impersonating someone, if the target doesn't exist or belongs to a different "
                    + "tenant, if the target isn't active, or if the target is the caller themself. On success, "
                    + "every subsequent request on this browser session operates as the target user until "
                    + "POST /stop is called or the grant's own short expiry lapses.")
    public void start(@Valid @RequestBody StartImpersonationRequest request, HttpServletResponse response) {
        impersonationService.start(request.targetUserId(), response);
    }

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End the caller's active impersonation session, returning to the original admin identity",
            description = "409 if the caller isn't currently impersonating anyone.")
    public void stop(HttpServletResponse response) {
        impersonationService.stop(response);
    }
}
