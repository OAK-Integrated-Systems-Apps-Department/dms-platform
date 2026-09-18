package com.oak.dms.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Emits the ACCESS_DENIED audit event for a blocked request. Shared by
 * the two places a Spring Security denial can surface from:
 *  - {@code GlobalExceptionHandler#handleAccessDenied}, for an
 *    AccessDeniedException thrown from inside a controller method's
 *    {@code @PreAuthorize} check - the common case, since it reaches
 *    DispatcherServlet's own exception resolution.
 *  - {@code AuditingAccessDeniedHandler}, registered directly on
 *    SecurityConfig's {@code exceptionHandling(...)}, for a denial
 *    SecurityConfig's own filter-level {@code requestMatchers(...)}
 *    rules throw before DispatcherServlet ever routes the request -
 *    confirmed by AccessDeniedAuditIT that those never reach a
 *    {@code @RestControllerAdvice} at all.
 */
@Component
@RequiredArgsConstructor
public class AccessDeniedAuditor {

    private final AuditService auditService;

    public void record(HttpServletRequest request, String failureReason) {
        // No objectId/permissionCode: this generic a layer only knows
        // "access was denied," never which specific business object or
        // which one of a possibly multi-permission check would have
        // allowed it through. Actor/tenant are left to AuditService's
        // TenantContext-based defaults - TenantContextFilter runs
        // immediately after token validation, before both denial paths
        // above (the filter-level authorization check and any
        // controller/method-security check), so this still attributes
        // the event to the real person denied, not a SYSTEM fallback,
        // regardless of which path called this.
        auditService.event(AuditActionCatalog.ACCESS_DENIED)
                .objectType(AuditObjectTypes.ENDPOINT)
                .resourceName(request.getMethod() + " " + request.getRequestURI())
                .outcome(AuditOutcome.DENIED)
                .failureReason(failureReason)
                .log();
    }
}
