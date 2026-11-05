package com.oak.dms.editing;

import com.oak.dms.security.auth.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * 2.10 online editing: whether it is on for the caller's organisation, the organisation's own switch, and
 * starting a session. The engine's own calls are {@link WopiController}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Online editing", description = "Embedded editing through the configured editing engine (Collabora, over WOPI)")
public class EditingController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final EditingSessionService sessions;
    private final EditingSettingsService settings;
    private final EditingEngine engine;
    private final PlatformEditingAccess platformAccess;

    /**
     * @param enabled           the platform allowed it, the organisation turned it on AND the engine is answering now;
     *                          the UI hides "Edit online" otherwise
     * @param allowedByPlatform the platform administrator allowed this organisation online editing at all - when false
     *                          the organisation's own Online Editing page and menu item are not shown
     */
    public record EditingConfig(boolean enabled, String mode, Set<String> extensions, boolean allowedByPlatform) {
    }

    /** What an administrator edits. {@code engineConfigured} false means the server has no engine, so it cannot be turned on. */
    public record SettingsRequest(boolean enabled, String mode) {
    }

    @GetMapping("/api/v1/editing/config")
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Whether online editing is available to me, in which mode, and for which file types")
    public EditingConfig config() {
        EditingSettingsService.Settings policy = settings.forTenant(TenantContext.getTenantId());
        // Not even asking the engine while the organisation has it off.
        Set<String> extensions = policy.enabled() ? engine.supportedExtensions() : Set.of();
        return new EditingConfig(policy.enabled() && !extensions.isEmpty(), policy.mode(), extensions,
                platformAccess.allowed(TenantContext.getTenantId()));
    }

    @GetMapping("/api/v1/editing/settings")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "CICO_EDITING_SELECT_PROVIDER, " + P + "CICO_EDITING_CONFIGURE_CONNECTION)")
    @Operation(summary = "My organisation's online-editing setting (off and exclusive until an administrator changes it)")
    public EditingSettingsService.View getSettings() {
        return settings.view();
    }

    @PutMapping("/api/v1/editing/settings")
    @PreAuthorize("@authz.hasPermission(" + P + "CICO_EDITING_SELECT_PROVIDER)")
    @Operation(summary = "Turn online editing on or off for my organisation and choose its mode",
            description = "Mode is exclusive (opening the editor checks the document out) or simultaneous (no lock). "
                    + "409 EDITING_ENGINE_NOT_CONFIGURED when asked to turn it on and the server has no engine. Audited.")
    public EditingSettingsService.View saveSettings(@RequestBody SettingsRequest request) {
        return settings.save(request.enabled(), request.mode());
    }

    @PostMapping("/api/v1/documents/{documentId}/edit-session")
    @PreAuthorize("@authz.hasPermission(" + P + "CICO_CHECK_OUT)")
    @Operation(summary = "Open the document in the online editor",
            description = "Exclusive mode checks the document out for you first (409 DOCUMENT_CHECKED_OUT names the "
                    + "holder). 409 DOCUMENT_LEGAL_HOLD / DOCUMENT_SIGNATURE_LOCKED when frozen, 409 "
                    + "EDITING_UNSUPPORTED_TYPE for a file the engine cannot edit, 503 EDITING_DISABLED when the "
                    + "organisation has it off and 503 EDITING_ENGINE_UNAVAILABLE when the engine is down - those last "
                    + "three say to use check-out and download instead.")
    public EditingSessionService.Session start(@PathVariable UUID documentId) {
        return sessions.start(documentId);
    }
}
