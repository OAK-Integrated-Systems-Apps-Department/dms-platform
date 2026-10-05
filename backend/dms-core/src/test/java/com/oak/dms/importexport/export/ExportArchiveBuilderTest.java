package com.oak.dms.importexport.export;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The download ZIP: the files only, laid out in their folders, with a note for files left out. */
class ExportArchiveBuilderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID FINANCE = UUID.randomUUID();
    private static final UUID INVOICES = UUID.randomUUID();
    private static final UUID ELSEWHERE = UUID.randomUUID();

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final FolderRepository folders = mock(FolderRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final ExportArchiveBuilder builder = new ExportArchiveBuilder(documents, versions, folders, documentService,
            mock(com.oak.dms.encryption.KeyPrefetcher.class));
    private final Map<UUID, Document> stored = new LinkedHashMap<>();

    @TempDir
    Path dir;

    private UUID report;
    private UUID invoice;
    private UUID sameName;
    private UUID unscanned;

    @BeforeEach
    void setUp() {
        report = document(FINANCE, "Annual report", "report.pdf", 3);
        invoice = document(INVOICES, "Invoice 1", "inv-1.pdf", 3);
        sameName = document(INVOICES, "Invoice 1 again", "INV-1.pdf", 3);
        unscanned = document(INVOICES, "Invoice 2", "inv-2.pdf", 5);
        when(documents.findAllByDocumentIdInAndTenantIdAndDeletedFalse(anyCollection(), eq(TENANT)))
                .thenAnswer(call -> stored.values().stream()
                        .filter(d -> ((java.util.Collection<?>) call.getArgument(0)).contains(d.getDocumentId())).toList());
        when(folders.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(TENANT)).thenReturn(List.of(
                Folder.builder().folderId(FINANCE).tenantId(TENANT).folderName("Finance").build(),
                Folder.builder().folderId(INVOICES).tenantId(TENANT).parentFolderId(FINANCE).folderName("Invoices").build(),
                Folder.builder().folderId(ELSEWHERE).tenantId(TENANT).folderName("Elsewhere").build()));
        for (UUID id : List.of(report, invoice, sameName)) {
            when(documentService.downloadDocumentFromJob(id, "bulk_export")).thenAnswer(call ->
                    new DownloadedDocument(new ByteArrayInputStream("PDF".getBytes(StandardCharsets.UTF_8)), 3,
                            "application/pdf", "x.pdf"));
        }
        when(documentService.downloadDocumentFromJob(unscanned, "bulk_export"))
                .thenThrow(new DocumentNotScannedException(unscanned));
    }

    @Test
    void aFolder_keepsItsFolders_andRealFileNames_withNoMetadataSheet() throws Exception {
        Path target = dir.resolve("finance.zip");

        ExportArchiveBuilder.Result result = builder.build(TENANT, List.of(report, invoice, sameName, unscanned),
                FINANCE, target, 1_000_000);

        assertThat(result.documentCount()).isEqualTo(4);
        assertThat(result.filesSkipped()).isEqualTo(1);
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly(
                    "Finance/report.pdf",
                    "Finance/Invoices/inv-1.pdf",
                    "Finance/Invoices/INV-1 (2).pdf", // same name in the same folder, any case: numbered
                    ExportArchiveBuilder.SKIPPED_ENTRY);
            assertThat(new String(zip.getInputStream(zip.getEntry("Finance/report.pdf")).readAllBytes()))
                    .isEqualTo("PDF");
            String note = new String(zip.getInputStream(zip.getEntry(ExportArchiveBuilder.SKIPPED_ENTRY))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertThat(note).contains("Invoice 2 (Finance/Invoices/inv-2.pdf)", unscanned.toString());
        }
    }

    @Test
    void aSelection_isFlat_andHasNoNoteWhenEverythingFits() throws Exception {
        Path target = dir.resolve("selection.zip");

        builder.build(TENANT, List.of(report, invoice), null, target, 1_000_000);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly("report.pdf", "inv-1.pdf");
        }
    }

    @Test
    void filesOverTheSizeLimit_failTheDownload() {
        assertThatThrownBy(() -> builder.build(TENANT, List.of(report, invoice), FINANCE, dir.resolve("big.zip"), 4))
                .isInstanceOf(ExportArchiveBuilder.ExportTooLargeException.class);
    }

    @Test
    void names_areSingleSafeSegments() {
        Set<String> used = new HashSet<>();
        DocumentVersion sneaky = DocumentVersion.builder().originalFilename("../../etc/pass:wd").build();
        assertThat(ExportArchiveBuilder.entryName("", sneaky, used)).isEqualTo("pass_wd");
        assertThat(ExportArchiveBuilder.entryName("", sneaky, used)).isEqualTo("pass_wd (2)");
        assertThat(ExportArchiveBuilder.safeName("..", "Folder")).isEqualTo("Folder");
        // A document outside the downloaded folder still lands under its top folder, never at the root.
        Map<UUID, Folder> byId = Map.of(FINANCE, Folder.builder().folderId(FINANCE).folderName("Fin/ance").build());
        assertThat(ExportArchiveBuilder.directory(ELSEWHERE, FINANCE, byId)).isEqualTo("ance/");
        assertThat(ExportArchiveBuilder.directory(FINANCE, null, byId)).isEmpty();
    }

    private UUID document(UUID folderId, String title, String filename, long size) {
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        stored.put(id, Document.builder().documentId(id).tenantId(TENANT).documentTypeId(UUID.randomUUID())
                .folderId(folderId).title(title).currentVersionId(versionId).latestVersionId(versionId)
                .ownerUserId(UUID.randomUUID()).build());
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, id, TENANT))
                .thenReturn(Optional.of(DocumentVersion.builder().versionId(versionId).documentId(id).tenantId(TENANT)
                        .versionNumber(1).originalFilename(filename).sizeBytes(size).build()));
        return id;
    }
}
