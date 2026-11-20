package com.oak.dms.reporting.run;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.ReportMetrics;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.RunRequest;
import com.oak.dms.reporting.dto.RunResponse;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.export.ReportExporter;
import com.oak.dms.reporting.repository.ReportRunRepository;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.template.ReportTemplateService;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Requesting, listing, cancelling, retrying and downloading report runs. Generation itself is {@link ReportRunWorker}'s.
 * <p>
 * Everything is the caller's own: a run that is someone else's - or does not exist - answers the same 404, so a run id tells nobody
 * anything. Tenant and user always come from the authenticated request, never from a body.
 */
@Service
public class ReportRunService {

    private static final EnumSet<ReportRunStatus> ACTIVE = EnumSet.of(ReportRunStatus.queued, ReportRunStatus.running);
    private static final int DEFAULT_HISTORY_DAYS = 30;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private final ReportRunRepository runs;
    private final ReportSettingsService settingsService;
    private final DocumentAccessService documentAccess;
    private final AuthorizationService authorization;
    private final DocumentReportDataSource dataSource;
    private final ReportRunStateMachine machine;
    private final ReportRunWorker worker;
    private final AuditService audit;
    private final ReportMetrics metrics;
    private final Map<String, ReportExporter> exporters;
    private final StorageLocationRepository locations;
    private final ObjectStorageServiceFactory storage;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportTemplateService templates;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ReportRunService(ReportRunRepository runs, ReportSettingsService settingsService,
                            DocumentAccessService documentAccess, AuthorizationService authorization,
                            DocumentReportDataSource dataSource, ReportRunStateMachine machine, ReportRunWorker worker,
                            AuditService audit, ReportMetrics metrics, List<ReportExporter> exporters,
                            StorageLocationRepository locations, ObjectStorageServiceFactory storage,
                            NamedParameterJdbcTemplate jdbc, ReportTemplateService templates) {
        this.templates = templates;
        this.runs = runs;
        this.settingsService = settingsService;
        this.documentAccess = documentAccess;
        this.authorization = authorization;
        this.dataSource = dataSource;
        this.machine = machine;
        this.worker = worker;
        this.audit = audit;
        this.metrics = metrics;
        this.exporters = exporters.stream().collect(Collectors.toMap(ReportExporter::format, Function.identity()));
        this.locations = locations;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    /** A downloadable file. The caller closes {@link #content()}. */
    public record Download(String filename, String contentType, long sizeBytes, InputStream content) {
    }

    // ---------------------------------------------------------------- request

    @Transactional
    public RunResponse request(RunRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
        requireDocumentAccess();

        if ((request.templateId() == null) == (request.definition() == null)) {
            throw ReportException.unprocessable("REPORT_RUN_INVALID", "Give either a template or a definition, not both and not neither.");
        }
        ReportDefinition definition;
        String title;
        if (request.templateId() != null) {
            // A saved template needs only the run permission: someone else built it, and it was checked when it was saved.
            ReportTemplateService.Resolved resolved = templates.resolveForRun(request.templateId(), request.filterOverrides());
            definition = resolved.definition();
            title = resolved.name();
        } else {
            if (request.filterOverrides() != null && !request.filterOverrides().isEmpty()) {
                throw ReportException.unprocessable("REPORT_RUN_INVALID", "Filter changes apply to a template, not to a definition you send.");
            }
            if (!authorization.hasPermission(PermissionCodes.CRG_BUILD)) {
                throw ReportException.forbidden("REPORT_BUILD_REQUIRED", "You need permission to build reports to run a report you have defined.");
            }
            definition = request.definition();
            title = "Custom report";
        }
        String format = request.format() == null || request.format().isBlank() ? settings.defaultFormat() : request.format();
        if (!settings.allows(format) || !exporters.containsKey(format)) {
            throw ReportException.invalid("REPORT_FORMAT_NOT_ALLOWED", "That file format is not available for your organisation.",
                    Map.of("format", "Choose one of: " + String.join(", ", settings.allowedFormats())));
        }

        ZoneId zone = ReportContext.zoneFor(definition.timeZone(), settings);
        // Refuse a definition that cannot run now, in the request, rather than as a failed run a minute later.
        dataSource.plan(definition, new ReportContext(tenantId, userId, documentAccess.forCaller(), settings, zone,
                java.time.Instant.now()));
        requireRoom(tenantId, userId, settings);

        return queue(tenantId, userId, request.templateId(), definition, zone, title, format, ReportRun.TRIGGER_MANUAL);
    }

    /** A new run of the same snapshot for a failed one: current data, current access. */
    @Transactional
    public RunResponse retry(UUID runId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
        requireDocumentAccess();
        ReportRun original = ownRun(runId);
        if (original.getStatus() != ReportRunStatus.failed || !userId.equals(original.getRequestedBy())) {
            throw ReportException.conflict("REPORT_RUN_NOT_RETRYABLE", "Only your own failed runs can be retried.");
        }
        if (!settings.allows(original.getFormat())) {
            throw ReportException.invalid("REPORT_FORMAT_NOT_ALLOWED", "That file format is not available for your organisation.",
                    Map.of("format", "Choose one of: " + String.join(", ", settings.allowedFormats())));
        }
        requireRoom(tenantId, userId, settings);
        RunSnapshot snapshot = RunSnapshot.from(original.getDefinitionSnapshot());
        return queue(tenantId, userId, original.getTemplateId(), snapshot.definition(), ZoneId.of(snapshot.zone()),
                snapshot.title() == null ? "Custom report" : snapshot.title(), original.getFormat(), ReportRun.TRIGGER_RETRY);
    }

    private RunResponse queue(UUID tenantId, UUID userId, UUID templateId, ReportDefinition definition, ZoneId zone, String title,
                              String format, String trigger) {
        ReportRun run = runs.save(ReportRun.builder()
                .tenantId(tenantId)
                .templateId(templateId)
                .requestedBy(userId)
                .triggerType(trigger)
                .format(format)
                .definitionSnapshot(new RunSnapshot(definition, zone.getId(), title).toMap())
                .correlationId(UUID.randomUUID())
                .build());

        // Counts and flags only: never a filter value, a row, or an address.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", format);
        details.put("trigger", trigger);
        details.put("filterCount", definition.filtersOrEmpty().size());
        details.put("columnCount", definition.columnsOrEmpty().size());
        details.put("grouped", definition.groupBy() != null && !definition.groupBy().isBlank());
        audit.event(AuditActionCatalog.REPORT_RUN_REQUESTED)
                .objectType(AuditObjectTypes.REPORT_RUN)
                .objectId(run.getRunId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(details)
                .log();

        UUID runId = run.getRunId();
        // Only once the row is committed: the worker reads it from another thread.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.start(runId);
            }
        });
        return RunResponse.from(run);
    }

    /** Interactive requests are refused (429) rather than queued without limit; scheduled runs wait instead. */
    private void requireRoom(UUID tenantId, UUID userId, EffectiveReportSettings settings) {
        if (runs.countByTenantIdAndRequestedByAndStatusIn(tenantId, userId, ACTIVE) >= settings.maxQueuedPerUser()) {
            metrics.refused("rate_limited");
            throw ReportException.rateLimited("You already have " + settings.maxQueuedPerUser()
                    + " reports waiting or running. Wait for one to finish, or cancel one.");
        }
        if (runs.countByTenantIdAndStatus(tenantId, ReportRunStatus.running) >= settings.maxConcurrentRuns()) {
            metrics.refused("rate_limited");
            throw ReportException.rateLimited("Your organisation is generating as many reports as it can at once. Try again in a moment.");
        }
    }

    private void requireDocumentAccess() {
        if (!authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            throw ReportException.forbidden("REPORT_DOCUMENT_ACCESS_REQUIRED", "You need access to documents to run a report on them.");
        }
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public Page<RunResponse> listMine(ReportRunStatus status, UUID templateId, OffsetDateTime from, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        OffsetDateTime since = from != null ? from : OffsetDateTime.now().minusDays(DEFAULT_HISTORY_DAYS);
        Specification<ReportRun> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("tenantId"), tenantId));
            // A run made FOR someone is theirs alone: the schedule's owner is its "requester" but its data was scoped to the
            // recipient's access, so the owner must not be able to open it.
            all.add(cb.or(
                    cb.and(cb.notEqual(root.get("deliveryScope"), ReportRun.SCOPE_RECIPIENT), cb.equal(root.get("requestedBy"), userId)),
                    cb.and(cb.equal(root.get("deliveryScope"), ReportRun.SCOPE_RECIPIENT), cb.equal(root.get("forUserId"), userId))));
            all.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (templateId != null) {
                all.add(cb.equal(root.get("templateId"), templateId));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
        return runs.findAll(spec, pageable).map(RunResponse::from);
    }

    @Transactional(readOnly = true)
    public RunResponse get(UUID runId) {
        return RunResponse.from(ownRun(runId));
    }

    // ---------------------------------------------------------------- cancel

    @Transactional
    public RunResponse cancel(UUID runId) {
        ReportRun run = ownRun(runId);
        if (!TenantContext.getUserId().equals(run.getRequestedBy()) || !machine.canCancel(run.getStatus())) {
            throw ReportException.conflict("REPORT_RUN_STATE_INVALID", "This run can no longer be cancelled.");
        }
        // Guarded: if it finished a moment ago, nothing changes and the caller is told.
        int updated = jdbc.update("""
                update report_runs set status = 'cancelled', finished_at = now()
                where run_id = :id and tenant_id = :tenant and status in ('queued', 'running')""",
                new MapSqlParameterSource("id", runId).addValue("tenant", run.getTenantId()));
        if (updated == 0) {
            throw ReportException.conflict("REPORT_RUN_STATE_INVALID", "This run can no longer be cancelled.");
        }
        entityManager.refresh(run); // the update above bypassed the entity: read the new status back
        return RunResponse.from(run);
    }

    // ---------------------------------------------------------------- download

    /**
     * The file, if the caller may still have it: it is theirs (or made for them), reports are still on for their organisation,
     * it has not expired, and it has a file at all. The controller has already checked the run and document permissions, which a
     * download needs every time, not only when the run was made.
     */
    @Transactional
    public Download download(UUID runId) {
        UUID tenantId = TenantContext.getTenantId();
        settingsService.requireEnabled(tenantId);
        ReportRun run = ownRun(runId);
        if (run.getStatus() == ReportRunStatus.expired
                || (run.getExpiresAt() != null && run.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            throw ReportException.gone("REPORT_EXPIRED", "This report has expired and its file was removed. Run it again.");
        }
        if (run.getStatus() != ReportRunStatus.completed) {
            throw ReportException.conflict("REPORT_NOT_READY", "This report isn't ready to download yet.");
        }
        if (run.getFileObjectKey() == null) {
            throw ReportException.notFound("REPORT_NO_FILE", "This report found no data, so there is no file.");
        }
        StorageLocation location = locations.findByStorageLocationIdAndDeletedFalse(run.getStorageLocationId())
                .orElseThrow(() -> ReportException.notFound("REPORT_RUN_NOT_FOUND", "That report was not found."));
        DownloadedObject object = storage.resolve(location).download(location, run.getFileObjectKey());

        audit.event(AuditActionCatalog.REPORT_DOWNLOADED)
                .objectType(AuditObjectTypes.REPORT_RUN)
                .objectId(runId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("format", run.getFormat(),
                        "fileSizeBytes", run.getFileSizeBytes() == null ? object.sizeBytes() : run.getFileSizeBytes()))
                .log();
        ReportExporter exporter = exporters.get(run.getFormat());
        long size = run.getFileSizeBytes() == null ? object.sizeBytes() : run.getFileSizeBytes();
        return new Download(filename(run, exporter.extension()), exporter.contentType(), size, object.content());
    }

    /** From the title, with anything unsafe in a header or a file name removed (letters of any script are kept). */
    public static String filename(ReportRun run, String extension) {
        String title = run.getDefinitionSnapshot() != null && run.getDefinitionSnapshot().get("title") instanceof String t ? t : "report";
        String safe = title.replaceAll("[^\\p{L}\\p{N}._ -]", "").strip().replaceAll("\\s+", "-");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        if (safe.isBlank() || safe.startsWith(".")) {
            safe = "report";
        }
        OffsetDateTime when = run.getFinishedAt() != null ? run.getFinishedAt() : run.getCreatedAt();
        return safe + "-" + FILE_STAMP.format(when) + "." + extension;
    }

    // ---------------------------------------------------------------- lookup

    /**
     * The run if it is the caller's; otherwise the same 404 as a run that does not exist. A run made for a schedule's recipient belongs
     * to that recipient only - not to the schedule's owner who "requested" it - because its rows were chosen by the recipient's access.
     */
    private ReportRun ownRun(UUID runId) {
        UUID userId = TenantContext.getUserId();
        return runs.findByRunIdAndTenantId(runId, TenantContext.getTenantId())
                .filter(r -> ReportRun.SCOPE_RECIPIENT.equals(r.getDeliveryScope())
                        ? userId.equals(r.getForUserId()) : userId.equals(r.getRequestedBy()))
                .orElseThrow(() -> ReportException.notFound("REPORT_RUN_NOT_FOUND", "That report was not found."));
    }
}
