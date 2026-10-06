package com.oak.dms.dashboard;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything the home dashboard shows, for one period ({@code days}, ending today) - and only what
 * the caller may see: documents by the same folder and document access rules as the document
 * browser; usage (views, downloads, uploads) and the activity feed as either their own or, with
 * the audit-history permission, the organisation's; the security summary only with quarantine view.
 *
 * @param documentsVisible false when the caller can't view documents - every document figure is then empty
 * @param usageScope       {@code mine} or {@code organisation} - whose views, downloads, uploads and activity
 * @param security         null without the quarantine-view permission
 * @param usageMonths      the last {@value DashboardService#USAGE_MONTHS} calendar months, oldest first (the
 *                         current one to date) - the same usage, whatever {@code days} is
 * @param usageYear        those months' totals, against the same span a year earlier
 */
public record DashboardResponse(
        int days,
        boolean documentsVisible,
        Documents documents,
        Storage storage,
        Usage usage,
        List<Day> daily,
        List<NamedCount> types,
        List<FormatCount> formats,
        List<FolderCount> folders,
        List<TopDocument> mostOpened,
        Security security,
        List<RecentDocument> recent,
        String usageScope,
        List<Activity> activity,
        List<Month> usageMonths,
        Usage usageYear
) {

    /** Live documents the caller can see; {@code added} in this period, {@code addedBefore} in the one before. */
    public record Documents(long total, long added, long addedBefore, long mine, long restricted) {
    }

    /** What those documents take up - every live version; {@code added} by versions stored this period and the one before. */
    public record Storage(long usedBytes, long addedBytes, long addedBytesBefore, long versions) {
    }

    /** Views, downloads and uploads this period and the one before - for the comparison. */
    public record Usage(long views, long downloads, long uploads,
                        long viewsBefore, long downloadsBefore, long uploadsBefore) {
    }

    /**
     * One day of the period (in the caller's time zone), every day present, zeros included.
     *
     * @param documents the documents the caller can see that existed by the end of that day
     */
    public record Day(LocalDate date, long added, long documents, long views, long downloads, long uploads) {
    }

    /** Views, downloads and uploads in one calendar month ({@code month} is its first day). */
    public record Month(LocalDate month, long views, long downloads, long uploads) {
    }

    public record NamedCount(String name, long count) {
    }

    /** Documents by kind of file (pdf, word, sheet, slides, image, archive, text, other), from their current version. */
    public record FormatCount(String format, long count, long bytes) {
    }

    /** @param folderId null for documents that aren't in a folder */
    public record FolderCount(UUID folderId, String name, long count, long bytes) {
    }

    public record TopDocument(UUID documentId, String title, String documentTypeName, String fileName,
                              Long sizeBytes, String mimeType, OffsetDateTime createdAt,
                              long views, long downloads) {
    }

    /**
     * Uploads checked this period: {@code clean} stored; {@code threats} quarantined as malware;
     * {@code unscannable} blocked because they couldn't be checked (encrypted, too deep);
     * {@code refused} turned away (duplicates, damaged or disallowed files); {@code quarantined} held now.
     */
    public record Security(long clean, long threats, long unscannable, long refused, long quarantined) {
    }

    public record RecentDocument(UUID documentId, String title, String documentTypeName, String fileName,
                                 Long sizeBytes, String mimeType, UUID ownerUserId, String ownerName,
                                 OffsetDateTime createdAt) {
    }

    public record Activity(UUID id, String action, String objectType, UUID objectId, String resourceName,
                           UUID actorId, String actorName, OffsetDateTime occurredAt) {
    }
}
