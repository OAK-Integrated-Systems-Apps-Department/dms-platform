package com.oak.dms.importexport.controller;

import com.oak.dms.importexport.dto.CreateUploadSessionRequest;
import com.oak.dms.importexport.dto.UploadSessionResponse;
import com.oak.dms.importexport.service.UploadSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/**
 * B4 - resumable, chunked upload of a large bulk import ZIP; see
 * {@link UploadSessionService}. Sessions belong to the user who started them.
 */
@RestController
@RequestMapping("/api/v1/upload-sessions")
@RequiredArgsConstructor
@Tag(name = "Upload sessions", description = "Resumable chunked upload of a large ZIP for a bulk import")
public class UploadSessionController {

    private static final String CAN_UPLOAD =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)";

    private final UploadSessionService uploadSessionService;

    @PostMapping
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "Start a resumable upload of a ZIP")
    public ResponseEntity<UploadSessionResponse> create(@Valid @RequestBody CreateUploadSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uploadSessionService.create(request));
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "How much of the upload has arrived - resume from receivedBytes")
    public UploadSessionResponse get(@PathVariable UUID sessionId) {
        return uploadSessionService.get(sessionId);
    }

    @PutMapping(value = "/{sessionId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "Send the next chunk",
            description = "offset must equal receivedBytes (else 409 - GET the session and resume). The body is the "
                    + "raw bytes; Content-Length is required.")
    public UploadSessionResponse appendChunk(@PathVariable UUID sessionId, @RequestParam long offset,
                                             HttpServletRequest request) throws IOException {
        return uploadSessionService.appendChunk(sessionId, offset, request.getContentLengthLong(),
                request.getInputStream());
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "Abandon an upload and delete what was received")
    public ResponseEntity<Void> abort(@PathVariable UUID sessionId) {
        uploadSessionService.abort(sessionId);
        return ResponseEntity.noContent().build();
    }
}
