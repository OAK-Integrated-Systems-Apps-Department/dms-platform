package com.oak.dms.metadata;

import com.oak.dms.common.exception.FieldValidationException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.dto.DocumentTypeFieldLabelAliasesResponse;
import com.oak.dms.metadata.dto.ReplaceLabelAliasesRequest;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetadataFieldLabelAliasServiceImpl}: wholesale
 * replace semantics (delete-then-insert, (index+1)*10 priority order), the
 * global vs. type-scoped split, the "field must be ACTIVE-mapped into the
 * type's current schema version" gate on the type-scoped endpoints (and the
 * row lock a type-scoped save takes first), and the publish-time cleanup of
 * unmapped fields' type-scoped hints. No
 * Spring context - repositories are mocked, pattern as
 * DocumentMetadataServiceApplyOcrFieldsTest.
 */
@ExtendWith(MockitoExtension.class)
class MetadataFieldLabelAliasServiceImplTest {

    @Mock
    private MetadataFieldLabelAliasRepository aliasRepository;
    @Mock
    private MetadataFieldRepository metadataFieldRepository;
    @Mock
    private DocumentTypeRepository documentTypeRepository;
    @Mock
    private DocumentTypeFieldRepository documentTypeFieldRepository;

    @InjectMocks
    private MetadataFieldLabelAliasServiceImpl service;

    @Captor
    private ArgumentCaptor<List<MetadataFieldLabelAlias>> rowsCaptor;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();
    private final UUID fieldId = UUID.randomUUID();
    private static final int CURRENT_VERSION = 3;

