package com.oak.dms.importexport.export;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one export's ZIP to a local file:
 * <pre>
 *   Finance/Invoices/inv-1.pdf     a folder's export: the folder and its subfolders as they are
 *   inv-1.pdf, inv-1 (2).pdf       a selection of documents: side by side, same names numbered
 *   metadata.xlsx | metadata.csv   one row per document, always present
 * </pre>
 * Files are read through {@link DocumentService#downloadDocumentFromJob} -
 * the same tenant, scan-gate and servability checks and DOCUMENT_DOWNLOADED
 * audit as a single download. Access is applied when the export is
 * requested and again when it runs (its selection leaves out what was hidden
 * from the requester at either point - see ExportJobWorker), so an export
 * never contains a file the requester couldn't download one by one; and its
 * download is refused once they lose access to any of its documents. A
 * document whose file can't be served (not scanned clean, for one) is still
 * listed in the manifest, with a note, and its file left out. Needs
 * {@code TenantContext} bound to the requester.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportArchiveBuilder {

    static final String METADATA_ENTRY = "metadata";
    private static final int CHUNK = 200;
    private static final int MAX_NAME = 150;

    /**
     * The two OCR columns, appended AFTER every pre-existing base column so
     * no column anyone already reads changes position.
     *
     * <p>There is deliberately no "OCR values" column. A version's extracted
     * values are written into {@code document_metadata_values} by
     * {@code DocumentMetadataService#applyOcrFields}, and a reviewer's
     * corrections are written to the same place by
     * {@code DocumentServiceImpl#saveReview} - so the per-field metadata
     * columns this sheet already emits <em>are</em> the reviewed values. A
     * second copy of them would be a copy that can disagree with the first.
     * {@link #OCR_REVIEW} is what tells you whether to trust them yet.
     */
    static final String OCR_STATUS = "OCR status";
    static final String OCR_REVIEW = "OCR review";

    private static final List<String> BASE_COLUMNS = List.of("Document ID", "Title", "Document type",
            "File in ZIP", "Original file name", "Size (bytes)", "Version", "Created at", "Note",
            OCR_STATUS, OCR_REVIEW);

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final FolderRepository folderRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final DocumentService documentService;
    private final DocumentTypeFieldService documentTypeFieldService;
    /** Unwraps a chunk's file keys in bulk first, instead of one key-service request per file. */
    private final com.oak.dms.encryption.KeyPrefetcher keyPrefetcher;

    /** @param filesSkipped documents listed whose file couldn't be included */
    public record Result(int documentCount, long filesBytes, int filesSkipped) {
    }

    /** Thrown when the files would take the export over its size limit. */
    public static class ExportTooLargeException extends RuntimeException {
        ExportTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * @param rootFolderId the folder being exported - its name becomes the top folder of every
     *                      file path, and its subfolders are kept; null for a selection of
     *                      documents (files sit side by side, with no folders)
     * @param includeFiles  whether documents' files are added to the ZIP at all, or just the
     *                      metadata sheet - a metadata-only export skips the scan-gate /
     *                      storage reads entirely, so it is far cheaper for a large selection
     */
    public Result build(UUID tenantId, List<UUID> documentIds, UUID rootFolderId, boolean includeFiles,
                        TemplateFormat format, Path target, long maxBytes) {
        Map<UUID, Folder> folders = rootFolderId == null ? Map.of()
                : folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId).stream()
                        .collect(Collectors.toMap(Folder::getFolderId, Function.identity()));
        List<Map<String, String>> rows = new ArrayList<>();
        Map<UUID, String> fieldColumn = new LinkedHashMap<>();
        Map<UUID, String> typeNames = new LinkedHashMap<>(); // insertion order = types as first met
        Set<String> usedEntryNames = new HashSet<>();
        long filesBytes = 0;
        int skipped = 0;

        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int from = 0; from < documentIds.size(); from += CHUNK) {
                List<UUID> chunk = documentIds.subList(from, Math.min(from + CHUNK, documentIds.size()));
                Map<UUID, Document> documents = documentRepository
                        .findAllByDocumentIdInAndTenantIdAndDeletedFalse(chunk, tenantId).stream()
                        .collect(Collectors.toMap(Document::getDocumentId, d -> d));
                loadTypeNames(documents.values(), typeNames);
                if (includeFiles) {
                    keyPrefetcher.prefetchDocuments(documents.keySet());
                }
                for (UUID documentId : chunk) {
                    Document document = documents.get(documentId);
                    if (document == null) {
                        continue; // deleted since the export was requested
                    }
                    DocumentVersion version = version(document, tenantId);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("Document ID", document.getDocumentId().toString());
                    row.put("Title", document.getTitle());
                    row.put("Document type", typeNames.getOrDefault(document.getDocumentTypeId(), ""));
                    row.put("Original file name", version != null ? version.getOriginalFilename() : "");
                    row.put("Size (bytes)", version != null ? String.valueOf(version.getSizeBytes()) : "");
                    row.put("Version", version != null ? String.valueOf(version.getVersionNumber()) : "");
                    row.put("Created at", document.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    addOcr(version, row);
                    addMetadata(tenantId, document.getDocumentId(), row, fieldColumn);

                    if (includeFiles) {
                        long size = version != null ? version.getSizeBytes() : 0;
                        if (filesBytes + size > maxBytes) {
                            throw new ExportTooLargeException("The files add up to more than the "
                                    + (maxBytes / (1024 * 1024)) + " MB an export may hold. Export fewer documents.");
                        }
                        String entryName = entryName(directory(document.getFolderId(), rootFolderId, folders),
                                version, usedEntryNames);
                        String note = addFile(zip, document.getDocumentId(), entryName);
                        if (note == null) {
                            row.put("File in ZIP", entryName);
                            filesBytes += size;
                        } else {
                            row.put("Note", note);
                            skipped++;
                        }
                    }
                    rows.add(row);
                }
            }

            List<String> columns = new ArrayList<>(BASE_COLUMNS);
            if (!includeFiles) {
                columns.remove("File in ZIP");
            }
            fieldOrder(typeNames.keySet(), fieldColumn.keySet()).forEach(id -> columns.add(fieldColumn.get(id)));
            zip.putNextEntry(new ZipEntry(METADATA_ENTRY + "." + format.name()));
            zip.write(format == TemplateFormat.csv ? csv(columns, rows) : xlsx(columns, rows));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the export ZIP", e);
        }
        return new Result(rows.size(), filesBytes, skipped);
    }

    /** Streams one document's file into the ZIP; returns why it was left out, or null when added. */
    private String addFile(ZipOutputStream zip, UUID documentId, String entryName) throws IOException {
        DownloadedDocument downloaded;
        try {
            downloaded = documentService.downloadDocumentFromJob(documentId, "bulk_export");
        } catch (ApiException e) {
            return "File not included: " + e.getMessage();
        }
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream content = downloaded.content()) {
            content.transferTo(zip);
        } finally {
            zip.closeEntry();
        }
        return null;
    }

    private DocumentVersion version(Document document, UUID tenantId) {
        UUID versionId = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return documentVersionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, document.getDocumentId(), tenantId)
                .orElse(null);
    }

    private void loadTypeNames(Collection<Document> documents, Map<UUID, String> typeNames) {
        Set<UUID> missing = documents.stream().map(Document::getDocumentTypeId)
                .filter(id -> !typeNames.containsKey(id)).collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            documentTypeRepository.findAllById(missing)
                    .forEach(type -> typeNames.put(type.getDocumentTypeId(), type.getTypeName()));
        }
    }

    /**
     * The exported version's OCR state: what the pipeline did, and whether a
     * human has signed the values off.
     *
     * <p>Read off the {@link DocumentVersion} already loaded for the row - no
     * extra query, so a 10,000-document export costs the same as before.
     *
     * <p>Both cells are left out (not blank-filled) when OCR never ran, so
     * {@code "not_required"} never appears in a sheet; the column simply
     * stays empty for documents nobody asked to OCR. A tenant with the
     * feature off therefore gets two empty columns, which is the truthful
     * rendering of "this does not apply here".
     */
    private void addOcr(DocumentVersion version, Map<String, String> row) {
        if (version == null) {
            return;
        }
        String status = version.getOcrStatus();
        if (status == null || status.isBlank() || "not_required".equals(status)) {
            return;
        }
        row.put(OCR_STATUS, status);
        // Only a version that actually produced fields can be said to be
        // reviewed or not: a failed or still-running one has nothing to
        // review, and claiming "Reviewed" for it would be a lie the sheet
        // outlives.
        if ("completed".equals(status)) {
            row.put(OCR_REVIEW, version.isRequiresReview() ? "Needs review" : "Reviewed");
        }
    }

    /** The document's metadata values, one column per field (labelled, first seen first). */
    private void addMetadata(UUID tenantId, UUID documentId, Map<String, String> row, Map<UUID, String> fieldColumn) {
        DocumentMetadata metadata = documentMetadataRepository
                .findById(new DocumentMetadataId(tenantId, documentId)).orElse(null);
        if (metadata == null || metadata.getFieldValues() == null || metadata.getFieldValues().isEmpty()) {
            return;
        }
        List<UUID> unknown = new ArrayList<>();
        for (String fieldId : metadata.getFieldValues().keySet()) {
            UUID id = parse(fieldId);
            if (id != null && !fieldColumn.containsKey(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            Set<String> taken = new LinkedHashSet<>(fieldColumn.values());
            taken.addAll(BASE_COLUMNS);
            for (MetadataField field : metadataFieldRepository.findAllByFieldIdInAndTenantIdAndDeletedFalse(unknown, tenantId)) {
                String label = field.getLabel() != null ? field.getLabel() : field.getFieldKey();
                if (!taken.add(label)) {
                    label = label + " (" + field.getFieldKey() + ")";
                    taken.add(label);
                }
                fieldColumn.put(field.getFieldId(), label);
            }
        }
        metadata.getFieldValues().forEach((fieldId, value) -> {
            String column = fieldColumn.get(parse(fieldId));
            if (column != null && value != null) {
                row.put(column, text(value.v()));
            }
        });
    }

    /**
     * Metadata columns in the order people know them from the form (and the
     * import template): each document type's fields by display order, types
     * in the order they first appear. A field no longer on its type's
     * current form goes last, in the order it was met.
     */
    private List<UUID> fieldOrder(Collection<UUID> documentTypeIds, Collection<UUID> fieldIds) {
        Map<UUID, Integer> rank = new HashMap<>();
        int next = 0;
        for (UUID typeId : documentTypeIds) {
            List<SchemaField> fields;
            try {
                fields = documentTypeFieldService.getSchema(typeId).fields().stream()
                        .sorted(Comparator.comparingInt(SchemaField::displayOrder))
                        .toList();
            } catch (RuntimeException e) {
                log.warn("Export: no schema for document type {}; its columns keep first-seen order", typeId);
                continue;
            }
            for (SchemaField field : fields) {
                if (!rank.containsKey(field.fieldId())) {
                    rank.put(field.fieldId(), next++);
                }
            }
        }
        List<UUID> ordered = new ArrayList<>(fieldIds);
        int unranked = Integer.MAX_VALUE - ordered.size();
        Map<UUID, Integer> position = new HashMap<>();
        for (UUID id : ordered) {
            position.put(id, rank.getOrDefault(id, unranked++));
        }
        ordered.sort(Comparator.comparingInt(position::get));
        return ordered;
    }

    static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).collect(Collectors.joining("; "));
        }
        return String.valueOf(value);
    }

    /**
     * "Finance/Invoices/" for a document in Finance › Invoices when Finance is exported - the
     * path from the exported folder down to the document's own folder. "" for a selection.
     */
    static String directory(UUID folderId, UUID rootFolderId, Map<UUID, Folder> folders) {
        if (rootFolderId == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID id = folderId; id != null && seen.add(id); ) {
            Folder folder = folders.get(id);
            if (folder == null) {
                break;
            }
            names.add(0, safeName(folder.getFolderName(), "Folder"));
            if (id.equals(rootFolderId)) {
                return String.join("/", names) + "/";
            }
            id = folder.getParentFolderId();
        }
        // Not under the root (shouldn't happen for a folder's export) - at the top folder, not lost.
        Folder root = folders.get(rootFolderId);
        return safeName(root != null ? root.getFolderName() : null, "Folder") + "/";
    }

    /** The file's own name inside {@code directory} - "name (2).pdf" when that name is taken there. */
    static String entryName(String directory, DocumentVersion version, Set<String> used) {
        String base = safeName(version != null ? version.getOriginalFilename() : null, "file");
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String extension = dot > 0 ? base.substring(dot) : "";
        String name = directory + base;
        for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
            name = directory + stem + " (" + n + ")" + extension;
        }
        return name;
    }

    /** A single path segment: no folders smuggled in, no characters Windows or ZIP tools choke on. */
    static String safeName(String raw, String fallback) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return fallback;
        }
        return name.length() > MAX_NAME ? name.substring(name.length() - MAX_NAME) : name;
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] xlsx(List<String> columns, List<Map<String, String>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Documents");
            CellStyle bold = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            bold.setFont(font);
            Row header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                header.createCell(c).setCellValue(columns.get(c));
                header.getCell(c).setCellStyle(bold);
                sheet.setColumnWidth(c, 22 * 256);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < columns.size(); c++) {
                    String value = rows.get(r).get(columns.get(c));
                    if (value != null && !value.isEmpty()) {
                        // Always a string cell: values are data, never formulas.
                        row.createCell(c).setCellValue(value);
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] csv(List<String> columns, List<Map<String, String>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord(columns);
            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>(columns.size());
                for (String column : columns) {
                    values.add(neutralise(row.getOrDefault(column, "")));
                }
                printer.printRecord(values);
            }
        }
        return out.toByteArray();
    }

    /**
     * Spreadsheet apps run a CSV cell starting with = + - @ (or a tab/CR)
     * as a formula; a leading apostrophe keeps a stored value from doing
     * that when the export is opened (CSV injection).
     */
    static String neutralise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }
}
