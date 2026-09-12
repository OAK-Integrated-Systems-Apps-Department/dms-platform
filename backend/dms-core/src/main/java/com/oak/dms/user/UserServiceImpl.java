package com.oak.dms.user;

import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateOwnProfileRequest;
import com.oak.dms.user.dto.UpdateUserRequest;
import com.oak.dms.user.dto.UserResponse;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.InvalidUserStateException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.exception.TenantMismatchException;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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

    @Override
    public UserResponse provisionCurrentUser(UUID keycloakId, UUID tenantId, String email,
                                              ProvisionUserRequest request) {
        userRepository.findByKeycloakIdAndDeletedFalse(keycloakId).ifPresent(existing -> {
            throw new DuplicateResourceException("User already provisioned: " + keycloakId);
        });

        User user = User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(email)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .displayName(User.displayName(request.firstName(), request.lastName()))
                .status(User.UserStatus.active)
                .build();

        User saved = userRepository.save(user);
        log.info("Self-provisioned user {} for tenant {}", saved.getUserId(), tenantId);
        return userMapper.toResponse(saved, resolveTenantName(tenantId));
    }

    @Override
    public UserResponse createUser(CreateUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        if (userRepository.existsByTenantIdAndEmailAndDeletedFalse(tenantId, request.email())) {
            throw new DuplicateResourceException("A user already exists with email " + request.email());
        }

        UUID keycloakId = keycloakUserService.createUser(request.email(), request.firstName(), request.lastName(),
                tenantId);

        User user = User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .displayName(User.displayName(request.firstName(), request.lastName()))
                .status(User.UserStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        User saved = userRepository.save(user);
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
                authorizationService.myEffectivePermissionCodes());
    }

    @Override
    public UserResponse updateCurrentUser(UpdateOwnProfileRequest request) {
        User user = findOwnedByCallerTenant(TenantContext.getUserId());

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setDisplayName(User.displayName(request.firstName(), request.lastName()));
        user.setUpdatedBy(user.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());

        User saved = userRepository.save(user);
        log.info("User {} updated their own profile", saved.getUserId());
        return userMapper.toResponse(saved, resolveTenantName(saved.getTenantId()),
                authorizationService.myEffectivePermissionCodes());
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

        return page.map(user -> userMapper.toResponse(user, tenantNamesById.get(user.getTenantId())));
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

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setDisplayName(User.displayName(request.firstName(), request.lastName()));
        user.setMfaEnabled(request.mfaEnabled());
        user.setStatus(User.UserStatus.valueOf(request.status().name()));
        user.setUpdatedBy(TenantContext.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());

        if (request.status() == UpdateUserRequest.Status.disabled) {
            keycloakUserService.disableUser(user.getKeycloakId());
        } else if (request.status() == UpdateUserRequest.Status.active) {
            keycloakUserService.enableUser(user.getKeycloakId());
        }

        User saved = userRepository.save(user);
        log.info("Updated user {}", saved.getUserId());
        return userMapper.toResponse(saved, resolveTenantName(saved.getTenantId()));
    }

    @Override
    public void deactivateUser(UUID userId) {
        User user = findOwnedByCallerTenant(userId);

        user.setDeleted(true);
        user.setDeletedAt(OffsetDateTime.now());
        user.setStatus(User.UserStatus.disabled);
        user.setUpdatedBy(TenantContext.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        keycloakUserService.disableUser(user.getKeycloakId());
        log.info("Deactivated user {}", userId);
    }

    @Override
    public void purgeUser(UUID userId) {
        // findPurgeableOwnedByCallerTenant, not findOwnedByCallerTenant:
        // the latter excludes anything already deleted=true, which a
        // merely-deactivated ("Inactive") user already is, and deleting an
        // inactive user from the list is exactly what this is for.
        User user = findPurgeableOwnedByCallerTenant(userId);
        UUID keycloakId = user.getKeycloakId();

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
        return userMapper.toResponse(saved, resolveTenantName(saved.getTenantId()));
    }

    @Override
    public void resetPassword(UUID userId) {
        User user = findOwnedByCallerTenant(userId);
        passwordResetService.issueForAdminReset(user);
        log.info("Sent password reset email for user {}", userId);
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

    private User findOwnedByCallerTenant(UUID userId) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(TenantContext.getTenantId())) {
            throw new TenantMismatchException("User", userId);
        }
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
        return user;
    }
}