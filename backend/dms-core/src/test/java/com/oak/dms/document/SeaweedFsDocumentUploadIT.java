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
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real end-to-end proof that {@code S3ObjectStorageAdapter} works against a
 * live SeaweedFS S3 gateway - not MinIO, not a mock. Every DMS-side
 * operation goes through the existing, unmodified adapter/factory/service
 * stack (see docs/storage-architecture.md); this class only adds a second,
 * independent {@link S3Client} pointed at the same SeaweedFS instance to
 * verify objects landed there for real, without trusting our own adapter's
 * word for it.
 * <p>
 * Requires a temporary local SeaweedFS container (S3 gateway on
 * {@code localhost:8333}) to be running - see the setup this test was
 * introduced alongside. Self-skips via {@link Assumptions} if that
 * container isn't reachable, so the rest of this suite is unaffected when
 * it isn't running.
 * <p>
 * Contains zero SeaweedFS-specific application code - the only SeaweedFS-
 * aware code in this whole change is in this test file itself (the
 * independent verification client and the {@code StorageLocation} fixture
 * below), never in {@code S3ObjectStorageAdapter} or anywhere else under
 * {@code src/main}.
 */
@SpringBootTest
class SeaweedFsDocumentUploadIT {

    private static final String SEAWEEDFS_ENDPOINT = "http://localhost:8333";
    private static final String BUCKET = "dms-seaweedfs-test";
    private static final String ACCESS_KEY = "DMSTESTACCESSKEY01";
    private static final String SECRET_KEY = "DmsTestSecretKey0123456789";
    private static final String CREDENTIAL_REF = "seaweedfs-e2e-test";

    private static S3Client verificationClient;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StorageLocationRepository storageLocationRepository;

    @BeforeAll
    static void setUpSeaweedFsAndBucket() {
        verificationClient = S3Client.builder()
                .endpointOverride(URI.create(SEAWEEDFS_ENDPOINT))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();

        try {
            verificationClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyExists) {
            // Fine - a previous run of this test already created it.
        } catch (SdkException unreachable) {
            Assumptions.assumeTrue(false, "SeaweedFS is not reachable at " + SEAWEEDFS_ENDPOINT
                    + " - start the temporary container before running this test: " + unreachable.getMessage());
        }

        // Same env-var-by-credentialRef mechanism S3ObjectStorageAdapter
        // always uses - resolveCredentials() reads live via Environment at
        // call time, so setting these as JVM system properties here (a
        // property source Spring's Environment already checks) is enough;
        // no OS environment variable or DB credential is involved.
        System.setProperty("STORAGE_CRED_" + CREDENTIAL_REF.toUpperCase().replace('-', '_') + "_ACCESS_KEY", ACCESS_KEY);
        System.setProperty("STORAGE_CRED_" + CREDENTIAL_REF.toUpperCase().replace('-', '_') + "_SECRET_KEY", SECRET_KEY);
    }

