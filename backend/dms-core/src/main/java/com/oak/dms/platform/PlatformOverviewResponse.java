package com.oak.dms.platform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the platform dashboard shows: every organisation on this
 * installation, with how much of the system each is using, plus the totals
 * across all of them.
 * <p>
 * Cross-tenant by definition, so PLATFORM_ADMIN only - see
 * {@link PlatformOverviewController}. Deliberately counts and sizes only:
 * nothing here reveals a document's title, a folder name or who did what,
 * which keeps "the vendor can see how much you store" separate from "the
 * vendor can see what you store".
 *
 * @param fileTypes storage split by file family across every organisation -
 *                  what the disk is actually filling up with, which no single
 *                  organisation's figures answer
 * @param monthly   the whole installation's intake, month by month
 */
public record PlatformOverviewResponse(Totals totals, List<Organisation> organisations,
                                       List<FileFamily> fileTypes,
                                       List<Month> monthly) {

    /**
     * One month of platform-wide intake, oldest first. Months with no
     * uploads are present with zeros rather than missing, so a line chart
     * shows a flat stretch instead of silently joining across the gap.
     *
     * @param month {@code YYYY-MM}
     */
    public record Month(String month, long documents, long storageBytes) {
    }

    /**
     * @param organisations     every organisation, deactivated ones included
     * @param activeOrganisations status active and not deactivated - the ones actually in use
     */
    public record Totals(
            long organisations,
            long activeOrganisations,
            long suspendedOrganisations,
            long deactivatedOrganisations,
            long documents,
            long storageBytes,
            long users
    ) {
    }

    /**
     * One organisation's usage. {@code documents} and {@code users} exclude
     * soft-deleted rows, and {@code storageBytes} is the sum over live
     * versions - so these are "what it is using now", not "what it has ever
     * created", which is what a bill or a capacity question needs.
     */
    public record Organisation(
            UUID tenantId,
            String tenantName,
            String domain,
            String subscriptionTier,
            String status,
            boolean deactivated,
            long documents,
            long storageBytes,
            long users,
            OffsetDateTime createdAt,
            /** Versions stored in the last 30 days - what separates a busy client from a dormant one. */
            long documentsAddedLast30Days,
            /** When anything was last stored. Null for an organisation that has never uploaded. */
            OffsetDateTime lastActivityAt
    ) {
    }
}
