package com.oak.dms.importexport.export;

import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkExportAccessPolicyTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final KeycloakUserService keycloakUserService = mock(KeycloakUserService.class);
    private final BulkExportAccessPolicy policy =
            new BulkExportAccessPolicy(rolePermissionRepository, userRepository, keycloakUserService);

    private final UUID userId = UUID.randomUUID();
    private final UUID keycloakId = UUID.randomUUID();

    // ---- the user check

    @Test
    void requireUserMayExport_bothCodesViaRoles_passesWithoutAskingKeycloak() {
        stubUser(User.UserStatus.active);
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, TENANT)).thenReturn(List.of(
                PermissionCodes.FIE_BULK_EXPORT_EXECUTE, PermissionCodes.DOCM_DOCUMENTS_VIEW, "docm.documents.create"));

        assertThatCode(() -> policy.requireUserMayExport(TENANT, userId)).doesNotThrowAnyException();
        verifyNoInteractions(keycloakUserService);
    }

    @Test
    void requireUserMayExport_missingOneCodeAndNotTenantAdmin_isRevoked() {
        stubUser(User.UserStatus.active);
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, TENANT))
                .thenReturn(List.of(PermissionCodes.FIE_BULK_EXPORT_EXECUTE));
        when(keycloakUserService.getPlatformRealmRoles(keycloakId)).thenReturn(List.of("MEMBER"));

        assertThatThrownBy(() -> policy.requireUserMayExport(TENANT, userId))
                .isInstanceOf(ExportPermissionRevokedException.class);
    }

    @Test
    void requireUserMayExport_noCodesButTenantAdmin_passes() {
        stubUser(User.UserStatus.active);
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, TENANT)).thenReturn(List.of());
        when(keycloakUserService.getPlatformRealmRoles(keycloakId)).thenReturn(List.of("TENANT_ADMIN"));

        assertThatCode(() -> policy.requireUserMayExport(TENANT, userId)).doesNotThrowAnyException();
    }

    @Test
    void requireUserMayExport_keycloakLookupFails_propagatesTheFailureNeverRevoked() {
        stubUser(User.UserStatus.active);
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, TENANT)).thenReturn(List.of());
        when(keycloakUserService.getPlatformRealmRoles(keycloakId))
                .thenThrow(new KeycloakIntegrationException("Keycloak unreachable"));

        assertThatThrownBy(() -> policy.requireUserMayExport(TENANT, userId))
                .isInstanceOf(KeycloakIntegrationException.class)
                .isNotInstanceOf(ExportPermissionRevokedException.class);
    }

    @Test
    void requireUserMayExport_userGoneOrNotActive_isRevokedWithoutCheckingRoles() {
        when(userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireUserMayExport(TENANT, userId))
                .isInstanceOf(ExportPermissionRevokedException.class);

        for (User.UserStatus status : List.of(User.UserStatus.locked, User.UserStatus.disabled)) {
            stubUser(status);
            assertThatThrownBy(() -> policy.requireUserMayExport(TENANT, userId))
                    .isInstanceOf(ExportPermissionRevokedException.class);
        }
        verify(rolePermissionRepository, never()).findEffectivePermissionCodes(any(), any());
        verifyNoInteractions(keycloakUserService);
    }

    private void stubUser(User.UserStatus status) {
        when(userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, TENANT)).thenReturn(Optional.of(
                User.builder().userId(userId).tenantId(TENANT).keycloakId(keycloakId).displayName("Asha")
                        .status(status).build()));
    }

    // ---- the document rule

    @Test
    void skipReason_eachFailingCondition_givesItsReason() {
        assertThat(policy.skipReason(row(c -> c.tenantId = UUID.randomUUID()), TENANT))
                .contains("The document belongs to another organisation");
        assertThat(policy.skipReason(row(c -> c.documentDeleted = true), TENANT))
                .contains("The document has been deleted");
        assertThat(policy.skipReason(row(c -> c.versionId = null), TENANT))
                .contains("The document has no current version");
        assertThat(policy.skipReason(row(c -> c.scanStatus = "infected"), TENANT))
                .contains("The current version has not passed the malware scan");
    }

    @Test
    void skipReason_cleanOrReleasedByAdministrator_isExportable() {
        assertThat(policy.skipReason(row(c -> c.scanStatus = "clean"), TENANT)).isEmpty();
        assertThat(policy.skipReason(row(c -> c.scanStatus = "overridden"), TENANT)).isEmpty();
    }

    @Test
    void documentPredicateSql_isEveryConditionAndBindsTheTenant() {
        String sql = policy.documentPredicateSql();
        for (BulkExportAccessPolicy.Condition condition : BulkExportAccessPolicy.Condition.values()) {
            assertThat(sql).contains(condition.sql);
        }
        assertThat(sql.split(" and ")).hasSize(BulkExportAccessPolicy.Condition.values().length);
        assertThat(policy.bindings(TENANT, userId)).containsEntry("tenantId", TENANT);
    }

    /**
     * The SQL predicate and skipReason must express the same rule: every
     * case is run through both, and they must agree on whether the
     * document is exportable. The SQL side is evaluated by a small
     * evaluator for the fragment's grammar, with SQL's NULL semantics (a
     * comparison with NULL is not true) - no database needed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleCases")
    void documentPredicateSqlAndSkipReason_agree(String name, ExportableDocumentRow row) {
        boolean sqlSaysExportable = evaluate(policy.documentPredicateSql(), policy.bindings(TENANT, userId),
                columns(row));
        boolean javaSaysExportable = policy.skipReason(row, TENANT).isEmpty();

        assertThat(sqlSaysExportable).as("SQL predicate vs skipReason for '%s'", name)
                .isEqualTo(javaSaysExportable);
    }

    static Stream<Arguments> ruleCases() {
        return Stream.of(
                Arguments.of("clean", row(c -> c.scanStatus = "clean")),
                Arguments.of("released by administrator", row(c -> c.scanStatus = "overridden")),
                Arguments.of("other tenant", row(c -> c.tenantId = UUID.randomUUID())),
                Arguments.of("document deleted", row(c -> c.documentDeleted = true)),
                Arguments.of("no current version", row(c -> c.versionId = null)),
                Arguments.of("current version deleted", row(c -> c.versionDeleted = true)),
                Arguments.of("scan pending", row(c -> c.scanStatus = "pending")),
                Arguments.of("infected", row(c -> c.scanStatus = "infected")),
                Arguments.of("scan failed", row(c -> c.scanStatus = "failed")),
                Arguments.of("quarantined", row(c -> c.scanStatus = "quarantined")),
                Arguments.of("no scan status", row(c -> c.scanStatus = null)),
                Arguments.of("other tenant and deleted", row(c -> {
                    c.tenantId = UUID.randomUUID();
                    c.documentDeleted = true;
                })));
    }

    /** The row's values under the SQL's column names; the version's columns are NULL when there is no version row. */
    private static Map<String, Object> columns(ExportableDocumentRow row) {
        boolean hasVersion = row.versionId() != null;
        Map<String, Object> columns = new HashMap<>();
        columns.put("d.tenant_id", row.tenantId());
        columns.put("d.is_deleted", row.documentDeleted());
        columns.put("v.version_id", row.versionId());
        columns.put("v.is_deleted", hasVersion ? row.versionDeleted() : null);
        columns.put("v.scan_status", hasVersion ? row.scanStatus() : null);
        return columns;
    }

    private static final Pattern EQUALS_PARAM = Pattern.compile("([a-z]+\\.[a-z_]+) = :([a-zA-Z]+)");
    private static final Pattern EQUALS_BOOLEAN = Pattern.compile("([a-z]+\\.[a-z_]+) = (true|false)");
    private static final Pattern IS_NOT_NULL = Pattern.compile("([a-z]+\\.[a-z_]+) is not null");
    private static final Pattern IN_LIST = Pattern.compile("([a-z]+\\.[a-z_]+) in \\((.+)\\)");

    /** Evaluates an AND of simple comparisons; fails the test on anything it doesn't understand. */
    private static boolean evaluate(String sql, Map<String, Object> bindings, Map<String, Object> columns) {
        boolean result = true;
        for (String clause : sql.split(" and ")) {
            result &= evaluateClause(clause.trim(), bindings, columns);
        }
        return result;
    }

    private static boolean evaluateClause(String clause, Map<String, Object> bindings, Map<String, Object> columns) {
        Matcher m;
        if ((m = EQUALS_PARAM.matcher(clause)).matches()) {
            Object value = column(columns, m.group(1));
            return value != null && value.equals(bindings.get(m.group(2)));
        }
        if ((m = EQUALS_BOOLEAN.matcher(clause)).matches()) {
            Object value = column(columns, m.group(1));
            return value != null && value.equals(Boolean.valueOf(m.group(2)));
        }
        if ((m = IS_NOT_NULL.matcher(clause)).matches()) {
            return column(columns, m.group(1)) != null;
        }
        if ((m = IN_LIST.matcher(clause)).matches()) {
            Object value = column(columns, m.group(1));
            List<String> literals = Arrays.stream(m.group(2).split(","))
                    .map(String::trim)
                    .map(literal -> literal.substring(1, literal.length() - 1))
                    .toList();
            return value != null && literals.contains(value.toString());
        }
        return fail("The test evaluator doesn't understand the clause '" + clause + "' - extend it");
    }

    private static Object column(Map<String, Object> columns, String name) {
        if (!columns.containsKey(name)) {
            fail("The test has no value for column " + name + " - add it to columns()");
        }
        return columns.get(name);
    }

    // ---- rows

    static final class RowBuilder {
        UUID tenantId = TENANT;
        boolean documentDeleted;
        UUID versionId = UUID.randomUUID();
        boolean versionDeleted;
        String scanStatus = "clean";
    }

    static ExportableDocumentRow row(java.util.function.Consumer<RowBuilder> change) {
        RowBuilder b = new RowBuilder();
        change.accept(b);
        return new ExportableDocumentRow(UUID.randomUUID(), b.tenantId, "Report", UUID.randomUUID(), "Invoice",
                UUID.randomUUID(), "Asha", OffsetDateTime.now(), b.documentDeleted, b.versionId,
                Objects.isNull(b.versionId) ? null : 1, "report.pdf", 10L, b.scanStatus, b.versionDeleted);
    }
}
