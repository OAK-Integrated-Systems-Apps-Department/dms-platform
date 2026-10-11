package com.oak.dms.importexport.export;

import com.oak.dms.importexport.dto.BulkExportFilters;
import com.oak.dms.importexport.dto.BulkExportSelection;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked") // any(RowMapper.class) against the generic query(..) overloads
class ExportableDocumentQueryTest {

    private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final BulkExportAccessPolicy accessPolicy = new BulkExportAccessPolicy(
            mock(RolePermissionRepository.class), mock(UserRepository.class), mock(KeycloakUserService.class));
    private final ExportableDocumentQuery query = new ExportableDocumentQuery(jdbcTemplate, accessPolicy);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void where_alwaysAppliesTheAccessRuleAndTenant() {
        ExportableDocumentQuery.Where where = query.where(tenantId, userId,
                new BulkExportSelection(null, BulkExportFilters.none()));

        assertThat(where.sql()).isEqualTo(" where " + accessPolicy.documentPredicateSql());
        assertThat(where.params().getValue("tenantId")).isEqualTo(tenantId);
    }

    @Test
    void where_eachFilter_addsItsConditionAndValue() {
        UUID typeId = UUID.randomUUID();
        UUID uploader = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-02-01T00:00:00Z");

        ExportableDocumentQuery.Where where = query.where(tenantId, userId, new BulkExportSelection(null,
                new BulkExportFilters(typeId, from, to, uploader, "  50%_Off\\ ", batchId)));

        assertThat(where.sql())
                .contains("d.document_type_id = :documentTypeId")
                .contains("d.created_at >= :createdFrom")
                .contains("d.created_at < :createdTo")
                .contains("d.created_by = :uploadedBy")
                .contains("lower(d.title) like :titlePattern escape '\\'")
                .contains("ii.document_id = d.document_id")
                .contains("ib.import_batch_id = :importBatchId and ib.tenant_id = :tenantId");
        MapSqlParameterSource params = where.params();
        assertThat(params.getValue("documentTypeId")).isEqualTo(typeId);
        assertThat(params.getValue("createdFrom")).isEqualTo(from);
        assertThat(params.getValue("createdTo")).isEqualTo(to);
        assertThat(params.getValue("uploadedBy")).isEqualTo(uploader);
        // Trimmed, lower-cased, wildcards and the escape character taken literally.
        assertThat(params.getValue("titlePattern")).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(params.getValue("importBatchId")).isEqualTo(batchId);
    }

    @Test
    void where_blankTitleFilter_isIgnored() {
        ExportableDocumentQuery.Where where = query.where(tenantId, userId, new BulkExportSelection(null,
                new BulkExportFilters(null, null, null, null, "   ", null)));

        assertThat(where.sql()).doesNotContain("titlePattern");
    }

    @Test
    void where_explicitIds_restrictToThemAndIgnoreFilters() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

        ExportableDocumentQuery.Where where = query.where(tenantId, userId, new BulkExportSelection(ids, null));

        assertThat(where.sql()).isEqualTo(" where " + accessPolicy.documentPredicateSql()
                + " and d.document_id in (:documentIds)");
        assertThat(where.params().getValue("documentIds")).isEqualTo(ids);
    }

    @Test
    void page_capsPageSizeAndOrdersNewestFirst() {
        when(jdbcTemplate.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(120L);
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        Page<ExportableDocumentRow> page = query.page(tenantId, userId, BulkExportFilters.none(),
                PageRequest.of(2, 500));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("order by d.created_at desc, d.document_id desc limit :limit offset :offset");
        assertThat(params.getValue().getValue("limit")).isEqualTo(ExportableDocumentQuery.MAX_PAGE_SIZE);
        assertThat(params.getValue().getValue("offset")).isEqualTo(2L * ExportableDocumentQuery.MAX_PAGE_SIZE);
        assertThat(page.getTotalElements()).isEqualTo(120);
        assertThat(page.getSize()).isEqualTo(ExportableDocumentQuery.MAX_PAGE_SIZE);
    }

    @Test
    void page_nothingMatches_skipsTheRowQuery() {
        when(jdbcTemplate.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);

        Page<ExportableDocumentRow> page = query.page(tenantId, userId, BulkExportFilters.none(),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void resolveIds_ordersNewestFirstWithTheLimit() {
        when(jdbcTemplate.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
                .thenReturn(List.of());

        query.resolveIds(tenantId, userId, new BulkExportSelection(null, BulkExportFilters.none()), 1000);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture(), eq(UUID.class));
        assertThat(sql.getValue()).startsWith("select d.document_id ")
                .endsWith("order by d.created_at desc, d.document_id desc limit :limit");
        assertThat(params.getValue().getValue("limit")).isEqualTo(1000);
    }

    @Test
    void emptyIdList_answersWithoutQuerying() {
        BulkExportSelection none = new BulkExportSelection(List.of(), null);

        assertThat(query.totals(tenantId, userId, none)).isEqualTo(new ExportableDocumentQuery.Totals(0, 0));
        assertThat(query.resolveIds(tenantId, userId, none, 10)).isEmpty();
        assertThat(query.loadForExport(List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void loadForExport_isNotFilteredByTheAccessRule() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        query.loadForExport(List.of(UUID.randomUUID()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue()).doesNotContain(accessPolicy.documentPredicateSql())
                .endsWith("where d.document_id in (:documentIds)");
    }
}
