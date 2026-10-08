package com.oak.dms.importexport.dto;

import java.util.List;

/**
 * Result of unpacking a ZIP into a bulk import.
 *
 * @param entriesRead   files found in the ZIP (folders and macOS metadata not counted)
 * @param filesMatched  entries staged against a template row
 * @param problemCount  entries not staged; {@code problems} lists at most the first 200
 * @param filesReceived rows of the batch that now have their file
 */
public record ImportArchiveReceivedResponse(
        int entriesRead,
        int filesMatched,
        int problemCount,
        List<EntryProblem> problems,
        int filesReceived,
        int totalRecords
) {

    public record EntryProblem(String entry, String message) {
    }
}
