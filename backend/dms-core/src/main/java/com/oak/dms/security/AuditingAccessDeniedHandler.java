package com.oak.dms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.controller.AccessDeniedAuditor;
import com.oak.dms.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handles an {@link AccessDeniedException} thrown from Spring Security's
 * own filter chain - specifically, {@link SecurityConfig}'s
 * {@code requestMatchers(...).hasRole(...)} rules, which run and can
 * reject a request before {@code DispatcherServlet} ever routes it to a
 * controller. A denial at that layer never reaches
 * {@code GlobalExceptionHandler} (a {@code @RestControllerAdvice}, which
 * only sees exceptions Spring MVC's own dispatch mechanism catches) -
 * confirmed by {@code AccessDeniedAuditIT}, which is what surfaced this
 * gap in the first place. This is the equivalent hook one layer further
 * out, registered directly on the {@code SecurityFilterChain} (see
 * {@link SecurityConfig#securityFilterChain}), so both denial paths
 * produce the same audit trail and the same JSON error shape from the
 * caller's point of view.
 */
@Component
@RequiredArgsConstructor
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedAuditor accessDeniedAuditor;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        accessDeniedAuditor.record(request, ex.getMessage());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden",
                        "You do not have permission to perform this action", request.getRequestURI()));
    }
}
