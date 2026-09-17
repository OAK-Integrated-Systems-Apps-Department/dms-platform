package com.oak.dms.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and verifies the opaque {@code X-Correlation-Id} token that lets a
 * caller continue an audit correlation across a related SEQUENCE of HTTP
 * requests - e.g. RolesStore#addRole's create-role call followed by its
 * assign-permission call(s) - without ever trusting a caller-chosen value
 * outright (see the correlation_id note in docs/audit_events_schema_spec.md:
 * "must not be accepted from the client... an untrusted caller could
 * provide an arbitrary value and manipulate the grouping of audit events").
 *
 * A token embeds the correlation id together with the tenant and actor it
 * was issued to, HMAC-SHA256-signed with a server-only secret
 * ({@code app.audit.correlation.secret}). Verifying a token requires both a
 * valid signature AND an exact tenant/actor match against the current
 * request's own authenticated identity - a structurally valid but foreign
 * token (e.g. leaked from another user's browser, or replayed against a
 * different tenant) is rejected exactly like a forged one. Neither check
 * alone would be enough: the signature alone doesn't stop a genuine token
 * being replayed by someone else it wasn't issued to, and the tenant/actor
 * check alone doesn't stop a client fabricating a correlation id for
 * itself.
 *
 * {@link AuditRequestContextFilter} is the only caller: it issues one of
 * these on every authenticated response and verifies one on every request
 * that supplies {@code X-Correlation-Id}.
 */
@Slf4j
@Component
public class AuditCorrelationTokenCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** Placeholder for a platform-level actor with no owning tenant, so "no tenant" round-trips through the signed payload distinguishably from a real tenant id. */
    private static final String NO_TENANT = "-";

    private final SecretKeySpec key;

    public AuditCorrelationTokenCodec(@Value("${app.audit.correlation.secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String issue(UUID correlationId, UUID tenantId, UUID actorId) {
        String payload = correlationId + "|" + (tenantId != null ? tenantId : NO_TENANT) + "|" + actorId;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encode(payloadBytes) + "." + encode(hmac(payloadBytes));
    }

    /**
     * Returns the embedded correlation id only if the signature is valid
     * and the token was issued to exactly this tenant/actor. Never throws -
     * a missing, malformed, forged, expired-format, or foreign token is
     * indistinguishable from "no continuation requested": the caller falls
     * back to starting a fresh correlation, which is always safe.
     */
    public Optional<UUID> verify(String token, UUID tenantId, UUID actorId) {
        try {
            int dot = token.indexOf('.');
            if (dot < 0) return Optional.empty();

            byte[] payloadBytes = decode(token.substring(0, dot));
            byte[] expectedSignature = hmac(payloadBytes);
            byte[] suppliedSignature = decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                return Optional.empty();
            }

            String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3) return Optional.empty();

            String expectedTenant = tenantId != null ? tenantId.toString() : NO_TENANT;
            if (!parts[1].equals(expectedTenant) || !parts[2].equals(String.valueOf(actorId))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(parts[0]));
        } catch (RuntimeException e) {
            // Garbage/foreign input (a header this codec didn't produce, a
            // format that changed, plain client error) - never worth
            // failing the request over, same handling as a missing header.
            log.debug("Rejected an invalid X-Correlation-Id token: {}", e.toString());
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
