package com.oak.dms.search.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * The paging token. Stateless and signed (HMAC-SHA256), carrying the engine's {@code search_after} values, how many
 * verified results came before, and what it is valid for: this tenant, this user, this exact set of criteria. A cursor
 * edited by hand, taken from another user, or replayed with different filters is rejected with a plain 400 that says
 * nothing about why. Without {@code dms.search.cursor-secret} a random per-boot key is used (cursors die on restart).
 */
@Component
public class SearchCursor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Decoded cursor contents. {@code page} is used by the title-search fallback (offset paging). */
    public record State(JsonNode searchAfter, long verifiedBefore, int page, String mode) {
    }

    private final byte[] key;

    public SearchCursor(SearchProperties props) {
        String configured = props.getCursorSecret();
        if (configured == null || configured.isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A short digest of everything that defines "the same search" except the page. */
    public static String fingerprint(UUID tenantId, UUID userId, SearchRequest r) {
        String canonical = String.join("|", tenantId.toString(), userId.toString(), String.valueOf(r.q()),
                String.valueOf(r.documentTypeId()), String.valueOf(r.departmentId()), String.valueOf(r.ownerId()),
                String.valueOf(r.createdFrom()), String.valueOf(r.createdTo()), String.valueOf(r.modifiedFrom()),
                String.valueOf(r.modifiedTo()), String.valueOf(new java.util.TreeSet<>(r.statuses())),
                String.valueOf(r.folderId()), String.valueOf(r.includeSubfolders()), String.valueOf(r.meta()),
                String.valueOf(r.sort()), String.valueOf(r.ascending()));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encode(State state, String fingerprint) {
        ObjectNode payload = JSON.createObjectNode();
        if (state.searchAfter() != null) {
            payload.set("v", state.searchAfter());
        }
        payload.put("n", state.verifiedBefore());
        payload.put("p", state.page());
        payload.put("m", state.mode());
        payload.put("f", fingerprint);
        try {
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
            return body + "." + sign(body);
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode the search cursor", e);
        }
    }

    public State decode(String token, String expectedFingerprint) {
        try {
            int dot = token.indexOf('.');
            if (dot < 0) {
                throw bad();
            }
            String body = token.substring(0, dot);
            if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                    token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
                throw bad();
            }
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(body));
            if (!expectedFingerprint.equals(payload.path("f").asText())) {
                throw bad();
            }
            return new State(payload.has("v") ? payload.get("v") : null, payload.path("n").asLong(), payload.path("p").asInt(),
                    payload.path("m").asText("search"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw bad();
        }
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "That page link is not valid any more. Run the search again.");
    }

    private String sign(String body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
