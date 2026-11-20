package com.oak.dms.document;

import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOC-03: the ZIP content limits - entries and unpacked bytes, totalled over a ZIP and every ZIP nested in it -
 * are enforced by the real ingestion gate, and are <em>only</em> ZIP-content limits: an ordinary file of any
 * size is not subject to them, and neither is the size of the uploaded ZIP itself. Original-file size stays
 * unlimited by the application (see {@code IngestionLimitDefaultsIT} for the shipped configuration).
 * <p>
 * The limits are lowered here (4 entries, 20,000 unpacked bytes) so no large payload is needed: a 300 KB text
 * file is fifteen times the unpacked limit and still goes through. Real storage, real ClamAV (the same
 * requirement as {@code DocumentUploadIT}), real gate.
 */
@SpringBootTest(properties = {
        "dms.ingestion.zip-max-entries=4",
        "dms.ingestion.zip-max-uncompressed-bytes=20000"})
class IngestionZipLimitsIT {

    @Autowired private com.oak.dms.document.DocumentService documentService;
    @Autowired private DocumentTypeRepository documentTypeRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StorageLocationRepository storageLocationRepository;

    @TempDir Path tempDir;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID documentType() {
        Tenant tenant = tenantRepository.save(Tenant.builder().tenantName("zip limits IT " + UUID.randomUUID())
                .domain("zip-limits-" + UUID.randomUUID() + ".invalid").subscriptionTier("standard")
                .status(Tenant.TenantStatus.active).build());
        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder().tenantId(tenant.getTenantId()).keycloakId(keycloakId)
                .email(keycloakId + "@zip-limits.invalid").username(keycloakId.toString()).firstName("Zip")
                .lastName("Limits").displayName("Zip Limits").status(User.UserStatus.active).build());
        storageLocationRepository.save(StorageLocation.builder().tenantId(tenant.getTenantId())
                .name("zip-limits-" + UUID.randomUUID()).provider(StorageProvider.FILE_SYSTEM)
                .basePath(tempDir.resolve(tenant.getTenantId().toString()).toString())
                .status(StorageLocation.Status.active).build());
        for (StoragePurpose purpose : new StoragePurpose[]{StoragePurpose.staging, StoragePurpose.quarantine}) {
            storageLocationRepository.save(StorageLocation.builder().tenantId(tenant.getTenantId())
                    .name("zip-limits-" + purpose + "-" + UUID.randomUUID()).provider(StorageProvider.FILE_SYSTEM)
                    .purpose(purpose).basePath(tempDir.resolve(tenant.getTenantId() + "-" + purpose).toString())
                    .status(StorageLocation.Status.active).build());
        }
        TenantContext.set(tenant.getTenantId(), user.getUserId());
        return documentTypeRepository.save(DocumentType.builder().tenantId(tenant.getTenantId())
                .typeCode("Z" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()).typeName("Zip limits")
                .build()).getDocumentTypeId();
    }

    private IngestionTicketResponse upload(UUID typeId, String filename, byte[] content) {
        return documentService.uploadDocument(typeId, filename, filename, content.length,
                new ByteArrayInputStream(content));
    }

    private static byte[] zip(Object... namesAndBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndBytes.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) namesAndBytes[i]));
                zip.write((byte[]) namesAndBytes[i + 1]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] text(int size) {
        return "ordinary document text, repeated. ".repeat(size / 34 + 1).substring(0, size)
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryFile_farOverTheZipContentLimits_isAccepted() {
        UUID typeId = documentType();
        byte[] big = text(300_000); // 15x the 20,000-byte ZIP limit, 75,000x the 4-entry one - not a ZIP, so neither applies

        IngestionTicketResponse ticket = upload(typeId, "notes.txt", big);

        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);
    }

    @Test
    void aSmallZipWithinTheLimits_isAccepted() throws IOException {
        UUID typeId = documentType();
        byte[] ok = zip("a.txt", text(2_000), "b.txt", text(3_000));

        assertThat(upload(typeId, "ok.zip", ok).status()).isEqualTo(ScanResultStatus.clean);
    }

    @Test
    void aZipThatUnpacksPastTheLimit_isRefused_whateverItsOwnSize() throws IOException {
        UUID typeId = documentType();
        byte[] flatBomb = zip("zeros.bin", new byte[200_000]); // a few hundred bytes zipped, 200 KB unpacked

        assertThat(flatBomb.length).isLessThan(2_000);
        assertThatThrownBy(() -> upload(typeId, "bomb.zip", flatBomb))
                .isInstanceOf(FileTypeNotAllowedException.class).hasMessageContaining("Unpacked");
    }

    @Test
    void aBombHiddenInANestedZip_isRefused_asWhenFlat() throws IOException {
        UUID typeId = documentType();
        byte[] nestedBomb = zip("inner.zip", zip("deeper.zip", zip("zeros.bin", new byte[200_000])));

        assertThatThrownBy(() -> upload(typeId, "nested.zip", nestedBomb))
                .isInstanceOf(FileTypeNotAllowedException.class).hasMessageContaining("Unpacked");
    }

    @Test
    void aZipWithMoreEntriesThanTheLimit_countingNestedOnes_isRefused() throws IOException {
        UUID typeId = documentType();
        byte[] flat = zip("1.txt", text(10), "2.txt", text(10), "3.txt", text(10), "4.txt", text(10),
                "5.txt", text(10));
        // 3 files + the inner ZIP + the 2 files inside it = 6 > 4, though no single level has more than 4
        byte[] nested = zip("1.txt", text(10), "2.txt", text(10), "3.txt", text(10),
                "inner.zip", zip("a.txt", text(10), "b.txt", text(10)));

        assertThatThrownBy(() -> upload(typeId, "flat.zip", flat))
                .isInstanceOf(FileTypeNotAllowedException.class).hasMessageContaining("more than 4 files");
        assertThatThrownBy(() -> upload(typeId, "nested.zip", nested))
                .isInstanceOf(FileTypeNotAllowedException.class).hasMessageContaining("more than 4 files");
    }
}
