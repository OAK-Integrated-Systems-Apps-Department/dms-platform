package com.oak.dms.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the platform dashboard's figures.
 * <p>
 * Plain JDBC rather than JPA: this is one grouped read across four tables
 * that deliberately ignores tenant scoping, which is the opposite of what
 * the JPA entities and their tenant filters are built for. Doing it through
 * repositories would mean either per-tenant round trips (N queries for N
 * clients) or fighting the tenant filter - both worse than one honest query.
 */
@Service
@RequiredArgsConstructor
public class PlatformOverviewService {

    /**
     * One pass over the four tables, aggregated per tenant before the join
     * so each contributes a single row - a plain join across them would
     * multiply documents by versions by users and report nonsense.
     * <p>
     * No system-tenant exclusion is needed: V24 seeded a "Platform (System)"
     * tenant and V37 deleted it again, so every row here is a real client.
     * Deactivated ones are included but sort last - they still hold storage,
     * which is exactly what someone looking at this page needs to know.
     */
    private static final String OVERVIEW_SQL = """
            select t.tenant_id, t.tenant_name, t.domain, t.subscription_tier, t.status,
                   t.is_deleted as deactivated, t.created_at,
                   coalesce(d.docs, 0)   as documents,
                   coalesce(v.bytes, 0)  as storage_bytes,
                   coalesce(u.users, 0)  as users,
                   coalesce(v.recent, 0) as recent_versions,
                   v.last_activity
              from tenants t
              left join (select tenant_id, count(*) as docs
                           from documents where not is_deleted
                          group by tenant_id) d on d.tenant_id = t.tenant_id
              left join (select tenant_id,
                                sum(size_bytes) as bytes,
                                max(created_at) as last_activity,
                                count(*) filter (where created_at > now() - interval '30 days') as recent
                           from document_versions where not is_deleted
                          group by tenant_id) v on v.tenant_id = t.tenant_id
              left join (select tenant_id, count(*) as users
                           from users where not is_deleted
                          group by tenant_id) u on u.tenant_id = t.tenant_id
             order by t.is_deleted, lower(t.tenant_name)
            """;

    /**
     * Twelve months of intake across every organisation, oldest first.
     * {@code generate_series} drives the left side so a quiet month comes
     * back as a zero row: without it the month is simply absent and a line
     * chart draws straight from the month before to the month after, which
     * reads as steady activity over a period when nothing happened.
     */
    private static final String MONTHLY_SQL = """
            select to_char(m.month, 'YYYY-MM')        as month,
                   coalesce(count(v.version_id), 0)   as documents,
                   coalesce(sum(v.size_bytes), 0)     as storage_bytes
              from generate_series(date_trunc('month', now()) - interval '11 months',
                                   date_trunc('month', now()),
                                   interval '1 month') as m(month)
              left join document_versions v
                     on date_trunc('month', v.created_at) = m.month
                    and not v.is_deleted
             group by m.month
             order by m.month
            """;

    /**
     * The same twelve months for one organisation. The tenant test sits in
     * the join condition rather than a {@code where} clause: in a
     * {@code where} it would discard the generated months that have no row
     * for this tenant, which is exactly the zero-fill the series needs.
     */
    private static final String TENANT_MONTHLY_SQL = """
            select to_char(m.month, 'YYYY-MM')        as month,
                   coalesce(count(v.version_id), 0)   as documents,
                   coalesce(sum(v.size_bytes), 0)     as storage_bytes
              from generate_series(date_trunc('month', now()) - interval '11 months',
                                   date_trunc('month', now()),
                                   interval '1 month') as m(month)
              left join document_versions v
                     on date_trunc('month', v.created_at) = m.month
                    and v.tenant_id = ?
                    and not v.is_deleted
             group by m.month
             order by m.month
            """;

    /**
     * Raw MIME types for one organisation. Grouped into families in Java
     * rather than with a {@code case} ladder in SQL - the mapping is a list
     * of string rules that belongs where it can be read and changed, and the
     * distinct MIME types in one tenant are few enough that folding them
     * afterwards costs nothing.
     */
    private static final String TENANT_FILE_TYPE_SQL = """
            select coalesce(mime_type, '') as mime_type,
                   count(*)                as documents,
                   sum(size_bytes)         as storage_bytes
              from document_versions
             where tenant_id = ? and not is_deleted
             group by coalesce(mime_type, '')
            """;

    /** The same split with no tenant filter - what the whole disk is made of. */
    private static final String PLATFORM_FILE_TYPE_SQL = """
            select coalesce(mime_type, '') as mime_type,
                   count(*)                as documents,
                   sum(size_bytes)         as storage_bytes
              from document_versions
             where not is_deleted
             group by coalesce(mime_type, '')
            """;

    /**
     * Where one organisation's documents sit in their lifecycle. The stages
     * come from a {@code values} list rather than from the data so a stage
     * nobody has reached comes back as a zero row: "nothing published yet" is
     * an answer, and a chart that simply omits the bar cannot give it.
     */
    private static final String TENANT_STATUS_SQL = """
            select s.status,
                   count(d.document_id) as documents
              from (values ('draft'), ('published'), ('archived'), ('pending-deletion')) as s(status)
              left join documents d
                     on d.status = s.status
                    and d.tenant_id = ?
                    and not d.is_deleted
             group by s.status
            """;

    /**
     * The lifecycle in order, so the chart reads left to right as a document
     * actually travels - never reordered by size, which would make the shape
     * of the chart change meaning between two organisations.
     */
    private static final List<String> STATUS_ORDER = List.of("draft", "published", "archived", "pending-deletion");

    private final JdbcTemplate jdbc;

