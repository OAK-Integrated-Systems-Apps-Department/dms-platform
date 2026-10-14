package com.oak.dms.platform;

/**
 * How much storage one kind of file accounts for - across the whole
 * installation ({@link PlatformOverviewResponse}) or inside one organisation
 * ({@link PlatformOrganisationBreakdown}). The same record for both so the
 * two charts are read the same way and a family keeps one meaning.
 * <p>
 * A family is a property of the bytes on disk, never of what a client wrote:
 * it needs no filename, document title or folder name, which is what keeps
 * "the vendor can see how much you store" apart from "the vendor can see
 * what you store".
 *
 * @param label     the vocabulary the document list already uses - "Word",
 *                  "Excel", "Image", "Archive", "Other"
 * @param documents live versions in this family
 */
public record FileFamily(String label, long documents, long storageBytes) {
}
