package com.oak.dms.tenant;

import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.tenant.dto.ProvisionTenantRequest;
import com.oak.dms.tenant.dto.TenantProvisioningResponse;
import com.oak.dms.user.User;
import com.oak.dms.user.UserMapper;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Onboards a brand-new client in one call: creates the {@link Tenant} row,
 * creates the Keycloak account for their first administrator (with the
 * tenant_id attribute set correctly), grants that account TENANT_ADMIN, and
 * creates the corresponding local {@link User} row.
 * <p>
 * Before this existed, all four of those had to be done separately - the
 * last three by hand in the Keycloak console, since no other endpoint can
 * create a user in a tenant other than the caller's own, or grant
 * TENANT_ADMIN at all. That doesn't scale past a handful of clients and is
 * easy to get wrong in a way that's invisible until the client's admin
 * can't log in (e.g. a mistyped tenant_id attribute).
 * <p>
 * <b>Failure handling:</b> the two DB writes (tenant, user) share one
 * Spring transaction and roll back together automatically. The two
 * Keycloak calls are not part of that transaction - Keycloak has no
 * concept of it - so once the Keycloak user exists, any later failure is
 * handled with an explicit compensating delete of that user rather than
 * relying on transactional rollback, which would never reach it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final TenantRepository tenantRepository;
    private final TenantMapper tenantMapper;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final KeycloakUserService keycloakUserService;

    @Transactional
    public TenantProvisioningResponse provisionTenant(ProvisionTenantRequest request) {
        if (tenantRepository.existsByDomainAndDeletedFalse(request.domain())) {
            throw new DuplicateResourceException("A tenant already exists for domain " + request.domain());
        }

        Tenant tenant = Tenant.builder()
                .tenantName(request.tenantName())
                .domain(request.domain())
                .subscriptionTier(request.subscriptionTier())
                .status(Tenant.TenantStatus.active)
                .build();
        tenant = tenantRepository.save(tenant);
        UUID tenantId = tenant.getTenantId();

        // Creates the Keycloak user, sets a temporary password, and sends
        // the verify-email/set-password required-actions email - same as
        // a normal user invite (see KeycloakUserService#createUser). If
        // this throws, nothing else has happened yet: the tenant insert
        // above rolls back automatically with the rest of this
        // transaction, so no manual cleanup is needed.
        UUID keycloakId = keycloakUserService.createUser(request.adminEmail(), request.adminFirstName(),
                request.adminLastName(), tenantId);

        try {
            keycloakUserService.assignPlatformRealmRole(keycloakId, TENANT_ADMIN_ROLE);

            User admin = User.builder()
                    .tenantId(tenantId)
                    .keycloakId(keycloakId)
                    .email(request.adminEmail())
                    .firstName(request.adminFirstName())
                    .lastName(request.adminLastName())
                    .displayName(User.displayName(request.adminFirstName(), request.adminLastName()))
                    .status(User.UserStatus.active)
                    .build();
            User savedAdmin = userRepository.save(admin);

            log.info("Provisioned tenant {} ({}) with first admin {} ({})",
                    tenantId, tenant.getDomain(), savedAdmin.getUserId(), savedAdmin.getEmail());

            return new TenantProvisioningResponse(tenantMapper.toResponse(tenant), userMapper.toResponse(savedAdmin));
        } catch (RuntimeException e) {
            // The Keycloak user (and possibly the TENANT_ADMIN grant) from
            // above is not covered by this method's DB transaction, so it
            // will NOT be rolled back automatically when we rethrow below.
            // Delete it explicitly so a failed provisioning attempt never
            // leaves an orphaned Keycloak account with no corresponding
            // tenant/user - that account would otherwise be invisible to
            // the app (no local user row) yet still able to authenticate.
            try {
                keycloakUserService.deleteUser(keycloakId);
            } catch (RuntimeException cleanupFailure) {
                log.error("Failed to clean up orphaned Keycloak user {} after a failed provisioning attempt "
                        + "for domain {}; manual cleanup required in the Keycloak console",
                        keycloakId, request.domain(), cleanupFailure);
            }
            throw e;
        }
    }
}