    @BeforeEach
    void bindTenantContext() {
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private DocumentType documentType() {
        return DocumentType.builder()
                .documentTypeId(documentTypeId).tenantId(tenantId)
                .currentSchemaVersion(CURRENT_VERSION).build();
    }

    /**
     * Stubs the document type lookup the way each endpoint really does it:
     * the PUT row-locks it (serializing concurrent saves and publishes),
     * the GET reads it plainly - it runs in a read-only transaction, where
     * Postgres refuses SELECT ... FOR UPDATE.
     */
    private void givenTypeAndField(boolean forWrite) {
        if (forWrite) {
            when(documentTypeRepository.lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId))
                    .thenReturn(Optional.of(documentType()));
        } else {
            when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                    .thenReturn(Optional.of(documentType()));
        }
        when(metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, tenantId))
                .thenReturn(Optional.of(MetadataField.builder().fieldId(fieldId).tenantId(tenantId).build()));
    }

    private void givenActivelyMappedForWrite(boolean mapped) {
        givenTypeAndField(true);
        givenMapped(mapped);
    }

    private void givenActivelyMappedForRead(boolean mapped) {
        givenTypeAndField(false);
        givenMapped(mapped);
    }

    private void givenMapped(boolean mapped) {
        when(documentTypeFieldRepository.existsActiveMapping(documentTypeId, fieldId, tenantId, CURRENT_VERSION))
                .thenReturn(mapped);
    }

    private MetadataFieldLabelAlias alias(UUID ofField, UUID scope, String text, int order) {
        return MetadataFieldLabelAlias.builder()
                .labelAliasId(UUID.randomUUID()).tenantId(tenantId).fieldId(ofField)
                .documentTypeId(scope).aliasText(text).displayOrder(order).build();
    }

    private void assertNoAliasWrites() {
        verify(aliasRepository, never()).deleteTypeScopedByField(any(), any(), any());
        verify(aliasRepository, never()).deleteGlobalByField(any(), any());
        verify(aliasRepository, never()).saveAllAndFlush(anyList());
    }

    // ------------------------------------------------------------------
    // Type-scoped replace
    // ------------------------------------------------------------------

    @Test
    void replaceTypeScopedDeletesThenInsertsTheNormalizedListInPriorityOrder() {
        givenActivelyMappedForWrite(true);

        DocumentTypeFieldLabelAliasesResponse response = service.replaceTypeScopedAliases(documentTypeId, fieldId,
                new ReplaceLabelAliasesRequest(List.of(" Purchase Order  Number", "PO Number", "po number", "Number")));

        assertThat(response.documentTypeId()).isEqualTo(documentTypeId);
        assertThat(response.fieldId()).isEqualTo(fieldId);
        assertThat(response.labelAliases()).containsExactly("Purchase Order Number", "PO Number", "Number");

        // The document type row lock must be held before the old list is
        // deleted - that's what stops two concurrent saves of different
        // lists from both committing into a merged list - and the old list
        // must be gone from the DB before any new row is inserted, or
        // re-saving an unchanged hint would trip the unique index.
        InOrder order = inOrder(documentTypeRepository, aliasRepository);
        order.verify(documentTypeRepository).lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId);
        order.verify(aliasRepository).deleteTypeScopedByField(tenantId, documentTypeId, fieldId);
        order.verify(aliasRepository).saveAllAndFlush(rowsCaptor.capture());
        verify(documentTypeRepository, never()).findByDocumentTypeIdAndTenantIdAndDeletedFalse(any(), any());

        List<MetadataFieldLabelAlias> rows = rowsCaptor.getValue();
        assertThat(rows).extracting(MetadataFieldLabelAlias::getAliasText)
                .containsExactly("Purchase Order Number", "PO Number", "Number");
        assertThat(rows).extracting(MetadataFieldLabelAlias::getDisplayOrder).containsExactly(10, 20, 30);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getTenantId()).isEqualTo(tenantId);
            assertThat(row.getFieldId()).isEqualTo(fieldId);
            assertThat(row.getDocumentTypeId()).isEqualTo(documentTypeId);
            assertThat(row.getCreatedBy()).isEqualTo(userId);
        });
        verify(aliasRepository, never()).deleteGlobalByField(any(), any());
    }

    @Test
    void replaceTypeScopedWithAnEmptyListClearsWithoutInserting() {
        givenActivelyMappedForWrite(true);

        DocumentTypeFieldLabelAliasesResponse response = service.replaceTypeScopedAliases(
                documentTypeId, fieldId, new ReplaceLabelAliasesRequest(List.of()));

        assertThat(response.labelAliases()).isEmpty();
        verify(aliasRepository).deleteTypeScopedByField(tenantId, documentTypeId, fieldId);
        verify(aliasRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void replaceTypeScopedRejectsAFieldNotMappedIntoTheCurrentSchemaVersion() {
        givenActivelyMappedForWrite(false);

        assertThatThrownBy(() -> service.replaceTypeScopedAliases(documentTypeId, fieldId,
                new ReplaceLabelAliasesRequest(List.of("PO Number"))))
                .isInstanceOf(ResourceNotFoundException.class);

        // Checked against the CURRENT version specifically - a field that was
        // only mapped in an older (archived) version doesn't qualify.
        verify(documentTypeFieldRepository).existsActiveMapping(documentTypeId, fieldId, tenantId, CURRENT_VERSION);
        assertNoAliasWrites();
    }

    @Test
    void replaceTypeScopedRejectsADocumentTypeOutsideTheCallersTenant() {
        when(documentTypeRepository.lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceTypeScopedAliases(documentTypeId, fieldId,
                new ReplaceLabelAliasesRequest(List.of("PO Number"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("DocumentType");

        verifyNoInteractions(documentTypeFieldRepository);
        assertNoAliasWrites();
    }

    @Test
    void replaceTypeScopedRejectsAFieldOutsideTheCallersTenant() {
        when(documentTypeRepository.lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType()));
        when(metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceTypeScopedAliases(documentTypeId, fieldId,
                new ReplaceLabelAliasesRequest(List.of("PO Number"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("MetadataField");

        verifyNoInteractions(documentTypeFieldRepository);
        assertNoAliasWrites();
    }

    @Test
    void replaceTypeScopedRejectsAnInvalidListWithoutTouchingTheStoredOne() {
        givenActivelyMappedForWrite(true);

        assertThatThrownBy(() -> service.replaceTypeScopedAliases(documentTypeId, fieldId,
                new ReplaceLabelAliasesRequest(List.of("PO Number", "x"))))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(ex -> assertThat(((FieldValidationException) ex).getFieldErrors())
                        .singleElement()
                        .satisfies(e -> assertThat(e.field()).isEqualTo("labelAliases")));

        assertNoAliasWrites();
    }

    // ------------------------------------------------------------------
    // Type-scoped read
    // ------------------------------------------------------------------

    @Test
    void getTypeScopedReturnsOnlyThatTypesListInOrder() {
        givenActivelyMappedForRead(true);
        when(aliasRepository.findTypeScopedByField(tenantId, documentTypeId, fieldId)).thenReturn(List.of(
                alias(fieldId, documentTypeId, "Purchase Order Number", 10),
                alias(fieldId, documentTypeId, "Number", 20)));

        DocumentTypeFieldLabelAliasesResponse response = service.getTypeScopedAliases(documentTypeId, fieldId);

        assertThat(response.labelAliases()).containsExactly("Purchase Order Number", "Number");
        verify(aliasRepository, never()).findGlobalByField(any(), any());
        // Read-only transaction: a row lock here would be refused by Postgres.
        verify(documentTypeRepository, never()).lockByDocumentTypeIdAndTenantId(any(), any());
    }

    @Test
    void getTypeScopedRejectsAFieldNotMappedIntoTheCurrentSchemaVersion() {
        givenActivelyMappedForRead(false);

        assertThatThrownBy(() -> service.getTypeScopedAliases(documentTypeId, fieldId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(aliasRepository, never()).findTypeScopedByField(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Publish cleanup of unmapped fields' type-scoped hints
    // ------------------------------------------------------------------

    @Test
    void deleteOfUnmappedFieldsKeepsOnlyTheNewVersionsFieldsTypeScopedHints() {
        UUID keptField = UUID.randomUUID();
        List<UUID> mappedFieldIds = List.of(fieldId, keptField);

        service.deleteTypeScopedAliasesOfUnmappedFields(tenantId, documentTypeId, mappedFieldIds);

        verify(aliasRepository).deleteTypeScopedByDocumentTypeExceptFields(tenantId, documentTypeId, mappedFieldIds);
        verify(aliasRepository, never()).deleteTypeScopedByDocumentType(any(), any());
        // Global hints belong to the field itself, never to one type's mapping.
        verify(aliasRepository, never()).deleteGlobalByField(any(), any());
    }

    @Test
    void deleteOfUnmappedFieldsClearsTheWholeTypeWhenThePublishMapsNoFields() {
        // An empty "not in ()" isn't portable JPQL, so the empty case must
        // take the unconditional per-type delete instead.
        service.deleteTypeScopedAliasesOfUnmappedFields(tenantId, documentTypeId, List.of());

        verify(aliasRepository).deleteTypeScopedByDocumentType(tenantId, documentTypeId);
        verify(aliasRepository, never()).deleteTypeScopedByDocumentTypeExceptFields(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Global hints
    // ------------------------------------------------------------------

    @Test
    void replaceGlobalDeletesOnlyTheGlobalScopeThenInsertsWithNullDocumentType() {
        when(aliasRepository.findGlobalByField(tenantId, fieldId))
                .thenReturn(List.of(alias(fieldId, null, "old hint", 10)));

        List<String> stored = service.replaceGlobalAliases(tenantId, fieldId, List.of("Invoice No", "Inv #"));

        assertThat(stored).containsExactly("Invoice No", "Inv #");
        InOrder order = inOrder(aliasRepository);
        order.verify(aliasRepository).deleteGlobalByField(tenantId, fieldId);
        order.verify(aliasRepository).saveAllAndFlush(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).allSatisfy(row -> assertThat(row.getDocumentTypeId()).isNull());
        assertThat(rowsCaptor.getValue()).extracting(MetadataFieldLabelAlias::getDisplayOrder).containsExactly(10, 20);
        verify(aliasRepository, never()).deleteTypeScopedByField(any(), any(), any());
    }

    @Test
    void replaceGlobalIsANoOpWhenTheNormalizedListIsUnchanged() {
        when(aliasRepository.findGlobalByField(tenantId, fieldId)).thenReturn(List.of(
                alias(fieldId, null, "Invoice No", 10),
                alias(fieldId, null, "Inv #", 20)));

        List<String> stored = service.replaceGlobalAliases(tenantId, fieldId, List.of(" Invoice  No ", "Inv #", "INV #"));

        assertThat(stored).containsExactly("Invoice No", "Inv #");
        assertNoAliasWrites();
    }

    @Test
    void replaceGlobalWithAnEmptyListClearsExistingHints() {
        when(aliasRepository.findGlobalByField(tenantId, fieldId))
                .thenReturn(List.of(alias(fieldId, null, "Invoice No", 10)));

        assertThat(service.replaceGlobalAliases(tenantId, fieldId, List.of())).isEmpty();

        verify(aliasRepository).deleteGlobalByField(tenantId, fieldId);
        verify(aliasRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void replaceGlobalRejectsAnInvalidListBeforeReadingOrWritingAnything() {
        assertThatThrownBy(() -> service.replaceGlobalAliases(tenantId, fieldId, List.of("--")))
                .isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(aliasRepository);
    }

    // ------------------------------------------------------------------
    // Batch loads (list endpoints - no N+1)
    // ------------------------------------------------------------------

    @Test
    void listGlobalByFieldReturnsAnEntryForEveryRequestedFieldInOneQuery() {
        UUID withHints = UUID.randomUUID();
        UUID withoutHints = UUID.randomUUID();
        when(aliasRepository.findGlobalByFields(tenantId, List.of(withHints, withoutHints))).thenReturn(List.of(
                alias(withHints, null, "Vendor", 10),
                alias(withHints, null, "Supplier", 20)));

        Map<UUID, List<String>> byField = service.listGlobalAliasesByField(tenantId, List.of(withHints, withoutHints));

        assertThat(byField).containsOnlyKeys(withHints, withoutHints);
        assertThat(byField.get(withHints)).containsExactly("Vendor", "Supplier");
        assertThat(byField.get(withoutHints)).isEmpty();
    }

    @Test
    void listGlobalByFieldSkipsTheQueryForAnEmptyPage() {
        assertThat(service.listGlobalAliasesByField(tenantId, List.of())).isEmpty();
        verifyNoInteractions(aliasRepository);
    }

    @Test
    void listTypeScopedByFieldGroupsOneDocumentTypesHintsByFieldKeepingOrder() {
        UUID otherField = UUID.randomUUID();
        when(aliasRepository.findTypeScopedByDocumentType(tenantId, documentTypeId)).thenReturn(List.of(
                alias(fieldId, documentTypeId, "Purchase Order Number", 10),
                alias(fieldId, documentTypeId, "Number", 20),
                alias(otherField, documentTypeId, "Supplier", 10)));

        Map<UUID, List<String>> byField = service.listTypeScopedAliasesByField(tenantId, documentTypeId);

        assertThat(byField.get(fieldId)).containsExactly("Purchase Order Number", "Number");
        assertThat(byField.get(otherField)).containsExactly("Supplier");
        verify(documentTypeFieldRepository, never()).existsActiveMapping(any(), any(), any(), anyInt());
    }
}
