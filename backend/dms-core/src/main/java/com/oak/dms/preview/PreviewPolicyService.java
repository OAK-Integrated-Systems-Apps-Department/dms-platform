package com.oak.dms.preview;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a user may do from a document preview, set per organisation (DPRM-FR-005). It applies to the preview
 * channel only and is independent of the document's own permissions: someone allowed to download a file still
 * cannot do it from a preview that blocks downloads - they open the file from the list instead, and that is
 * governed by their document permission as always.
 * <p>
 * Each of print and download is {@code allow}, {@code watermark} (the viewer's name and the time are burned into
 * what they print or download) or {@code block}; copying text is {@code allow} or {@code block}. No row = all
 * allowed, which is how previews have always worked.
 * <p>
 * Anything other than all-allowed makes the preview a <em>protected view</em>: the server draws the pages as
 * pictures (watermark included) and the file itself never reaches the browser, so the restriction cannot be undone
 * from the page.
 */
@Service
@RequiredArgsConstructor
public class PreviewPolicyService {

    public static final String ALLOW = "allow";
    public static final String WATERMARK = "watermark";
    public static final String BLOCK = "block";

    public record Policy(String print, String download, String copy) {

        /** True when the preview is the plain one - nothing is restricted. */
        public boolean unrestricted() {
            return ALLOW.equals(print) && ALLOW.equals(download) && ALLOW.equals(copy);
        }

        /** True when pages must carry the viewer's name: anything is "watermark". */
        public boolean watermarked() {
            return WATERMARK.equals(print) || WATERMARK.equals(download);
        }
    }

    public static final Policy DEFAULT = new Policy(ALLOW, ALLOW, ALLOW);

    private final TenantPreviewPolicyRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Policy forTenant(UUID tenantId) {
        return repository.findById(tenantId)
                .map(p -> new Policy(p.getPrintMode(), p.getDownloadMode(), p.getCopyMode()))
                .orElse(DEFAULT);
    }

    /** The caller's organisation's policy. */
    @Transactional(readOnly = true)
    public Policy mine() {
        return forTenant(TenantContext.getTenantId());
    }

    /** @throws FolderException 400 {@code PREVIEW_POLICY_INVALID} */
    @Transactional
    public Policy save(Policy wanted) {
        String print = mode(wanted.print(), "Printing", true);
        String download = mode(wanted.download(), "Downloading", true);
        String copy = mode(wanted.copy(), "Copying text", false);
        UUID tenantId = TenantContext.getTenantId();
        Policy before = forTenant(tenantId);
        TenantPreviewPolicy row = repository.findById(tenantId)
                .orElseGet(() -> TenantPreviewPolicy.builder().tenantId(tenantId).build());
        row.setPrintMode(print);
        row.setDownloadMode(download);
        row.setCopyMode(copy);
        row.setUpdatedBy(TenantContext.getUserId());
        row.setUpdatedAt(OffsetDateTime.now());
        repository.save(row);

        Policy after = new Policy(print, download, copy);
        auditService.event(AuditActionCatalog.PREVIEW_POLICY_CHANGED)
                .objectType(AuditObjectTypes.TENANT).objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS).previousValue(asMap(before)).newValue(asMap(after)).log();
        return after;
    }

    private static String mode(String value, String what, boolean mayWatermark) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        boolean ok = ALLOW.equals(mode) || BLOCK.equals(mode) || (mayWatermark && WATERMARK.equals(mode));
        if (!ok) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_POLICY_INVALID",
                    what + " from a preview can be set to " + (mayWatermark ? "allow, watermark or block." : "allow or block."));
        }
        return mode;
    }

    private static Map<String, Object> asMap(Policy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("print", p.print());
        m.put("download", p.download());
        m.put("copy", p.copy());
        return m;
    }
}
