package com.oak.dms.importexport.export;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
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

/** The ZIP's contents: scan-gated files, one metadata row per document, notes for files left out. */
class ExportArchiveBuilderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID AMOUNT_FIELD = UUID.randomUUID();
    private static final UUID TAGS_FIELD = UUID.randomUUID();

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final DocumentMetadataRepository metadata = mock(DocumentMetadataRepository.class);
    private final MetadataFieldRepository fields = mock(MetadataFieldRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentTypeFieldService schemas = mock(DocumentTypeFieldService.class);
    private final ExportArchiveBuilder builder =
            new ExportArchiveBuilder(documents, versions, types, metadata, fields, documentService, schemas,
                    org.mockito.Mockito.mock(com.oak.dms.encryption.KeyPrefetcher.class));
    private final Map<UUID, Document> stored = new LinkedHashMap<>();

    @TempDir
    Path dir;

    private UUID clean;
    private UUID unscanned;

    @BeforeEach
    void setUp() {
        clean = document("Invoice 1", "inv-1.pdf", 3);
        unscanned = document("=HYPERLINK(\"x\")", "inv-1.pdf", 5);
        when(documents.findAllByDocumentIdInAndTenantIdAndDeletedFalse(anyCollection(), eq(TENANT)))
                .thenAnswer(call -> stored.values().stream()
                        .filter(d -> ((java.util.Collection<?>) call.getArgument(0)).contains(d.getDocumentId())).toList());
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
        when(documentService.downloadDocumentFromJob(clean, "bulk_export")).thenAnswer(call ->
                new DownloadedDocument(new ByteArrayInputStream("PDF".getBytes(StandardCharsets.UTF_8)), 3,
                        "application/pdf", "inv-1.pdf"));
        when(documentService.downloadDocumentFromJob(unscanned, "bulk_export")).thenThrow(new DocumentNotScannedException(unscanned));
    }

    @Test
    void zipHoldsCleanFilesNumbered_andAMetadataRowPerDocument() throws Exception {
        Path target = dir.resolve("export.zip");

        ExportArchiveBuilder.Result result = builder.build(TENANT, List.of(clean, unscanned), true, TemplateFormat.csv,
                target, 1_000_000);

        assertThat(result.documentCount()).isEqualTo(2);
        assertThat(result.filesSkipped()).isEqualTo(1);
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly("files/0001_inv-1.pdf", "metadata.csv");
            assertThat(new String(zip.getInputStream(zip.getEntry("files/0001_inv-1.pdf")).readAllBytes())).isEqualTo("PDF");
            String csv = new String(zip.getInputStream(zip.getEntry("metadata.csv")).readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = csv.lines().toList();
            // Field columns follow the form's display order (Tags before Amount).
            assertThat(lines.get(0)).endsWith("Note,Tags,Amount");
            assertThat(lines.get(1)).contains("Invoice 1", "Invoice", "files/0001_inv-1.pdf", "a; b,12.50");
            // Not scanned: listed, file left out with why; a formula-like title is neutralised.
            assertThat(lines.get(2)).contains("'=HYPERLINK", "File not included: Document " + unscanned);
        }
    }

    @Test
    void metadataOnly_xlsx_hasNoFilesAndNoFileColumn() throws Exception {
        Path target = dir.resolve("report.zip");

        builder.build(TENANT, List.of(clean), false, TemplateFormat.xlsx, target, 1_000_000);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly("metadata.xlsx");
        }
    }

    @Test
    void filesOverTheSizeLimit_failTheExport() {
        assertThatThrownBy(() -> builder.build(TENANT, List.of(clean, unscanned), true, TemplateFormat.xlsx,
                dir.resolve("big.zip"), 4))
                .isInstanceOf(ExportArchiveBuilder.ExportTooLargeException.class);
    }

    @Test
    void entryNames_areBaseNamesOnly_andNeverCollide() {
        Set<String> used = new java.util.HashSet<>();
        DocumentVersion sneaky = DocumentVersion.builder().originalFilename("../../etc/pass:wd").build();
        assertThat(ExportArchiveBuilder.entryName(1, sneaky, used)).isEqualTo("files/0001_pass_wd");
        assertThat(ExportArchiveBuilder.entryName(1, sneaky, used)).isEqualTo("files/0001_pass_wd_");
        assertThat(ExportArchiveBuilder.neutralise("+1")).isEqualTo("'+1");
        assertThat(ExportArchiveBuilder.neutralise("plain")).isEqualTo("plain");
    }

    private static DocumentTypeSchemaResponse.SchemaField schemaField(UUID id, String key, int order) {
        return new DocumentTypeSchemaResponse.SchemaField(id, key, key, null, "text", "active", false, false, order,
                null, null, List.of(), List.of());
    }

    private UUID document(String title, String filename, long size) {
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        stored.put(id, Document.builder().documentId(id).tenantId(TENANT).documentTypeId(TYPE).title(title)
                .currentVersionId(versionId).latestVersionId(versionId).ownerUserId(UUID.randomUUID()).build());
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, id, TENANT))
                .thenReturn(Optional.of(DocumentVersion.builder().versionId(versionId).documentId(id).tenantId(TENANT)
                        .versionNumber(1).originalFilename(filename).sizeBytes(size).build()));
        return id;
    }
}
