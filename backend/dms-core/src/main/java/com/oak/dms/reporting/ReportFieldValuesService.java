package com.oak.dms.reporting;

import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.dto.FieldValuesResponse;
import com.oak.dms.reporting.dto.FieldValuesResponse.Value;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.TenantContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The values a choice or reference field can take, one page at a time and searchable, so the builder never has to be sent every user
 * of a large organisation. Only fields in the organisation's current catalogue can be asked about - a field an administrator switched
 * off is a 404 like one that never existed - and only choice and reference fields have values to list.
 * <p>
 * The text typed is matched with {@code ILIKE} after its wildcard characters are escaped, and is only ever a bound parameter. What is
 * listed is what the report builder already lets people filter by: names of the organisation's own document types, departments and
 * people, never an address.
 */
@Service
public class ReportFieldValuesService {

    static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final List<Value> STATUS = List.of(new Value("draft", "Draft"), new Value("published", "Published"),
            new Value("archived", "Archived"), new Value("pending-deletion", "Pending deletion"));
    private static final List<Value> APPROVAL = List.of(new Value("not_submitted", "Not submitted"), new Value("in_progress", "In approval"),
            new Value("approved", "Approved"), new Value("rejected", "Rejected"), new Value("cancelled", "Cancelled"));
    private static final List<Value> LIFECYCLE = List.of(new Value("active", "Active"), new Value("inactive", "Inactive"),
            new Value("archived", "Archived"));

    private final DocumentReportDataSource dataSource;
    private final ReportSettingsService settingsService;
    private final NamedParameterJdbcTemplate jdbc;

    public ReportFieldValuesService(DocumentReportDataSource dataSource, ReportSettingsService settingsService,
                                    NamedParameterJdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.settingsService = settingsService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public FieldValuesResponse values(String key, String query, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        settingsService.requireEnabled(tenantId);
        EffectiveCatalogue catalogue = dataSource.catalogue(tenantId);
        FieldDescriptor field = catalogue.find(key).filter(FieldDescriptor::filterable)
                .orElseThrow(() -> ReportException.notFound("REPORT_FIELD_NOT_FOUND", "That field was not found."));
        if (field.type() != FieldType.CHOICE && field.type() != FieldType.REFERENCE) {
            throw ReportException.unprocessable("REPORT_FIELD_HAS_NO_CHOICES", "This field takes a typed value, not one picked from a list.");
        }
        int pageNumber = Math.max(0, page);
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String text = query == null ? "" : query.strip();
        if (text.length() > 100) {
            text = text.substring(0, 100);
        }

        List<Value> rows = switch (field.key()) {
            case "status" -> fixed(STATUS, text, pageNumber, pageSize);
            case "approvalStatus" -> fixed(APPROVAL, text, pageNumber, pageSize);
            case "lifecycleState" -> fixed(LIFECYCLE, text, pageNumber, pageSize);
            case "documentType" -> query(tenantId, text, pageNumber, pageSize, """
                    select document_type_id::text, type_name from document_types
                    where tenant_id = :tenant and not is_deleted and type_name ilike :pattern escape '\\'
                    order by lower(type_name), document_type_id limit :limit offset :offset""");
            case "department" -> query(tenantId, text, pageNumber, pageSize, """
                    select department_id::text, name from departments
                    where tenant_id = :tenant and not is_deleted and name ilike :pattern escape '\\'
                    order by lower(name), department_id limit :limit offset :offset""");
            case "workflowStage" -> query(tenantId, text, pageNumber, pageSize, """
                    select min(name), min(name) from workflow_stages
                    where tenant_id = :tenant and name ilike :pattern escape '\\'
                    group by lower(name) order by lower(name) limit :limit offset :offset""");
            case "owner", "createdBy", "modifiedBy" -> people(tenantId, text, pageNumber, pageSize);
            default -> metadataValues(field, tenantId, text, pageNumber, pageSize);
        };
        boolean more = rows.size() > pageSize;
        return new FieldValuesResponse(more ? rows.subList(0, pageSize) : rows, pageNumber, pageSize, more);
    }

    private List<Value> metadataValues(FieldDescriptor field, UUID tenantId, String text, int page, int size) {
        if (!field.isMetadata()) {
            throw ReportException.unprocessable("REPORT_FIELD_HAS_NO_CHOICES", "This field takes a typed value, not one picked from a list.");
        }
        if (field.type() == FieldType.REFERENCE) {
            return people(tenantId, text, page, size);
        }
        MapSqlParameterSource p = params(tenantId, text, page, size).addValue("field", field.metadataFieldId());
        return jdbc.query("""
                select option_value, option_label from metadata_field_options
                where tenant_id = :tenant and field_id = :field and status = 'active' and option_label ilike :pattern escape '\\'
                order by display_order, lower(option_label), option_id limit :limit offset :offset""", p,
                (rs, i) -> new Value(rs.getString(1), rs.getString(2)));
    }

    /** Names only, never an address. Includes people who have left: their documents are still theirs, and a report may ask for them. */
    private List<Value> people(UUID tenantId, String text, int page, int size) {
        return query(tenantId, text, page, size, """
                select user_id::text, display_name from users
                where tenant_id = :tenant and not is_deleted and display_name ilike :pattern escape '\\'
                order by lower(display_name), user_id limit :limit offset :offset""");
    }

    private List<Value> query(UUID tenantId, String text, int page, int size, String sql) {
        return jdbc.query(sql, params(tenantId, text, page, size), (rs, i) -> new Value(rs.getString(1), rs.getString(2)));
    }

    /** One more than a page is read, to know whether another page exists. */
    private static MapSqlParameterSource params(UUID tenantId, String text, int page, int size) {
        return new MapSqlParameterSource("tenant", tenantId).addValue("pattern", "%" + escape(text) + "%")
                .addValue("limit", size + 1).addValue("offset", (long) page * size);
    }

    private static List<Value> fixed(List<Value> all, String text, int page, int size) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<Value> matching = new ArrayList<>();
        for (Value v : all) {
            if (v.label().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(v);
            }
        }
        int from = Math.min(matching.size(), page * size);
        return matching.subList(from, Math.min(matching.size(), from + size + 1));
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
