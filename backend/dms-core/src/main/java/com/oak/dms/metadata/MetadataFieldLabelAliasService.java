package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.DocumentTypeFieldLabelAliasesResponse;
import com.oak.dms.metadata.dto.ReplaceLabelAliasesRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OCR label hints ({@code metadata_field_label_aliases}, V40) - the words a
 * field may appear as on a scanned document, which ocr-worker's label
 * matcher searches for. Two scopes: GLOBAL hints (document_type_id null)
 * are part of the field itself and edited with it
 * ({@link MetadataFieldService}); TYPE-SCOPED hints apply to one document
 * type only and are edited per mapped field of that type (the endpoints
 * below). A list is always replaced wholesale, in the given order.
 * <p>
 * The {@code tenantId}-taking methods are building blocks for other
 * metadata services that have already resolved and ownership-checked the
 * field; the {@code documentTypeId}/{@code fieldId} endpoint methods resolve
 * the caller's tenant themselves and 404 on anything outside it.
 */
public interface MetadataFieldLabelAliasService {

    /** A field's global hints, in priority order. */
    List<String> listGlobalAliases(UUID tenantId, UUID fieldId);

    /**
     * Global hints for many fields in one query, keyed by field id - every
     * requested id is present in the result (empty list when it has none).
     */
    Map<UUID, List<String>> listGlobalAliasesByField(UUID tenantId, Collection<UUID> fieldIds);

    /**
     * Normalizes/validates {@code rawAliases} and replaces the field's whole
     * global list with it; returns the stored list. Caller must already have
     * verified the field belongs to {@code tenantId}.
     *
     * @throws com.oak.dms.common.exception.FieldValidationException 400, field "labelAliases"
     */
    List<String> replaceGlobalAliases(UUID tenantId, UUID fieldId, List<String> rawAliases);

    /**
     * Every type-scoped hint list of one document type in one query, keyed by
     * field id; fields with no type-scoped hints are simply absent.
     */
    Map<UUID, List<String>> listTypeScopedAliasesByField(UUID tenantId, UUID documentTypeId);

    /** {@code GET /document-types/{documentTypeId}/fields/{fieldId}/label-aliases}. */
    DocumentTypeFieldLabelAliasesResponse getTypeScopedAliases(UUID documentTypeId, UUID fieldId);

    /**
     * {@code PUT /document-types/{documentTypeId}/fields/{fieldId}/label-aliases}.
     * Does NOT publish a new schema version - hints aren't part of the schema.
     * Takes the document type's row lock, like a publish does.
     */
    DocumentTypeFieldLabelAliasesResponse replaceTypeScopedAliases(UUID documentTypeId, UUID fieldId,
                                                                   ReplaceLabelAliasesRequest request);

    /**
     * Deletes every type-scoped hint of {@code documentTypeId} whose field is
     * not in {@code mappedFieldIds} (empty = the type maps no fields, so all
     * of them go). Called by a publish with the new schema version's field
     * set, so type-scoped hints never outlive their field's mapping into the
     * type; the field's global hints are untouched. Caller must hold the
     * document type's row lock and have tenant-checked the type.
     */
    void deleteTypeScopedAliasesOfUnmappedFields(UUID tenantId, UUID documentTypeId,
                                                 Collection<UUID> mappedFieldIds);
}
