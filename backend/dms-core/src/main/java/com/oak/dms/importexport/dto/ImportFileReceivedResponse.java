package com.oak.dms.importexport.dto;

/**
 * Response to an accepted upload. A rejected one returns its 4xx instead,
 * and still shows up in the batch's results as a failed item.
 *
 * @param rowNumber     the item this upload became
 * @param filename      the file's name as kept, without any folder path
 * @param filesReceived uploads accepted into the batch so far
 * @param filesRejected uploads refused so far
 * @param totalFiles    every upload so far, accepted or rejected
 */
public record ImportFileReceivedResponse(
        int rowNumber,
        String filename,
        int filesReceived,
        int filesRejected,
        int totalFiles
) {
}
