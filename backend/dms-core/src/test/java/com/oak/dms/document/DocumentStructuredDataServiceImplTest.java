package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant isolation and passthrough behaviour of the structured-data routes:
 * the only logic dms-core owns here (ocr-worker produces the result).
 */
@ExtendWith(MockitoExtension.class)
class DocumentStructuredDataServiceImplTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private OcrWorkerClient ocrWorkerClient;
    @InjectMocks
    private DocumentStructuredDataServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID latestVersionId = UUID.randomUUID();

    @BeforeEach
    void setTenant() {
        TenantContext.set(tenantId, UUID.randomUUID());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void documentOwnedBy(UUID owner) {
        Document document = Document.builder()
                .documentId(documentId)
                .tenantId(owner)
                .latestVersionId(latestVersionId)
                .build();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    }

    @Test
    void returnsTheStructuredResultOfTheLatestVersion() {
        documentOwnedBy(tenantId);
        Map<String, Object> result = Map.of("status", "completed", "document_type", "invoice",
                "fields", Map.of("invoice_number", "INV-4471"));
        when(ocrWorkerClient.getStructuredResult(latestVersionId)).thenReturn(result);

        assertThat(service.getStructuredData(documentId)).isEqualTo(result);
    }

    @Test
    void noResultYetIsNotFound() {
        documentOwnedBy(tenantId);
        when(ocrWorkerClient.getStructuredResult(latestVersionId)).thenReturn(null);

        assertThatThrownBy(() -> service.getStructuredData(documentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("structured_data");
    }

    @Test
    void anotherTenantsDocumentIsNotFoundAndOcrWorkerIsNeverCalled() {
        documentOwnedBy(UUID.randomUUID());

        assertThatThrownBy(() -> service.getStructuredData(documentId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.requestStructuredExtraction(documentId)).isInstanceOf(ResourceNotFoundException.class);
        verify(ocrWorkerClient, never()).getStructuredResult(any());
        verify(ocrWorkerClient, never()).requestStructuredExtraction(any());
    }

    @Test
    void missingDocumentIsNotFound() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStructuredData(documentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("documents");
    }

    @Test
    void rerunTargetsTheLatestVersion() {
        documentOwnedBy(tenantId);
        Map<String, Object> pending = Map.of("status", "pending");
        when(ocrWorkerClient.requestStructuredExtraction(latestVersionId)).thenReturn(pending);

        assertThat(service.requestStructuredExtraction(documentId)).isEqualTo(pending);
    }
}
