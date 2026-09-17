package com.oak.dms.metadata;

import com.oak.dms.common.exception.DuplicateDisplayOrderException;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the display-order integrity requirements added alongside V26:
 * duplicate rejection on the list-shaped mapping-publish endpoint, and the
 * DEFERRABLE partial unique constraint permitting a genuine mid-transaction
 * reorder swap.
 */
class DocumentTypeFieldOrderingIT extends AbstractMetadataIntegrationTest {

    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private MetadataFieldService metadataFieldService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private DocumentTypeFieldRepository documentTypeFieldRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void duplicateDisplayOrderInMappingRequestIsRejectedWithBadRequest() {
        UUID documentTypeId = createDocumentType("CONTRACT_ORD").documentTypeId();
        UUID fieldA = createDropdownField("field_a").fieldId();
        UUID fieldB = createDropdownField("field_b").fieldId();

        ReplaceDocumentTypeFieldsRequest request = new ReplaceDocumentTypeFieldsRequest(List.of(
                new DocumentTypeFieldMappingItem(fieldA, false, false, 10, null, null),
                new DocumentTypeFieldMappingItem(fieldB, false, false, 10, null, null)));

        assertThatThrownBy(() -> documentTypeFieldService.replaceMapping(documentTypeId, request))
                .isInstanceOf(DuplicateDisplayOrderException.class)
                .satisfies(ex -> {
                    DuplicateDisplayOrderException dup = (DuplicateDisplayOrderException) ex;
                    assertThat(dup.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(dup.getCode()).isEqualTo("DUPLICATE_DISPLAY_ORDER");
                });
    }

    @Test
    void publishingAMappingWithDistinctOrdersRestampsAsCanonicalTenSequence() {
        UUID documentTypeId = createDocumentType("INVOICE_ORD").documentTypeId();
        UUID fieldA = createDropdownField("field_x").fieldId();
        UUID fieldB = createDropdownField("field_y").fieldId();

        // Client submits out-of-order, non-canonical values - the service
        // must sort by them and re-stamp as a gap-free (index+1)*10 sequence
        // rather than storing them verbatim.
        ReplaceDocumentTypeFieldsRequest request = new ReplaceDocumentTypeFieldsRequest(List.of(
                new DocumentTypeFieldMappingItem(fieldA, false, false, 500, null, null),
                new DocumentTypeFieldMappingItem(fieldB, false, false, 3, null, null)));

        List<DocumentTypeFieldResponse> result = documentTypeFieldService.replaceMapping(documentTypeId, request);

        assertThat(result).hasSize(2);
        DocumentTypeFieldResponse first = result.stream().filter(r -> r.fieldId().equals(fieldB)).findFirst().orElseThrow();
        DocumentTypeFieldResponse second = result.stream().filter(r -> r.fieldId().equals(fieldA)).findFirst().orElseThrow();
        assertThat(first.displayOrder()).isEqualTo(10);
        assertThat(second.displayOrder()).isEqualTo(20);
    }

    @Test
    void deferredConstraintPermitsFullReorderWithinOneTransaction() {
        UUID documentTypeId = createDocumentType("POLICY_ORD").documentTypeId();
        UUID fieldA = createDropdownField("field_p").fieldId();
        UUID fieldB = createDropdownField("field_q").fieldId();

        documentTypeFieldService.replaceMapping(documentTypeId, new ReplaceDocumentTypeFieldsRequest(List.of(
                new DocumentTypeFieldMappingItem(fieldA, false, false, 10, null, null),
                new DocumentTypeFieldMappingItem(fieldB, false, false, 20, null, null))));

        List<DocumentTypeField> current = documentTypeFieldRepository
                .findByDocumentTypeAndVersion(documentTypeId, tenantId, 2);
        DocumentTypeField rowA = current.stream().filter(r -> r.getFieldId().equals(fieldA)).findFirst().orElseThrow();
        DocumentTypeField rowB = current.stream().filter(r -> r.getFieldId().equals(fieldB)).findFirst().orElseThrow();
        assertThat(rowA.getDisplayOrder()).isEqualTo(10);
        assertThat(rowB.getDisplayOrder()).isEqualTo(20);

        // Swap the two positions directly against the repository, in one
        // real (committed) transaction, forcing a genuine transient
        // duplicate mid-transaction: after the first flush below, both rows
        // briefly sit at display_order=20. A non-deferred constraint would
        // reject that flush outright; the DEFERRABLE constraint added in
        // V26 must let it through and only check at commit, by which point
        // the swap is complete and no duplicate remains.
        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
            rowA.setDisplayOrder(20);
            documentTypeFieldRepository.saveAndFlush(rowA);
            rowB.setDisplayOrder(10);
            documentTypeFieldRepository.saveAndFlush(rowB);
        })).doesNotThrowAnyException();

        List<DocumentTypeField> afterSwap = documentTypeFieldRepository
                .findByDocumentTypeAndVersion(documentTypeId, tenantId, 2);
        DocumentTypeField reloadedA = afterSwap.stream().filter(r -> r.getFieldId().equals(fieldA)).findFirst().orElseThrow();
        DocumentTypeField reloadedB = afterSwap.stream().filter(r -> r.getFieldId().equals(fieldB)).findFirst().orElseThrow();
        assertThat(reloadedA.getDisplayOrder()).isEqualTo(20);
        assertThat(reloadedB.getDisplayOrder()).isEqualTo(10);
    }

    private DocumentTypeResponse createDocumentType(String typeCode) {
        return documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest(typeCode, typeCode, null, 0, false, false));
    }

    private MetadataFieldResponse createDropdownField(String fieldKey) {
        return metadataFieldService.createField(
                new CreateMetadataFieldRequest(fieldKey, fieldKey, null, "dropdown", null, false, true, false));
    }
}
