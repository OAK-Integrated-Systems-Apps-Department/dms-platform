package com.oak.dms.dashboard;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.dashboard.DashboardResponse.Activity;
import com.oak.dms.dashboard.DashboardResponse.Day;
import com.oak.dms.dashboard.DashboardResponse.Documents;
import com.oak.dms.dashboard.DashboardResponse.FolderCount;
import com.oak.dms.dashboard.DashboardResponse.FormatCount;
import com.oak.dms.dashboard.DashboardResponse.NamedCount;
import com.oak.dms.dashboard.DashboardResponse.RecentDocument;
import com.oak.dms.dashboard.DashboardResponse.Security;
import com.oak.dms.dashboard.DashboardResponse.Storage;
import com.oak.dms.dashboard.DashboardResponse.TopDocument;
import com.oak.dms.dashboard.DashboardResponse.Usage;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The home dashboard's figures, worked out for the caller over a period ending today. Documents
 * are counted with the same visibility as the document browser (DocumentRepository#findVisible):
 * a document following its folder counts when that folder isn't hidden from the caller, a
 * restricted one only when the caller is on its list; templates and deleted documents never count.
 * Usage - views, downloads, uploads, the activity feed - is the caller's own unless they may read
 * the organisation's audit history.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** The periods the dashboard offers; anything else is read as the default. */
    static final List<Integer> PERIODS = List.of(7, 30, 90);
    static final int DEFAULT_DAYS = 30;
    static final int RECENT_DOCUMENTS = 6;
    static final int ACTIVITY_ENTRIES = 10;
    static final int TOP_FOLDERS = 6;
    static final int MOST_OPENED = 5;

    /** The events worth showing in the feed - what people did, not every view, login or scan. */
    static final List<String> ACTIVITY_ACTIONS = List.of(
            AuditActionCatalog.DOCUMENT_UPLOADED, AuditActionCatalog.DOCUMENT_DOWNLOADED,
            AuditActionCatalog.DOCUMENT_DELETED, AuditActionCatalog.DOCUMENT_RESTORED,
            AuditActionCatalog.DOCUMENT_MOVED, AuditActionCatalog.DOCUMENT_VERSION_RESTORED,
            AuditActionCatalog.DOCUMENT_ACCESS_CHANGED, AuditActionCatalog.DOCUMENT_QUARANTINED,
            AuditActionCatalog.FOLDER_CREATED, AuditActionCatalog.FOLDER_RENAMED, AuditActionCatalog.FOLDER_MOVED,
            AuditActionCatalog.FOLDER_DELETED, AuditActionCatalog.FOLDER_ACCESS_CHANGED);

    /** The events counted as usage. */
    static final List<String> USAGE_ACTIONS = List.of(AuditActionCatalog.DOCUMENT_VIEWED,
            AuditActionCatalog.DOCUMENT_DOWNLOADED, AuditActionCatalog.DOCUMENT_UPLOADED);

    /** File extensions by the kind of file the dashboard groups them into. */
    private static final Map<String, String> FORMATS = new HashMap<>();

    static {
        for (String ext : List.of("doc", "docx", "odt", "rtf")) FORMATS.put(ext, "word");
        for (String ext : List.of("xls", "xlsx", "ods", "csv", "tsv")) FORMATS.put(ext, "sheet");
        for (String ext : List.of("ppt", "pptx", "odp")) FORMATS.put(ext, "slides");
        for (String ext : List.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff")) {
            FORMATS.put(ext, "image");
        }
        for (String ext : List.of("txt", "md", "json", "xml", "eml", "msg")) FORMATS.put(ext, "text");
        FORMATS.put("pdf", "pdf");
        FORMATS.put("zip", "archive");
    }

    /** The documents the caller can see - :everything skips the folder/document access filter. */
    private static final String VISIBLE = " d.tenant_id = :tenant and not d.is_deleted and not d.is_template"
            + " and (:everything or (d.access_mode = 'inherit' and (d.folder_id is null or d.folder_id not in (:hidden)))"
            + " or (d.access_mode = 'restricted' and d.document_id in (:granted))) ";

    /** Audit events in scope - everyone's, or (:actor set) only the caller's. */
    private static final String IN_SCOPE = " a.tenant_id = :tenant and a.outcome = 'SUCCESS'"
            + " and (cast(:actor as uuid) is null or a.actor_id = :actor) ";

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentAccessService documentAccessService;
    private final DocumentService documentService;
    private final AuthorizationService authorization;

    @Transactional(readOnly = true)
    public DashboardResponse forCaller(ZoneId zone, int requestedDays) {
        int days = period(requestedDays);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean organisation = authorization.hasPermission(PermissionCodes.ABDR_VIEW_AUDIT_HISTORY);
        String scope = organisation ? "organisation" : "mine";
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(days - 1L);
        LocalDate previousStart = start.minusDays(days);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("me", userId)
                .addValue("actor", organisation ? null : userId)
                .addValue("zone", zone.getId())
                .addValue("start", start)
                .addValue("previousStart", previousStart)
                .addValue("usage", USAGE_ACTIONS);

        List<Activity> activity = activity(params);
        Security security = authorization.hasPermission(PermissionCodes.DOCM_QUARANTINE_VIEW)
                ? security(params) : null;

        // Usage per day over both periods: this one is charted, the one before is the comparison.
        Map<LocalDate, long[]> usagePerDay = new HashMap<>();
        jdbc.query("""
                select (a.occurred_at at time zone :zone)::date as day, a.action, count(*) as n
                from audit_events a
                where""" + IN_SCOPE + """
                  and a.action in (:usage) and (a.occurred_at at time zone :zone)::date >= :previousStart
                group by 1, 2""", params, rs -> {
            long[] counts = usagePerDay.computeIfAbsent(rs.getObject("day", LocalDate.class), k -> new long[3]);
            counts[USAGE_ACTIONS.indexOf(rs.getString("action"))] += rs.getLong("n");
        });
        Usage usage = usage(usagePerDay, start);

        if (!authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            return new DashboardResponse(days, false, new Documents(0, 0, 0, 0, 0), new Storage(0, 0, 0, 0), usage,
                    series(start, today, 0, Map.of(), usagePerDay), List.of(), List.of(), List.of(), List.of(),
                    security, List.of(), scope, activity);
        }

        DocumentAccessView access = documentAccessService.forCaller();
        Collection<UUID> hidden = nonEmpty(access.folders().hiddenFolderIds());
        params.addValue("everything", access.seesEverything())
                .addValue("hidden", hidden)
                .addValue("granted", access.grantedIds());

        Documents documents = jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where (d.created_at at time zone :zone)::date >= :start) as added,
                       count(*) filter (where (d.created_at at time zone :zone)::date >= :previousStart
                                          and (d.created_at at time zone :zone)::date < :start) as added_before,
                       count(*) filter (where d.owner_user_id = :me) as mine,
                       count(*) filter (where d.access_mode = 'restricted') as restricted
                from documents d where""" + VISIBLE, params,
                (rs, i) -> new Documents(rs.getLong("total"), rs.getLong("added"), rs.getLong("added_before"),
                        rs.getLong("mine"), rs.getLong("restricted")));

        Storage storage = jdbc.queryForObject("""
                select coalesce(sum(v.size_bytes), 0) as used,
                       coalesce(sum(v.size_bytes) filter (where (v.created_at at time zone :zone)::date >= :start), 0) as added,
                       coalesce(sum(v.size_bytes) filter (where (v.created_at at time zone :zone)::date >= :previousStart
                                                            and (v.created_at at time zone :zone)::date < :start), 0) as added_before,
                       count(*) as versions
                from document_versions v join documents d on d.document_id = v.document_id
                where not v.is_deleted and""" + VISIBLE, params,
                (rs, i) -> new Storage(rs.getLong("used"), rs.getLong("added"), rs.getLong("added_before"),
                        rs.getLong("versions")));

        Map<LocalDate, Long> addedPerDay = new HashMap<>();
        jdbc.query("""
                select (d.created_at at time zone :zone)::date as day, count(*) as added
                from documents d
                where (d.created_at at time zone :zone)::date >= :start and""" + VISIBLE + " group by 1", params,
                rs -> {
                    addedPerDay.put(rs.getObject("day", LocalDate.class), rs.getLong("added"));
                });
        long beforeStart = documents.total() - addedPerDay.values().stream().mapToLong(Long::longValue).sum();

        List<NamedCount> types = jdbc.query("""
                select t.type_name as name, count(*) as n
                from documents d join document_types t on t.document_type_id = d.document_type_id
                where""" + VISIBLE + " group by t.type_name order by n desc, name", params,
                (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")));

        Map<String, long[]> byFormat = new LinkedHashMap<>();
        jdbc.query("""
                select lower(substring(v.original_filename from '\\.([^.]+)$')) as ext, count(*) as files,
                       coalesce(sum(v.size_bytes), 0) as bytes
                from documents d join document_versions v
                  on v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                where""" + VISIBLE + " group by 1", params,
                rs -> {
                    long[] sum = byFormat.computeIfAbsent(formatOf(rs.getString("ext")), k -> new long[2]);
                    sum[0] += rs.getLong("files");
                    sum[1] += rs.getLong("bytes");
                });
        List<FormatCount> formats = byFormat.entrySet().stream()
                .map(e -> new FormatCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted((a, b) -> Long.compare(b.bytes(), a.bytes()))
                .toList();

        // Folders the caller can see, by how many of those documents they hold. A restricted document
        // they were given inside a folder hidden from them counts under no folder - its name stays hidden.
        List<FolderCount> folders = jdbc.query("""
                select f.folder_id, f.folder_name, count(*) as n, coalesce(sum(v.size_bytes), 0) as bytes
                from documents d
                join folders f on f.folder_id = d.folder_id and not f.is_deleted
                left join document_versions v on v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                where (:everything or f.folder_id not in (:hidden)) and""" + VISIBLE + """
                group by f.folder_id, f.folder_name
                order by n desc, f.folder_name
                limit :limit""", params.addValue("limit", TOP_FOLDERS),
                (rs, i) -> new FolderCount(rs.getObject("folder_id", UUID.class), rs.getString("folder_name"),
                        rs.getLong("n"), rs.getLong("bytes")));
        long inFolders = jdbc.queryForObject("""
                select count(*) from documents d join folders f on f.folder_id = d.folder_id and not f.is_deleted
                where (:everything or f.folder_id not in (:hidden)) and""" + VISIBLE, params, Long.class);
        long notInFolder = documents.total() - inFolders;
        List<FolderCount> foldersWithTop = new ArrayList<>(folders);
        if (notInFolder > 0) {
            foldersWithTop.add(new FolderCount(null, null, notInFolder, 0));
            foldersWithTop.sort((a, b) -> Long.compare(b.count(), a.count()));
        }

        List<TopDocument> mostOpened = jdbc.query("""
                select d.document_id, d.title, t.type_name, v.original_filename, v.size_bytes, v.mime_type, d.created_at,
                       count(*) filter (where a.action = 'DOCUMENT_VIEWED') as views,
                       count(*) filter (where a.action = 'DOCUMENT_DOWNLOADED') as downloads
                from audit_events a
                join documents d on d.document_id = a.object_id
                left join document_types t on t.document_type_id = d.document_type_id
                left join document_versions v on v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                where""" + IN_SCOPE + """
                  and a.action in ('DOCUMENT_VIEWED', 'DOCUMENT_DOWNLOADED')
                  and (a.occurred_at at time zone :zone)::date >= :start and""" + VISIBLE + """
                group by d.document_id, d.title, t.type_name, v.original_filename, v.size_bytes, v.mime_type, d.created_at
                order by count(*) desc, d.title
                limit :most""", params.addValue("most", MOST_OPENED),
                (rs, i) -> new TopDocument(rs.getObject("document_id", UUID.class), rs.getString("title"),
                        rs.getString("type_name"), rs.getString("original_filename"),
                        (Long) rs.getObject("size_bytes"), rs.getString("mime_type"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("views"),
                        rs.getLong("downloads")));

        return new DashboardResponse(days, true, documents, storage, usage,
                series(start, today, beforeStart, addedPerDay, usagePerDay), types, formats, foldersWithTop,
                mostOpened, security, recent(tenantId), scope, activity);
    }

    static int period(int requested) {
        return PERIODS.contains(requested) ? requested : DEFAULT_DAYS;
    }

    static String formatOf(String extension) {
        return extension == null ? "other" : FORMATS.getOrDefault(extension.toLowerCase(Locale.ROOT), "other");
    }

    /**
     * Every day from {@code start} to {@code today}, oldest first, with what was added and used that
     * day and the running total of documents ({@code beforeStart} existed already).
     */
    static List<Day> series(LocalDate start, LocalDate today, long beforeStart, Map<LocalDate, Long> addedPerDay,
                            Map<LocalDate, long[]> usagePerDay) {
        List<Day> days = new ArrayList<>();
        long running = beforeStart;
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            long added = addedPerDay.getOrDefault(day, 0L);
            running += added;
            long[] usage = usagePerDay.getOrDefault(day, new long[3]);
            days.add(new Day(day, added, running, usage[0], usage[1], usage[2]));
        }
        return days;
    }

    /** This period's usage against the one before, from the per-day counts (views, downloads, uploads). */
    static Usage usage(Map<LocalDate, long[]> perDay, LocalDate start) {
        long[] now = new long[3];
        long[] before = new long[3];
        perDay.forEach((day, counts) -> {
            long[] into = day.isBefore(start) ? before : now;
            for (int i = 0; i < 3; i++) {
                into[i] += counts[i];
            }
        });
        return new Usage(now[0], now[1], now[2], before[0], before[1], before[2]);
    }

    /** Uploads checked this period, by outcome, and what's held in quarantine now. */
    private Security security(MapSqlParameterSource params) {
        return jdbc.queryForObject("""
                select count(*) filter (where s.status = 'clean') as clean,
                       count(*) filter (where s.status = 'quarantined') as threats,
                       count(*) filter (where s.status = 'unscannable') as unscannable,
                       count(*) filter (where s.status = 'failed') as refused,
                       (select count(*) from quarantine_records q
                        where q.tenant_id = :tenant and q.status = 'held' and not q.is_deleted) as held
                from scan_results s
                where s.tenant_id = :tenant and (s.created_at at time zone :zone)::date >= :start""", params,
                (rs, i) -> new Security(rs.getLong("clean"), rs.getLong("threats"), rs.getLong("unscannable"),
                        rs.getLong("refused"), rs.getLong("held")));
    }

    /** The newest documents, through the document browser's own listing (and its access rules). */
    private List<RecentDocument> recent(UUID tenantId) {
        List<DocumentListItem> items = documentService.listDocuments(null, true, null,
                PageRequest.of(0, RECENT_DOCUMENTS, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        Map<UUID, String> names = userNames(tenantId, items.stream().map(DocumentListItem::ownerUserId).toList());
        return items.stream()
                .map(d -> new RecentDocument(d.documentId(), d.title(), d.documentTypeName(), d.fileName(),
                        d.sizeBytes(), d.mimeType(), d.ownerUserId(), names.get(d.ownerUserId()), d.createdAt()))
                .toList();
    }

    private List<Activity> activity(MapSqlParameterSource scope) {
        MapSqlParameterSource params = new MapSqlParameterSource(scope.getValues())
                .addValue("actions", ACTIVITY_ACTIONS)
                .addValue("entries", ACTIVITY_ENTRIES);
        return jdbc.query("""
                select a.id, a.action, a.object_type, a.object_id, a.resource_name, a.actor_id,
                       coalesce(u.display_name, a.actor_display_name) as actor_name, a.occurred_at
                from audit_events a
                left join users u on u.user_id = a.actor_id and u.tenant_id = a.tenant_id
                where""" + IN_SCOPE + """
                  and a.action in (:actions)
                order by a.occurred_at desc
                limit :entries""", params,
                (rs, i) -> new Activity(rs.getObject("id", UUID.class), rs.getString("action"),
                        rs.getString("object_type"), rs.getObject("object_id", UUID.class),
                        rs.getString("resource_name"), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_name"), rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    private Map<UUID, String> userNames(UUID tenantId, Collection<UUID> userIds) {
        List<UUID> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.query("select user_id, display_name from users where tenant_id = :tenant and user_id in (:ids)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("ids", ids),
                rs -> {
                    names.put(rs.getObject("user_id", UUID.class), rs.getString("display_name"));
                });
        return names;
    }

    /** An IN list is never empty - the all-zero id matches nothing. */
    private static Collection<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }
}
