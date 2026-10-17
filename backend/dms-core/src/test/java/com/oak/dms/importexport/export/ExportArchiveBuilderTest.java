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
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The export ZIP's contents: folders preserved, scan-gated files, one
 * metadata row per document (with OCR state and custom fields), and a note
 * for any file left out.
 */
class ExportArchiveBuilderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID FINANCE = UUID.randomUUID();
    private static final UUID INVOICES = UUID.randomUUID();
    private static final UUID ELSEWHERE = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID AMOUNT_FIELD = UUID.randomUUID();
    private static final UUID TAGS_FIELD = UUID.randomUUID();

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final FolderRepository folders = mock(FolderRepository.class);
    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final DocumentMetadataRepository metadata = mock(DocumentMetadataRepository.class);
    private final MetadataFieldRepository fields = mock(MetadataFieldRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentTypeFieldService schemas = mock(DocumentTypeFieldService.class);
    private final ExportArchiveBuilder builder = new ExportArchiveBuilder(documents, versions, folders, types,
            metadata, fields, documentService, schemas, mock(com.oak.dms.encryption.KeyPrefetcher.class));
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
        when(types.findAllById(any())).thenReturn(List.of(DocumentType.builder().documentTypeId(TYPE)
                .tenantId(TENANT).typeCode("INV").typeName("Invoice").build()));
        when(fields.findAllByFieldIdInAndTenantIdAndDeletedFalse(anyList(), eq(TENANT))).thenReturn(List.of(
                MetadataField.builder().fieldId(AMOUNT_FIELD).tenantId(TENANT).fieldKey("amount").label("Amount").build(),
                MetadataField.builder().fieldId(TAGS_FIELD).tenantId(TENANT).fieldKey("tags").label("Tags").build()));
        Map<String, DocumentMetadata.FieldValue> values = new HashMap<>();
        values.put(AMOUNT_FIELD.toString(), new DocumentMetadata.FieldValue(new java.math.BigDecimal("12.50"), "import", null));
        values.put(TAGS_FIELD.toString(), new DocumentMetadata.FieldValue(List.of("a", "b"), "import", null));
        when(metadata.findById(any())).thenReturn(Optional.of(DocumentMetadata.builder().fieldValues(values).build()));
        when(schemas.getSchema(TYPE)).thenReturn(new DocumentTypeSchemaResponse(TYPE, "INV", "Invoice", 1, List.of(
                schemaField(TAGS_FIELD, "tags", 10), schemaField(AMOUNT_FIELD, "amount", 20))));
        for (UUID id : List.of(report, invoice, sameName)) {
            when(documentService.downloadDocumentFromJob(id, "bulk_export")).thenAnswer(call ->
                    new DownloadedDocument(new ByteArrayInputStream("PDF".getBytes(StandardCharsets.UTF_8)), 3,
                            "application/pdf", "x.pdf"));
        }
        when(documentService.downloadDocumentFromJob(unscanned, "bulk_export"))
                .thenThrow(new DocumentNotScannedException(unscanned));
    }

    @Test
    void aFolder_keepsItsFolders_andRealFileNames_plusAMetadataRowPerDocument() throws Exception {
        Path target = dir.resolve("finance.zip");

        ExportArchiveBuilder.Result result = builder.build(TENANT, List.of(report, invoice, sameName, unscanned),
                FINANCE, true, TemplateFormat.csv, target, 1_000_000);

        assertThat(result.documentCount()).isEqualTo(4);
        assertThat(result.filesSkipped()).isEqualTo(1);
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly(
                    "Finance/report.pdf",
                    "Finance/Invoices/inv-1.pdf",
                    "Finance/Invoices/INV-1 (2).pdf", // same name in the same folder, any case: numbered
                    "metadata.csv");
            assertThat(new String(zip.getInputStream(zip.getEntry("Finance/report.pdf")).readAllBytes()))
                    .isEqualTo("PDF");
            String csv = new String(zip.getInputStream(zip.getEntry("metadata.csv")).readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = csv.lines().toList();
            // Field columns follow the form's display order (Tags before Amount),
            // after the OCR columns, which are last among the base columns.
            assertThat(lines.get(0)).endsWith("Note,OCR status,OCR review,Tags,Amount");
            assertThat(lines.get(1)).contains("Annual report", "Invoice", "Finance/report.pdf", "a; b,12.50");
            // Not scanned: listed in the manifest, file left out with why.
            assertThat(lines.get(4)).contains("Invoice 2", "File not included: Document " + unscanned);
        }
    }

    @Test
    void aSelection_isFlat_andAlwaysHasAMetadataSheet() throws Exception {
        Path target = dir.resolve("selection.zip");

        builder.build(TENANT, List.of(report, invoice), null, true, TemplateFormat.csv, target, 1_000_000);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName))
                    .containsExactly("report.pdf", "inv-1.pdf", "metadata.csv");
        }
    }

    @Test
    void metadataOnly_xlsx_hasNoFilesAndNoFileColumn() throws Exception {
        Path target = dir.resolve("report.zip");

        builder.build(TENANT, List.of(report), null, false, TemplateFormat.xlsx, target, 1_000_000);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly("metadata.xlsx");
        }
    }

    @Test
    void filesOverTheSizeLimit_failTheExport() {
        assertThatThrownBy(() -> builder.build(TENANT, List.of(report, invoice), FINANCE, true, TemplateFormat.xlsx,
                dir.resolve("big.zip"), 4))
                .isInstanceOf(ExportArchiveBuilder.ExportTooLargeException.class);
    }

    @Test
    void names_areSingleSafeSegments() {
        Set<String> used = new HashSet<>();
        DocumentVersion sneaky = DocumentVersion.builder().originalFilename("../../etc/pass:wd").build();
        assertThat(ExportArchiveBuilder.entryName("", sneaky, used)).isEqualTo("pass_wd");
        assertThat(ExportArchiveBuilder.entryName("", sneaky, used)).isEqualTo("pass_wd (2)");
        assertThat(ExportArchiveBuilder.safeName("..", "Folder")).isEqualTo("Folder");
        // A document outside the exported folder still lands under its top folder, never at the root.
        Map<UUID, Folder> byId = Map.of(FINANCE, Folder.builder().folderId(FINANCE).folderName("Fin/ance").build());
        assertThat(ExportArchiveBuilder.directory(ELSEWHERE, FINANCE, byId)).isEqualTo("ance/");
        assertThat(ExportArchiveBuilder.directory(FINANCE, null, byId)).isEmpty();
    }

    @Test
    void neutralise_guardsAgainstFormulaInjectionInTheManifest() {
        assertThat(ExportArchiveBuilder.neutralise("=HYPERLINK(\"x\")")).isEqualTo("'=HYPERLINK(\"x\")");
        assertThat(ExportArchiveBuilder.neutralise("+1")).isEqualTo("'+1");
        assertThat(ExportArchiveBuilder.neutralise("plain")).isEqualTo("plain");
    }

    /**
     * The OCR columns say what the pipeline did and whether a human has signed
     * the values off - and say nothing at all for a document nobody asked to
     * OCR, so the sheet never shows "not_required" to a tenant with the
     * feature off.
     */
    @Test
    void ocrColumns_reportStatusAndReviewState_andStayEmptyWhenOcrNeverRan() throws Exception {
        UUID reviewed = document("Reviewed", "r.pdf", 1, "completed", false);
        UUID pending = document("Still in review", "p.pdf", 1, "completed", true);
        UUID failed = document("Failed", "f.pdf", 1, "failed", false);

        // Metadata-only: no file is fetched, so this exercises the columns alone.
        Path target = dir.resolve("ocr.zip");
        builder.build(TENANT, List.of(reviewed, pending, failed, report), null, false, TemplateFormat.csv, target,
                1_000_000);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            List<String> lines = new String(zip.getInputStream(zip.getEntry("metadata.csv")).readAllBytes(),
                    StandardCharsets.UTF_8).lines().toList();
            assertThat(lines.get(1)).contains("completed,Reviewed,");
            assertThat(lines.get(2)).contains("completed,Needs review,");
            // Failed: the status is reported, but nothing claims it was
            // reviewed - there are no fields to review.
            assertThat(lines.get(3)).contains("failed,,");
            // Never OCR'd: both cells empty, no "not_required" leaking out.
            assertThat(lines.get(4)).contains(",,,a; b,12.50");
        }
    }

    private static DocumentTypeSchemaResponse.SchemaField schemaField(UUID id, String key, int order) {
        return new DocumentTypeSchemaResponse.SchemaField(id, key, key, null, "text", "active", false, false, false, order,
                null, null, List.of(), List.of());
    }

    private UUID document(String title, String filename, long size) {
        return document(null, title, filename, size, null, false);
    }

    private UUID document(String title, String filename, long size, String ocrStatus, boolean requiresReview) {
        return document(null, title, filename, size, ocrStatus, requiresReview);
    }

    private UUID document(UUID folderId, String title, String filename, long size) {
        return document(folderId, title, filename, size, null, false);
    }

    private UUID document(UUID folderId, String title, String filename, long size, String ocrStatus,
                          boolean requiresReview) {
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        stored.put(id, Document.builder().documentId(id).tenantId(TENANT).documentTypeId(TYPE)
                .folderId(folderId).title(title).currentVersionId(versionId).latestVersionId(versionId)
                .ownerUserId(UUID.randomUUID()).build());
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, id, TENANT))
                .thenReturn(Optional.of(DocumentVersion.builder().versionId(versionId).documentId(id).tenantId(TENANT)
                        .versionNumber(1).originalFilename(filename).sizeBytes(size)
                        .ocrStatus(ocrStatus).requiresReview(requiresReview).build()));
        return id;
    }
}
