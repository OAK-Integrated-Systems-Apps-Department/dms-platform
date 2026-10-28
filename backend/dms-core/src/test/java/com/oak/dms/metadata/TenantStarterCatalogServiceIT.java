package com.oak.dms.metadata;

import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.seed.TenantStarterCatalogService;
import com.oak.dms.metadata.seed.TenantStarterCatalogService.InstallResult;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static com.oak.dms.metadata.support.StarterCatalogAssertions.assertExactStarterCatalog;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.catalogSnapshot;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.count;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.expectedElements;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.schemaElements;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Proves {@link TenantStarterCatalogService} installs exactly the starter
 * catalogue, is idempotent, and follows the same rules as V37's backfill
 * around a tenant's own data (see V37MigrationIT for the SQL side).
 */
@SpringBootTest
class TenantStarterCatalogServiceIT {

    @Autowired
    private TenantStarterCatalogService starterCatalogService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private SchemaCacheService schemaCacheService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void freshTenant_getsExactlyTheStarterCatalogue_withNoAuthorOutsideARequest() throws Exception {
        UUID tenantId = createTenant();

        InstallResult result = starterCatalogService.install(tenantId);

        assertThat(result).isEqualTo(new InstallResult(24, 8, 4, 4));
        try (Connection c = dataSource.getConnection()) {
            assertExactStarterCatalog(c, tenantId, null);
        }
        verify(schemaCacheService).evictTenant(tenantId);
    }

    @Test
    void withABoundUser_recordsThatUserAsAuthor() throws Exception {
        UUID tenantId = createTenant();
        UUID keycloakId = UUID.randomUUID();
        User admin = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@starter-catalog-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Admin")
                .displayName("Test Admin")
                .status(User.UserStatus.active)
                .build());
        TenantContext.set(tenantId, admin.getUserId(), admin.getDisplayName(), keycloakId);

        starterCatalogService.install(tenantId);

        try (Connection c = dataSource.getConnection()) {
            assertExactStarterCatalog(c, tenantId, admin.getUserId());
        }
    }

    @Test
    void installingTwice_changesNothingTheSecondTime() throws Exception {
        UUID tenantId = createTenant();
        starterCatalogService.install(tenantId);
        List<String> before;
        try (Connection c = dataSource.getConnection()) {
            before = catalogSnapshot(c, tenantId);
        }

        InstallResult second = starterCatalogService.install(tenantId);

        assertThat(second).isEqualTo(new InstallResult(0, 0, 0, 0));
        try (Connection c = dataSource.getConnection()) {
            assertThat(catalogSnapshot(c, tenantId)).isEqualTo(before);
        }
    }

    @Test
    void tenantWithItsOwnData_isInstalledAroundItWithoutTouchingIt() throws Exception {
        UUID tenantId = createTenant();
        jdbcTemplate.update("""
                insert into document_types (tenant_id, type_code, type_name, display_order) values
                    (?, 'HR', 'Human resources', 10), (?, 'LEGAL', 'Legal', 20), (?, 'INVOICE', 'Our own invoices', 30)""",
                tenantId, tenantId, tenantId);
        jdbcTemplate.update("""
                insert into document_type_schemas (tenant_id, document_type_id, schema_version)
                select tenant_id, document_type_id, 1 from document_types where tenant_id = ?""", tenantId);
        jdbcTemplate.update("""
                insert into metadata_fields (tenant_id, field_key, label, data_type, status, is_deleted) values
                    (?, 'currency', 'Our currency', 'dropdown', 'active', false),
                    (?, 'description', 'Old description', 'long_text', 'deprecated', false),
                    (?, 'tags', 'Deleted tags', 'multi_select', 'active', true)""", tenantId, tenantId, tenantId);
        jdbcTemplate.update("""
                insert into metadata_field_options (tenant_id, field_id, option_value, option_label, display_order)
                select tenant_id, field_id, v.val, v.val, v.ord
                from metadata_fields, (values ('AUD', 10), ('NZD', 20)) as v(val, ord)
                where tenant_id = ? and field_key = 'currency'""", tenantId);
        List<String> before;
        try (Connection c = dataSource.getConnection()) {
            before = catalogSnapshot(c, tenantId);
        }

        InstallResult result = starterCatalogService.install(tenantId);

        assertThat(result).isEqualTo(new InstallResult(22, 4, 3, 3));
        try (Connection c = dataSource.getConnection()) {
            assertThat(catalogSnapshot(c, tenantId)).containsAll(before);
            assertThat(strings(c, "select type_code || ':' || display_order from document_types "
                    + "where tenant_id = ? and is_deleted = false order by display_order", tenantId))
                    .containsExactly("HR:10", "LEGAL:20", "INVOICE:30", "CONTRACT:40", "POLICY:50", "GENERAL:60");
            assertThat(strings(c, """
                    select o.option_value from metadata_field_options o join metadata_fields f on f.field_id = o.field_id
                    where f.tenant_id = ? and f.field_key = 'currency' order by o.display_order""", tenantId))
                    .containsExactly("AUD", "NZD");
            assertThat(count(c, "select count(*) from metadata_fields where tenant_id = ? and field_key = 'tags' "
                    + "and is_deleted = false and status = 'active'", tenantId)).isEqualTo(1);
            assertThat(schemaElements(c, tenantId, List.of("CONTRACT", "POLICY", "GENERAL")))
                    .containsExactlyElementsOf(expectedElements(List.of("CONTRACT", "POLICY", "GENERAL"),
                            List.of("description")));
        }
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
                .tenantName("Starter catalogue test tenant " + UUID.randomUUID())
                .domain("starter-catalog-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();
    }
}
