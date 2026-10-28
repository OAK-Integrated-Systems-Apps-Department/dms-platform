package com.oak.dms.platform;

import java.util.List;

/**
 * One organisation in detail, for the row a platform administrator has
 * opened on the dashboard.
 * <p>
 * Held to the same line as {@link PlatformOverviewResponse}: counts, sizes
 * and dates only - see {@link FileFamily} for why a file family is on the
 * right side of that line. The tenant's own document-type names are
 * deliberately left out: those are the client's vocabulary, closer to a
 * folder name than to a file format.
 *
 * @param fileTypes storage split by file family, largest first
 * @param statuses  where this organisation's documents are in their lifecycle
 * @param monthly   twelve months of this organisation's intake, oldest first
 */
public record PlatformOrganisationBreakdown(List<FileFamily> fileTypes,
                                            List<StatusCount> statuses,
                                            List<PlatformOverviewResponse.Month> monthly) {

    /**
     * Documents at one lifecycle stage. Every stage the schema allows is
     * present even at zero, so the chart shows the full lifecycle rather than
     * only the stages this organisation happens to be using - "nothing is
     * published yet" is itself the answer someone is looking for.
     *
     * @param status one of draft, published, archived, pending-deletion
     */
    public record StatusCount(String status, long documents) {
    }
}
