package com.oak.dms.importexport.matching;

import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ImportFileAlreadyReceivedException;
import com.oak.dms.importexport.exception.ImportFileNotInBatchException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Matches an uploaded file to the manifest row it belongs to. Filenames
 * compare case-insensitively, and a filename listed on several rows fills
 * them in row order - each upload claims the lowest-numbered matching row
 * that has no staged file yet. Rows skipped at creation because of template
 * errors ({@link ImportItem#isSkippedAtPreview()}) are never matched.
 */
@Component
public class ImportFilenameMatcher {

    public ImportItem match(List<ImportItem> items, String originalFilename) {
        String filename = normalize(originalFilename);
        if (filename.isEmpty()) {
            throw new ImportFileNotInBatchException("The uploaded file has no filename.");
        }

        List<ImportItem> candidates = items.stream()
                .filter(item -> item.getFilenameReference() != null
                        && item.getFilenameReference().trim().equalsIgnoreCase(filename))
                .sorted(Comparator.comparingInt(ImportItem::getRowNumber))
                .toList();
        if (candidates.isEmpty()) {
            throw new ImportFileNotInBatchException(
                    "'" + filename + "' is not listed in the Filename column of this import's template.");
        }

        // Rows that failed the template checks never take a file.
        List<ImportItem> open = candidates.stream()
                .filter(item -> !item.isSkippedAtPreview())
                .toList();
        if (open.isEmpty()) {
            throw new ImportFileNotInBatchException(skippedMessage(candidates));
        }

        return open.stream()
                .filter(item -> item.getStagedObjectKey() == null)
                .findFirst()
                .orElseThrow(() -> new ImportFileAlreadyReceivedException(
                        "'" + filename + "' has already been uploaded for every row that lists it."));
    }

    /** "Row 5 was skipped ..., so it doesn't ..." / "Rows 5 and 7 were skipped ..., so they don't ...". */
    static String skippedMessage(List<ImportItem> skipped) {
        List<String> rows = skipped.stream().map(item -> String.valueOf(item.getRowNumber())).toList();
        if (rows.size() == 1) {
            return "Row " + rows.get(0) + " was skipped because of template errors, so it doesn't need a file.";
        }
        String joined = String.join(", ", rows.subList(0, rows.size() - 1)) + " and " + rows.get(rows.size() - 1);
        return "Rows " + joined + " were skipped because of template errors, so they don't need a file.";
    }

    /** Drops any client-supplied directory prefix and surrounding whitespace. */
    static String normalize(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int lastSeparator = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        return originalFilename.substring(lastSeparator + 1).trim();
    }
}
