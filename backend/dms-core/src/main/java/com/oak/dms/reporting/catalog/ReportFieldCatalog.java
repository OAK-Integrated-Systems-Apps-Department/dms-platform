package com.oak.dms.reporting.catalog;

import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.entity.ReportFieldSetting;
import com.oak.dms.reporting.repository.ReportFieldSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the field catalogue of the Documents data source: code-defined built-ins, the tenant's active
 * {@link MetadataField}s as {@code meta.<fieldKey>}, then the administrator's {@code report_field_settings}.
 * <p>
 * {@link #merge} is a pure function so the rules are unit-tested without a database.
 */
@Service
@RequiredArgsConstructor
public class ReportFieldCatalog {

    private static final Set<String> NO_JOINS = Set.of();

    private final MetadataFieldRepository metadataFields;
    private final ReportFieldSettingRepository settings;

    @Transactional(readOnly = true)
    public EffectiveCatalogue forTenant(UUID tenantId) {
        return merge(metadataFields.findAllByTenantIdAndDeletedFalse(tenantId), settings.findAllByTenantId(tenantId));
    }

    /**
     * Every field the code offers the organisation - built-ins and its active custom fields - exactly as the code defines them,
     * before the administrator's overrides. What the admin screen shows each field's default as.
     */
    @Transactional(readOnly = true)
    public List<FieldDescriptor> codeDefaults(UUID tenantId) {
        return defaultsOf(metadataFields.findAllByTenantIdAndDeletedFalse(tenantId));
    }

    private static List<FieldDescriptor> defaultsOf(List<MetadataField> metadata) {
        List<FieldDescriptor> all = new ArrayList<>(builtIns());
        metadata.stream()
                .filter(f -> f.getStatus() == MetadataField.FieldStatus.active && !f.isDeleted())
                .sorted(Comparator.comparing(MetadataField::getLabel, String.CASE_INSENSITIVE_ORDER))
                .map(ReportFieldCatalog::fromMetadata)
                .forEach(all::add);
        return all;
    }

    public static EffectiveCatalogue merge(List<MetadataField> metadata, List<ReportFieldSetting> overrides) {
        Map<String, ReportFieldSetting> byKey = overrides.stream()
                .collect(Collectors.toMap(ReportFieldSetting::getFieldKey, Function.identity(), (a, b) -> a));
        List<FieldDescriptor> all = defaultsOf(metadata);

        List<FieldDescriptor> resolved = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            FieldDescriptor field = all.get(i);
            ReportFieldSetting s = byKey.get(field.key());
            if (s == null) {
                resolved.add(field.withSettings(null, true, true, true, true, field.defaultSelected(),
                        i * 10, false));
            } else if (s.isEnabled()) {
                resolved.add(field.withSettings(s.getLabelOverride(), s.isSelectable(), s.isFilterable(), s.isSortable(),
                        s.isGroupable(), s.isDefaultSelected(), s.getDisplayOrder() != null ? s.getDisplayOrder() : i * 10,
                        s.isShowCode()));
            }
        }
        resolved.sort(Comparator.comparing(FieldDescriptor::displayOrder));
        return new EffectiveCatalogue(resolved);
    }

    /** Everything the brief lists, each confirmed against Document, DocumentVersion and the retention columns. */
    static List<FieldDescriptor> builtIns() {
        return List.of(
                builtIn("title", "Title", FieldType.TEXT, ValueKind.TEXT, "d.title", "d.title", NO_JOINS, true, false),
                builtIn("documentType", "Document type", FieldType.CHOICE, ValueKind.UUID,
                        "t.type_name", "d.document_type_id", Set.of("type"), false, true),
                builtIn("department", "Department", FieldType.REFERENCE, ValueKind.UUID,
                        "dep.name", "d.owner_department_id", Set.of("dept"), false, true),
                builtIn("owner", "Owner", FieldType.REFERENCE, ValueKind.UUID,
                        "own.display_name", "d.owner_user_id", Set.of("owner"), false, true),
                builtIn("status", "Document status", FieldType.CHOICE, ValueKind.TEXT, "d.status", "d.status",
                        NO_JOINS, false, true),
                builtIn("approvalStatus", "Approval status", FieldType.CHOICE, ValueKind.TEXT, APPROVAL_LABEL,
                        "coalesce(wf.status, 'not_submitted')", Set.of("wf"), false, true),
                new FieldDescriptor("workflowStage", "Workflow stage", FieldType.CHOICE, ValueKind.TEXT, false,
                        "case when wf.status = 'in_progress' then stg.name end", null, Set.of("wf", "stage"),
                        true, true, true, true, false, null, false, null, null, Special.STAGE),
                builtIn("createdAt", "Created at", FieldType.DATE, ValueKind.TIMESTAMP, "d.created_at", "d.created_at",
                        NO_JOINS, false, false),
                builtIn("createdBy", "Created by", FieldType.REFERENCE, ValueKind.UUID,
                        "cre.display_name", "d.created_by", Set.of("creator"), false, true),
                builtIn("modifiedAt", "Modified at", FieldType.DATE, ValueKind.TIMESTAMP, "d.updated_at", "d.updated_at",
                        NO_JOINS, false, false),
                builtIn("modifiedBy", "Modified by", FieldType.REFERENCE, ValueKind.UUID,
                        "upd.display_name", "d.updated_by", Set.of("modifier"), false, true),
                builtIn("lifecycleState", "Lifecycle state", FieldType.CHOICE, ValueKind.TEXT, "d.lifecycle_state",
                        "d.lifecycle_state", NO_JOINS, false, true),
                // Column only: folders have no stored path, so it is built from the folder tree when rows are written
                // (an in-memory walk of the tenant's folders). Filtering or sorting by it would need a recursive query
                // per row, which is not cheap - so it is shown, not filtered.
                new FieldDescriptor("folderPath", "Folder path", FieldType.TEXT, ValueKind.TEXT, false, "d.folder_id",
                        null, NO_JOINS, true, false, false, false, false, null, false, null, null, Special.FOLDER_PATH),
                builtIn("versionNumber", "Version", FieldType.NUMBER, ValueKind.NUMBER, "v.version_number",
                        "v.version_number", Set.of("version"), false, false),
                builtIn("pageCount", "Page count", FieldType.NUMBER, ValueKind.NUMBER, "v.page_count", "v.page_count",
                        Set.of("version"), false, false),
                builtIn("fileSize", "File size (bytes)", FieldType.NUMBER, ValueKind.NUMBER, "v.size_bytes",
                        "v.size_bytes", Set.of("version"), false, false),
                builtIn("fileType", "File type", FieldType.TEXT, ValueKind.TEXT, FILE_EXTENSION, FILE_EXTENSION,
                        Set.of("version"), false, true),
                builtIn("legalHold", "Legal hold", FieldType.BOOLEAN, ValueKind.BOOLEAN, "d.legal_hold", "d.legal_hold",
                        NO_JOINS, false, true),
                // review_due_at is the end of total retention (V130/V132); archive_due_at is the earlier archive step.
                builtIn("retentionDue", "Retention due", FieldType.DATE, ValueKind.TIMESTAMP, "d.review_due_at",
                        "d.review_due_at", NO_JOINS, false, false));
    }

    /** Derived from the document's latest workflow instance, not {@code Document.status} (brief 2.1). */
    static final String APPROVAL_LABEL = "case wf.status when 'in_progress' then 'In approval' when 'approved' then 'Approved'"
            + " when 'rejected' then 'Rejected' when 'cancelled' then 'Cancelled' else 'Not submitted' end";

    /** Same extension rule as the dashboard's format breakdown. */
    static final String FILE_EXTENSION = "lower(nullif(case when position('.' in v.original_filename) > 0"
            + " then reverse(split_part(reverse(v.original_filename), '.', 1)) end, ''))";

    private static FieldDescriptor builtIn(String key, String label, FieldType type, ValueKind kind, String select,
                                           String filter, Set<String> joins, boolean defaultSelected, boolean groupable) {
        boolean sortable = true;
        return new FieldDescriptor(key, label, type, kind, false, select, filter, joins, true, true, sortable, groupable,
                defaultSelected, null, false, null, null, Special.NONE);
    }

    static FieldDescriptor fromMetadata(MetadataField f) {
        String dt = f.getDataType();
        FieldType type = switch (dt) {
            case "number", "decimal" -> FieldType.NUMBER;
            case "date", "datetime" -> FieldType.DATE;
            case "boolean" -> FieldType.BOOLEAN;
            case "dropdown", "multi_select" -> FieldType.CHOICE;
            case "user_ref" -> FieldType.REFERENCE;
            default -> FieldType.TEXT; // text, long_text, lookup
        };
        ValueKind kind = switch (type) {
            case NUMBER -> ValueKind.NUMBER;
            case DATE -> ValueKind.DATE;
            case BOOLEAN -> ValueKind.BOOLEAN;
            default -> ValueKind.TEXT;
        };
        boolean multi = "multi_select".equals(dt);
        boolean sortable = !multi && !"long_text".equals(dt) && type != FieldType.REFERENCE;
        boolean groupable = !multi && (type == FieldType.CHOICE || type == FieldType.BOOLEAN);
        // A field that is not searchable is column-only (brief 2.1).
        return new FieldDescriptor(FieldDescriptor.META_PREFIX + f.getFieldKey(), f.getLabel(), type, kind, multi, null,
                null, Set.of("meta"), true, f.isSearchable(), sortable, groupable, false, null, false, f.getFieldId(), dt,
                Special.NONE);
    }
}
