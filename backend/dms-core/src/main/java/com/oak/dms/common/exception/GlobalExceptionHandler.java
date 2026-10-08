package com.oak.dms.common.exception;

import com.oak.dms.audit.controller.AccessDeniedAuditor;
import com.oak.dms.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * Real bug found live: a document photo/scan that exceeds
     * spring.servlet.multipart.max-file-size (see application.yml's own
     * comment -- Spring Boot's silent default is 1MB, easily exceeded by
     * a real phone-camera photo of a document) was falling through to the
     * generic handleUnexpected below, surfacing only "An unexpected error
     * occurred" with no indication the file itself was the problem.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Upload exceeded max size on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ErrorResponse.of(HttpStatus.CONTENT_TOO_LARGE.value(), "Content Too Large",
                        "This file is too large to upload. Please choose a smaller file.", request.getRequestURI()));
    }

    /**
     * Anything that already carries the right status AND a sentence meant
     * for a person -- today, the ocr-worker proxy routes (see
     * {@code OcrWorkerClient}).
     * <p>
     * Without this, Spring renders these as a ProblemDetail, whose text
     * lives in `detail` rather than `message`. Every client here reads
     * `message` (see the frontend's api-fetch), so the carefully-chosen
     * sentence would be dropped on the floor and the user would get the
     * generic fallback instead -- the exact failure mode this uniform
     * shape exists to prevent.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.warn("Upstream-mapped failure on {}: {} {}", request.getRequestURI(), status.value(), ex.getReason());
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(),
                        // A reason is always set by the code that throws
                        // these. The fallback is here so a future thrower
                        // that forgets one still cannot leak a class name.
                        ex.getReason() != null ? ex.getReason() : "Something went wrong. Please try again.",
                        request.getRequestURI()));
    }

    /*
     * Client mistakes the framework raises before (or instead of) a
     * controller: answered 4xx with a plain message, never the 500 the
     * catch-all below would give. They used to land there - e.g.
     * POST /api/v1/documents?folderId=undefined answered 500 for an
     * unparseable UUID, and a path no controller maps answered 500 instead
     * of 404.
     */

    /** A path variable or request parameter that can't be converted - folderId=undefined, a malformed UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        Class<?> type = ex.getRequiredType();
        String expected = type == null ? "a valid value"
                : type == java.util.UUID.class ? "a valid ID"
                : type == Boolean.class || type == boolean.class ? "true or false"
                : Number.class.isAssignableFrom(type) || type.isPrimitive() ? "a number"
                : type.isEnum() ? "one of " + java.util.Arrays.toString(type.getEnumConstants())
                : "a valid value";
        return badRequest("'" + ex.getName() + "' must be " + expected + ".", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        return badRequest("'" + ex.getParameterName() + "' is required.", request);
    }

    /** No body, or one that isn't valid JSON for the endpoint. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        return badRequest("The request body is missing or isn't valid.", request);
    }

    /** A path no controller maps - e.g. /api/v1/exports/preview instead of /api/v1/export-jobs/preview. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found",
                        "There is nothing at this address.", request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED.value(), "Method Not Allowed",
                        ex.getMethod() + " isn't supported here.", request.getRequestURI()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "Unsupported Media Type",
                        "This endpoint doesn't accept that content type.", request.getRequestURI()));
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", message,
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
