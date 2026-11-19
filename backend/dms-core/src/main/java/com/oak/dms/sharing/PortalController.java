package com.oak.dms.sharing;

import com.oak.dms.sharing.SharingDtos.AcceptInvite;
import com.oak.dms.sharing.SharingDtos.InviteInfo;
import com.oak.dms.sharing.SharingDtos.PortalListing;
import com.oak.dms.sharing.SharingDtos.PortalLogin;
import com.oak.dms.sharing.SharingDtos.PortalLoginRequest;
import com.oak.dms.sharing.SharingDtos.PortalMe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

/**
 * The guests' client portal: invitation, sign-in, browsing and reading. Public to Spring Security
 * ({@code /api/v1/public/**}); a guest proves who they are with their own signed token, sent as
 * {@code X-Guest-Token} (or, for a file opened in a frame or a download link, the {@code access} parameter) - see
 * {@link PortalService}.
 */
@RestController
@RequestMapping("/api/v1/public/portal")
@RequiredArgsConstructor
@Tag(name = "Client portal", description = "Guest accounts: accept an invitation, sign in, browse what was shared")
public class PortalController {

    static final String GUEST_HEADER = "X-Guest-Token";

    private final PortalService service;

    @GetMapping("/invite/{token}")
    @Operation(summary = "Is this invitation still valid, and for whom")
    public InviteInfo invite(@PathVariable("token") String token) {
        return service.inviteInfo(token);
    }

    @PostMapping("/accept-invite")
    @Operation(summary = "Set a password and activate the guest account")
    public ResponseEntity<java.util.Map<String, Boolean>> accept(@RequestBody AcceptInvite request) {
        service.acceptInvite(request.token(), request.password());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in to the portal")
    public PortalLogin login(@RequestBody PortalLoginRequest request) {
        return service.login(request.email(), request.password(), request.tenantId());
    }

    @GetMapping("/me")
    @Operation(summary = "Who the signed-in guest is")
    public PortalMe me(@RequestHeader(GUEST_HEADER) String token) {
        return service.me(token);
    }

    @GetMapping("/items")
    @Operation(summary = "What the guest has been given, or the contents of one folder of it")
    public PortalListing items(@RequestHeader(GUEST_HEADER) String token,
                               @RequestParam(name = "parentFolderId", required = false) UUID parentFolderId) {
        return service.items(token, parentFolderId);
    }

    @GetMapping("/content")
    @Operation(summary = "A file the guest has access to - shown in the browser, or downloaded when allowed")
    public ResponseEntity<StreamingResponseBody> content(@RequestParam("access") String token,
                                                         @RequestParam("documentId") UUID documentId,
                                                         @RequestParam(name = "download", defaultValue = "false") boolean download) {
        return SharedFileResponse.of(service.content(token, documentId, download));
    }
}
