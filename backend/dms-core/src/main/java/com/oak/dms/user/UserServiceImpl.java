package com.oak.dms.user;

import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateUserRequest;
import com.oak.dms.user.dto.UserResponse;
import com.oak.dms.user.User;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.exception.TenantMismatchException;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.UserMapper;
import com.oak.dms.user.UserRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link UserService}. Every method after provisioning enforces tenant
 * ownership via {@link #findOwnedByCallerTenant} before returning or mutating
 * a user, so one tenant can never read or modify another tenant's users even
 * given a guessed id. createUser() also emits a user.created outbox event.
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
    private final OutboxEventPublisher outboxEventPublisher;

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
                .displayName(request.displayName())
                .status(User.UserStatus.active)
                .build();

        User saved = userRepository.save(user);
        log.info("Self-provisioned user {} for tenant {}", saved.getUserId(), tenantId);
        return userMapper.toResponse(saved);
    }

    @Override
    public UserResponse createUser(CreateUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        if (userRepository.existsByTenantIdAndEmailAndDeletedFalse(tenantId, request.email())) {
            throw new DuplicateResourceException("A user already exists with email " + request.email());
        }

        UUID keycloakId = keycloakUserService.createUser(request.email(), request.displayName(), tenantId);

        User user = User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(request.email())
                .displayName(request.displayName())
                .status(User.UserStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        User saved = userRepository.save(user);
        log.info("Created user {} ({}) in tenant {}", saved.getUserId(), saved.getEmail(), tenantId);

        // Same transaction as the save() above: either both the user row
        // and this outbox row commit, or neither does. OutboxRelay picks
        // this up asynchronously and forwards it to RabbitMQ for whichever
        // workers care (e.g. integration-worker sending a welcome email).
        outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE, saved.getUserId(), "user.created",
                Map.of("userId", saved.getUserId(), "tenantId", tenantId, "email", saved.getEmail()));

        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        User user = findOwnedByCallerTenant(userId);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        return getUser(TenantContext.getUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(Pageable pageable) {
        return userRepository.findAllActiveByTenant(TenantContext.getTenantId(), pageable)
                .map(userMapper::toResponse);
    }

    @Override
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = findOwnedByCallerTenant(userId);

        user.setDisplayName(request.displayName());
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
        return userMapper.toResponse(saved);
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

    private User findOwnedByCallerTenant(UUID userId) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(TenantContext.getTenantId())) {
            throw new TenantMismatchException("User", userId);
        }
        return user;
    }
}
