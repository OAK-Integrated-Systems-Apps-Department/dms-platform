package com.oak.dms.signature.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.signature.entity.UserVisualSignature;
import com.oak.dms.signature.enums.VisualSignatureKind;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.repository.UserVisualSignatureRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A user's own saved visual signature - a cosmetic mark (typed name in a
 * script font, or a small canvas drawing) shown alongside the real
 * cryptographic signature {@code DocumentSignatureService} produces. This is
 * never part of {@code SignatureManifestBuilder}'s manifest and never checked
 * by {@code SignatureVerifier} - the password/TOTP step-up and the ECDSA
 * signature remain the only thing that is actually verified. One row per
 * {@code (tenantId, userId)}, upserted in place (unlike
 * {@code user_signing_keys}/{@code document_signatures}, this is the user's
 * own changeable preference, not evidence).
 */
@Service
@RequiredArgsConstructor
public class UserVisualSignatureService {

    /** A short name-sized doodle, not a photo - well under what the column could hold. */
    static final int MAX_IMAGE_BYTES = 100_000;

    private final UserVisualSignatureRepository repository;
    private final UserRepository userRepository;
    private final StepUpAuthenticator stepUp;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Optional<UserVisualSignature> find(UUID tenantId, UUID userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId);
    }

    /**
     * {@link #upsert}, but re-verifies the caller's own password first (no TOTP - MFA isn't in use
     * yet) - used by {@code VisualSignatureController}'s standalone PUT endpoint (editing a signature
     * from My Profile, outside of any signing ceremony), where unlike {@code DocumentSignatureService
     * #sign} calling {@link #upsert} directly, nothing else in the request already proved it's really
     * this person. Same step-up mechanism the real cryptographic signature depends on
     * ({@code StepUpAuthenticator}), reused here purely so someone else at an unlocked, already-
     * signed-in browser can't silently swap out what a person's signature looks like.
     *
     * @throws SignatureException 400 if the password is wrong/missing (same as a failed signing step-up)
     */
    @Transactional
    public UserVisualSignature upsertWithVerification(UUID tenantId, UUID userId, VisualSignatureKind kind,
                                                       String typedText, String imageBase64, String password,
                                                       UUID actorUserId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        stepUp.verify(user, password, null);
        return upsert(tenantId, userId, kind, typedText, imageBase64, actorUserId);
    }

    /**
     * Validates {@code kind} matches exactly one of {@code typedText}/{@code imageBase64}, decodes and
     * size-checks a drawn image, and upserts the caller's one row. No password check here - the one
     * caller that isn't already behind {@link #upsertWithVerification} is
     * {@code DocumentSignatureService#sign}'s own best-effort "save what they just used" call, which
     * only ever runs after that request's own step-up already succeeded moments earlier.
     *
     * @throws SignatureException 400 on a kind/payload mismatch, a blank typed name, or an image over
     *                             {@link #MAX_IMAGE_BYTES}/not valid base64
     */
    @Transactional
    public UserVisualSignature upsert(UUID tenantId, UUID userId, VisualSignatureKind kind, String typedText,
                                       String imageBase64, UUID actorUserId) {
        // drawn/uploaded are stored and validated identically - see VisualSignatureKind's own doc comment.
        String cleanedText = kind == VisualSignatureKind.typed ? requireTypedText(typedText) : null;
        byte[] imagePng = kind == VisualSignatureKind.typed ? null : decodeAndCheckImage(imageBase64);

        UserVisualSignature existing = repository.findByTenantIdAndUserId(tenantId, userId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        UserVisualSignature saved;
        if (existing != null) {
            existing.setKind(kind);
            existing.setTypedText(cleanedText);
            existing.setImagePng(imagePng);
            existing.setUpdatedBy(actorUserId);
            existing.setUpdatedAt(now);
            saved = repository.save(existing);
        } else {
            saved = repository.save(UserVisualSignature.builder()
                    .visualSignatureId(UUID.randomUUID())
                    .tenantId(tenantId)
                    .userId(userId)
                    .kind(kind)
                    .typedText(cleanedText)
                    .imagePng(imagePng)
                    .createdBy(actorUserId)
                    .createdAt(now)
                    .updatedBy(actorUserId)
                    .updatedAt(now)
                    .build());
        }

        auditService.event(AuditActionCatalog.VISUAL_SIGNATURE_UPDATED)
                .objectType(AuditObjectTypes.VISUAL_SIGNATURE)
                .objectId(saved.getVisualSignatureId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("userId", userId, "kind", kind.name()))
                .log();
        return saved;
    }

    /**
     * Same validation/decoding as {@link #upsert}, without the save - used by
     * {@code DocumentSignatureService#sign} to snapshot what the signer typed/drew THIS time onto their
     * signature row, whether or not it matches their saved profile.
     */
    String validateTypedText(String typedText) {
        return requireTypedText(typedText);
    }

    byte[] validateImage(String imageBase64) {
        return decodeAndCheckImage(imageBase64);
    }

    private static String requireTypedText(String typedText) {
        if (typedText == null || typedText.isBlank()) {
            throw SignatureException.badRequest("VISUAL_SIGNATURE_TEXT_REQUIRED",
                    "Type your name for your visual signature.");
        }
        String trimmed = typedText.trim();
        if (trimmed.length() > 150) {
            throw SignatureException.badRequest("VISUAL_SIGNATURE_TEXT_TOO_LONG",
                    "Keep your typed signature under 150 characters.");
        }
        return trimmed;
    }

    private static byte[] decodeAndCheckImage(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw SignatureException.badRequest("VISUAL_SIGNATURE_IMAGE_REQUIRED",
                    "Draw your signature before signing.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(stripDataUrlPrefix(imageBase64));
        } catch (IllegalArgumentException e) {
            throw SignatureException.badRequest("VISUAL_SIGNATURE_IMAGE_INVALID",
                    "Your drawn signature could not be read - try drawing it again.");
        }
        if (decoded.length == 0) {
            throw SignatureException.badRequest("VISUAL_SIGNATURE_IMAGE_REQUIRED",
                    "Draw your signature before signing.");
        }
        if (decoded.length > MAX_IMAGE_BYTES) {
            throw SignatureException.badRequest("VISUAL_SIGNATURE_IMAGE_TOO_LARGE",
                    "Your drawn signature is too large - try a simpler signature.");
        }
        return decoded;
    }

    /** The canvas's own `toDataURL()` includes a "data:image/png;base64," prefix - strip it if present. */
    private static String stripDataUrlPrefix(String value) {
        int comma = value.indexOf(',');
        return value.startsWith("data:") && comma >= 0 ? value.substring(comma + 1) : value;
    }
}
