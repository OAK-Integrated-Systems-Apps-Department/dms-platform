package com.oak.dms.editing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.checkout.EmbeddedSignatureDetector;
import com.oak.dms.checkout.SignedContentGuard;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The WOPI host the editing engine calls (CheckFileInfo, GetFile, PutFile) - see {@link WopiTokenCodec} for why
 * this authenticates by a signed token rather than a login. Not reachable from outside: {@code /internal/**}
 * is not proxied by nginx.
 *
 * <h2>What is checked on EVERY call, not once at session start</h2>
 * The token is valid for hours; the document is not frozen for hours. So each call re-reads the live document
 * (same tenant, not deleted) and a SAVE also requires that it is still editable by this user: no signature lock
 * (a document signed mid-session must not be overwritten), no legal hold, and in exclusive mode still checked out
 * by them (a force-release or an expiry stops further saves even though the token is unexpired).
 *
 * <h2>How a save becomes a version</h2>
 * The same path as any upload: {@link IngestionGate} scans the bytes and the document gets a new current version
 * once clean - there is no second, untracked way into the version history (the module's business objective).
 * AUTOSAVES ARE IGNORED (answered 200, nothing stored): the engine autosaves every few minutes and each would
 * otherwise become a version. A deliberate save (Ctrl+S, or closing the editor) is kept. The trade-off: edits since
 * the last deliberate save are not stored if the browser dies mid-session.
 *
 * <h2>Not supported on purpose</h2>
 * WOPI locking ({@code SupportsLocks} is false): our own check-out is the lock in exclusive mode, and in
 * simultaneous mode the engine reconciles concurrent edits itself. Renaming/relative-saves are off too.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal/v1/wopi/files/{documentId}")
@RequiredArgsConstructor
public class WopiController {

    /** Largest save accepted (bytes) - bigger than any editable Office file the upload limits already allow. */
    private static final long MAX_SAVE_BYTES = 200L * 1024 * 1024;

    private final WopiTokenCodec tokens;
    private final DocumentRepository documentRepository;
    private final DocumentContentReader contentReader;
    private final IngestionGate ingestionGate;
    private final AuditService auditService;
    private final EditingSessionService sessions;
    private final SignedContentGuard signedContentGuard;

    /** The web app's public address, e.g. "https://dms.example.com/" - only its origin is used. */
    @org.springframework.beans.factory.annotation.Value("${app.frontend-base-url:}")
    private String frontendBaseUrl;

    @GetMapping
    public Map<String, Object> checkFileInfo(@PathVariable UUID documentId,
                                             @RequestParam(value = "access_token", required = false) String accessToken) {
        WopiTokenCodec.Grant grant = authorize(documentId, accessToken);
        try {
            Document document = live(grant);
            DocumentContentReader.EditableVersion version = contentReader.currentForEditing(grant.tenantId(), documentId);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("BaseFileName", version.filename());
            info.put("OwnerId", document.getOwnerUserId().toString());
            info.put("Size", version.sizeBytes());
            info.put("UserId", grant.userId().toString());
            info.put("UserFriendlyName", grant.userName() == null || grant.userName().isBlank() ? "User" : grant.userName());
            info.put("Version", version.versionId().toString());
            info.put("LastModifiedTime", version.createdAt() == null ? null : version.createdAt().toInstant().toString());
            info.put("UserCanWrite", canWriteNow(document, grant));
            info.put("SupportsUpdate", true);
            info.put("SupportsLocks", false);
            info.put("UserCanNotWriteRelative", true);
            info.put("DisablePrint", false);
            // The DMS already has Download, Export and its own signing, so the editor does not offer a second way
            // to take a copy out (Download / Download as) or to roll back outside version control (Repair). Signing
            // inside the editor is switched off at the engine itself (document_signing.enable=false) and, whatever
            // buttons it shows, a save that carries a signature is refused below.
            // The one origin allowed to talk to the editor frame: the DMS web app itself. Without it Collabora stays
            // silent, and the page could not tell it to hide the controls the DMS has its own versions of (the security
            // label, signing) - see frontend lib/checkout/editor-bridge.ts.
            info.put("PostMessageOrigin", webOrigin());
            info.put("DisableExport", true);
            info.put("HideExportOption", true);
            info.put("HideRepairOption", true);
            return info;
        } finally {
            TenantContext.clear();
        }
    }

    @GetMapping("/contents")
    public ResponseEntity<StreamingResponseBody> getFile(@PathVariable UUID documentId,
                                                         @RequestParam(value = "access_token", required = false) String accessToken) {
        WopiTokenCodec.Grant grant = authorize(documentId, accessToken);
        try {
            live(grant);
            DownloadedDocument file = contentReader.openCurrentForEditing(grant.tenantId(), documentId);
            StreamingResponseBody body = out -> {
                try (InputStream in = file.content()) {
                    in.transferTo(out);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.sizeBytes())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(body);
        } finally {
            TenantContext.clear();
        }
    }

    @PostMapping("/contents")
    public ResponseEntity<Map<String, Object>> putFile(
            @PathVariable UUID documentId,
            @RequestParam(value = "access_token", required = false) String accessToken,
            @RequestHeader(value = "X-COOL-WOPI-IsAutosave", required = false) String coolAutosave,
            @RequestHeader(value = "X-LOOL-WOPI-IsAutosave", required = false) String loolAutosave,
            HttpServletRequest request) throws IOException {
        WopiTokenCodec.Grant grant = authorize(documentId, accessToken);
        try {
            Document document = live(grant);
            if (!canWriteNow(document, grant)) {
                // Frozen, held, or the lock moved on since the session started. 409 makes the engine say the save failed.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This document can no longer be saved to.");
            }
            if ("true".equalsIgnoreCase(coolAutosave) || "true".equalsIgnoreCase(loolAutosave)) {
                return ResponseEntity.ok(Map.of("LastModifiedTime", OffsetDateTime.now().toInstant().toString()));
            }
            long declared = request.getContentLengthLong();
            if (declared > MAX_SAVE_BYTES) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "That file is too large to save.");
            }
            byte[] bytes;
            try (InputStream in = request.getInputStream()) {
                bytes = in.readNBytes((int) MAX_SAVE_BYTES + 1);
            }
            if (bytes.length > MAX_SAVE_BYTES) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "That file is too large to save.");
            }
            String filename = contentReader.currentForEditing(grant.tenantId(), documentId).filename();
            // The version being edited carried no signature (that is checked before a session may start), so a saved
            // file that DOES is one the editor signed. Signing is the DMS's job, with its own records and locks - a
            // signature made in here would sit outside all of that, so the save is refused.
            if (EmbeddedSignatureDetector.hasSignature(filename, new ByteArrayInputStream(bytes))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Digital signatures can't be added in the editor. Sign the document from its page instead.");
            }
            IngestionTicketResponse ticket = ingestionGate.submit(IngestionRequest.newVersion(documentId,
                    DocumentVersionService.REPLACEMENT_UPLOAD, filename, bytes.length,
                    new ByteArrayInputStream(bytes), "Edited online"));
            boolean accepted = ticket.status() == ScanResultStatus.pending || ticket.status() == ScanResultStatus.clean
                    || ticket.status() == ScanResultStatus.released_override;
            if (!accepted) {
                log.warn("Online save of document {} refused by the scanner: {}", documentId, ticket.status());
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "The saved file was not accepted (" + ticket.status() + ").");
            }
            auditService.event(AuditActionCatalog.DOCUMENT_EDIT_SESSION_SAVED)
                    .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                    .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                    .newValue(Map.of("scanResultId", ticket.scanResultId(), "bytes", bytes.length))
                    .log();
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("LastModifiedTime", OffsetDateTime.now().toInstant().toString()));
        } finally {
            TenantContext.clear();
        }
    }

    // ---------------------------------------------------------------------------------------

    /** scheme://host[:port] of the web app - what a browser reports as the page's origin - or "" when it is unset or malformed. */
    private String webOrigin() {
        try {
            java.net.URI uri = java.net.URI.create(frontendBaseUrl == null ? "" : frontendBaseUrl.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The token must be valid AND name this very document; binds the tenant and user for the rest of the call. */
    private WopiTokenCodec.Grant authorize(UUID documentId, String accessToken) {
        WopiTokenCodec.Grant grant = tokens.verify(accessToken)
                .filter(g -> g.documentId().equals(documentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired access token."));
        TenantContext.set(grant.tenantId(), grant.userId());
        // The token outlives a decision to switch editing off (it lasts hours): the platform administrator stopping it
        // for the organisation, or its own administrator turning it off, ends an open editor's loading and saving now.
        if (!sessions.editingEnabled(grant.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Online editing is turned off.");
        }
        return grant;
    }

    /** The document as it is NOW, inside the token's tenant - a deleted or foreign document is simply not there. */
    private Document live(WopiTokenCodec.Grant grant) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(grant.documentId(), grant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));
    }

    private boolean canWriteNow(Document document, WopiTokenCodec.Grant grant) {
        // A document signed (or given a signature mark) since the session began must not be overwritten - asked from
        // the DMS records each time, not just when the session started.
        if (document.getSignatureLockedAt() != null || document.isLegalHold()
                || signedContentGuard.protectedByRecord(document)) {
            return false;
        }
        boolean heldByUser = document.isCheckedOutAt(OffsetDateTime.now())
                && Objects.equals(document.getCheckedOutBy(), grant.userId());
        if (sessions.modeFor(grant.tenantId(), document) == EditingSessionService.Mode.exclusive) {
            return heldByUser;
        }
        // Simultaneous: nobody else may hold an exclusive lock on it (e.g. a plain check-out for download).
        return heldByUser || !document.isCheckedOutAt(OffsetDateTime.now());
    }
}
