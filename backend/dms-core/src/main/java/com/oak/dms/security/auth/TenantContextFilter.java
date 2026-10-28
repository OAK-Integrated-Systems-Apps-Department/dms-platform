package com.oak.dms.security.auth;

import com.oak.dms.user.UserRepository;
import com.oak.dms.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs once per request, after the Bearer token has been validated.
 * Resolves:
 *  - tenant_id, from the "tenant_id" custom claim added by a Keycloak
 *    protocol mapper on the oak-dms realm (sourced from the Keycloak
 *    user attribute set when the user is provisioned).
 *  - the local users.user_id row, matched by keycloak_id = JWT subject.
 * Both are bound to {@link TenantContext} for the duration of the
 * request and cleared afterwards, since ThreadLocals are reused across
 * requests on pooled threads.
 *
 * Also marks the resolved user verified (see {@link UserService#markVerified})
 * the first time this happens for them: reaching this point at all means
 * their JWT was valid, which Keycloak won't issue while the VERIFY_EMAIL /
 * UPDATE_PASSWORD required actions set at invite time are still
 * outstanding — so successfully getting here is itself the completion
 * signal, no separate callback/webhook from Keycloak needed. A no-op once
 * already verified, so this doesn't add a write to every request forever,
 * just the first one after setup completes.
 *
 * Requests whose JWT does not yet correspond to a provisioned local user
 * (e.g. the very first call after a Keycloak signup, before the
 * self-provisioning endpoint has run) proceed with no context bound;
 * any service call that needs TenantContext will then fail fast with a
 * clear 401, handled centrally in GlobalExceptionHandler.
 *
 * This filter must never let a malformed or unexpected token crash the
 * request with an unhandled exception (500) - a JWT that lacks a usable
 * "sub" claim, or a sub that isn't a valid UUID, is treated the same way
 * as "no matching local user": proceed with no context bound, let
 * downstream authorization/TenantContext usage fail cleanly instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final UserService userService;
    private final OrganisationStatusGuard organisationStatus;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Still reachable for a closed organisation's users: signing out clears their cookies. */
    private static final java.util.Set<String> OPEN_PATHS = java.util.Set.of("/api/v1/auth/logout");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                Optional<com.oak.dms.user.User> found = resolveKeycloakId(jwt)
                        .flatMap(userRepository::findByKeycloakIdAndDeletedFalse);
                if (found.isPresent()) {
                    com.oak.dms.user.User user = found.get();
                    // A suspended or closed organisation: a session its users already had stops working
                    // on its very next request, not when the token runs out. Platform admins get through.
                    Optional<String> inactive = organisationStatus.inactiveReason(user.getTenantId());
                    if (inactive.isPresent() && !isPlatformAdmin(authentication)
                            && !OPEN_PATHS.contains(request.getRequestURI())) {
                        log.info("Refused {} {} for user {}: organisation {} is not active", request.getMethod(),
                                request.getRequestURI(), user.getUserId(), user.getTenantId());
                        refuse(request, response, inactive.get());
                        return;
                    }
                    TenantContext.set(user.getTenantId(), user.getUserId(), user.getDisplayName(),
                            user.getKeycloakId());
                    if (!user.isVerified()) {
                        userService.markVerified(user.getUserId());
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean isPlatformAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Same shape as GlobalExceptionHandler's answers - ISO dates, not epoch numbers.
        objectMapper.copy().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValue(response.getOutputStream(), com.oak.dms.common.dto.ErrorResponse.of(
                HttpServletResponse.SC_FORBIDDEN, "Forbidden", OrganisationStatusGuard.CODE, message,
                request.getRequestURI()));
    }

    private Optional<UUID> resolveKeycloakId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            log.warn("JWT on {} has no usable 'sub' claim - proceeding without tenant context", jwt.getId());
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(subject));
        } catch (IllegalArgumentException e) {
            log.warn("JWT 'sub' claim '{}' is not a valid UUID - proceeding without tenant context", subject);
            return Optional.empty();
        }
    }
}