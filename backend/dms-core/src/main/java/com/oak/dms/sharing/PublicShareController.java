package com.oak.dms.sharing;

import com.oak.dms.sharing.SharingDtos.LinkInfo;
import com.oak.dms.sharing.SharingDtos.Listing;
import com.oak.dms.sharing.SharingDtos.OpenRequest;
import com.oak.dms.sharing.SharingDtos.OpenResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

/**
 * The public side of an external share link: no login. Permitted by SecurityConfig ({@code /api/v1/public/**}) and
 * authenticated here by the token in the path plus, after the password check, a signed access token - see
 * {@link PublicShareService}. {@code info} and {@code open} always answer 200 so an expired or revoked link is a
 * clear page and never an error (SEA-NFR-003).
 */
@RestController
@RequestMapping("/api/v1/public/share")
@RequiredArgsConstructor
@Tag(name = "Public share links", description = "What the recipient of an external link sees - no sign-in")
public class PublicShareController {

    private final PublicShareService service;

    @GetMapping("/{token}")
    @Operation(summary = "What a link is and whether it can be used")
    public LinkInfo info(@PathVariable("token") String token) {
        return service.info(token);
    }

    @PostMapping("/{token}/open")
    @Operation(summary = "Open a link: check the password, count the view, get an access token")
    public OpenResult open(@PathVariable("token") String token, @RequestBody(required = false) OpenRequest request) {
        return service.open(token, request == null ? null : request.password());
    }

    @GetMapping("/{token}/items")
    @Operation(summary = "The contents of a shared folder")
    public Listing items(@PathVariable("token") String token, @RequestParam("access") String access,
                         @RequestParam(name = "folderId", required = false) UUID folderId) {
        return service.items(token, access, folderId);
    }

    @GetMapping("/{token}/content")
    @Operation(summary = "A shared file - shown in the browser, or downloaded when the link allows it")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable("token") String token,
                                                         @RequestParam("access") String access,
                                                         @RequestParam(name = "documentId", required = false) UUID documentId,
                                                         @RequestParam(name = "download", defaultValue = "false") boolean download) {
        return SharedFileResponse.of(service.content(token, access, documentId, download));
    }
}
