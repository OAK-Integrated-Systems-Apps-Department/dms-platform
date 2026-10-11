package com.oak.dms.importexport.export;

import com.oak.dms.document.DocumentVersion;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * The one answer to "can user U in tenant T export document D?", used by
 * every part of bulk export - the document list, preview, request and the
 * worker. Today: same tenant, document not deleted, its current version
 * present, not deleted and scanned clean (or released by an administrator
 * - the same {@link DocumentVersion#isServable} single-document download
 * uses), and U holds {@value PermissionCodes#FIE_BULK_EXPORT_EXECUTE} and
 * {@value PermissionCodes#DOCM_DOCUMENTS_VIEW}. When scoped access
 * (module 1.1) arrives, this class is the one place to change.
 *
 * <p>The document rule has two forms: {@link #documentPredicateSql()} for
 * the queries that list and count documents, and {@link #skipReason} for
 * the worker, which needs to say why a frozen document was left out.
 * <b>They must express the same rule - change both together.</b> To make
 * drift hard, both are built from the one {@link Condition} list below,
 * each condition carrying its SQL clause, its Java check and its reason;
 * {@code BulkExportAccessPolicyTest} runs the same cases through both
 * forms and checks they agree.
 *
 * <p>The user check runs with no web request (the worker is a queue
 * consumer), so it can't use {@code AuthorizationService}, which reads the
 * caller's token. It reads the RBAC tables through
 * {@link RolePermissionRepository#findEffectivePermissionCodes}, the query
 * {@code AuthorizationService} itself runs. Tenant admins hold every
 * permission through the Keycloak TENANT_ADMIN realm role rather than RBAC
 * rows, so when the RBAC rows aren't enough the user's realm roles are
 * looked up live - the same pairing {@code ThreatNotificationListener}
 * uses off the request.
 */
@Component
@RequiredArgsConstructor
public class BulkExportAccessPolicy {

    static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";
    static final Set<String> REQUIRED_PERMISSIONS =
            Set.of(PermissionCodes.FIE_BULK_EXPORT_EXECUTE, PermissionCodes.DOCM_DOCUMENTS_VIEW);
    static final List<String> SERVABLE_SCAN_STATUSES =
            List.of(DocumentVersion.SCAN_STATUS_CLEAN, DocumentVersion.SCAN_STATUS_OVERRIDDEN);

    private final RolePermissionRepository rolePermissionRepository;
    private final UserRepository userRepository;
    private final KeycloakUserService keycloakUserService;

    /**
     * One part of the document rule. The SQL is written against the
     * aliases {@code d} (documents) and {@code v} (the current
     * document_versions row, left-joined - null when there is none), with
     * the bind parameter {@code :tenantId}. Order matters for
     * {@link #skipReason}: the first failing condition gives the reason.
     */
    enum Condition {
        SAME_TENANT("d.tenant_id = :tenantId",
                (row, tenantId) -> Objects.equals(row.tenantId(), tenantId),
                "The document belongs to another organisation"),
        DOCUMENT_NOT_DELETED("d.is_deleted = false",
                (row, tenantId) -> !row.documentDeleted(),
                "The document has been deleted"),
        CURRENT_VERSION_PRESENT("v.version_id is not null",
                (row, tenantId) -> row.versionId() != null,
                "The document has no current version"),
        CURRENT_VERSION_NOT_DELETED("v.is_deleted = false",
                (row, tenantId) -> row.versionId() != null && !row.versionDeleted(),
                "The document's current version has been deleted"),
        SCAN_SERVABLE("v.scan_status in (" + SERVABLE_SCAN_STATUSES.stream()
                .map(status -> "'" + status + "'")
                .collect(Collectors.joining(", ")) + ")",
                // Null-safe: List.of(..).contains(null) throws.
                (row, tenantId) -> row.scanStatus() != null && SERVABLE_SCAN_STATUSES.contains(row.scanStatus()),
                "The current version has not passed the malware scan");

        final String sql;
        final BiPredicate<ExportableDocumentRow, UUID> check;
        final String reason;

        Condition(String sql, BiPredicate<ExportableDocumentRow, UUID> check, String reason) {
            this.sql = sql;
            this.check = check;
            this.reason = reason;
        }
    }

    /**
     * The document rule as a SQL boolean expression - see {@link Condition}
     * for the aliases. <b>Must express the same rule as
     * {@link #skipReason}.</b> Bind it with {@link #bindings}. (Joined on
     * each call rather than held in a static: Condition's constants read
     * this class's statics, so a static built from Condition here would
     * make the two initialise each other.)
     */
    public String documentPredicateSql() {
        return Arrays.stream(Condition.values())
                .map(condition -> condition.sql)
                .collect(Collectors.joining(" and "));
    }

    /**
     * Bind values for {@link #documentPredicateSql()}. {@code userId} isn't
     * used by today's rule (every document of the tenant is visible to a
     * user holding the permissions); it is here so scoped access can
     * narrow the rule per user without changing any caller.
     */
    public Map<String, Object> bindings(UUID tenantId, UUID userId) {
        return Map.of("tenantId", tenantId, "userId", userId);
    }

    /**
     * Why {@code row} can't be exported by a user of {@code tenantId}, or
     * empty if it can. <b>Must express the same rule as
     * {@link #documentPredicateSql()}.</b>
     */
    public Optional<String> skipReason(ExportableDocumentRow row, UUID tenantId) {
        for (Condition condition : Condition.values()) {
            if (!condition.check.test(row, tenantId)) {
                return Optional.of(condition.reason);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns normally if {@code userId} may export documents in
     * {@code tenantId}.
     *
     * @throws ExportPermissionRevokedException if the lookups succeeded and
     *         show the user can't: gone, not active, or holding neither
     *         both permission codes nor the TENANT_ADMIN role
     * @throws RuntimeException (typically {@code KeycloakIntegrationException})
     *         if the Keycloak role lookup itself failed - a temporary
     *         error, never to be treated as a revoked permission
     */
    public void requireUserMayExport(UUID tenantId, UUID userId) {
        User user = userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ExportPermissionRevokedException(userId, "user not found"));
        if (user.getStatus() != User.UserStatus.active) {
            throw new ExportPermissionRevokedException(userId, "user is " + user.getStatus());
        }
        if (rolePermissionRepository.findEffectivePermissionCodes(userId, tenantId)
                .containsAll(REQUIRED_PERMISSIONS)) {
            return;
        }
        if (user.getKeycloakId() == null) {
            throw new ExportPermissionRevokedException(userId, "missing permissions and no Keycloak account");
        }
        // May throw - deliberately not caught: a failed lookup is not an answer.
        if (!keycloakUserService.getPlatformRealmRoles(user.getKeycloakId()).contains(TENANT_ADMIN_ROLE)) {
            throw new ExportPermissionRevokedException(userId, "missing " + REQUIRED_PERMISSIONS);
        }
    }
}
