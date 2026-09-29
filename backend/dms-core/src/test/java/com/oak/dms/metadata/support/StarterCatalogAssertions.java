package com.oak.dms.metadata.support;

import com.oak.dms.metadata.seed.StarterCatalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-JDBC checks that a tenant holds exactly the {@link StarterCatalog},
 * shared by the V37 migration tests and the installer tests so both prove
 * the same thing.
 */
public final class StarterCatalogAssertions {

    private StarterCatalogAssertions() {
    }

    /** Asserts the tenant's catalogue is exactly the starter catalogue, installed into an empty tenant. */
    public static void assertExactStarterCatalog(Connection c, UUID tenantId, UUID expectedActor) throws SQLException {
        String actor = expectedActor == null ? null : expectedActor.toString();

        assertThat(strings(c, """
                select field_key || '|' || label || '|' || help_text || '|' || data_type || '|' || is_system_field
                       || '|' || is_searchable || '|' || status || '|' || coalesce(created_by::text, 'null')
                from metadata_fields where tenant_id = ? and is_deleted = false order by field_key collate \"C\"""", tenantId))
                .containsExactlyElementsOf(StarterCatalog.FIELDS.stream()
                        .map(f -> f.key() + "|" + f.label() + "|" + f.helpText() + "|" + f.dataType() + "|"
                                + f.systemField() + "|true|active|" + actor)
                        .sorted()
                        .toList());

        assertThat(strings(c, """
                select f.field_key || '|' || o.option_value || '|' || o.option_label || '|' || o.display_order
                       || '|' || o.status || '|' || coalesce(o.created_by::text, 'null')
                from metadata_field_options o join metadata_fields f on f.field_id = o.field_id
                where o.tenant_id = ? and o.is_deleted = false order by f.field_key collate \"C\", o.display_order""", tenantId))
                .containsExactlyElementsOf(StarterCatalog.OPTIONS.stream()
                        .sorted(Comparator.comparing(StarterCatalog.OptionDef::fieldKey)
                                .thenComparingInt(StarterCatalog.OptionDef::displayOrder))
                        .map(o -> o.fieldKey() + "|" + o.value() + "|" + o.label() + "|" + o.displayOrder()
                                + "|active|" + actor)
                        .toList());

        assertThat(strings(c, """
                select type_code || '|' || type_name || '|' || description || '|' || display_order || '|'
                       || current_schema_version || '|' || requires_watermark || '|' || coediting_enabled || '|'
                       || status || '|' || coalesce(created_by::text, 'null')
                from document_types where tenant_id = ? and is_deleted = false order by display_order""", tenantId))
                .containsExactlyElementsOf(IntStream.range(0, StarterCatalog.TYPES.size())
                        .mapToObj(i -> {
                            StarterCatalog.TypeDef t = StarterCatalog.TYPES.get(i);
                            return t.code() + "|" + t.name() + "|" + t.description() + "|" + (i + 1) * 10 + "|1|"
                                    + t.requiresWatermark() + "|" + t.coeditingEnabled() + "|active|" + actor;
                        })
                        .toList());

        assertThat(strings(c, """
                select dt.type_code || '|' || s.schema_version || '|' || s.status || '|'
                       || coalesce(s.published_by::text, 'null')
                from document_type_schemas s join document_types dt on dt.document_type_id = s.document_type_id
                where s.tenant_id = ? order by dt.display_order""", tenantId))
                .containsExactlyElementsOf(StarterCatalog.TYPES.stream()
                        .map(t -> t.code() + "|1|active|" + actor)
                        .toList());

        assertThat(schemaElements(c, tenantId, null))
                .containsExactlyElementsOf(expectedElements(StarterCatalog.TYPES.stream()
                        .map(StarterCatalog.TypeDef::code).toList(), List.of()));

        assertThat(strings(c, """
                select (count(*) = count(distinct e ->> 'mappingId') and bool_and(e ->> 'mappingId' is not null))::text
                from document_type_schemas s, jsonb_array_elements(s.fields) e where s.tenant_id = ?""", tenantId))
                .containsExactly("true");
    }

    /**
     * Every schema element of the tenant's schemas, in stored order, as
     * {@code typeCode|fieldKey|mandatory|hidden|requiresBackfill|displayOrder|fieldGroup|defaultValueOverride};
     * optionally only for the given type codes.
     */
    public static List<String> schemaElements(Connection c, UUID tenantId, List<String> typeCodes) throws SQLException {
        List<String> all = strings(c, """
                select dt.type_code || '|' || mf.field_key || '|' || (e ->> 'mandatory') || '|' || (e ->> 'hidden')
                       || '|' || (e ->> 'requiresBackfill') || '|' || (e ->> 'displayOrder') || '|'
                       || (e ->> 'fieldGroup') || '|' || coalesce(e ->> 'defaultValueOverride', 'null')
                from document_type_schemas s
                join document_types dt on dt.document_type_id = s.document_type_id
                cross join lateral jsonb_array_elements(s.fields) with ordinality as x(e, pos)
                join metadata_fields mf on mf.field_id = (e ->> 'fieldId')::uuid and mf.tenant_id = s.tenant_id
                where s.tenant_id = ?
                order by dt.display_order, x.pos""", tenantId);
        return typeCodes == null ? all
                : all.stream().filter(line -> typeCodes.contains(line.substring(0, line.indexOf('|')))).toList();
    }

    /** The starter mappings for the given types, minus the given field keys, in the schemaElements format. */
    public static List<String> expectedElements(List<String> typeCodes, List<String> skippedFieldKeys) {
        List<String> out = new ArrayList<>();
        for (String typeCode : typeCodes) {
            StarterCatalog.MAPPINGS.stream()
                    .filter(m -> m.typeCode().equals(typeCode) && !skippedFieldKeys.contains(m.fieldKey()))
                    .forEach(m -> out.add(m.typeCode() + "|" + m.fieldKey() + "|" + m.mandatory() + "|false|false|"
                            + m.displayOrder() + "|" + m.fieldGroup() + "|null"));
        }
        return out;
    }

    public static long count(Connection c, String sql, Object... params) throws SQLException {
        return Long.parseLong(strings(c, sql, params).get(0));
    }

    public static List<String> strings(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    /** Every catalogue row the tenant owns, as JSON text, for before/after comparisons. */
    public static List<String> catalogSnapshot(Connection c, UUID tenantId) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String[] table : new String[][]{
                {"metadata_fields", "field_id"},
                {"metadata_field_options", "option_id"},
                {"metadata_field_validations", "validation_id"},
                {"document_types", "document_type_id"},
                {"document_type_schemas", "schema_id"}}) {
            out.addAll(strings(c, "select '" + table[0] + " ' || row_to_json(t)::text from " + table[0]
                    + " t where tenant_id = ? order by " + table[1], tenantId));
        }
        return out;
    }
}