    /**
     * Which family a stored file belongs to, in the vocabulary the document
     * list already uses. Order matters: an Office document is a ZIP
     * underneath, so the Office rules have to be tried before the archive
     * one or every .docx would be reported as an archive.
     */
    static String familyOf(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase();
        if (mime.equals("application/pdf")) return "PDF";
        if (mime.contains("wordprocessingml") || mime.equals("application/msword")
                || mime.contains("opendocument.text") || mime.contains("rtf")) return "Word";
        if (mime.contains("spreadsheetml") || mime.contains("ms-excel")
                || mime.contains("opendocument.spreadsheet") || mime.equals("text/csv")) return "Excel";
        if (mime.contains("presentationml") || mime.contains("ms-powerpoint")
                || mime.contains("opendocument.presentation")) return "PowerPoint";
        if (mime.startsWith("image/")) return "Image";
        if (mime.startsWith("video/")) return "Video";
        if (mime.startsWith("audio/")) return "Audio";
        if (mime.equals("message/rfc822") || mime.contains("ms-outlook")) return "Email";
        if (mime.contains("zip") || mime.contains("7z-compressed") || mime.contains("rar")
                || mime.contains("tar") || mime.contains("gzip")) return "Archive";
        if (mime.startsWith("text/")) return "Text";
        return "Other";
    }

    /**
     * Runs one of the MIME-type queries and folds the rows into families,
     * largest first. Shared by both scopes so the whole-platform chart and a
     * single organisation's chart can never disagree about what a family is.
     */
    private List<FileFamily> familiesFrom(String sql, Object... args) {
        // Summed into a map because several MIME types fold into one family -
        // computeIfAbsent then +=, so the second .docx variant adds to Word
        // rather than replacing it.
        Map<String, long[]> families = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            long[] totals = families.computeIfAbsent(familyOf(rs.getString("mime_type")), key -> new long[2]);
            totals[0] += rs.getLong("documents");
            totals[1] += rs.getLong("storage_bytes");
        }, args);

        return families.entrySet().stream()
                .map(e -> new FileFamily(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(FileFamily::storageBytes).reversed())
                .toList();
    }

    /**
     * One organisation's composition, fetched only when its row is opened -
     * three queries per organisation would be wasted on every row nobody
     * looks at, which is why this is not folded into {@link #overview()}.
     */
    @Transactional(readOnly = true)
    public PlatformOrganisationBreakdown breakdown(UUID tenantId) {
        List<FileFamily> fileTypes = familiesFrom(TENANT_FILE_TYPE_SQL, tenantId);

        // A RowMapper rather than a callback: the query groups by a fixed
        // values() list, so it already returns exactly one row per stage.
        Map<String, Long> counts = jdbc.query(TENANT_STATUS_SQL,
                        (rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("documents")),
                        tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // Re-ordered here because SQL's group by gives no useful order, and
        // the lifecycle has to read in lifecycle order.
        List<PlatformOrganisationBreakdown.StatusCount> statuses = STATUS_ORDER.stream()
                .map(status -> new PlatformOrganisationBreakdown.StatusCount(
                        status, counts.getOrDefault(status, 0L)))
                .toList();

        List<PlatformOverviewResponse.Month> monthly = jdbc.query(TENANT_MONTHLY_SQL,
                (rs, rowNum) -> new PlatformOverviewResponse.Month(
                        rs.getString("month"),
                        rs.getLong("documents"),
                        rs.getLong("storage_bytes")),
                tenantId);

        return new PlatformOrganisationBreakdown(fileTypes, statuses, monthly);
    }

    @Transactional(readOnly = true)
    public PlatformOverviewResponse overview() {
        List<PlatformOverviewResponse.Organisation> organisations = jdbc.query(OVERVIEW_SQL,
                (rs, rowNum) -> new PlatformOverviewResponse.Organisation(
                        rs.getObject("tenant_id", java.util.UUID.class),
                        rs.getString("tenant_name"),
                        rs.getString("domain"),
                        rs.getString("subscription_tier"),
                        rs.getString("status"),
                        rs.getBoolean("deactivated"),
                        rs.getLong("documents"),
                        rs.getLong("storage_bytes"),
                        rs.getLong("users"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getLong("recent_versions"),
                        rs.getObject("last_activity", java.time.OffsetDateTime.class)));

        // Summed here rather than in a second query: the rows are already in
        // hand, and a separate aggregate could disagree with them if anything
        // changed in between.
        long active = organisations.stream()
                .filter(o -> !o.deactivated() && "active".equals(o.status()))
                .count();
        long suspended = organisations.stream()
                .filter(o -> !o.deactivated() && "suspended".equals(o.status()))
                .count();
        long deactivated = organisations.stream().filter(PlatformOverviewResponse.Organisation::deactivated).count();

        PlatformOverviewResponse.Totals totals = new PlatformOverviewResponse.Totals(
                organisations.size(),
                active,
                suspended,
                deactivated,
                organisations.stream().mapToLong(PlatformOverviewResponse.Organisation::documents).sum(),
                organisations.stream().mapToLong(PlatformOverviewResponse.Organisation::storageBytes).sum(),
                organisations.stream().mapToLong(PlatformOverviewResponse.Organisation::users).sum());

        List<PlatformOverviewResponse.Month> monthly = jdbc.query(MONTHLY_SQL,
                (rs, rowNum) -> new PlatformOverviewResponse.Month(
                        rs.getString("month"),
                        rs.getLong("documents"),
                        rs.getLong("storage_bytes")));

        return new PlatformOverviewResponse(totals, organisations,
                familiesFrom(PLATFORM_FILE_TYPE_SQL), monthly);
    }
}
