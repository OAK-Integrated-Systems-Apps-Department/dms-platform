package com.oak.dms.user;

import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateOwnProfileRequest;
import com.oak.dms.user.dto.UpdateUserRequest;
import com.oak.dms.user.dto.UserResponse;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.InvalidUserStateException;
import com.oak.dms.common.exception.EmailInUseException;
import com.oak.dms.common.exception.UsernameInUseException;
import com.oak.dms.common.exception.KeycloakUserAlreadyExistsException;
import org.springframework.dao.DataIntegrityViolationException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.exception.TenantMismatchException;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.service.SigningKeyService;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link UserService}. Every method after provisioning enforces tenant
 * ownership via {@link #findOwnedByCallerTenant} before returning or mutating
 * a user, so one tenant can never read or modify another tenant's users even
 * given a guessed id. createUser() also sends the new user a branded
 * "set your password" email (see {@code PasswordResetService#issueForNewUser})
 * and emits a user.created outbox event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private static final String AGGREGATE_TYPE = "user";

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final KeycloakUserService keycloakUserService;
    private final PasswordResetService passwordResetService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final AuthorizationService authorizationService;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;
    private final SigningKeyService signingKeyService;
    private final DepartmentRepository departmentRepository;

    @Override
    public UserResponse provisionCurrentUser(UUID keycloakId, UUID tenantId, String email, String username,
                                              ProvisionUserRequest request) {
        userRepository.findByKeycloakIdAndDeletedFalse(keycloakId).ifPresent(existing -> {
            throw new DuplicateResourceException("User already provisioned: " + keycloakId);
        });

        User user = User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(email)
                .username(username)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .displayName(User.displayName(request.firstName(), request.lastName()))
                .status(User.UserStatus.active)
                .build();

        User saved = userRepository.save(user);
        log.info("Self-provisioned user {} for tenant {}", saved.getUserId(), tenantId);

        // TenantContext isn't bound yet (no local user row existed for it
        // to resolve until the save() above) - actor is the new user
        // themselves, acting on their own behalf, so passed explicitly
        // rather than relying on AuditService's TenantContext defaults.
        auditService.event(AuditActionCatalog.USER_CREATED)
                .tenantId(tenantId)
                .actor(saved.getUserId(), saved.getDisplayName())
                .objectType(AuditObjectTypes.USER)
                .objectId(saved.getUserId())
                .resourceName(saved.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("email", saved.getEmail(), "username", saved.getUsername()))
                .log();

        return userMapper.toResponse(saved, resolveTenantName(tenantId));
    }

    @Override
    public UserResponse createUser(CreateUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        String email = request.email().strip();
        String username = request.username().strip();

        // The same email check as an edit (emailTaken): no other user in the tenant - deactivated
        // ones included, ignoring case - and no Keycloak account by email or username. Then the
        // username, the same way. Each has one answer whatever the clash, see below.
        if (emailTaken(email, null)) {
            throw new EmailInUseException();
        }
        if (userRepository.existsByTenantIdAndUsernameIgnoreCase(tenantId, username)) {
            throw new UsernameInUseException();
        }

        // Keycloak enforces email and username uniqueness realm-wide, and every tenant shares the
        // one realm - so an email or username registered in some OTHER tenant collides too. Every
        // clash, here or in the checks above, gets the same EmailInUseException/UsernameInUseException,
        // so nobody with otua.manage-users.create can tell an in-tenant clash from one in another
        // tenant (the one cross-tenant probe this system doesn't otherwise allow - every other
        // resource collapses a cross-tenant hit to an indistinguishable 404).
        UUID keycloakId;
        try {
            keycloakId = keycloakUserService.createUser(username, email, request.firstName(),
                    request.lastName(), tenantId);
        } catch (KeycloakUserAlreadyExistsException e) {
            // The email was free a moment ago, so this is the username (or the same email, just taken).
            throw emailTaken(email, null) ? new EmailInUseException() : new UsernameInUseException();
        }

        User user = User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(email)
                .username(username)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .displayName(User.displayName(request.firstName(), request.lastName()))
                .status(User.UserStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        // Flushed now, so a clash on uq_users_tenant_email/_username (two admins inviting at once)
        // fails here - and the Keycloak account just made is removed again rather than left orphaned.
        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            try {
                keycloakUserService.deleteUser(keycloakId);
            } catch (RuntimeException cleanup) {
                log.warn("Could not remove Keycloak account {} after a failed invite: {}", keycloakId,
                        cleanup.getMessage());
            }
            throw userRepository.existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(tenantId, email, new UUID(0, 0))
                    ? new EmailInUseException() : new UsernameInUseException();
        }
        log.info("Created user {} ({}) in tenant {}", saved.getUserId(), saved.getEmail(), tenantId);

        // dms-core's own branded "set your password" email (see
        // PasswordResetService#issueForNewUser) - deliberately not
        // Keycloak's hosted executeActionsEmail flow, so the invited
        // person lands on our own /update-password page instead of
        // Keycloak's default-themed one.
        passwordResetService.issueForNewUser(saved);

        // Same transaction as the save() above: either both the user row
        // and this outbox row commit, or neither does. OutboxRelay picks
        // this up asynchronously and forwards it to RabbitMQ for whichever
        // workers care (the welcome email itself is sent synchronously
        // above, not via this event - this is for other side effects,
        // e.g. search indexing or audit/analytics).
        outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE, saved.getUserId(), "user.created",
                Map.of("userId", saved.getUserId(), "tenantId", tenantId, "email", saved.getEmail()));

        auditService.event(AuditActionCatalog.USER_CREATED)
                .objectType(AuditObjectTypes.USER)
                .objectId(saved.getUserId())
                .resourceName(saved.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("email", saved.getEmail(), "username", saved.getUsername()))
                .log();

        return userMapper.toResponse(saved, resolveTenantName(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        User user = findAnyOwnedByCallerTenant(userId);
        return userMapper.toResponse(user, resolveTenantName(user.getTenantId()));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        // Doesn't delegate to getUser(TenantContext.getUserId()) - this
        // needs the manual mapper overload that also resolves and attaches
        // the caller's own effective permission set (see UserResponse's
        // and UserMapper's own doc comments for why that's "me"-only).
        User user = findAnyOwnedByCallerTenant(TenantContext.getUserId());
        return userMapper.toResponse(user, resolveTenantName(user.getTenantId()),
                authorizationService.myEffectivePermissionCodes(), authorizationService.myRoleNames(),
                resolveImpersonatorDisplayNameOrNull(), resolveDepartmentNameOrNull(user));
    }

    @Override
    public UserResponse updateCurrentUser(UpdateOwnProfileRequest request) {
        User user = findOwnedByCallerTenant(TenantContext.getUserId());
        Map<String, Object> previousValue = Map.of(
                "firstName", user.getFirstName(), "lastName", user.getLastName() == null ? "" : user.getLastName());

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setDisplayName(User.displayName(request.firstName(), request.lastName()));
        user.setPosition(request.position() == null || request.position().isBlank() ? null : request.position().trim());
        user.setUpdatedBy(user.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());

        User saved = userRepository.save(user);
        log.info("User {} updated their own profile", saved.getUserId());

        auditService.event(AuditActionCatalog.USER_UPDATED)
                .objectType(AuditObjectTypes.USER)
                .objectId(saved.getUserId())
                .resourceName(saved.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of("firstName", saved.getFirstName(), "lastName", saved.getLastName() == null ? "" : saved.getLastName()))
                .log();

        return userMapper.toResponse(saved, resolveTenantName(saved.getTenantId()),
                authorizationService.myEffectivePermissionCodes(), authorizationService.myRoleNames(),
                resolveImpersonatorDisplayNameOrNull(), resolveDepartmentNameOrNull(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(Pageable pageable) {
        Page<User> page = userRepository.findAllByTenant(TenantContext.getTenantId(), pageable);

        // Batched rather than resolved per row - every user on this page
        // belongs to the caller's own tenant today (findAllByTenant is
        // already scoped to it), so this is a single distinct id in
        // practice, but resolving it this way rather than per-row means
        // this doesn't quietly become an N+1 if listUsers is ever widened
        // to a cross-tenant view (e.g. for PLATFORM_ADMIN).
        Map<UUID, String> tenantNamesById = tenantRepository
                .findAllById(page.getContent().stream().map(User::getTenantId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Tenant::getTenantId, Tenant::getTenantName));

        // The tenant admin's own row is invisible to everyone except
        // another full-access caller - see requireVisibleToCaller's doc
        // comment for why. Filtered here rather than relying on the
        // frontend to hide it, since a delegate could otherwise still see
        // (and, before this, act on - see purgeUser/updateUser/etc.) it
        // by paging through the raw list.
        //
        // Total/last are computed from the page's real totalElements, not
        // the filtered content size, so pagination arithmetic
        // (PageResponse.from's totalPages/last) stays correct even though
        // this page's content may now be a little shorter than its size -
        // the alternative (recomputing total from filtered counts) can't
        // be done correctly per-page without knowing how many were
        // filtered on every OTHER page too.
        boolean fullAccess = authorizationService.isTenantAdmin();
        List<UserResponse> content = page.getContent().stream()
                .filter(user -> fullAccess || !isTenantAdminAccount(user))
                .map(user -> userMapper.toResponse(user, tenantNamesById.get(user.getTenantId())))
                .toList();

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Override
    public void markVerified(UUID userId) {
        // Deliberately doesn't go through findOwnedByCallerTenant/
        // findAnyOwnedByCallerTenant: this is called from
        // TenantContextFilter on every request, before TenantContext is
        // necessarily useful for anything else, and a missing/mismatched
        // row here just means "nothing to mark" rather than an error the
        // caller should see — the filter doesn't (and shouldn't) fail a
        // request over this.
        userRepository.findById(userId).ifPresent(user -> {
            if (!user.isVerified()) {
                user.setVerified(true);
                user.setVerifiedAt(OffsetDateTime.now());
                userRepository.save(user);
                log.info("User {} completed account setup (verified)", userId);
            }
        });
    }

    @Override
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = findOwnedByCallerTenant(userId);
        Map<String, Object> previousValue = Map.of(
                "firstName", user.getFirstName(),
                "lastName", user.getLastName() == null ? "" : user.getLastName(),
                "email", user.getEmail(),
                "mfaEnabled", user.isMfaEnabled(),
                "status", user.getStatus());

        // A new email must be no other account's: another user in this tenant (deactivated ones
        // included - they keep theirs), or any Keycloak account in the shared realm, by email or by
        // username (both sign in). One generic answer for all of these - see EmailInUseException.
        String requested = request.email() == null ? "" : request.email().strip();
        String newEmail = requested.isEmpty() || requested.equals(user.getEmail()) ? null : requested; // null = unchanged
        if (newEmail != null) {
            if (emailTaken(newEmail, user)) {
                throw new EmailInUseException();
            }
            user.setEmail(newEmail);
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setDisplayName(User.displayName(request.firstName(), request.lastName()));
        user.setMfaEnabled(request.mfaEnabled());

        // Same blanket rule as deactivateUser - this Edit User status
        // dropdown is a second route to the exact same outcome (disabled
        // status + Keycloak account disabled), and would otherwise bypass
        // that check entirely.
        if (request.status() == UpdateUserRequest.Status.disabled && isTenantAdminAccount(user)) {
            throw new InvalidUserStateException("The tenant admin account can't be deactivated.");
        }

        user.setStatus(User.UserStatus.valueOf(request.status().name()));
        user.setUpdatedBy(TenantContext.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());

        if (request.status() == UpdateUserRequest.Status.disabled) {
            keycloakUserService.disableUser(user.getKeycloakId());
        } else if (request.status() == UpdateUserRequest.Status.active) {
            keycloakUserService.enableUser(user.getKeycloakId());
        }

        // The row first, flushed - so a clash on uq_users_tenant_email (two admins at once) fails
        // here - then the Keycloak account. If Keycloak refuses, the transaction rolls the row back
        // and the two never disagree.
        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailInUseException();
        }
        try {
            keycloakUserService.updateProfile(saved.getKeycloakId(), saved.getFirstName(), saved.getLastName(),
                    newEmail);
        } catch (KeycloakUserAlreadyExistsException e) {
            throw new EmailInUseException();
        }
        log.info("Updated user {}", saved.getUserId());

        auditService.event(AuditActionCatalog.USER_UPDATED)
                .objectType(AuditObjectTypes.USER)
                .objectId(saved.getUserId())
                .resourceName(saved.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "firstName", saved.getFirstName(),
                        "lastName", saved.getLastName() == null ? "" : saved.getLastName(),
                        "email", saved.getEmail(),
                        "mfaEnabled", saved.isMfaEnabled(),
                        "status", saved.getStatus()))
                .log();

        return userMapper.toResponse(saved, resolveTenantName(saved.getTenantId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email, UUID userId) {
        String candidate = email == null ? "" : email.strip();
        if (candidate.isEmpty()) {
            return false;
        }
        User user = userId == null ? null : findOwnedByCallerTenant(userId);
        if (user != null && candidate.equalsIgnoreCase(user.getEmail())) {
            return true; // their own
        }
        return !emailTaken(candidate, user);
    }

    /**
     * Another account has {@code email}: a user in the caller's tenant (deactivated ones too - they
     * keep theirs, and uq_users_tenant_email covers them), or any Keycloak account in the shared
     * realm, by email or username. {@code self} (null for a new user) is never counted.
     */
    private boolean emailTaken(String email, User self) {
        UUID tenantId = self != null ? self.getTenantId() : TenantContext.getTenantId();
        UUID selfId = self != null ? self.getUserId() : new UUID(0, 0);
        return userRepository.existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(tenantId, email, selfId)
                || keycloakUserService.emailTakenByAnotherAccount(email,
                self != null ? self.getKeycloakId() : new UUID(0, 0));
    }

    @Override
    public void deactivateUser(UUID userId, String reason) {
        User user = findOwnedByCallerTenant(userId);

        // Blanket rule, same as the tenant admin account already being
        // unpurgeable (see purgeUser below) - not a "last admin"
        // headcount, which would need real counting infrastructure that
        // doesn't exist for a Keycloak realm role like TENANT_ADMIN (no
        // repository column, no bulk membership lookup) and would be
        // racy to check safely anyway (two admins deactivating two
        // different admins at once could each pass an "are there others"
        // check before either commits).
        if (isTenantAdminAccount(user)) {
            throw new InvalidUserStateException("The tenant admin account can't be deactivated.");
        }

        User.UserStatus previousStatus = user.getStatus();

        user.setDeleted(true);
        user.setDeletedAt(OffsetDateTime.now());
        user.setStatus(User.UserStatus.disabled);
        user.setUpdatedBy(TenantContext.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        keycloakUserService.disableUser(user.getKeycloakId());
        // 1.4 Digital Signature Integration: a deactivated user signs nothing new -
        // their already-made signatures are untouched (decision 9).
        signingKeyService.revokeActiveKeyIfAny(user.getTenantId(), user.getUserId(),
                "User account deactivated.", TenantContext.getUserId());
        log.info("Deactivated user {}", userId);

        auditService.event(AuditActionCatalog.USER_DEACTIVATED)
                .objectType(AuditObjectTypes.USER)
                .objectId(user.getUserId())
                .resourceName(user.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("status", previousStatus))
                .newValue(Map.of("status", user.getStatus()))
                .reason(reason)
                .log();
    }

    @Override
    public void purgeUser(UUID userId) {
        // findPurgeableOwnedByCallerTenant, not findOwnedByCallerTenant:
        // the latter excludes anything already deleted=true, which a
        // merely-deactivated ("Inactive") user already is, and deleting an
        // inactive user from the list is exactly what this is for.
        User user = findPurgeableOwnedByCallerTenant(userId);

        // Blanket rule, same as deactivateUser's own - a permanent,
        // irreversible delete is a strictly more destructive version of
        // deactivate, which is already unconditionally blocked against
        // the tenant admin account (not just for non-full-access
        // callers - findPurgeableOwnedByCallerTenant's requireVisibleToCaller
        // already 404s those). No caller, including another tenant admin,
        // purges the tenant admin account through this app.
        if (isTenantAdminAccount(user)) {
            throw new InvalidUserStateException("The tenant admin account can't be permanently deleted.");
        }
        // 1.4 Digital Signature Integration: the identity behind a legal
        // signature is never erased - user_signing_keys.user_id (and,
        // transitively, document_signatures) is RESTRICT, not CASCADE/SET
        // NULL, so this would fail at the database anyway; checked here
        // first for a clear message instead of a raw constraint violation.
        if (signingKeyService.hasEverSigned(user.getTenantId(), user.getUserId())) {
            throw new InvalidUserStateException(
                    "This user has signed documents and can't be permanently deleted - deactivate instead.");
        }

        UUID keycloakId = user.getKeycloakId();
        String displayName = user.getDisplayName();
        String email = user.getEmail();

        // A real DELETE FROM users, not another flavor of the soft-delete
        // deactivateUser already does - see V13__allow_hard_delete_of_users
        // for the ON DELETE SET NULL / CASCADE behavior this relies on for
        // every other table with a foreign key to users.user_id.
        userRepository.delete(user);

        // Same call order as deactivateUser/reactivateUser (DB write,
        // then Keycloak) and the same reasoning: the class-level
        // @Transactional means if this throws, the delete() above never
        // actually commits, so a Keycloak-side failure can't leave the
        // local row gone while the account still exists there.
        keycloakUserService.deleteUser(keycloakId);
        log.info("Permanently deleted user {}", userId);

        // objectId still points at a row that no longer exists in users -
        // deliberate, and safe: audit_events.actor_id/impersonated_by are
        // the only user-referencing columns without a live FK (see
        // V23__create_audit_events.sql), object_id has never been a live
        // FK to begin with (it's polymorphic across object_type), so this
        // row simply outlives the user it describes, same as any other
        // audit record for a deleted resource.
        auditService.event(AuditActionCatalog.USER_DELETED)
                .objectType(AuditObjectTypes.USER)
                .objectId(userId)
                .resourceName(displayName)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("email", email))
                .log();
    }

    @Override
    public UserResponse reactivateUser(UUID userId) {
        User user = findDeletedOwnedByCallerTenant(userId);

        user.setDeleted(false);
        user.setDeletedAt(null);
        user.setStatus(User.UserStatus.active);
        user.setUpdatedBy(TenantContext.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());

        User saved = userRepository.save(user);

        // Same call order as deactivateUser (DB write, then Keycloak).
        // Safe either way: the class-level @Transactional means if
        // enableUser throws, this whole method's transaction rolls back —
        // the save() above never actually commits — so a Keycloak-side
        // failure can't leave the local row reactivated while the account
        // stays disabled there.
        keycloakUserService.enableUser(user.getKeycloakId());

        log.info("Reactivated user {}", userId);

        auditService.event(AuditActionCatalog.USER_REACTIVATED)
                .objectType(AuditObjectTypes.USER)
                .objectId(saved.getUserId())
                .resourceName(saved.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .log();

        return userMapper.toResponse(saved, resolveTenantName(saved.getTenantId()));
    }

    @Override
    public void resetPassword(UUID userId) {
        User user = findOwnedByCallerTenant(userId);
        passwordResetService.issueForAdminReset(user);
        log.info("Sent password reset email for user {}", userId);

        auditService.event(AuditActionCatalog.PASSWORD_RESET_REQUESTED)
                .objectType(AuditObjectTypes.USER)
                .objectId(user.getUserId())
                .resourceName(user.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getPlatformRoles(UUID userId) {
        // findAnyOwnedByCallerTenant, not findOwnedByCallerTenant - a
        // deactivated user's platform role (if they held one) shouldn't
        // disappear from their profile just because their account is
        // currently deactivated, matching getUser's own choice of lookup.
        User user = findAnyOwnedByCallerTenant(userId);
        return keycloakUserService.getPlatformRealmRoles(user.getKeycloakId());
    }

    /**
     * Resolves a single user's tenant name for {@link UserMapper#toResponse(User, String)} -
     * {@code null} only if the tenant row is somehow missing/deleted, which
     * shouldn't happen for a user's own tenant in practice. {@link #listUsers}
     * batches this across a page instead of calling this per row.
     */
    private String resolveTenantName(UUID tenantId) {
        return tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .map(Tenant::getTenantName)
                .orElse(null);
    }

    /**
     * True if `user` holds the TENANT_ADMIN platform role. Resolved fresh
     * per call (no local cache/column for it - see the class-level doc
     * comments this mirrors on {@link #deactivateUser} and elsewhere), so
     * a caller that needs both the visibility check and the actual value
     * (e.g. {@link #getPlatformRoles}) ends up calling Keycloak twice.
     * Accepted: this only runs on single-user admin actions, never in a
     * hot path, and keeping the check centralized in one place is worth
     * more than saving one Keycloak round-trip on those.
     */
    private boolean isTenantAdminAccount(User user) {
        return keycloakUserService.getPlatformRealmRoles(user.getKeycloakId()).contains("TENANT_ADMIN");
    }

    /**
     * The tenant admin's own account must not be visible to - or
     * therefore actionable by - any caller who isn't a full-access
     * caller themselves (TENANT_ADMIN or PLATFORM_ADMIN, see
     * {@link AuthorizationService#isTenantAdmin()}). Without this, every
     * granular otua.manage-users.* permission this app can delegate to a
     * custom role (view, update, reset-password, activate/deactivate,
     * delete, assign-role) would also reach the tenant admin's own
     * record - reset-password and delete are the sharpest versions of
     * this, since either would hand a delegate a path to the tenant
     * admin's own credentials or account outright, turning a narrow
     * delegation into full tenant takeover.
     * <p>
     * Throws 404, not 403 - same reasoning as {@link TenantMismatchException}
     * across a tenant boundary: a delegate must not even be able to
     * confirm the record exists. Centralized here (called from every
     * finder below) rather than repeated at each call site, so a future
     * method built on one of these finders inherits the guard
     * automatically instead of needing to remember it.
     * <p>
     * Deliberately does NOT block a full-access caller - two tenant
     * admins (or a platform admin) can still see and manage each other.
     * {@link #deactivateUser} and {@link #purgeUser} each additionally
     * refuse their specific action against a tenant admin unconditionally
     * (even for a full-access caller) - that's a separate, stricter rule
     * layered on top of this visibility check, not a replacement for it.
     */
    private void requireVisibleToCaller(User user, UUID userId) {
        if (!authorizationService.isTenantAdmin() && isTenantAdminAccount(user)) {
            throw new ResourceNotFoundException("User", userId);
        }
    }

    /**
     * The real admin's display name if the current request is running under
     * an active impersonation session, else null - see
     * {@link UserResponse}'s own doc comment for why this, not a raw id, is
     * what the frontend's impersonation banner needs. A missing/deleted
     * admin row (shouldn't happen - the admin's own session is what's
     * currently making this request) falls back to null rather than
     * throwing, so a data inconsistency here never breaks an otherwise
     * unrelated "get my profile" call.
     */
    private String resolveImpersonatorDisplayNameOrNull() {
        UUID impersonatorId = TenantContext.getImpersonatorIdOrNull();
        if (impersonatorId == null) {
            return null;
        }
        return userRepository.findById(impersonatorId).map(User::getDisplayName).orElse(null);
    }

    /** {@code user.getDepartmentId()}'s resolved name, or null if unset/deleted - see {@code UserResponse#departmentName}'s own doc comment on why this has to be resolved here rather than left to the frontend. */
    private String resolveDepartmentNameOrNull(User user) {
        if (user.getDepartmentId() == null) {
            return null;
        }
        return departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(user.getDepartmentId(), user.getTenantId())
                .map(com.oak.dms.department.entity.Department::getName).orElse(null);
    }

    private User findOwnedByCallerTenant(UUID userId) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(TenantContext.getTenantId())) {
            throw new TenantMismatchException("User", userId);
        }
        requireVisibleToCaller(user, userId);
        return user;
    }

    /**
     * Same tenant-ownership check as {@link #findOwnedByCallerTenant}, but
     * doesn't exclude deleted rows - used by {@link #getUser} (and, via
     * that, {@link #listUsers}'s per-row mapping is unaffected since that
     * one goes through the repository directly) so a deactivated user is
     * still viewable rather than 404ing, which is what lets the View User
     * page and User List show a "Reactivate" action instead of the row
     * just vanishing.
     */
    private User findAnyOwnedByCallerTenant(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(TenantContext.getTenantId())) {
            throw new TenantMismatchException("User", userId);
        }
        requireVisibleToCaller(user, userId);
        return user;
    }

    /**
     * Same shape as {@link #findOwnedByCallerTenant}, but for
     * {@link #reactivateUser}, which needs the opposite filter: it only
     * makes sense against a user that IS currently soft-deleted. A
     * not-deleted user id (never deactivated, or only ever
     * locked/disabled via {@link #updateUser}) is a 409, not a 404 - the
     * user exists and the caller can see it via getUser/listUsers, it's
     * just not in the state this operation requires.
     */
    private User findDeletedOwnedByCallerTenant(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(TenantContext.getTenantId())) {
            throw new TenantMismatchException("User", userId);
        }
        if (!user.isDeleted()) {
            throw new InvalidUserStateException("User " + userId + " is not deactivated");
        }
        requireVisibleToCaller(user, userId);
        return user;
    }

    /**
     * Lookup for {@link #purgeUser}. Deliberately ignores {@code deleted}
     * (unlike {@link #findOwnedByCallerTenant}) since permanently deleting
     * an already-deactivated ("Inactive") user is exactly what the User
     * List's Delete action is for - any user id owned by the caller's
     * tenant is a valid target, active or not.
     */
    private User findPurgeableOwnedByCallerTenant(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(TenantContext.getTenantId())) {
            throw new TenantMismatchException("User", userId);
        }
        requireVisibleToCaller(user, userId);
        return user;
    }
}