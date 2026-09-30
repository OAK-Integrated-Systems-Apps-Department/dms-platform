package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.matching.ImportFilenameMatcher;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkImportServiceImplTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final DocumentTypeRepository documentTypeRepository = mock(DocumentTypeRepository.class);
    private final DocumentTypeFieldService documentTypeFieldService = mock(DocumentTypeFieldService.class);
    private final ImportTemplateWorkbook realWorkbook = new ImportTemplateWorkbook();

    @BeforeEach
    void setTenant() {
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createBatch_templateForOlderSchemaVersion_rejectsAndSavesNothing() {
        byte[] template = realWorkbook.generate(schema(2), 500);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.active)));

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)))
                .isInstanceOf(InvalidImportTemplateException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_OUTDATED");

        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(importItemRepository);
    }

    @Test
    void createBatch_typeNotInCallersTenant_rejectsAsInvalidTemplate() {
        byte[] template = realWorkbook.generate(schema(3), 500);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)))
                .isInstanceOf(InvalidImportTemplateException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_INVALID");
    }

    @Test
    void createBatch_templateOverSizeLimit_rejectsWithoutParsing() {
        ImportTemplateWorkbook workbook = mock(ImportTemplateWorkbook.class);
        long sixMegabytes = 6L * 1024 * 1024;

        assertThatThrownBy(() -> service(workbook).createBatch(sixMegabytes, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(ImportBatchSizeException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_TOO_LARGE");

        verifyNoInteractions(workbook);
    }

    @Test
    void createBatch_inactiveDocumentType_rejects() {
        byte[] template = realWorkbook.generate(schema(3), 500);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.inactive)));

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)))
                .isInstanceOf(InactiveDocumentTypeException.class);

        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void generateTemplate_inactiveDocumentType_rejectsWithoutLoadingSchema() {
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.inactive)));

        assertThatThrownBy(() -> service(realWorkbook).generateTemplate(documentTypeId))
                .isInstanceOf(InactiveDocumentTypeException.class);

        verifyNoInteractions(documentTypeFieldService);
    }

    private BulkImportServiceImpl service(ImportTemplateWorkbook workbook) {
        return new BulkImportServiceImpl(importBatchRepository, importItemRepository, documentTypeRepository,
                documentTypeFieldService, workbook, new ImportFilenameMatcher(),
                mock(StorageLocationResolver.class), mock(ObjectStorageServiceFactory.class),
                mock(OutboxEventPublisher.class), new ImportExportProperties(), new ObjectMapper());
    }

    private DocumentTypeSchemaResponse schema(int version) {
        return new DocumentTypeSchemaResponse(documentTypeId, "INV", "Invoice", version, List.of(
                new SchemaField(UUID.randomUUID(), "reference", "Reference", null, "text", "active",
                        false, false, 10, null, null, List.of(), List.of())));
    }

    private DocumentType documentType(int currentSchemaVersion, DocumentType.DocumentTypeStatus status) {
        return DocumentType.builder()
                .documentTypeId(documentTypeId)
                .tenantId(tenantId)
                .typeCode("INV")
                .typeName("Invoice")
                .currentSchemaVersion(currentSchemaVersion)
                .status(status)
                .build();
    }
}
