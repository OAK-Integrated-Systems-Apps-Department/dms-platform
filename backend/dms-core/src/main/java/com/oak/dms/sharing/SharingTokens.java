package com.oak.dms.sharing;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Every secret the sharing module makes or checks:
 * <ul>
 *   <li><b>link and invite tokens</b> - 32 random bytes; only their SHA-256 is stored, so a database leak yields no
 *       working link (SEA-NFR-001);</li>
 *   <li><b>passwords</b> - BCrypt (one-way; a stolen hash can't be turned back into the password);</li>
 *   <li><b>access tokens</b> - HMAC-signed and short-lived, bound to ONE link or ONE guest, handed out after the
 *       password check so a recipient isn't asked again for every image or page. They prove who passed the gate,
 *       not that the gate is still open: {@link PublicShareService} and {@link PortalService} re-read the link or
 *       grant on every call.</li>
 * </ul>
 * Same shape as the editing engine's {@code WopiTokenCodec}, with its own key so the two never affect each other.
 */
@Slf4j
@Component
public class SharingTokens {

    static final Duration LINK_ACCESS_TTL = Duration.ofMinutes(30);
    static final Duration GUEST_TTL = Duration.ofHours(8);

    private static final String HMAC = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(10);
    /** Checked against when there is no real hash, so "no such account" costs the same time as "wrong password". */
    private static final String DUMMY_HASH = PASSWORDS.encode("not-a-real-password");

    private final String configuredSecret;
    private SecretKeySpec key;

    public SharingTokens(@Value("${dms.sharing.token-secret:}") String secret) {
        this.configuredSecret = secret;
    }

    @PostConstruct
    void init() {
        byte[] bytes;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            log.warn("dms.sharing.token-secret is not set - share-link and guest access tokens are signed with a "
                    + "key made at start-up, so they stop working on restart and are not valid across instances.");
        } else {
            bytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        key = new SecretKeySpec(bytes, HMAC);
    }

    /** A new unguessable token for a link or an invitation. */
    public String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** BCrypt only reads the first 72 bytes (and newer versions refuse more), so a longer password is refused up front. */
    static final int MAX_PASSWORD_BYTES = 72;

    public String hashPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw SharingException.badRequest("SHARING_PASSWORD_INVALID", "That password is too long.");
        }
        return PASSWORDS.encode(password);
    }

    /** True when {@code password} matches {@code hash}; a missing hash never matches (but still costs a BCrypt round). */
    public boolean matches(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return false;
        }
        boolean matched = PASSWORDS.matches(password, hash == null ? DUMMY_HASH : hash);
        return matched && hash != null;
    }

    // ---- link access tokens ----

    public record Signed(String token, long expiresInSeconds) {
    }

    public Signed mintLinkAccess(UUID linkId) {
        Instant exp = Instant.now().plus(LINK_ACCESS_TTL);
        return new Signed(sign("L;" + linkId + ";" + exp.getEpochSecond()), LINK_ACCESS_TTL.toSeconds());
    }

    /** The link this token was issued for, or empty when it is malformed, wrongly signed or expired. */
    public Optional<UUID> verifyLinkAccess(String token) {
        return verify(token, "L", 3).map(parts -> UUID.fromString(parts[1]));
    }

    // ---- guest tokens ----

    public record GuestClaim(UUID tenantId, UUID guestId) {
    }

    public Signed mintGuest(UUID tenantId, UUID guestId) {
        Instant exp = Instant.now().plus(GUEST_TTL);
        return new Signed(sign("G;" + tenantId + ";" + guestId + ";" + exp.getEpochSecond()), GUEST_TTL.toSeconds());
    }

    public Optional<GuestClaim> verifyGuest(String token) {
        return verify(token, "G", 4).map(parts -> new GuestClaim(UUID.fromString(parts[1]), UUID.fromString(parts[2])));
    }

    // ---- internals ----

    private String sign(String payload) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + mac(body);
    }

    /** The payload fields, or empty for anything malformed, wrongly signed or expired - never throws on bad input. */
    private Optional<String[]> verify(String token, String kind, int fields) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        if (!MessageDigest.isEqual(mac(body).getBytes(StandardCharsets.UTF_8),
                token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split(";", -1);
            if (parts.length != fields || !kind.equals(parts[0])) {
                return Optional.empty();
            }
            Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[fields - 1]));
            return expires.isAfter(Instant.now()) ? Optional.of(parts) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String mac(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign a sharing token", e);
        }
    }
}
