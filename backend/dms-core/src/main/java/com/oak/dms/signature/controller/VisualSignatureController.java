package com.oak.dms.signature.controller;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.UpsertVisualSignatureRequest;
import com.oak.dms.signature.dto.VisualSignatureDto;
import com.oak.dms.signature.entity.UserVisualSignature;
import com.oak.dms.signature.service.UserVisualSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.UUID;

/**
 * A user's own saved visual signature - self-service, like {@code GET/PUT
 * /api/v1/users/me}, so no {@code @PreAuthorize} beyond being an
 * authenticated tenant user: nobody needs a special permission to set how
 * their OWN name is drawn. See {@code UserVisualSignatureService}'s own doc
 * comment for why this is purely cosmetic and never part of what's actually
 * verified.
 */
@RestController
@RequestMapping("/api/v1/users/me/visual-signature")
@ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Visual signatures", description = "A user's own saved cosmetic signature mark")
public class VisualSignatureController {

    private final UserVisualSignatureService service;

    @GetMapping
    @Operation(summary = "The caller's own saved visual signature",
            description = "404 if they've never set one - the sign-and-approve dialog then asks them to create one.")
    public VisualSignatureDto get() {
        UserVisualSignature signature = service.find(TenantContext.getTenantId(), TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("UserVisualSignature", TenantContext.getUserId()));
        return toDto(signature);
    }

    @PutMapping
    @Operation(summary = "Set/replace the caller's own saved visual signature",
            description = "Re-verifies the caller's own password first (step-up, same mechanism signing "
                    + "itself depends on) - this standalone endpoint has nothing else proving it's really this "
                    + "person, unlike sign-and-approve (which also updates this row automatically, without a "
                    + "second password prompt, since that request's own step-up already covers it).")
    public VisualSignatureDto put(@Valid @RequestBody UpsertVisualSignatureRequest request) {
        UUID userId = TenantContext.getUserId();
        UserVisualSignature saved = service.upsertWithVerification(TenantContext.getTenantId(), userId,
                request.kind(), request.typedText(), request.imageBase64(), request.password(), userId);
        return toDto(saved);
    }

    private static VisualSignatureDto toDto(UserVisualSignature s) {
        String imageBase64 = s.getImagePng() == null ? null : Base64.getEncoder().encodeToString(s.getImagePng());
        return new VisualSignatureDto(s.getKind().name(), s.getTypedText(), imageBase64, s.getUpdatedAt());
    }
}
