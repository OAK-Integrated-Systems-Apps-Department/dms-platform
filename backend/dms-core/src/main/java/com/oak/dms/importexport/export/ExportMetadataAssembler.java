package com.oak.dms.importexport.export;

import com.oak.dms.document.DocumentVersion;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadata.FieldValue;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a bulk export's metadata table: one row per exported document -
 * the fixed document columns, then one column per metadata field across
 * the exported documents' document types (each type at the schema version
 * its document's metadata was saved at), blank where a field doesn't apply
 * to a document. Hidden fields are left out, as they are on screen.
 * <p>
 * Values are read from {@code document_metadata.field_values} and typed by
 * the field's data type, so each writer can render them for its reader
 * (see {@link ExportCell}). Option labels include inactive options: a
 * document may still hold a value whose option was retired later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportMetadataAssembler {

    static final List<String> FIXED_HEADERS = List.of("File in ZIP", "Title", "Document type", "Uploaded by",
            "Uploaded at (UTC)", "Version", "Size (bytes)", "Scan status");
    static final String SCAN_CLEAN = "Clean";
    static final String SCAN_RELEASED = "Released by administrator";

    private final DocumentMetadataRepository documentMetadataRepository;
    private final ImportSchemaFields importSchemaFields;
    private final MetadataFieldOptionRepository metadataFieldOptionRepository;
    private final UserRepository userRepository;

    /** A metadata column: one catalogue field, possibly on several document types. */
    private record Column(String header, ImportField field) {
    }

    private record SchemaKey(UUID documentTypeId, int schemaVersion) {
    }

    public ExportMetadataTable build(UUID tenantId, List<ExportedDocument> documents) {
        Map<UUID, DocumentMetadata> metadataByDocument = loadMetadata(tenantId, documents);

        // Visible fields per (type, version), and the columns in first-seen order.
        Map<SchemaKey, Map<String, ImportField>> visibleBySchema = new HashMap<>();
        Map<String, ImportField> columnFields = new LinkedHashMap<>();
        for (ExportedDocument document : documents) {
            DocumentMetadata metadata = metadataByDocument.get(document.row().documentId());
            if (metadata == null) {
                continue;
            }
            SchemaKey key = new SchemaKey(metadata.getDocumentTypeId(), metadata.getSchemaVersion());
            Map<String, ImportField> visible = visibleBySchema.computeIfAbsent(key,
                    k -> visibleFields(tenantId, k));
            visible.forEach(columnFields::putIfAbsent);
        }
        List<Column> columns = columns(columnFields.values());

        Map<UUID, Map<String, String>> optionLabels = optionLabels(tenantId, columns);
        Map<String, String> userNames = userNames(tenantId, documents, metadataByDocument, columns);

        List<String> headers = new ArrayList<>(FIXED_HEADERS);
        columns.forEach(column -> headers.add(column.header()));

        List<List<ExportCell>> rows = new ArrayList<>(documents.size());
        for (ExportedDocument document : documents) {
            List<ExportCell> row = new ArrayList<>(headers.size());
            row.addAll(fixedCells(document));
            DocumentMetadata metadata = metadataByDocument.get(document.row().documentId());
            Map<String, ImportField> visible = metadata == null ? Map.of()
                    : visibleBySchema.get(new SchemaKey(metadata.getDocumentTypeId(), metadata.getSchemaVersion()));
            for (Column column : columns) {
                if (!visible.containsKey(column.field().fieldKey())) {
                    row.add(ExportCell.BLANK);
                    continue;
                }
                FieldValue value = metadata.getFieldValues().get(column.field().fieldId().toString());
                row.add(cell(column.field(), value == null ? null : value.v(),
                        optionLabels.getOrDefault(column.field().fieldId(), Map.of()), userNames));
            }
            rows.add(row);
        }
        return new ExportMetadataTable(headers, FIXED_HEADERS.size(), rows);
    }

    private Map<UUID, DocumentMetadata> loadMetadata(UUID tenantId, List<ExportedDocument> documents) {
        List<DocumentMetadataId> ids = documents.stream()
                .map(document -> new DocumentMetadataId(tenantId, document.row().documentId()))
                .toList();
        return documentMetadataRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(metadata -> metadata.getId().getDocumentId(), Function.identity()));
    }

    private Map<String, ImportField> visibleFields(UUID tenantId, SchemaKey key) {
        try {
            Map<String, ImportField> visible = new LinkedHashMap<>();
            importSchemaFields.load(tenantId, key.documentTypeId(), key.schemaVersion()).forEach((fieldKey, field) -> {
                if (!field.hidden()) {
                    visible.put(fieldKey, field);
                }
            });
            return visible;
        } catch (IllegalStateException e) {
            log.warn("No schema version {} for document type {}; its documents' metadata is left out of the export",
                    key.schemaVersion(), key.documentTypeId());
            return Map.of();
        }
    }

    /** Headers are the field labels; a label two fields share gets its field key added to tell them apart. */
    private static List<Column> columns(Collection<ImportField> fields) {
        Map<String, Long> labelCounts = fields.stream()
                .collect(Collectors.groupingBy(field -> field.label().toLowerCase(Locale.ROOT), Collectors.counting()));
        return fields.stream()
                .map(field -> new Column(labelCounts.get(field.label().toLowerCase(Locale.ROOT)) > 1
                        || FIXED_HEADERS.stream().anyMatch(fixed -> fixed.equalsIgnoreCase(field.label()))
                        ? field.label() + " (" + field.fieldKey() + ")" : field.label(), field))
                .toList();
    }

    private Map<UUID, Map<String, String>> optionLabels(UUID tenantId, List<Column> columns) {
        Map<UUID, Map<String, String>> labels = new HashMap<>();
        for (Column column : columns) {
            if (isOptionType(column.field().dataType())) {
                Map<String, String> byCode = new HashMap<>();
                for (MetadataFieldOption option : metadataFieldOptionRepository
                        .findAllByFieldOrdered(column.field().fieldId(), tenantId)) {
                    byCode.putIfAbsent(option.getOptionValue(), option.getOptionLabel());
                }
                labels.put(column.field().fieldId(), byCode);
            }
        }
        return labels;
    }

    /** Display names of the users named by user_ref values, by ID string - looked up once for the whole table. */
    private Map<String, String> userNames(UUID tenantId, List<ExportedDocument> documents,
                                          Map<UUID, DocumentMetadata> metadataByDocument, List<Column> columns) {
        Set<UUID> ids = new HashSet<>();
        for (Column column : columns) {
            if (!"user_ref".equals(column.field().dataType())) {
                continue;
            }
            for (ExportedDocument document : documents) {
                DocumentMetadata metadata = metadataByDocument.get(document.row().documentId());
                FieldValue value = metadata == null ? null
                        : metadata.getFieldValues().get(column.field().fieldId().toString());
                if (value != null && value.v() != null) {
                    parseUuid(value.v().toString()).ifPresent(ids::add);
                }
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .filter(user -> tenantId.equals(user.getTenantId()))
                .collect(Collectors.toMap(user -> user.getUserId().toString(), User::getDisplayName, (a, b) -> a));
    }

    private static List<ExportCell> fixedCells(ExportedDocument document) {
        ExportableDocumentRow row = document.row();
        return List.of(
                ExportCell.text(document.zipEntryName()),
                ExportCell.text(row.title()),
                ExportCell.text(row.documentTypeName()),
                ExportCell.text(row.uploadedByName()),
                row.createdAt() == null ? ExportCell.BLANK : ExportCell.DateTime.utc(row.createdAt()),
                row.versionNumber() == null ? ExportCell.BLANK : new ExportCell.Number(BigDecimal.valueOf(row.versionNumber())),
                row.sizeBytes() == null ? ExportCell.BLANK : new ExportCell.Number(BigDecimal.valueOf(row.sizeBytes())),
                ExportCell.text(scanStatusLabel(row.scanStatus())));
    }

    static String scanStatusLabel(String scanStatus) {
        if (DocumentVersion.SCAN_STATUS_CLEAN.equals(scanStatus)) {
            return SCAN_CLEAN;
        }
        if (DocumentVersion.SCAN_STATUS_OVERRIDDEN.equals(scanStatus)) {
            return SCAN_RELEASED;
        }
        return scanStatus;
    }

    /**
     * {@code value} typed by {@code field}'s data type. A value that doesn't
     * fit its type (saved before a type change, say) is kept as text
     * rather than dropped.
     */
    static ExportCell cell(ImportField field, Object value, Map<String, String> optionLabels,
                           Map<String, String> userNames) {
        if (value == null || (value instanceof String s && s.isEmpty())) {
            return ExportCell.BLANK;
        }
        String text = value.toString();
        return switch (field.dataType()) {
            case "number", "decimal" -> value instanceof java.lang.Number n
                    ? new ExportCell.Number(new BigDecimal(n.toString()))
                    : parseDecimal(text).<ExportCell>map(ExportCell.Number::new).orElseGet(() -> ExportCell.text(text));
            case "date" -> parseDate(text).<ExportCell>map(ExportCell.Date::new).orElseGet(() -> ExportCell.text(text));
            case "datetime" -> parseDateTime(text).orElseGet(() -> ExportCell.text(text));
            case "boolean" -> value instanceof Boolean b ? new ExportCell.Bool(b)
                    : "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)
                    ? new ExportCell.Bool(Boolean.parseBoolean(text)) : ExportCell.text(text);
            case "dropdown", "multi_select" -> {
                List<String> codes = codes(value);
                yield codes.isEmpty() ? ExportCell.BLANK : new ExportCell.Options(codes,
                        codes.stream().map(code -> optionLabels.getOrDefault(code, code)).toList());
            }
            case "user_ref" -> new ExportCell.UserRef(text, userNames.get(text));
            default -> ExportCell.text(text);
        };
    }

    private static boolean isOptionType(String dataType) {
        return "dropdown".equals(dataType) || "multi_select".equals(dataType);
    }

    /** A multi_select is saved as a list; a single string is split on {@code ;}, as bulk import reads it. */
    private static List<String> codes(Object value) {
        List<String> codes = value instanceof Collection<?> list
                ? list.stream().filter(Objects::nonNull).map(Object::toString).toList()
                : Arrays.asList(value.toString().split(";"));
        return codes.stream().map(String::trim).filter(code -> !code.isEmpty()).toList();
    }

    private static Optional<BigDecimal> parseDecimal(String text) {
        try {
            return Optional.of(new BigDecimal(text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> parseDate(String text) {
        try {
            return Optional.of(LocalDate.parse(text.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<ExportCell> parseDateTime(String text) {
        String value = text.trim();
        try {
            return Optional.of(new ExportCell.DateTime(LocalDateTime.parse(value), null));
        } catch (DateTimeParseException e) {
            try {
                OffsetDateTime withOffset = OffsetDateTime.parse(value);
                return Optional.of(new ExportCell.DateTime(withOffset.toLocalDateTime(),
                        withOffset.getOffset()));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private static Optional<UUID> parseUuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
