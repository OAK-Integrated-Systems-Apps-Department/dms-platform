package com.oak.dms.search.query;

import com.oak.dms.security.auth.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pick-lists the search filter panel needs (document types, departments, owners, searchable metadata fields), so the
 * panel works for anyone allowed to search without also needing the administration permissions those lists usually sit
 * behind. Names only, from the caller's own tenant. Deliberately independent of documents: nothing here depends on which
 * documents exist or who may see them (a person is offered as an owner whether or not they own anything visible).
 */
@Service
public class SearchFilterOptions {

    public record Option(UUID id, String name) {
    }

    public record ValueOption(String value, String label) {
    }

    public record MetadataFieldOption(UUID fieldId, String key, String label, String dataType, List<ValueOption> options) {
    }

    public record Options(List<Option> documentTypes, List<Option> departments, List<Option> owners,
                          List<MetadataFieldOption> metadataFields) {
    }

    private final JdbcTemplate jdbc;

    public SearchFilterOptions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Options load() {
        UUID tenantId = TenantContext.getTenantId();
        List<Option> types = jdbc.query("select document_type_id, type_name from document_types where tenant_id = ? and not is_deleted order by lower(type_name)",
                (rs, n) -> new Option(rs.getObject(1, UUID.class), rs.getString(2)), tenantId);
        List<Option> departments = jdbc.query("select department_id, name from departments where tenant_id = ? and not is_deleted order by lower(name)",
                (rs, n) -> new Option(rs.getObject(1, UUID.class), rs.getString(2)), tenantId);
        List<Option> owners = jdbc.query("select user_id, display_name from users where tenant_id = ? and not is_deleted order by lower(display_name)",
                (rs, n) -> new Option(rs.getObject(1, UUID.class), rs.getString(2)), tenantId);

        Map<UUID, List<ValueOption>> options = new HashMap<>();
        jdbc.query("select field_id, option_value, option_label from metadata_field_options where tenant_id = ? and not is_deleted and status = 'active' order by display_order, option_label",
                rs -> {
                    options.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new ValueOption(rs.getString(2), rs.getString(3)));
                }, tenantId);
        List<MetadataFieldOption> fields = jdbc.query("""
                select field_id, field_key, label, data_type from metadata_fields
                 where tenant_id = ? and not is_deleted and is_searchable and status <> 'inactive'
                 order by lower(label)
                """, (rs, n) -> {
            UUID id = rs.getObject(1, UUID.class);
            return new MetadataFieldOption(id, rs.getString(2), rs.getString(3), rs.getString(4), options.getOrDefault(id, List.of()));
        }, tenantId);
        return new Options(types, departments, owners, fields);
    }
}
