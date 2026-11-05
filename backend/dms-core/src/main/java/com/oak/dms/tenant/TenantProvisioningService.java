package com.oak.dms.tenant;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.metadata.seed.TenantStarterCatalogService;
import com.oak.dms.relationship.seed.RelationshipTypeSeeder;
import com.oak.dms.security.auth.PasswordResetService;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <b>Failure handling:</b> the DB writes (tenant, its starter metadata
 * catalogue, user) share one Spring transaction and roll back together
 * automatically. The two
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
    private final PasswordResetService passwordResetService;
    private final AuditService auditService;
    private final TenantStarterCatalogService starterCatalogService;
    private final RelationshipTypeSeeder relationshipTypeSeeder;

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
        // Flushed so the tenants row exists before the catalogue rows that
        // reference it. Installed before any Keycloak call, so a failure here
        // rolls back with the rest of this transaction and leaves nothing
        // outside the database to clean up.
        tenant = tenantRepository.saveAndFlush(tenant);
        UUID tenantId = tenant.getTenantId();
        starterCatalogService.install(tenantId);
        relationshipTypeSeeder.install(tenantId);

        // Every administrator - the first, then any extra ones - is an invited TENANT_ADMIN. Distinct
        // usernames and emails across the request are checked up front: Keycloak would catch a clash too,
        // but only after some of the accounts already exist.
        List<AdminSpec> specs = new ArrayList<>();
        specs.add(new AdminSpec(request.adminEmail(), request.adminUsername(), request.adminFirstName(),
                request.adminLastName()));
        if (request.additionalAdmins() != null) {
            request.additionalAdmins().forEach(extra -> specs.add(
                    new AdminSpec(extra.email(), extra.username(), extra.firstName(), extra.lastName())));
        }
        requireDistinct(specs);

        // Keycloak accounts created so far. They are not part of this DB transaction, so a failure part-way
        // must delete exactly these - an orphaned account with no tenant/user row would be invisible to the
        // app yet still able to sign in.
        List<UUID> createdInKeycloak = new ArrayList<>();
        List<User> savedAdmins = new ArrayList<>();
        try {
            for (AdminSpec spec : specs) {
                // Creates the Keycloak user with an unguessable temporary password nobody is told - same as
                // a normal user invite. The set-password email is sent below, once the local User row exists.
                UUID keycloakId = keycloakUserService.createUser(spec.username(), spec.email(), spec.firstName(),
                        spec.lastName(), tenantId);
                createdInKeycloak.add(keycloakId);
                keycloakUserService.assignPlatformRealmRole(keycloakId, TENANT_ADMIN_ROLE);

                User saved = userRepository.save(User.builder()
                        .tenantId(tenantId)
                        .keycloakId(keycloakId)
                        .email(spec.email())
                        .username(spec.username())
                        .firstName(spec.firstName())
                        .lastName(spec.lastName())
                        .displayName(User.displayName(spec.firstName(), spec.lastName()))
                        .status(User.UserStatus.active)
                        .build());
                savedAdmins.add(saved);
            }

            // The invitation is each administrator's only route to a real password, so a send failure is
            // not swallowed: it undoes the whole organisation, and the platform administrator is told what
            // is wrong (the mail settings) instead of "An unexpected error occurred".
            for (User saved : savedAdmins) {
                try {
                    passwordResetService.issueForNewUser(saved);
                } catch (RuntimeException mailFailure) {
                    log.error("Invitation email to administrator {} of {} could not be sent; undoing the organisation: {}",
                            saved.getEmail(), request.domain(), mailFailure.getMessage(), mailFailure);
                    throw new com.oak.dms.common.exception.MailDeliveryException("An administrator of the organisation "
                            + "could not be invited by email, so the organisation was not created. Check the mail settings (SMTP) and try again.");
                }
            }

            User first = savedAdmins.get(0);
            log.info("Provisioned tenant {} ({}) with {} admin(s), first {} ({})",
                    tenantId, tenant.getDomain(), savedAdmins.size(), first.getUserId(), first.getEmail());

            // platformLevel() - same reasoning as TenantServiceImpl's own tenant-lifecycle events: this isn't
            // about the caller's own tenant. objectType is TENANT (not USER), since the tenant is the primary
            // resource this endpoint creates - the administrators are captured in newValue.
            auditService.event(AuditActionCatalog.TENANT_PROVISIONED)
                    .platformLevel()
                    .objectType(AuditObjectTypes.TENANT)
                    .objectId(tenantId)
                    .resourceName(tenant.getTenantName())
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(Map.of(
                            "tenantName", tenant.getTenantName(),
                            "domain", tenant.getDomain(),
                            "subscriptionTier", tenant.getSubscriptionTier(),
                            "adminUserId", first.getUserId(),
                            "adminUsername", first.getUsername(),
                            "adminEmail", first.getEmail(),
                            "adminCount", savedAdmins.size(),
                            "adminEmails", savedAdmins.stream().map(User::getEmail).toList()))
                    .log();

            String tenantName = tenant.getTenantName();
            return new TenantProvisioningResponse(tenantMapper.toResponse(tenant),
                    userMapper.toResponse(first, tenantName),
                    savedAdmins.stream().skip(1).map(u -> userMapper.toResponse(u, tenantName)).toList());
        } catch (RuntimeException e) {
            for (UUID keycloakId : createdInKeycloak) {
                try {
                    keycloakUserService.deleteUser(keycloakId);
                } catch (RuntimeException cleanupFailure) {
                    log.error("Failed to clean up orphaned Keycloak user {} after a failed provisioning attempt "
                            + "for domain {}; manual cleanup required in the Keycloak console",
                            keycloakId, request.domain(), cleanupFailure);
                }
            }
            throw e;
        }
    }

    /** Two administrators of one organisation can't share a username or an email (case-insensitive). */
    private static void requireDistinct(List<AdminSpec> specs) {
        Set<String> usernames = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (AdminSpec spec : specs) {
            if (!usernames.add(spec.username().toLowerCase(Locale.ROOT))) {
                throw new DuplicateResourceException("Two administrators have the username " + spec.username());
            }
            if (!emails.add(spec.email().toLowerCase(Locale.ROOT))) {
                throw new DuplicateResourceException("Two administrators have the email " + spec.email());
            }
        }
    }

    private record AdminSpec(String email, String username, String firstName, String lastName) {
    }
}
