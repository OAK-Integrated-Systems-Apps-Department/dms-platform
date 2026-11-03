package com.oak.dms.editing;

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
 * The access token the editing engine presents back to us on every WOPI call.
 *
 * <h2>Why a token, not the user's login</h2>
 * The engine fetches and saves the file server-to-server and has no browser session. What it holds instead is
 * this token, minted by {@link EditingSessionService} only AFTER the caller passed the document-access and lock
 * checks. It names ONE document, ONE user, ONE tenant and an expiry, and it is HMAC-signed, so the engine cannot
 * widen it (another document, another user, another tenant) or extend it.
 *
 * <h2>What it does not do</h2>
 * It proves who the session was granted to, not that the action is still allowed: {@link WopiController}
 * re-checks the live document (still exists, same tenant, not frozen by a signature or legal hold since, and in
 * exclusive mode still checked out by this user) on every call. A lock force-released a second ago stops saves
 * even though the token is still unexpired.
 *
 * <p>Same shape as the OCR job grant ({@code OcrJobTokenCodec}) and kept as its own class with its own secret, so
 * leaking or rotating one never affects the other. A blank secret disables minting and verification outright
 * rather than signing with an empty key (which would be a token anybody could mint).
 */
@Component
public class WopiTokenCodec {

    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec key;

    public WopiTokenCodec(@Value("${dms.editing.token-secret:}") String secret) {
        this.key = secret == null || secret.isBlank() ? null : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC);
    }

    /** What a valid token says. {@code userName} is only for the editor's "who is here" display. */
    public record Grant(UUID tenantId, UUID userId, UUID documentId, String userName, Instant expiresAt) {
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String mint(Grant grant) {
        requireKey();
        String payload = grant.tenantId() + ";" + grant.userId() + ";" + grant.documentId() + ";"
                + grant.expiresAt().getEpochSecond() + ";"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (grant.userName() == null ? "" : grant.userName()).getBytes(StandardCharsets.UTF_8));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    /** Empty for anything malformed, wrongly signed or expired - never throws on bad input. */
    public Optional<Grant> verify(String token) {
        if (key == null || token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        // Constant-time: the comparison must not leak how many leading characters were right.
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split(";", -1);
            if (parts.length != 5) {
                return Optional.empty();
            }
            Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[3]));
            if (!expires.isAfter(Instant.now())) {
                return Optional.empty();
            }
            String name = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            return Optional.of(new Grant(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), name, expires));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        requireKey();
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("dms.editing.token-secret is not set");
        }
    }
}
