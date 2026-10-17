package com.oak.dms.watermark.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.dto.WatermarkTemplateRequest;
import com.oak.dms.watermark.dto.WatermarkTemplateResponse;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.repository.WatermarkTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tenant's watermark templates, plus the four read-only system ones
 * (module DW, {@code dw.configure-watermark-policy.*}).
 *
 * <h2>The two rules that matter</h2>
 * <b>A tenant only ever sees its own templates and the system ones</b>, and
 * <b>a system template is read-only to everybody</b>. Both are enforced
 * here, in service code, and deliberately not as permissions: TENANT_ADMIN
 * bypasses every {@code @PreAuthorize} check through
 * {@code AuthorizationService#hasFullAccess}, so a rule expressed as a
 * permission would be no rule at all for exactly the role most likely to
 * try it. The edit paths load through
 * {@link WatermarkTemplateRepository#findByTemplateIdAndTenantIdAndDeletedFalse},
 * which a system template (tenant_id null) can never match, so the usual
 * failure is a 404 before any check runs; {@link #requireEditable} is the
 * belt to that braces, and turns the one case where the id IS visible to the
 * caller into a 403 that explains itself.
 *
 * <h2>Changes never reach a job that already exists</h2>
 * A job snapshots its resolved text and style when it is requested, so
 * editing - or deleting - a template changes what the NEXT job will say and
 * nothing else. That is why delete is a soft delete and why
 * {@code watermark_jobs.template_id} has no cascade: the provenance of a
 * copy somebody is already holding has to survive the template being
 * retired.
 * <p>
 * Needs {@link TenantContext}.
 */
@Service
@RequiredArgsConstructor
public class WatermarkTemplateService {

    private final WatermarkTemplateRepository repository;
    private final WatermarkStyleValidator styleValidator;
    private final AuditService auditService;

    // ------------------------------------------------------------- reads

    /**
     * Everything the caller may use: their tenant's templates and the
     * system ones, system first and then by label.
     *
     * @param includeInactive admins listing what they have retired; false
     *                        for the ordinary "pick a watermark" list
     */
    @Transactional(readOnly = true)
    public List<WatermarkTemplateResponse> list(boolean includeInactive) {
        return repository.findVisibleForTenant(TenantContext.getTenantId(), includeInactive).stream()
                .map(WatermarkTemplateResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WatermarkTemplateResponse get(UUID templateId) {
        return WatermarkTemplateResponse.from(requireVisible(templateId));
    }

    /**
     * One template the caller may USE in a watermark request - their own or
     * a system one, and still active. Returns the entity rather than the
     * response DTO because the caller needs its style and its {@code version}
     * to snapshot onto the job.
     */
    @Transactional(readOnly = true)
    public WatermarkTemplate requireUsable(UUID templateId) {
        WatermarkTemplate template = requireVisible(templateId);
        if (!template.isActive()) {
            throw WatermarkException.badRequest("WATERMARK_TEMPLATE_INACTIVE",
                    "The template '" + template.getLabel() + "' has been retired - choose another one.");
        }
        return template;
    }

    /**
     * One template the caller may SEE, for the preview endpoint.
     * <p>
     * Unlike {@link #requireUsable} this does not insist the template is
     * active: an admin who has just retired a template should still be able
     * to look at it on the settings screen. Previewing creates nothing, so
     * there is nothing to protect against here beyond tenancy.
     */
    @Transactional(readOnly = true)
    public WatermarkTemplate requireVisibleForPreview(UUID templateId) {
        return requireVisible(templateId);
    }

    // ------------------------------------------------------------ writes

    @Transactional
    public WatermarkTemplateResponse create(WatermarkTemplateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        String label = request.label().trim();
        requireLabelFree(tenantId, label);

        WatermarkTemplate template = WatermarkTemplate.builder()
                .tenantId(tenantId)
                .label(label)
                .defaultText(request.defaultText().trim())
                .fontSize(styleValidator.fontSize(request.fontSize()))
                .opacity(styleValidator.opacity(request.opacity()))
                .rotation(styleValidator.rotation(request.rotation()))
                .color(styleValidator.color(request.color()))
                .placement(styleValidator.placement(request.placement()))
                .pageScope(styleValidator.pageScope(request.pageScope()))
                .systemDefault(false)
                .active(request.active() == null || request.active())
                .createdBy(TenantContext.getUserId())
                .updatedBy(TenantContext.getUserId())
                .build();

        WatermarkTemplate saved = save(template, label);
        audit(AuditActionCatalog.WATERMARK_TEMPLATE_CREATED, saved, null);
        return WatermarkTemplateResponse.from(saved);
    }

    /**
     * A PUT: the body is the whole template, so a style field left out is
     * cleared back to the system default rather than kept. This is also how
     * a template is activated and deactivated - there is no separate
     * endpoint for it, because "retire this template" is an edit like any
     * other and deserves the same before/after audit entry.
     */
    @Transactional
    public WatermarkTemplateResponse update(UUID templateId, WatermarkTemplateRequest request) {
        WatermarkTemplate template = requireEditable(templateId);
        String label = request.label().trim();
        if (!label.equalsIgnoreCase(template.getLabel())) {
            requireLabelFree(template.getTenantId(), label);
        }

        Map<String, Object> before = snapshot(template);
        template.setLabel(label);
        template.setDefaultText(request.defaultText().trim());
        template.setFontSize(styleValidator.fontSize(request.fontSize()));
        template.setOpacity(styleValidator.opacity(request.opacity()));
        template.setRotation(styleValidator.rotation(request.rotation()));
        template.setColor(styleValidator.color(request.color()));
        template.setPlacement(styleValidator.placement(request.placement()));
        template.setPageScope(styleValidator.pageScope(request.pageScope()));
        if (request.active() != null) {
            template.setActive(request.active());
        }
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());

        WatermarkTemplate saved = save(template, label);
        audit(AuditActionCatalog.WATERMARK_TEMPLATE_UPDATED, saved, before);
        return WatermarkTemplateResponse.from(saved);
    }

    /**
     * Soft delete, gated by the UPDATE permission - the catalogue seeded in
     * V10 has no delete-template code, and inventing one would mean every
     * existing admin role had to be granted it by hand before they could
     * carry on working. Anyone who can rewrite a template's text can already
     * change what it says to anything at all, so being able to remove it
     * grants nothing further.
     */
    @Transactional
    public void delete(UUID templateId) {
        WatermarkTemplate template = requireEditable(templateId);
        Map<String, Object> before = snapshot(template);
        template.setDeleted(true);
        template.setDeletedAt(OffsetDateTime.now());
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
        repository.save(template);
        audit(AuditActionCatalog.WATERMARK_TEMPLATE_DELETED, template, before);
    }

    // ----------------------------------------------------------- helpers

    /** The caller's tenant's template, or a system one. Anything else is a 404, as if it didn't exist. */
    private WatermarkTemplate requireVisible(UUID templateId) {
        return repository.findVisible(templateId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("WatermarkTemplate", templateId));
    }

    /**
     * The caller's tenant's own template. Another tenant's is a 404; a
     * system template is a 403 that says why, because the caller can see
     * that id in their own list and pretending it doesn't exist would only
     * be confusing.
     */
    private WatermarkTemplate requireEditable(UUID templateId) {
        UUID tenantId = TenantContext.getTenantId();
        return repository.findByTemplateIdAndTenantIdAndDeletedFalse(templateId, tenantId)
                .orElseThrow(() -> repository.findVisible(templateId, tenantId)
                        .filter(WatermarkTemplate::isSystemDefault)
                        .<RuntimeException>map(system -> WatermarkException.forbidden("WATERMARK_TEMPLATE_READ_ONLY",
                                "'" + system.getLabel() + "' is a built-in template and can't be changed."
                                        + " Create your own to use different wording."))
                        .orElseGet(() -> new ResourceNotFoundException("WatermarkTemplate", templateId)));
    }

    private void requireLabelFree(UUID tenantId, String label) {
        if (repository.existsByTenantIdAndLabelIgnoreCaseAndDeletedFalse(tenantId, label)) {
            throw labelTaken(label);
        }
    }

    /**
     * {@link #requireLabelFree} loses a race between two admins saving the
     * same name at once; ux_watermark_templates_label is what actually
     * guarantees uniqueness, so its violation is turned back into the same
     * sentence rather than surfacing as a generic conflict.
     */
    private WatermarkTemplate save(WatermarkTemplate template, String label) {
        try {
            return repository.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            throw labelTaken(label);
        }
    }

    private static WatermarkException labelTaken(String label) {
        return WatermarkException.conflict("WATERMARK_TEMPLATE_LABEL_TAKEN",
                "A watermark template called '" + label + "' already exists. Choose another name.");
    }

    private static Map<String, Object> snapshot(WatermarkTemplate template) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", template.getLabel());
        values.put("defaultText", template.getDefaultText());
        values.put("fontSize", template.getFontSize());
        values.put("opacity", template.getOpacity());
        values.put("rotation", template.getRotation());
        values.put("color", template.getColor());
        values.put("placement", template.getPlacement() == null ? null : template.getPlacement().getValue());
        values.put("pageScope", template.getPageScope() == null ? null : template.getPageScope().getValue());
        values.put("active", template.isActive());
        values.put("version", template.getVersion());
        return values;
    }

    private void audit(String action, WatermarkTemplate template, Map<String, Object> before) {
        var event = auditService.event(action)
                .objectType(AuditObjectTypes.WATERMARK_TEMPLATE)
                .objectId(template.getTemplateId())
                .resourceName(template.getLabel())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(snapshot(template));
        if (before != null) {
            event.previousValue(before);
        }
        event.log();
    }
}
