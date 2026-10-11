package com.oak.dms.importexport.dto;

/**
 * An upload the batch accepted, as the Uploads list shows it after a reload.
 *
 * @param rowNumber    its row in the batch
 * @param filename     its name, without any folder path
 * @param clientFileId the ID the browser sent with it (null if none), so the
 *                     screen can match it to a file it's still tracking
 */
public record ImportAcceptedFile(int rowNumber, String filename, String clientFileId) {
}
