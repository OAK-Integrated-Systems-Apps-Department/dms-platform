package com.oak.dms.common.exception;

import com.oak.dms.audit.AccessDeniedAuditor;
import com.oak.dms.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Central translation from exceptions to {@link ErrorResponse}.
 * Handles this module's own {@link ApiException} hierarchy plus the common
 * Spring exceptions (validation, data-integrity, access-denied) so
 * individual controllers never need their own try/catch blocks.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AccessDeniedAuditor accessDeniedAuditor;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        log.warn("API exception on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getStatus().value(), ex.getStatus().getReasonPhrase(),
                        ex.getCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<ErrorResponse.FieldValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldValidationError(fe.getField(),
                        fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "Validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ErrorResponse> handleFieldValidation(FieldValidationException ex,
            HttpServletRequest request) {
        // Service-layer counterpart of handleValidation above (it is an
        // ApiException, but this more specific handler wins over
        // handleApiException) - same status, message and fieldErrors
        // shape, so a client can't tell which layer rejected the field.
        log.warn("Field validation failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "Validation failed", request.getRequestURI(), ex.getFieldErrors()));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex,
            HttpServletRequest request) {
        // Thrown by @Validated method-parameter checks (e.g. @RequestParam
        // constraints), as opposed to MethodArgumentNotValidException above
        // which covers @Valid @RequestBody. Same 400 shape either way so
        // API consumers don't need to know which annotation style produced it.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "Validation failed", request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict",
                        "The request conflicts with an existing resource",
                        request.getRequestURI()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleNoTenantContext(
            IllegalStateException ex,
            HttpServletRequest request) {

        log.warn(
                "Request rejected, no tenant context on {}: {}",
                request.getRequestURI(),
                ex.getMessage());

        // Carries a `code` (see ErrorResponse/ApiException's own doc comments)
        // distinct from a real expired/invalid session, both of which
        // otherwise arrive as indistinguishable 401s - the frontend's
        // global session-expiry handler (isSessionExpired in
        // lib/api-error.ts) matches on this code to avoid treating "this
        // account has no tenant context yet" as "your session expired",
        // which would incorrectly log the caller out and clear their
        // whole client cache instead of just surfacing the message below.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(
                        HttpStatus.UNAUTHORIZED.value(),
                        "Unauthorized",
                        "TENANT_CONTEXT_MISSING",
                        "Your account is authenticated, but provisioning is not yet complete. "
                                + "Please complete the provisioning process before accessing this resource.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDataAccessApiUsage(InvalidDataAccessApiUsageException ex,
            HttpServletRequest request) {
        // Most commonly triggered by an invalid `sort` query parameter on a paginated
        // list endpoint (e.g. sorting by a property that doesn't exist), which Spring
        // Data JPA rejects at query-rewrite time rather than at request-binding time.
        log.warn("Invalid data access API usage on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "One or more request parameters are invalid, most likely an unsupported 'sort' field",
                        request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        // Covers every @PreAuthorize denial across the whole app - but
        // NOT SecurityConfig's own filter-level requestMatchers(...)
        // denials, which reject before DispatcherServlet ever routes
        // here; AuditingAccessDeniedHandler covers those separately, via
        // the same AccessDeniedAuditor. See that auditor's own doc
        // comment for why there are two call sites.
        accessDeniedAuditor.record(request, ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden",
                        "You do not have permission to perform this action",
                        request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "An unexpected error occurred", request.getRequestURI()));
    }
}
