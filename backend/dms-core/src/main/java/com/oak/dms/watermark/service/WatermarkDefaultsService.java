package com.oak.dms.watermark.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.DocumentContentReader.VersionDetails;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.config.WatermarkAvailability;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkDefaultsRequest;
import com.oak.dms.watermark.dto.WatermarkDefaultsResponse;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.entity.TenantWatermarkSettings;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.WatermarkFormatService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkServiceFactory;
import com.oak.dms.watermark.repository.TenantWatermarkSettingsRepository;
import com.oak.dms.watermark.repository.WatermarkTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Whether this tenant's plain "Download" action, and separately whether a
 * bulk/ZIP export's files, should quietly stamp a watermark onto the file
 * before it is streamed - and which template to use. Neither is the async
 * "Add watermark" copy, which is unaffected by any of this.
 *
 * <h2>Two different jobs, one class</h2>
 * {@link #get} / {@link #update} are the admin-facing policy CRUD, backing
 * {@code GET}/{@code PUT /api/v1/watermark-settings}. {@link #applyIfConfigured}
 * is the plain-download hook, called from
 * {@code DocumentServiceImpl#downloadDocument} after the original bytes are
 * read; {@link #applyForBulkExportIfConfigured} is the equivalent hook called
 * from {@code ExportArchiveBuilder} per file as a ZIP export is built. Both
 * never fail the caller - any reason they can't watermark (feature off, no
 * template configured, an unsupported or oversized file, fonts unavailable, a
 * drawing error) is swallowed and the caller gets the original bytes back
 * exactly as it would have without this feature.
 *
 * <h2>Deliberately not the job pipeline</h2>
 * This never creates a {@code watermark_jobs} row and never touches
 * {@link com.oak.dms.watermark.job.WatermarkJobQueue}. The drawing itself
 * reuses {@link WatermarkServiceFactory}/{@link WatermarkFormatService} -
 * the same code {@code WatermarkJobWorker} calls - but runs synchronously,
 * inline, on the request thread, because this is a default rendering of the
 * SAME download rather than a new copy somebody asked for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkDefaultsService {

    private final TenantWatermarkSettingsRepository settingsRepository;
    private final WatermarkTemplateRepository templateRepository;
    private final WatermarkTemplateService templateService;
    private final WatermarkServiceFactory formatServices;
    private final WatermarkAvailability availability;
    private final WatermarkProperties properties;
    private final PlaceholderResolver placeholderResolver;
    private final AuditService auditService;

    // ------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public WatermarkDefaultsResponse get() {
        return settingsRepository.findById(TenantContext.getTenantId())
                .map(this::toResponse)
                .orElse(WatermarkDefaultsResponse.OFF);
    }

    private WatermarkDefaultsResponse toResponse(TenantWatermarkSettings settings) {
        if (settings.getDefaultTemplateId() == null) {
            return new WatermarkDefaultsResponse(settings.isDownloadWatermarkEnabled(),
                    settings.isBulkExportWatermarkEnabled(), null, null);
        }
        // The template may since have been retired or (in theory) belong to
        // another tenant's now-stale row; either way, show what is stored
        // rather than fail the settings screen over it.
        String label = templateRepository.findVisible(settings.getDefaultTemplateId(), settings.getTenantId())
                .map(WatermarkTemplate::getLabel)
                .orElse(null);
        return new WatermarkDefaultsResponse(settings.isDownloadWatermarkEnabled(),
                settings.isBulkExportWatermarkEnabled(), settings.getDefaultTemplateId(), label);
    }

    /**
     * @throws WatermarkException 400 if {@code enabled} or {@code bulkExportEnabled}
     *                            is true with no template named, surfaced through
     *                            {@link WatermarkTemplateService#requireUsable}
     *                            if that template isn't one this tenant may
     *                            use or isn't active
     */
    @Transactional
    public WatermarkDefaultsResponse update(WatermarkDefaultsRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        WatermarkTemplate template = null;
        if (request.enabled() || request.bulkExportEnabled()) {
            if (request.templateId() == null) {
                throw WatermarkException.badRequest("WATERMARK_DEFAULTS_TEMPLATE_REQUIRED",
                        "Choose a watermark template to apply by default.");
            }
            // Reuses the exact check a watermark request already makes: this
            // tenant's own template, or a system one, and still active.
            template = templateService.requireUsable(request.templateId());
        }

        TenantWatermarkSettings settings = settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantWatermarkSettings.builder().tenantId(tenantId).build());
        Map<String, Object> before = snapshot(settings);
        settings.setDownloadWatermarkEnabled(request.enabled());
        settings.setBulkExportWatermarkEnabled(request.bulkExportEnabled());
        settings.setDefaultTemplateId(template == null ? null : template.getTemplateId());
        settings.setUpdatedBy(TenantContext.getUserId());
        settings.setUpdatedAt(OffsetDateTime.now());
        TenantWatermarkSettings saved = settingsRepository.save(settings);

        auditService.event(AuditActionCatalog.WATERMARK_DEFAULTS_UPDATED)
                .objectType(AuditObjectTypes.TENANT_WATERMARK_SETTINGS)
                .objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(snapshot(saved))
                .log();

        return toResponse(saved);
    }

    private static Map<String, Object> snapshot(TenantWatermarkSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("downloadWatermarkEnabled", settings.isDownloadWatermarkEnabled());
        values.put("bulkExportWatermarkEnabled", settings.isBulkExportWatermarkEnabled());
        values.put("defaultTemplateId", settings.getDefaultTemplateId());
        return values;
    }

    // --------------------------------------------------------- the download hooks

    /**
     * Watermarks {@code original} with this tenant's configured default
     * template, or returns it unchanged - never throws. Called only for a
     * plain, non-preview download; see the class comment.
     *
     * @param original the document's stored bytes, as read for the download
     * @param version  what {@code DocumentServiceImpl} already knows about
     *                 the version being served, reused here only to resolve
     *                 watermark placeholders ({@code {documentId}}, etc.)
     */
    @Transactional(readOnly = true)
    public byte[] applyIfConfigured(byte[] original, VersionDetails version) {
        return applyIfConfigured(original, version, TenantWatermarkSettings::isDownloadWatermarkEnabled,
                "the default download watermark");
    }

    /**
     * The bulk-export equivalent of {@link #applyIfConfigured}, called once
     * per file as {@code ExportArchiveBuilder} assembles a ZIP - same
     * template, same fallback-to-original behaviour, gated by
     * {@link TenantWatermarkSettings#isBulkExportWatermarkEnabled()} instead
     * of the plain-download flag.
     */
    @Transactional(readOnly = true)
    public byte[] applyForBulkExportIfConfigured(byte[] original, VersionDetails version) {
        return applyIfConfigured(original, version, TenantWatermarkSettings::isBulkExportWatermarkEnabled,
                "the default bulk-export watermark");
    }

    /**
     * Whether this tenant currently has bulk-export watermarking switched on,
     * so {@code ExportArchiveBuilder} can skip reading a file fully into
     * memory for the common case where it won't be touched.
     */
    @Transactional(readOnly = true)
    public boolean isBulkExportWatermarkEnabled() {
        return availability.isAvailable() && settingsRepository.findById(TenantContext.getTenantId())
                .map(s -> s.isBulkExportWatermarkEnabled() && s.getDefaultTemplateId() != null)
                .orElse(false);
    }

    private byte[] applyIfConfigured(byte[] original, VersionDetails version,
                                      Predicate<TenantWatermarkSettings> enabled, String what) {
        try {
            return tryApply(original, version, enabled).orElse(original);
        } catch (RuntimeException e) {
            // Belt and braces on top of tryApply's own narrower catches: this
            // must never fail the caller.
            log.warn("Could not apply {} to document {} - serving the original", what, version.documentId(), e);
            return original;
        }
    }

    private Optional<byte[]> tryApply(byte[] original, VersionDetails version,
                                       Predicate<TenantWatermarkSettings> enabled) {
        if (!availability.isAvailable()) {
            return Optional.empty();
        }
        TenantWatermarkSettings settings = settingsRepository.findById(TenantContext.getTenantId()).orElse(null);
        if (settings == null || !enabled.test(settings) || settings.getDefaultTemplateId() == null) {
            return Optional.empty();
        }
        if (!formatServices.isSupported(version.mimeType())) {
            return Optional.empty();
        }
        if (version.sizeBytes() > maxBytesFor(version.mimeType()).toBytes()) {
            log.info("Document {} is over the inline-watermark size cap for {} - serving the original",
                    version.documentId(), version.mimeType());
            return Optional.empty();
        }

        WatermarkTemplate template = templateRepository
                .findVisible(settings.getDefaultTemplateId(), TenantContext.getTenantId())
                .filter(WatermarkTemplate::isActive)
                .orElse(null);
        if (template == null) {
            log.info("Tenant {}'s default download-watermark template is missing or retired - "
                    + "serving the original", TenantContext.getTenantId());
            return Optional.empty();
        }

        WatermarkSpec spec = buildSpec(template, version);
        WatermarkFormatService service = formatServices.forMimeType(version.mimeType());
        WatermarkOptions options = new WatermarkOptions(spec, version.mimeType(), version.originalFilename());
        try (InputStream input = new ByteArrayInputStream(original)) {
            return Optional.of(service.watermark(input, options));
        } catch (Exception e) {
            log.warn("Document {} could not be watermarked for download - serving the original",
                    version.documentId(), e);
            return Optional.empty();
        }
    }

    private DataSize maxBytesFor(String mimeType) {
        String key = switch (mimeType == null ? "" : mimeType) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "image/jpeg", "image/png" -> "image";
            default -> "";
        };
        return properties.getMaxBytes().getOrDefault(key, properties.getDefaultMaxBytes());
    }

    /**
     * Template style, falling back to the system default - there is no
     * per-request override here, unlike {@code WatermarkService#buildSpec},
     * because nobody is asking; this is the tenant's standing policy.
     */
    private WatermarkSpec buildSpec(WatermarkTemplate template, VersionDetails version) {
        UUID jobId = UUID.randomUUID();
        String text = placeholderResolver.resolve(template.getDefaultText(), new PlaceholderResolver.Context(
                jobId, version, OffsetDateTime.now(), placeholderResolver.effectiveUser()));

        WatermarkProperties.Style defaults = properties.getStyle();
        return new WatermarkSpec(
                text,
                template.getFontSize() != null ? template.getFontSize() : defaults.getFontSize(),
                template.getOpacity() != null ? template.getOpacity() : defaults.getOpacity(),
                template.getRotation() != null ? template.getRotation() : defaults.getRotation(),
                template.getColor() != null ? template.getColor() : defaults.getColor().toLowerCase(Locale.ROOT),
                template.getPlacement() != null ? template.getPlacement()
                        : WatermarkPlacement.fromValue(defaults.getPlacement()),
                template.getPageScope() != null ? template.getPageScope()
                        : WatermarkPageScope.fromValue(defaults.getPageScope()));
    }
}
