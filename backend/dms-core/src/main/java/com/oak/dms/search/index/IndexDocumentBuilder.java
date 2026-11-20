package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.extract.ExtractedText;
import com.oak.dms.search.extract.TextExtractionService;
import com.oak.dms.search.index.SearchSourceRepository.DocRow;
import com.oak.dms.search.index.SearchSourceRepository.FieldDef;
import com.oak.dms.search.index.SearchSourceRepository.VersionRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the index document of one document from the source of truth, read at processing time (never from the job).
 *
 * <p>What is indexed: title, file name, the searchable metadata (as text for ranking and as typed nested slots for
 * filters), the body text, and the facts the filters need (type, owner, department, folder and ancestors, lifecycle,
 * legal hold, dates). What is deliberately NOT indexed: who may see the document. Visibility is checked in
 * PostgreSQL per query, so a permission change never needs a reindex and the index can never be the reason someone
 * sees a document they should not.
 *
 * <p>A document that must not be searchable (deleted, purged, a template, or its tenant is gone) yields
 * {@link Built#delete()} so the indexer removes it.
 */
@Slf4j
@Component
public class IndexDocumentBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int KEYWORD_MAX = 256;

    private final SearchSourceRepository source;
    private final TextExtractionService extraction;

    public IndexDocumentBuilder(SearchSourceRepository source, TextExtractionService extraction) {
        this.source = source;
        this.extraction = extraction;
    }

    /** Index or delete, plus the JSON source for an index action. */
    public record Built(boolean delete, String json) {
        public static Built remove() {
            return new Built(true, null);
        }
    }

    /** Per-batch cache of tenant-wide lookups (field definitions and option labels). */
    public static final class Context {
        private final Map<UUID, Map<UUID, FieldDef>> fields = new ConcurrentHashMap<>();
        private final Map<UUID, Map<UUID, Map<String, String>>> options = new ConcurrentHashMap<>();
        private final Map<UUID, Boolean> tenantActive = new ConcurrentHashMap<>();
    }

    public Built build(UUID tenantId, UUID documentId, long changeCounter, long generation, Context ctx) {
        if (!ctx.tenantActive.computeIfAbsent(tenantId, source::tenantActive)) {
            return Built.remove();
        }
        Optional<DocRow> found = source.document(tenantId, documentId);
        if (found.isEmpty()) {
            return Built.remove();
        }
        DocRow d = found.get();
        if (d.deleted() || d.purged() || d.template()) {
            return Built.remove();
        }

        ObjectNode doc = JSON.createObjectNode();
        doc.put("tenant_id", tenantId.toString());
        doc.put("document_id", documentId.toString());
        doc.put("change_counter", changeCounter);
        doc.put("generation", generation);
        doc.put("title", d.title());
        putUuid(doc, "document_type_id", d.documentTypeId());
        if (d.documentTypeName() != null) {
            doc.put("document_type_name", d.documentTypeName());
        }
        putUuid(doc, "owner_id", d.ownerId());
        putUuid(doc, "owner_department_id", d.departmentId());
        putUuid(doc, "folder_id", d.folderId());
        ArrayNode ancestors = doc.putArray("ancestor_folder_ids");
        source.folderAndAncestors(tenantId, d.folderId()).forEach(f -> ancestors.add(f.toString()));
        doc.put("lifecycle_state", d.lifecycleState());
        doc.put("legal_hold", d.legalHold());
        doc.put("is_deleted", false);
        doc.put("purged", false);
        doc.put("is_template", false);
        if (d.createdAt() != null) {
            doc.put("created_at", d.createdAt().toInstant().toString());
        }
        if (d.updatedAt() != null) {
            doc.put("updated_at", d.updatedAt().toInstant().toString());
        } else if (d.createdAt() != null) {
            doc.put("updated_at", d.createdAt().toInstant().toString());
        }
        doc.put("indexed_at", Instant.now().toString());

        UUID versionId = d.versionToIndex();
        Optional<VersionRow> version = versionId == null ? Optional.empty() : source.version(versionId);
        if (version.isPresent()) {
            VersionRow v = version.get();
            doc.put("file_name", v.fileName());
            doc.put("mime_type", v.mimeType());
            doc.put("size_bytes", v.sizeBytes());
            doc.put("version_number", v.versionNumber());
            ExtractedText text = extraction.extract(v);
            doc.put("body_status", text.status().name().toLowerCase());
            if (text.status() == ExtractedText.Status.READY) {
                doc.put("body", text.text());
            } else if (text.status() == ExtractedText.Status.UNAVAILABLE) {
                doc.put("body_unavailable_reason", text.reason());
            }
        } else {
            doc.put("body_status", "unavailable");
            doc.put("body_unavailable_reason", "no_version");
        }

        addMetadata(doc, tenantId, documentId, ctx);
        try {
            return new Built(false, JSON.writeValueAsString(doc));
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise the index document", e);
        }
    }

    private void addMetadata(ObjectNode doc, UUID tenantId, UUID documentId, Context ctx) {
        Optional<SearchSourceRepository.MetadataRow> row = source.metadata(tenantId, documentId);
        if (row.isEmpty() || row.get().fieldValuesJson() == null) {
            return;
        }
        Map<UUID, FieldDef> defs = ctx.fields.computeIfAbsent(tenantId, source::fieldDefs);
        Map<UUID, Map<String, String>> labels = ctx.options.computeIfAbsent(tenantId, source::optionLabels);
        JsonNode values;
        try {
            values = JSON.readTree(row.get().fieldValuesJson());
        } catch (IOException e) {
            log.warn("Unreadable metadata JSON on document {} - indexed without metadata", documentId);
            return;
        }
        StringBuilder text = new StringBuilder();
        ArrayNode nested = JSON.createArrayNode();
        values.fields().forEachRemaining(entry -> {
            UUID fieldId = parse(entry.getKey());
            FieldDef def = fieldId == null ? null : defs.get(fieldId);
            if (def == null || !def.searchable() || "inactive".equals(def.status())) {
                return;
            }
            JsonNode v = entry.getValue().path("v");
            if (v.isMissingNode() || v.isNull()) {
                return;
            }
            List<JsonNode> elements = new ArrayList<>();
            if (v.isArray()) {
                v.forEach(elements::add);
            } else {
                elements.add(v);
            }
            for (JsonNode e : elements) {
                addValue(nested, text, def, labels.getOrDefault(fieldId, Map.of()), e);
            }
        });
        if (!text.isEmpty()) {
            doc.put("metadata_text", text.toString().strip());
        }
        if (!nested.isEmpty()) {
            doc.set("metadata", nested);
        }
    }

    private static void addValue(ArrayNode nested, StringBuilder text, FieldDef def, Map<String, String> labels, JsonNode v) {
        ObjectNode slot = JSON.createObjectNode();
        slot.put("field_id", def.fieldId().toString());
        slot.put("key", def.key());
        switch (def.dataType()) {
            case "number", "decimal" -> {
                if (!v.isNumber() && !numeric(v.asText())) {
                    return;
                }
                slot.put("v_num", v.isNumber() ? v.asDouble() : Double.parseDouble(v.asText()));
                text.append(v.asText()).append(' ');
            }
            case "date", "datetime" -> slot.put("v_date", v.asText());
            case "boolean" -> slot.put("v_bool", v.asBoolean());
            case "user_ref" -> slot.put("v_kw", clip(v.asText()));
            case "dropdown", "multi_select" -> {
                slot.put("v_kw", clip(v.asText()));
                String label = labels.get(v.asText());
                text.append(v.asText()).append(' ');
                if (label != null && !label.equals(v.asText())) {
                    text.append(label).append(' ');
                }
            }
            default -> { // text, long_text, lookup
                slot.put("v_kw", clip(v.asText()));
                text.append(v.asText()).append(' ');
            }
        }
        nested.add(slot);
    }

    private static boolean numeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String clip(String s) {
        return s.length() > KEYWORD_MAX ? s.substring(0, KEYWORD_MAX) : s;
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void putUuid(ObjectNode n, String field, UUID value) {
        if (value != null) {
            n.put(field, value.toString());
        }
    }
}