    @AfterAll
    static void tearDownVerificationClient() {
        if (verificationClient != null) {
            verificationClient.close();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void uploadDownloadMetadataAndDelete_realSeaweedFsRoundTrip() {
        TenantFixture tenant = createTenantWithSeaweedFsStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());

        byte[] content = "hello real SeaweedFS storage".getBytes(StandardCharsets.UTF_8);
        DocumentResponse uploaded = uploadClean(documentTypeId, "SeaweedFS Test Doc", "seaweed-hello.txt", content);

        assertThat(uploaded.title()).isEqualTo("SeaweedFS Test Doc");
        assertThat(uploaded.status()).isEqualTo("draft");
        assertThat(uploaded.currentVersionId()).isEqualTo(uploaded.latestVersionId());

        // Independent verification: fetch the object key our adapter used
        // (not exposed via DocumentResponse - see DocumentVersionResponse's
        // own doc comment) and ask SeaweedFS about it directly, through a
        // completely separate S3Client from the one S3ObjectStorageAdapter
        // built and cached internally.
        String objectKey = documentVersionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(
                        uploaded.currentVersionId(), uploaded.documentId(), tenant.tenantId())
                .orElseThrow().getStorageObjectKey();

        HeadObjectResponse head = verificationClient.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build());
        assertThat(head.contentLength()).isEqualTo(content.length);

        byte[] rawBytes;
        try (var raw = verificationClient.getObject(GetObjectRequest.builder().bucket(BUCKET).key(objectKey).build())) {
            rawBytes = raw.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(rawBytes).isEqualTo(content);

        // Now the normal DMS-side download, through the unmodified adapter.
        DownloadedDocument downloaded = documentService.downloadDocument(uploaded.documentId());
        byte[] roundTripped;
        try (var in = downloaded.content()) {
            roundTripped = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(roundTripped).isEqualTo(content);
        assertThat(downloaded.filename()).isEqualTo("seaweed-hello.txt");
        assertThat(downloaded.contentType()).isEqualTo("text/plain");

        DocumentResponse fetched = documentService.getDocument(uploaded.documentId());
        assertThat(fetched.title()).isEqualTo("SeaweedFS Test Doc");

        // Soft-delete only, per the current implementation - the DMS API
        // stops surfacing the document, but the physical object is
        // deliberately left in SeaweedFS (see DocumentServiceImpl's own
        // doc comment on why: audit-trail/legal-hold concerns, no purge
        // wired up in this pass).
        documentService.deleteDocument(uploaded.documentId());
        assertThatThrownBy(() -> documentService.getDocument(uploaded.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);

        HeadObjectResponse stillThere = verificationClient.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build());
        assertThat(stillThere.contentLength()).isEqualTo(content.length);
    }

    @Test
    void multipleDocuments_eachGetsItsOwnDistinctObjectKeyInSeaweedFs() {
        // The current DocumentService has no "add a new version to an
        // existing document" operation (only the initial upload creates a
        // version - see DocumentService's own interface), so true
        // multi-version-per-document isn't something this pass can
        // exercise. What IS covered: every upload gets its own
        // server-generated object key, and two uploads never collide, even
        // in the same bucket/tenant.
        TenantFixture tenant = createTenantWithSeaweedFsStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());

        byte[] contentOne = "first document's content".getBytes(StandardCharsets.UTF_8);
        byte[] contentTwo = "second document's content - different bytes".getBytes(StandardCharsets.UTF_8);

        DocumentResponse docOne = uploadClean(documentTypeId, "Doc One", "one.txt", contentOne);
        DocumentResponse docTwo = uploadClean(documentTypeId, "Doc Two", "two.txt", contentTwo);

        String keyOne = documentVersionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(
                        docOne.currentVersionId(), docOne.documentId(), tenant.tenantId())
                .orElseThrow().getStorageObjectKey();
        String keyTwo = documentVersionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(
                        docTwo.currentVersionId(), docTwo.documentId(), tenant.tenantId())
                .orElseThrow().getStorageObjectKey();

        assertThat(keyOne).isNotEqualTo(keyTwo);

        try (var rawOne = verificationClient.getObject(GetObjectRequest.builder().bucket(BUCKET).key(keyOne).build());
             var rawTwo = verificationClient.getObject(GetObjectRequest.builder().bucket(BUCKET).key(keyTwo).build())) {
            assertThat(rawOne.readAllBytes()).isEqualTo(contentOne);
            assertThat(rawTwo.readAllBytes()).isEqualTo(contentTwo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void crossTenantAccess_anotherTenantsSeaweedFsDocumentLooksLikeNotFound() {
        TenantFixture tenantA = createTenantWithSeaweedFsStorage();
        TenantContext.set(tenantA.tenantId(), tenantA.userId());
        UUID documentTypeIdA = createDocumentType(tenantA.tenantId());
        byte[] content = "tenant A's private SeaweedFS content".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeIdA, "Tenant A Doc", "a.txt", content);
        TenantContext.clear();

        TenantFixture tenantB = createTenantWithSeaweedFsStorage();
        TenantContext.set(tenantB.tenantId(), tenantB.userId());

        assertThatThrownBy(() -> documentService.getDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> documentService.downloadDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> documentService.deleteDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private TenantFixture createTenantWithSeaweedFsStorage() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("SeaweedFS test tenant " + UUID.randomUUID())
                .domain("seaweedfs-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@seaweedfs-test.invalid")
                .username(keycloakId.toString())
                .firstName("SeaweedFS")
                .lastName("Tester")
                .displayName("SeaweedFS Tester")
                .status(User.UserStatus.active)
                .build());

        // This is the only place a real StorageLocation targeting SeaweedFS
        // is created - the existing S3 provider/adapter, just pointed at a
        // different (real, temporary) endpoint. No credential is stored
        // here - only the credentialRef indirection, exactly like every
        // other S3-compatible StorageLocation.
        storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("seaweedfs-" + UUID.randomUUID())
                .provider(StorageProvider.S3)
                .endpoint(SEAWEEDFS_ENDPOINT)
                .region("us-east-1")
                .bucket(BUCKET)
                .pathStyleAccess(true)
                .credentialRef(CREDENTIAL_REF)
                .status(StorageLocation.Status.active)
                .build());

        // Staging and quarantine for the scan gate - also in SeaweedFS, so the
        // clean-file promotion is an S3 -> S3 copy. Same bucket is fine for
        // a test: every key is prefixed staging/, quarantine/ or documents/.
        for (StoragePurpose purpose : new StoragePurpose[]{StoragePurpose.staging, StoragePurpose.quarantine}) {
            storageLocationRepository.save(StorageLocation.builder()
                    .tenantId(tenant.getTenantId())
                    .name("seaweedfs-" + purpose + "-" + UUID.randomUUID())
                    .provider(StorageProvider.S3)
                    .purpose(purpose)
                    .endpoint(SEAWEEDFS_ENDPOINT)
                    .region("us-east-1")
                    .bucket(BUCKET)
                    .pathStyleAccess(true)
                    .credentialRef(CREDENTIAL_REF)
                    .status(StorageLocation.Status.active)
                    .build());
        }

        return new TenantFixture(tenant.getTenantId(), user.getUserId());
    }

    private UUID createDocumentType(UUID tenantId) {
        DocumentType documentType = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode("SEAWEEDFS_TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .typeName("SeaweedFS Test Document Type")
                .build());
        return documentType.getDocumentTypeId();
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
