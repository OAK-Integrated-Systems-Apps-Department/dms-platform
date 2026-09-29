package com.oak.dms.security.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and verifies the opaque token carried in the {@code dms_impersonation}
 * cookie - the trusted grant that lets a request operate as a different
 * user's identity while recording who really made it. Same shape and
 * reasoning as {@link com.oak.dms.audit.filter.AuditCorrelationTokenCodec}, kept as
 * its own class (own secret, own payload) rather than reused: a leak or key
 * rotation on one must never affect the other, and their payloads/trust
 * requirements are unrelated.
 * <p>
 * Deliberately does NOT embed the target user's authorities/roles - only
 * identity (impersonator, target, tenant) and a validity window.
 * {@link ImpersonationContextFilter} re-resolves the target's current
 * permissions from the existing RBAC tables on every request instead, so a
 * permission change or revocation made mid-session takes effect on the very
 * next request rather than only once a new impersonation grant is issued.
 * <p>
 * The embedded {@code expiresAt} is checked here, independently of the
 * cookie's own Max-Age - defense in depth against a stale cookie surviving
 * (e.g. a browser that ignored Max-Age, or a cookie jar restored from a
 * backup) past the grant's real validity window.
 */
@Slf4j
@Component
public class ImpersonationTokenCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public ImpersonationTokenCodec(@Value("${app.impersonation.secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public record Grant(UUID impersonatorId, UUID targetUserId, UUID tenantId, Instant issuedAt, Instant expiresAt) {
    }

    public String issue(UUID impersonatorId, UUID targetUserId, UUID tenantId, Instant issuedAt, Instant expiresAt) {
        String payload = impersonatorId + "|" + targetUserId + "|" + tenantId + "|"
                + issuedAt.getEpochSecond() + "|" + expiresAt.getEpochSecond();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encode(payloadBytes) + "." + encode(hmac(payloadBytes));
    }

    /**
     * Returns the embedded grant only if the signature is valid and the
     * grant hasn't expired. Never throws - a missing, malformed, forged, or
     * expired token is indistinguishable from "no impersonation active":
     * the caller proceeds as the real, authenticated identity, exactly as
     * if the cookie had never been sent.
     */
    public Optional<Grant> verify(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot < 0) return Optional.empty();

            byte[] payloadBytes = decode(token.substring(0, dot));
            byte[] expectedSignature = hmac(payloadBytes);
            byte[] suppliedSignature = decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                return Optional.empty();
            }

            String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", 5);
            if (parts.length != 5) return Optional.empty();

            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[4]));
            if (Instant.now().isAfter(expiresAt)) {
                return Optional.empty();
            }

            return Optional.of(new Grant(
                    UUID.fromString(parts[0]),
                    UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]),
                    Instant.ofEpochSecond(Long.parseLong(parts[3])),
                    expiresAt));
        } catch (RuntimeException e) {
            log.debug("Rejected an invalid impersonation token: {}", e.toString());
            return Optional.empty();
        }
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is not available", e);
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
