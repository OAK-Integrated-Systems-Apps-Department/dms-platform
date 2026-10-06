package com.oak.dms.dashboard;

import com.oak.dms.dashboard.DigitalImpactResponse.Coverage;
import com.oak.dms.dashboard.DigitalImpactResponse.Day;
import com.oak.dms.dashboard.DigitalImpactResponse.PageSource;
import com.oak.dms.dashboard.DigitalImpactResponse.TopDocument;
import com.oak.dms.dashboard.DigitalImpactResponse.Method;
import com.oak.dms.dashboard.DigitalImpactResponse.Totals;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dashboard's Digital Impact: the pages of documents processed digitally in a period - read
 * from the files themselves (V71, PageCountBackfill), never guessed from their size - turned into
 * the paper they'd have taken and that paper's estimated footprint, with the configured factors
 * ({@link ImpactProperties}). A page is "processed" when a version holding it is uploaded; pages
 * of documents since deleted aren't counted, so the figures can only understate. Documents are
 * those the caller can see (the same rules as the dashboard's other figures).
 */
@Service
@RequiredArgsConstructor
public class DigitalImpactService {

    /** The longest period one request may cover. */
    static final int MAX_DAYS = 366;
    static final int TOP_DOCUMENTS = 5;

    /** Which kind of file an extension is - the paged formats PageCounter reads. */
    static String kindOf(String extension) {
        if (extension == null) {
            return "other";
        }
        return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
            case "pdf" -> "pdf";
            case "doc", "docx", "odt" -> "word";
            case "ppt", "pptx", "odp" -> "slides";
            case "tif", "tiff" -> "scans";
            default -> "other";
        };
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentAccessService documentAccessService;
    private final AuthorizationService authorization;
    private final ImpactProperties properties;

    /** The period: {@code from}-{@code to} when both are given, otherwise the last {@code days} days. */
    record Period(LocalDate from, LocalDate to) {
        int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    static Period period(ZoneId zone, int days, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(zone);
        if (from != null || to != null) {
            if (from == null || to == null) {
                throw invalid("Choose both a start and an end date.");
            }
            if (from.isAfter(to)) {
                throw invalid("The start date must be on or before the end date.");
            }
            if (to.isAfter(today)) {
                throw invalid("The end date can't be in the future.");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw invalid("Choose a period of at most " + MAX_DAYS + " days.");
            }
            return new Period(from, to);
        }
        return new Period(today.minusDays(DashboardService.period(days) - 1L), today);
    }

    private static FolderException invalid(String message) {
        return new FolderException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", message);
    }

    @Transactional(readOnly = true)
    public DigitalImpactResponse forCaller(ZoneId zone, int days, LocalDate from, LocalDate to) {
        Period period = period(zone, days, from, to);
        Period previous = new Period(period.from().minusDays(period.days()), period.from().minusDays(1));
        UUID tenantId = TenantContext.getTenantId();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("zone", zone.getId())
                .addValue("from", period.from())
                .addValue("to", period.to())
                .addValue("previousFrom", previous.from())
                .addValue("previousTo", previous.to());

        Map<LocalDate, Long> pagesPerDay = new HashMap<>();
        long pagesBefore = 0;
        long allTimePages = 0;
        Coverage coverage = new Coverage(0, 0, 0, 0, 0, 0, 0);
        List<PageSource> sources = List.of();
        List<TopDocument> topDocuments = List.of();

        if (authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            DocumentAccessView access = documentAccessService.forCaller();
            params.addValue("everything", access.seesEverything())
                    .addValue("hidden", DashboardService.nonEmpty(access.folders().hiddenFolderIds()))
                    .addValue("granted", access.grantedIds());
            String versionsOf = """
                    from document_versions v join documents d on d.document_id = v.document_id
                    where not v.is_deleted and v.purged_at is null and""" + DashboardService.VISIBLE;

            jdbc.query("select (v.created_at at time zone :zone)::date as day, coalesce(sum(v.page_count), 0) as pages "
                    + versionsOf + " and (v.created_at at time zone :zone)::date between :from and :to group by 1",
                    params, rs -> {
                        pagesPerDay.put(rs.getObject("day", LocalDate.class), rs.getLong("pages"));
                    });
            pagesBefore = jdbc.queryForObject("select coalesce(sum(v.page_count), 0) " + versionsOf
                    + " and (v.created_at at time zone :zone)::date between :previousFrom and :previousTo",
                    params, Long.class);
            coverage = jdbc.queryForObject("""
                    select count(*) as versions,
                           count(*) filter (where v.page_count is not null) as counted,
                           count(*) filter (where v.page_count_source = 'counted') as from_file,
                           count(*) filter (where v.page_count_source = 'reported') as reported,
                           count(*) filter (where v.pages_counted_at is not null and v.page_count is null) as no_pages,
                           count(*) filter (where v.pages_counted_at is null
                                            and v.scan_status in ('clean', 'overridden')) as pending,
                           count(*) filter (where v.pages_counted_at is null
                                            and v.scan_status not in ('clean', 'overridden')) as unscanned
                    """ + versionsOf + " and (v.created_at at time zone :zone)::date between :from and :to",
                    params, (rs, i) -> new Coverage(rs.getLong("versions"), rs.getLong("counted"),
                            rs.getLong("from_file"), rs.getLong("reported"), rs.getLong("no_pages"),
                            rs.getLong("pending"), rs.getLong("unscanned")));
            allTimePages = jdbc.queryForObject("select coalesce(sum(v.page_count), 0) " + versionsOf,
                    params, Long.class);

            // Where the period's pages came from, by kind of file.
            Map<String, long[]> byKind = new LinkedHashMap<>();
            jdbc.query("select lower(substring(v.original_filename from '\\.([^.]+)$')) as ext,"
                            + " sum(v.page_count) as pages, count(*) as files " + versionsOf
                            + " and v.page_count is not null"
                            + " and (v.created_at at time zone :zone)::date between :from and :to group by 1",
                    params, rs -> {
                        long[] sum = byKind.computeIfAbsent(kindOf(rs.getString("ext")), k -> new long[2]);
                        sum[0] += rs.getLong("pages");
                        sum[1] += rs.getLong("files");
                    });
            sources = byKind.entrySet().stream()
                    .map(e -> new PageSource(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .sorted((a, b) -> Long.compare(b.pages(), a.pages()))
                    .toList();

            // The documents whose uploads in the period held the most pages.
            topDocuments = jdbc.query("""
                    select d.document_id, d.title, t.type_name, cv.original_filename, cv.size_bytes, cv.mime_type,
                           d.created_at, sum(v.page_count) as pages
                    from document_versions v join documents d on d.document_id = v.document_id
                    left join document_types t on t.document_type_id = d.document_type_id
                    left join document_versions cv on cv.version_id = coalesce(d.current_version_id, d.latest_version_id)
                    where not v.is_deleted and v.purged_at is null and v.page_count is not null
                      and (v.created_at at time zone :zone)::date between :from and :to and""" + DashboardService.VISIBLE + """
                    group by d.document_id, d.title, t.type_name, cv.original_filename, cv.size_bytes, cv.mime_type,
                             d.created_at
                    order by pages desc, d.title
                    limit :top""", params.addValue("top", TOP_DOCUMENTS),
                    (rs, i) -> new TopDocument(rs.getObject("document_id", UUID.class), rs.getString("title"),
                            rs.getString("type_name"), rs.getString("original_filename"),
                            (Long) rs.getObject("size_bytes"), rs.getString("mime_type"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getLong("pages")));
        }

        long pages = pagesPerDay.values().stream().mapToLong(Long::longValue).sum();
        return new DigitalImpactResponse(period.from(), period.to(), period.days(), totals(pages, properties),
                totals(pagesBefore, properties), totals(allTimePages, properties), coverage, sources, topDocuments,
                daily(period, pagesPerDay, properties), method(properties));
    }

    /** Pages, and what they stand for under the configured assumptions. */
    static Totals totals(long pages, ImpactProperties p) {
        double sheets = (double) pages / Math.max(1, p.getPagesPerSheet());
        double paperKg = pages * p.gramsPerPage() / 1000;
        return new Totals(pages, sheets, paperKg, paperKg * p.getCo2eKgPerKgPaper(),
                paperKg * p.getWaterLitresPerKgPaper());
    }

    /** Every day of the period, oldest first, zeros included. */
    static List<Day> daily(Period period, Map<LocalDate, Long> pagesPerDay, ImpactProperties p) {
        List<Day> days = new ArrayList<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
            Totals t = totals(pagesPerDay.getOrDefault(day, 0L), p);
            days.add(new Day(day, t.pages(), t.paperKg(), t.co2eKg()));
        }
        return days;
    }

    static Method method(ImpactProperties p) {
        return new Method(p.getCalculationVersion(), p.getPaperSize(), p.getPaperWidthMm(), p.getPaperHeightMm(),
                p.getPaperGsm(), p.getPagesPerSheet(), p.gramsPerPage(), p.getCo2eKgPerKgPaper(), p.getCo2eSource(),
                p.getWaterLitresPerKgPaper(), p.getWaterSource());
    }
}
