package com.oak.dms.ocrworker;

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
 * Signs and verifies the per-job grant ocr-worker presents to read a
 * document's plaintext bytes.
 *
 * <h2>Why a per-job token at all</h2>
 * The worker cannot read storage directly: objects are encrypted at rest by
 * {@code EncryptingObjectStorageService}, so a direct read returns ciphertext,
 * and handing the worker the keys would put data-key unwrapping in a second
 * process. So dms-core serves the plaintext - which means it needs an answer
 * to "who is allowed to ask, for which document, and for how long".
 * <p>
 * A single shared secret cannot answer that. It is the same value for every
 * job and every version, it does not expire, and anything that learns it can
 * read any document in any tenant for as long as nobody rotates it. This token
 * names exactly one version, inside one tenant, for one job, for a few
 * minutes. The static {@code X-Internal-Token} is still required alongside it
 * as a second factor - see {@code OcrInternalAuthFilter} - so a leaked grant
 * on its own is not enough either.
 *
 * <h2>What it deliberately does not do</h2>
 * It does not say the read is permitted - only who is asking. The endpoint
 * still re-checks that the job is live, that the version belongs to the named
 * tenant, and that the version passed the scan gate. A token is a claim about
 * identity; authorisation is decided against the database every time, so a job
 * cancelled or a version quarantined a second after the token was minted is
 * refused with the token still perfectly valid.
 * <p>
 * Same shape and reasoning as {@link com.oak.dms.security.auth.ImpersonationTokenCodec}
 * and {@code AuditCorrelationTokenCodec}, kept as its own class with its own
 * secret: a leak or rotation of one must not affect the others.
 */
@Slf4j
@Component
public class OcrJobTokenCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public OcrJobTokenCodec(@Value("${dms.ocr.job-token-secret:}") String secret) {
        // Empty is allowed so a deployment with OCR switched off needs no
        // secret at all. OcrInternalAuthFilter refuses every request when the
        // secret is blank, rather than signing with an empty key - which would
        // be a token anybody could mint.
        this.key = secret == null || secret.isBlank()
                ? null
                : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * Why the worker needs these bytes, which decides what else is checked.
     *
     * <p><b>job</b> - an OCR run. The grant names a live {@code processing_jobs}
     * row and the endpoint verifies it is still queued or processing, so
     * finishing or cancelling the job revokes the grant before it expires.
     *
     * <p><b>adhoc</b> - the review screen asked for a page image or read a
     * region the user dragged. There is no job to check, so this kind is
     * deliberately short-lived (minutes) and minted only while serving a
     * request that has already passed the caller's {@code os.*} permission AND
     * document-level access. It is strictly narrower than a job grant in
     * lifetime and strictly wider in what it skips; both are bound to one
     * version in one tenant.
     */
    public enum Purpose { job, adhoc }

    /** What the token asserts. Verified against the database before any byte is served. */
    public record Grant(Purpose purpose, UUID jobId, UUID versionId, UUID tenantId,
                        Instant issuedAt, Instant expiresAt) {
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** A grant for one OCR job. {@code jobId} is checked live at read time. */
    public String issueForJob(UUID jobId, UUID versionId, UUID tenantId, Instant issuedAt, Instant expiresAt) {
        return issue(Purpose.job, jobId, versionId, tenantId, issuedAt, expiresAt);
    }

    /** A grant for a page image or region read - no job, so keep it short. */
    public String issueAdhoc(UUID versionId, UUID tenantId, Instant issuedAt, Instant expiresAt) {
        return issue(Purpose.adhoc, null, versionId, tenantId, issuedAt, expiresAt);
    }

    private String issue(Purpose purpose, UUID jobId, UUID versionId, UUID tenantId,
                         Instant issuedAt, Instant expiresAt) {
        if (key == null) {
            throw new IllegalStateException("dms.ocr.job-token-secret is not set - cannot issue an OCR grant");
        }
        // jobId is "-" rather than absent for an adhoc grant: a fixed field
        // count means a forged token can't change the shape of the payload to
        // make one field read as another.
        String payload = purpose + "|" + (jobId != null ? jobId.toString() : "-") + "|" + versionId + "|" + tenantId
                + "|" + issuedAt.getEpochSecond() + "|" + expiresAt.getEpochSecond();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encode(payloadBytes) + "." + encode(hmac(payloadBytes));
    }

    /**
     * The embedded grant, only if the signature is valid and it hasn't expired.
     * Never throws: a missing, malformed, forged or expired token is simply
     * "no grant", and the caller answers 401 either way. Distinguishing the
     * cases in the response would tell an attacker which part they got wrong.
     */
    public Optional<Grant> verify(String token) {
        if (key == null || token == null) {
            return Optional.empty();
        }
        try {
            int dot = token.indexOf('.');
            if (dot < 0) return Optional.empty();

            byte[] payloadBytes = decode(token.substring(0, dot));
            byte[] expectedSignature = hmac(payloadBytes);
            byte[] suppliedSignature = decode(token.substring(dot + 1));
            // Constant-time: a byte-by-byte comparison leaks how much of a
            // forged signature was right, which is enough to forge the rest.
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                return Optional.empty();
            }

            String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", 6);
            if (parts.length != 6) return Optional.empty();

            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[5]));
            if (Instant.now().isAfter(expiresAt)) {
                return Optional.empty();
            }

            Purpose purpose = Purpose.valueOf(parts[0]);
            UUID jobId = "-".equals(parts[1]) ? null : UUID.fromString(parts[1]);
            // A job grant without a job id is malformed, whatever it is signed
            // with - it would otherwise skip the live-job check by omission.
            if (purpose == Purpose.job && jobId == null) {
                return Optional.empty();
            }
            return Optional.of(new Grant(
                    purpose,
                    jobId,
                    UUID.fromString(parts[2]),
                    UUID.fromString(parts[3]),
                    Instant.ofEpochSecond(Long.parseLong(parts[4])),
                    expiresAt));
        } catch (RuntimeException e) {
            log.debug("Rejected an invalid OCR job token: {}", e.toString());
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
