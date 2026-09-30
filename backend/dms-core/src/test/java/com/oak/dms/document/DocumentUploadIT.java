package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the pluggable storage architecture end to end against the
 * {@code FILE_SYSTEM} provider (see docs/storage-architecture.md - this
 * repo has no Testcontainers, so a live S3-compatible server isn't part of
 * this suite; the S3 adapter's own wiring is covered at the unit level by
 * {@code S3ObjectStorageAdapterTest}). Each test registers its own
 * tenant-scoped {@link StorageLocation} pointing at a fresh {@code @TempDir}
 * rather than relying on the platform-default location {@code
 * StorageLocationBootstrap} seeds on startup, so this test's storage
 * location can never be affected by (or leak into) any other test or
 * app-startup run sharing this suite's Postgres instance - same reasoning
 * {@code PermissionCodeAuditIT}/{@code AccessDeniedAuditIT} already apply
 * to creating a fresh tenant/user per test rather than reusing shared rows.
 */
@SpringBootTest
class DocumentUploadIT {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StorageLocationRepository storageLocationRepository;
    @Autowired
    private WebApplicationContext context;

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void uploadDownloadAndDelete_roundTripsTheOriginalBytesThroughFileSystemStorage() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());

        byte[] content = "hello pluggable storage".getBytes(StandardCharsets.UTF_8);
        DocumentResponse uploaded = uploadClean(documentTypeId, "Test Doc", "hello.txt", content);

        assertThat(uploaded.title()).isEqualTo("Test Doc");
        assertThat(uploaded.status()).isEqualTo("draft");
        assertThat(uploaded.currentVersionId()).isEqualTo(uploaded.latestVersionId());

        DownloadedDocument downloaded = documentService.downloadDocument(uploaded.documentId());
        byte[] roundTripped;
        try (var in = downloaded.content()) {
            roundTripped = in.readAllBytes();
        }
        assertThat(roundTripped).isEqualTo(content);
        assertThat(downloaded.filename()).isEqualTo("hello.txt");
        assertThat(downloaded.contentType()).isEqualTo("text/plain");
        assertThat(downloaded.sizeBytes()).isEqualTo(content.length);

        documentService.deleteDocument(uploaded.documentId());
        assertThatThrownBy(() -> documentService.getDocument(uploaded.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossTenantAccess_anotherTenantsDocumentLooksLikeNotFound() {
        TenantFixture tenantA = createTenantWithFileSystemStorage();
        TenantContext.set(tenantA.tenantId(), tenantA.userId());
        UUID documentTypeIdA = createDocumentType(tenantA.tenantId());
        byte[] content = "tenant A's private content".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeIdA, "Tenant A Doc", "a.txt", content);
        TenantContext.clear();

        TenantFixture tenantB = createTenantWithFileSystemStorage();
        TenantContext.set(tenantB.tenantId(), tenantB.userId());

        assertThatThrownBy(() -> documentService.getDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> documentService.downloadDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> documentService.deleteDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadWithoutDocmCreatePermission_isForbidden() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        UUID tenantId = createTenantAndPermissionlessUser(keycloakId);

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "data".getBytes());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentTypeId", UUID.randomUUID().toString())
                        .param("title", "Test")
                        .with(jwt().jwt(builder -> builder.subject(keycloakId.toString())
                                .claim("tenant_id", tenantId.toString())).authorities()))
                .andExpect(status().isForbidden());
    }

    private TenantFixture createTenantWithFileSystemStorage() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Document upload test tenant " + UUID.randomUUID())
                .domain("document-upload-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@document-upload-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Uploader")
                .displayName("Test Uploader")
                .status(User.UserStatus.active)
                .build());

        storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("test-filesystem-" + UUID.randomUUID())
                .provider(com.oak.dms.storage.enums.StorageProvider.FILE_SYSTEM)
                .basePath(tempDir.resolve(tenant.getTenantId().toString()).toString())
                .status(StorageLocation.Status.active)
                .build());

        // Staging and quarantine for the scan gate - tenant-scoped in the same
        // @TempDir, separate directories from the document location.
        for (StoragePurpose purpose : new StoragePurpose[]{StoragePurpose.staging, StoragePurpose.quarantine}) {
            storageLocationRepository.save(StorageLocation.builder()
                    .tenantId(tenant.getTenantId())
                    .name("test-" + purpose + "-" + UUID.randomUUID())
                    .provider(com.oak.dms.storage.enums.StorageProvider.FILE_SYSTEM)
                    .purpose(purpose)
                    .basePath(tempDir.resolve(tenant.getTenantId() + "-" + purpose).toString())
                    .status(StorageLocation.Status.active)
                    .build());
        }

        return new TenantFixture(tenant.getTenantId(), user.getUserId());
    }

    private UUID createDocumentType(UUID tenantId) {
        DocumentType documentType = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode("TEST_TYPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .typeName("Test Document Type")
                .build());
        return documentType.getDocumentTypeId();
    }

    /** A real tenant + a real user with zero role/permission grants - no DOCM_* codes. */
    private UUID createTenantAndPermissionlessUser(UUID keycloakId) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Document upload forbidden test tenant " + keycloakId)
                .domain("document-upload-forbidden-test-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@document-upload-forbidden-test.invalid")
                .username(keycloakId.toString())
                .firstName("No")
                .lastName("Permissions")
                .displayName("No Permissions")
                .status(User.UserStatus.active)
                .build());

        return tenant.getTenantId();
    }

    /**
     * Uploads go through the malware scan gate and return an ingestion
     * ticket; with the local ClamAV engine a clean file is promoted before
     * the call returns. Needs clamd reachable (docker compose up clamav).
     */
    private DocumentResponse uploadClean(UUID documentTypeId, String title, String filename, byte[] content) {
        IngestionTicketResponse ticket = documentService.uploadDocument(documentTypeId, title, filename,
                content.length, new ByteArrayInputStream(content));
        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);
        return documentService.getDocument(ticket.documentId());
    }

    private record TenantFixture(UUID tenantId, UUID userId) {
    }
}
