package com.oak.dms.editing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.checkout.CheckoutException;
import com.oak.dms.checkout.DocumentCheckoutService;
import com.oak.dms.checkout.DocumentEditGuard;
import com.oak.dms.checkout.SignedContentGuard;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Starts an online editing session (CICO-FR-005, 006, 010): every check that decides whether this person may
 * edit this document right now, THEN a signed token the engine uses for the rest of the session.
 *
 * <p>Order matters and mirrors check-out: access, then the frozen states (a signature lock or legal hold refuses
 * exactly as it does for check-out), then the engine (off, down, or not editing this file type - each refused
 * with a message that says to use check-out, download and re-upload instead: CICO-NFR-002 and the "engine
 * mismatch" alternate flow), and only then the lock. In {@code exclusive} mode starting a session IS checking the
 * document out - the lock is what stops a second person editing - so a document already held by someone else
 * refuses here with the same 409 naming the holder. In {@code simultaneous} mode there is no lock and everyone
 * with edit access may open it together; the engine reconciles their edits and shows who is present.
 *
 * <p>Presence indicators (CICO-FR-007) are the engine's own - they appear inside the editor, so nothing here
 * has to track who is connected.
 */
@Service
@RequiredArgsConstructor
public class EditingSessionService {

    /** Who may be in the document at once. */
    public enum Mode {
        exclusive, simultaneous
    }

    public record Session(String editorUrl, String accessToken, long accessTokenExpiresAtMillis, String mode) {
    }

    private final DocumentRepository documentRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentAccessService documentAccessService;
    private final DocumentSignatureLockService signatureLockService;
    private final DocumentEditGuard editGuard;
    private final SignedContentGuard signedContentGuard;
    private final DocumentCheckoutService checkoutService;
    private final DocumentContentReader contentReader;
    private final EditingEngine engine;
    private final EditingSettingsService settings;
    private final WopiTokenCodec tokens;
    private final AuditService auditService;

    @Value("${dms.editing.wopi-host-url:http://dms-core:8080}")
    private String wopiHostUrl;
    @Value("${dms.editing.token-ttl:PT10H}")
    private Duration tokenTtl;

    /**
     * Whether online editing is on for this organisation right now: allowed by the platform administrator AND turned
     * on by the organisation. Asked on every file call of an open editor, so switching it off stops saves at once
     * instead of when the (hours-long) token expires.
     */
    public boolean editingEnabled(UUID tenantId) {
        return settings.forTenant(tenantId).enabled();
    }

    /** The organisation's own mode (V128) - exclusive unless it chose simultaneous. */
    public Mode modeFor(UUID tenantId) {
        return EditingSettingsService.SIMULTANEOUS.equals(settings.forTenant(tenantId).mode())
                ? Mode.simultaneous : Mode.exclusive;
    }

    /**
     * The mode that applies to one document: the organisation's choice, but people may be in a document together
     * only when its document type also allows co-editing ("Co-editing enabled" on the type). Otherwise it stays
     * exclusive, whatever the organisation chose.
     */
    public Mode modeFor(UUID tenantId, Document document) {
        if (modeFor(tenantId) == Mode.exclusive || document.getDocumentTypeId() == null) {
            return Mode.exclusive;
        }
        return documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(document.getDocumentTypeId(), tenantId)
                .filter(type -> type.isCoeditingEnabled())
                .map(type -> Mode.simultaneous)
                .orElse(Mode.exclusive);
    }

    public Session start(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID me = TenantContext.getUserId();
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.edit);

        // Frozen first: a signed or held document is refused with the reason, not "engine unavailable".
        signatureLockService.guard(document);
        editGuard.guard(document);

        // The organisation's own switch (default off), THEN whether this server has an engine at all.
        EditingSettingsService.Settings policy = settings.forTenant(tenantId);
        if (!policy.enabled()) {
            throw CheckoutException.unavailable("EDITING_DISABLED",
                    fallback("Online editing is turned off for your organisation."));
        }
        if (!engine.isConfigured() || !tokens.isConfigured()) {
            throw CheckoutException.unavailable("EDITING_ENGINE_UNAVAILABLE", fallback("Online editing is not available."));
        }
        Mode mode = modeFor(tenantId, document);
        String filename = contentReader.currentForEditing(tenantId, documentId).filename();
        String actionUrl;
        try {
            engine.requireAvailable();
            actionUrl = engine.editorUrlFor(filename).orElseThrow(() -> CheckoutException.conflict(
                    "EDITING_UNSUPPORTED_TYPE", "\"" + filename + "\" can't be edited online. " + fallback("")));
        } catch (EditingEngine.EngineUnavailableException e) {
            throw CheckoutException.unavailable("EDITING_ENGINE_UNAVAILABLE", fallback(e.getMessage()));
        }

        if (mode == Mode.exclusive) {
            // Idempotent for the holder; 409 naming the holder otherwise. Also refuses a signed document (any file
            // type), so the signature check is not repeated here.
            checkoutService.checkOut(documentId);
        } else {
            // No lock is taken in simultaneous mode, so ask the signature question directly: a signed document is
            // not opened for editing by anyone.
            signedContentGuard.guardOpening(document);
        }

        Instant expires = Instant.now().plus(tokenTtl);
        String token = tokens.mint(new WopiTokenCodec.Grant(tenantId, me, documentId,
                TenantContext.getDisplayNameOrNull(), expires));
        String wopiSrc = wopiHostUrl.replaceAll("/+$", "") + "/internal/v1/wopi/files/" + documentId;
        String url = actionUrl + "WOPISrc=" + URLEncoder.encode(wopiSrc, StandardCharsets.UTF_8);

        auditService.event(AuditActionCatalog.DOCUMENT_EDIT_SESSION_STARTED)
                .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("mode", mode.name(), "file", filename))
                .log();
        return new Session(url, token, expires.toEpochMilli(), mode.name());
    }

    private static String fallback(String lead) {
        return (lead + " Use Check out, edit the downloaded file, then Check in the edited file instead.").trim();
    }
}
