package com.oak.dms.tenant;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.EmailInUseException;
import com.oak.dms.common.exception.InvalidUserStateException;
import com.oak.dms.common.exception.MailDeliveryException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.common.exception.KeycloakUserAlreadyExistsException;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.tenant.dto.TenantAdminResponse;
import com.oak.dms.tenant.dto.UpdateTenantAdminRequest;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the platform administrator can do about a tenant's administrators after provisioning
 * ({@link TenantProvisioningService}): see who they are, correct their details, and send the
 * invitation again. Every other user operation acts on the caller's own tenant only, which is why a
 * platform administrator could not reach these accounts before.
 * <p>
 * The administrators of a tenant are its users holding the TENANT_ADMIN realm role. Keycloak lists
 * the members of that role across all tenants; this narrows them to one tenant by matching
 * {@code users.keycloak_id}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAdminService {

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final KeycloakUserService keycloakUserService;
    private final PasswordResetService passwordResetService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<TenantAdminResponse> listAdmins(UUID tenantId) {
        requireTenant(tenantId);
        return findAdmins(tenantId).stream()
                .sorted(Comparator.comparing(User::getCreatedAt))
                .map(TenantAdminService::toResponse)
                .toList();
    }

    @Transactional
    public TenantAdminResponse updateAdmin(UUID tenantId, UUID userId, UpdateTenantAdminRequest request) {
        requireTenant(tenantId);
        User admin = requireAdmin(tenantId, userId);
        Map<String, Object> previous = Map.of(
                "firstName", admin.getFirstName(),
                "lastName", admin.getLastName() == null ? "" : admin.getLastName(),
                "email", admin.getEmail());

        String requested = request.email().strip();
        String newEmail = requested.equalsIgnoreCase(admin.getEmail()) ? null : requested; // null = unchanged
        if (newEmail != null) {
            if (userRepository.existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(tenantId, newEmail, userId)
                    || keycloakUserService.emailTakenByAnotherAccount(newEmail, admin.getKeycloakId())) {
                throw new EmailInUseException();
            }
            admin.setEmail(newEmail);
        }
        admin.setFirstName(request.firstName().strip());
        admin.setLastName(request.lastName() == null || request.lastName().isBlank() ? null : request.lastName().strip());
        admin.setDisplayName(User.displayName(admin.getFirstName(), admin.getLastName()));
        admin.setUpdatedAt(OffsetDateTime.now());

        // The row first, flushed, so a clash fails here; then Keycloak. If Keycloak refuses, the
        // transaction rolls the row back and the two never disagree (same order as UserServiceImpl).
        User saved;
        try {
            saved = userRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            throw new EmailInUseException();
        }
        try {
            keycloakUserService.updateProfile(saved.getKeycloakId(), saved.getFirstName(), saved.getLastName(), newEmail);
        } catch (KeycloakUserAlreadyExistsException e) {
            throw new EmailInUseException();
        }
        log.info("Platform admin updated tenant admin {} of tenant {}", userId, tenantId);

        auditService.event(AuditActionCatalog.USER_UPDATED)
                .platformLevel()
                .objectType(AuditObjectTypes.USER)
                .objectId(saved.getUserId())
                .resourceName(saved.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous)
                .newValue(Map.of(
                        "firstName", saved.getFirstName(),
                        "lastName", saved.getLastName() == null ? "" : saved.getLastName(),
                        "email", saved.getEmail(),
                        "tenantId", tenantId))
                .log();
        return toResponse(saved);
    }

    /**
     * Sends the invitation (set-password) email again - for an administrator who has not signed in
     * yet, typically after their email address was corrected.
     */
    @Transactional
    public void resendInvite(UUID tenantId, UUID userId) {
        requireTenant(tenantId);
        User admin = requireAdmin(tenantId, userId);
        if (admin.isVerified()) {
            throw new InvalidUserStateException("This administrator has already set up their account.");
        }
        try {
            passwordResetService.issueForNewUser(admin);
        } catch (RuntimeException mailFailure) {
            log.error("Invitation email to tenant admin {} could not be sent: {}", admin.getEmail(), mailFailure.getMessage(), mailFailure);
            throw new MailDeliveryException("The invitation could not be sent by email. Check the mail settings (SMTP) and try again.");
        }
    }

    private List<User> findAdmins(UUID tenantId) {
        List<UUID> members = keycloakUserService.listRealmRoleMemberIds(TENANT_ADMIN_ROLE);
        if (members.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllByTenantIdAndKeycloakIdInAndDeletedFalse(tenantId, members);
    }

    private User requireAdmin(UUID tenantId, UUID userId) {
        return findAdmins(tenantId).stream()
                .filter(user -> user.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Tenant administrator", userId));
    }

    private void requireTenant(UUID tenantId) {
        tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
    }

    private static TenantAdminResponse toResponse(User user) {
        return new TenantAdminResponse(user.getUserId(), user.getUsername(), user.getEmail(), user.getFirstName(),
                user.getLastName(), user.getDisplayName(), user.getStatus().name(), user.isVerified(),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
