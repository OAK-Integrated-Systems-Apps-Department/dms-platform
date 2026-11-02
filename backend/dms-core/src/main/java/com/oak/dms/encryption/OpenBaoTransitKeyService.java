package com.oak.dms.encryption;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Data keys wrapped by OpenBao's Transit secrets engine: master keys never
 * leave OpenBao, every unwrap is an authenticated, audited OpenBao request
 * (DE-NFR-001/006), and rotation and re-wrapping are Transit operations - a
 * re-wrap never exposes the data key.
 * <p>
 * One Transit key per scope: {@code <key-name>} for the platform scope,
 * {@code <key-name>-<tenantId>} for each tenant (created on first use,
 * aes256-gcm96, not exportable). The policy needs these paths for
 * {@code <key-name>*}: datakey/plaintext, encrypt, decrypt, rewrap (update),
 * keys (read, create) and keys/.../rotate (update) - plus sys/audit (read,
 * sudo) when the audit-device check is on.
 * <p>
 * Signs in through {@link OpenBaoSession} (AppRole in production, renewed
 * before expiry); a refused request signs in again and is retried once.
 * Bodies are handled as strings with the application's Jackson 2 mapper.
 * <p>
 * {@link #ensureSigningKey} / {@link #sign} are the digital-signature
 * feature's hook into this same class (1.4, module DSI -
 * {@code com.oak.dms.signature.service.TransitDocumentSigner}): an ECDSA
 * P-256, non-exportable key named {@code dms-sig-<tenantId>-<userId>-<generation>}
 * rather than this class's own {@code keyNameFor} scoping, since a signing
 * key belongs to one user, not one tenant/platform scope. The policy needs
 * these EXTRA paths for {@code dms-sig-*} (on top of the encryption paths
 * above, typically a separate token/policy - see
 * docs/storage-architecture.md): keys (read, create - never delete, since
 * revoking a key must never affect signatures it already made) and
 * sign/.../sha2-256 (update).
 */
@Slf4j
public class OpenBaoTransitKeyService implements KeyEncryptionService, AutoCloseable {

    public static final String PROVIDER = "openbao";
    /** Transit batch requests are split into chunks of this many items. */
    private static final int BATCH = 100;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final OpenBaoSession session;
    private final String mount;
    private final String keyName;
    private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();

    public OpenBaoTransitKeyService(RestClient.Builder builder, ObjectMapper mapper, EncryptionProperties.OpenBao config) {
        this(builder, mapper, config, true);
    }

    /** @param background renew the token on a background thread (off in unit tests) */
    public OpenBaoTransitKeyService(RestClient.Builder builder, ObjectMapper mapper, EncryptionProperties.OpenBao config,
                                    boolean background) {
        if (config.getAddress().isBlank()) {
            throw new KeyServiceException("dms.encryption.provider=openbao needs dms.encryption.openbao.address "
                    + "(ENCRYPTION_OPENBAO_ADDR)");
        }
        this.client = builder.baseUrl(config.getAddress().replaceAll("/+$", "") + "/v1").build();
        this.mapper = mapper;
        this.session = new OpenBaoSession(client, mapper, config, Clock.systemUTC(), background);
        this.mount = config.getMount();
        this.keyName = config.getKeyName();
    }

    /** The Transit key for a scope. */
    String keyNameFor(String scope) {
        return PLATFORM.equals(scope) ? keyName : keyName + "-" + scope;
    }

    OpenBaoSession session() {
        return session;
    }

    /** Creates the scope's Transit key if it doesn't exist yet; checks OpenBao is reachable. */
    public void ensureKey(String scope) {
        String name = keyNameFor(scope);
        if (knownKeys.contains(name)) {
            return;
        }
        try {
            authorised(token -> client.get().uri("/{mount}/keys/{name}", mount, name).header("X-Vault-Token", token)
                    .retrieve().body(String.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                throw failure("read the transit key", e);
            }
            call("create the transit key", token -> client.post().uri("/{mount}/keys/{name}", mount, name)
                    .header("X-Vault-Token", token).contentType(MediaType.APPLICATION_JSON)
                    .body(json(Map.of("type", "aes256-gcm96"))).retrieve().toBodilessEntity());
            log.info("Created OpenBao transit key {}/{}", mount, name);
        } catch (RestClientException e) {
            throw failure("reach OpenBao", e);
        }
        knownKeys.add(name);
    }

    /**
     * The enabled audit devices (DE-NFR-006: every unwrap is logged there). Needs read + sudo on sys/audit.
     *
     * @throws KeyServiceException when OpenBao won't say - including a token without the sudo capability
     */
    public List<String> auditDevices() {
        String body = call("list the audit devices", token -> client.get().uri("/sys/audit")
                .header("X-Vault-Token", token).retrieve().body(String.class));
        try {
            JsonNode root = mapper.readTree(body == null ? "{}" : body);
            JsonNode devices = root.has("data") ? root.path("data") : root;
            List<String> names = new ArrayList<>();
            devices.fieldNames().forEachRemaining(name -> {
                if (devices.path(name).has("type")) {
                    names.add(name);
                }
            });
            return names;
        } catch (JsonProcessingException e) {
            throw new KeyServiceException("OpenBao returned an unreadable audit device list", e);
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public DataKey generateDataKey(String scope) {
        JsonNode data = post(scope, "generate a data key", "/{mount}/datakey/plaintext/{name}", Map.of("bits", 256));
        return new DataKey(Base64.getDecoder().decode(data.path("plaintext").asText()),
                data.path("ciphertext").asText(), data.path("key_version").asInt());
    }

    @Override
    public byte[] unwrap(String scope, String wrappedKey) {
        JsonNode data = post(scope, "unwrap a data key", "/{mount}/decrypt/{name}", Map.of("ciphertext", wrappedKey));
        return Base64.getDecoder().decode(data.path("plaintext").asText());
    }

    @Override
    public String rewrap(String scope, String wrappedKey) {
        return post(scope, "re-wrap a data key", "/{mount}/rewrap/{name}", Map.of("ciphertext", wrappedKey))
                .path("ciphertext").asText();
    }

    @Override
    public String wrap(String scope, byte[] dataKey) {
        return post(scope, "wrap a data key", "/{mount}/encrypt/{name}",
                Map.of("plaintext", Base64.getEncoder().encodeToString(dataKey))).path("ciphertext").asText();
    }

    /** One Transit request per 100 keys instead of one per key. A key OpenBao refuses comes back null. */
    @Override
    public List<byte[]> unwrapAll(String scope, List<String> wrappedKeys) {
        return batch(scope, "unwrap data keys", "/{mount}/decrypt/{name}", wrappedKeys,
                result -> Base64.getDecoder().decode(result.path("plaintext").asText()));
    }

    @Override
    public List<String> rewrapAll(String scope, List<String> wrappedKeys) {
        return batch(scope, "re-wrap data keys", "/{mount}/rewrap/{name}", wrappedKeys,
                result -> result.path("ciphertext").asText());
    }

    @Override
    public KeyInfo info(String scope) {
        ensureKey(scope);
        String name = keyNameFor(scope);
        JsonNode data = data("read the transit key", call("read the transit key",
                token -> client.get().uri("/{mount}/keys/{name}", mount, name).header("X-Vault-Token", token)
                        .retrieve().body(String.class)));
        return new KeyInfo(PROVIDER, mount + "/" + name, data.path("latest_version").asInt(),
                data.path("min_decryption_version").asInt(1), true);
    }

    @Override
    public KeyInfo rotate(String scope) {
        ensureKey(scope);
        String name = keyNameFor(scope);
        call("rotate the transit key", token -> client.post().uri("/{mount}/keys/{name}/rotate", mount, name)
                .header("X-Vault-Token", token).retrieve().toBodilessEntity());
        return info(scope);
    }

    /**
     * Transit keys can't be deleted until they're marked deletable, so: mark
     * it, then delete it. Needs update on transit/keys/&lt;name&gt;/config and
     * delete on transit/keys/&lt;name&gt;. Already gone (404 on the read) counts as done.
     */
    @Override
    public void destroy(String scope) {
        KeyEncryptionService.requireTenantScope(scope);
        String name = keyNameFor(scope);
        knownKeys.remove(name);
        try {
            // Read first: OpenBao answers a missing key with 404 here, but with 400 on config and delete.
            authorised(token -> client.get().uri("/{mount}/keys/{name}", mount, name)
                    .header("X-Vault-Token", token).retrieve().toBodilessEntity());
            authorised(token -> client.post().uri("/{mount}/keys/{name}/config", mount, name)
                    .header("X-Vault-Token", token).contentType(MediaType.APPLICATION_JSON)
                    .body(json(Map.of("deletion_allowed", true))).retrieve().toBodilessEntity());
            authorised(token -> client.delete().uri("/{mount}/keys/{name}", mount, name)
                    .header("X-Vault-Token", token).retrieve().toBodilessEntity());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                throw failure("destroy the transit key", e);
            }
        } catch (RestClientException e) {
            throw failure("reach OpenBao", e);
        }
        knownKeys.remove(name);
        log.warn("Destroyed OpenBao transit key {}/{} - everything it wrapped is now unreadable", mount, name);
    }

    /**
     * Creates {@code fullKeyName} as an ECDSA P-256, non-exportable Transit
     * key if it doesn't exist yet - the signing counterpart of
     * {@link #ensureKey}, but taking the caller's own fully-qualified name
     * (e.g. {@code dms-sig-<tenantId>-<userId>-<generation>}) rather than
     * this class's tenant/platform {@link #keyNameFor} scoping.
     */
    public void ensureSigningKey(String fullKeyName) {
        if (knownKeys.contains(fullKeyName)) {
            return;
        }
        try {
            authorised(token -> client.get().uri("/{mount}/keys/{name}", mount, fullKeyName)
                    .header("X-Vault-Token", token).retrieve().body(String.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                throw failure("read the signing key", e);
            }
            call("create the signing key", token -> client.post().uri("/{mount}/keys/{name}", mount, fullKeyName)
                    .header("X-Vault-Token", token).contentType(MediaType.APPLICATION_JSON)
                    .body(json(Map.of("type", "ecdsa-p256", "exportable", false))).retrieve().toBodilessEntity());
            log.info("Created OpenBao signing key {}/{}", mount, fullKeyName);
        } catch (RestClientException e) {
            throw failure("reach OpenBao", e);
        }
        knownKeys.add(fullKeyName);
    }

    /**
     * A DER-encoded (ASN.1) ECDSA signature of a SHA-256 digest the caller
     * already computed, via {@code transit/sign/<name>/sha2-256} with
     * {@code prehashed=true, marshaling_algorithm=asn1} - the exact shape a
     * plain JCA {@code Signature.getInstance("SHA256withECDSA")} verifies
     * with no further conversion. Never logs the digest or the signature -
     * both could be sensitive, and the signature uniquely links a user to a
     * specific document version.
     */
    public byte[] sign(String fullKeyName, byte[] sha256Digest) {
        ensureSigningKey(fullKeyName);
        String response = call("sign the document hash", token -> client.post()
                .uri("/{mount}/sign/{name}/sha2-256", mount, fullKeyName).header("X-Vault-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json(Map.of("input", Base64.getEncoder().encodeToString(sha256Digest),
                        "prehashed", true, "marshaling_algorithm", "asn1")))
                .retrieve().body(String.class));
        String signature = data("sign the document hash", response).path("signature").asText();
        // "vault:v<key-version>:<base64 DER>" - the version prefix is stripped; this
        // class always verifies with the public key copied at signing time, never
        // by re-reading the (possibly since-rotated) Transit key, so the version
        // number itself is not needed by any caller.
        int lastColon = signature.lastIndexOf(':');
        if (lastColon < 0 || lastColon == signature.length() - 1) {
            throw new KeyServiceException("OpenBao returned an unrecognised signature format");
        }
        return Base64.getDecoder().decode(signature.substring(lastColon + 1));
    }

    /** The signing key's current public key, PEM-encoded - read once, right after {@link #ensureSigningKey}. */
    public String signingPublicKeyPem(String fullKeyName) {
        JsonNode data = data("read the signing key", call("read the signing key",
                token -> client.get().uri("/{mount}/keys/{name}", mount, fullKeyName).header("X-Vault-Token", token)
                        .retrieve().body(String.class)));
        int latest = data.path("latest_version").asInt();
        String pem = data.path("keys").path(String.valueOf(latest)).path("public_key").asText(null);
        if (pem == null || pem.isBlank()) {
            throw new KeyServiceException("OpenBao returned no public key for the signing key");
        }
        return pem;
    }

    @Override
    public void close() {
        session.close();
    }

    private <T> List<T> batch(String scope, String what, String path, List<String> ciphertexts,
                              Function<JsonNode, T> read) {
        ensureKey(scope);
        String name = keyNameFor(scope);
        List<T> out = new ArrayList<>(ciphertexts.size());
        for (int from = 0; from < ciphertexts.size(); from += BATCH) {
            List<String> chunk = ciphertexts.subList(from, Math.min(ciphertexts.size(), from + BATCH));
            List<Map<String, String>> input = chunk.stream().map(c -> Map.of("ciphertext", c)).toList();
            // OpenBao answers 400 when any item fails, but still returns every item's result.
            String response = call(what, token -> client.post().uri(path, mount, name).header("X-Vault-Token", token)
                    .contentType(MediaType.APPLICATION_JSON).body(json(Map.of("batch_input", input)))
                    .exchange((request, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        int status = res.getStatusCode().value();
                        if (res.getStatusCode().is2xxSuccessful() || status == 400 && body.contains("\"batch_results\"")) {
                            return body;
                        }
                        // The body is left out: it could hold key material.
                        throw new RestClientResponseException(res.getStatusText(), res.getStatusCode(),
                                res.getStatusText(), null, null, null);
                    }));
            JsonNode results = data(what, response).path("batch_results");
            for (int i = 0; i < chunk.size(); i++) {
                JsonNode result = results.path(i);
                out.add(result.isMissingNode() || result.hasNonNull("error") && !result.path("error").asText().isEmpty()
                        ? null : read.apply(result));
            }
        }
        return out;
    }

    private JsonNode post(String scope, String what, String path, Map<String, Object> body) {
        ensureKey(scope);
        String name = keyNameFor(scope);
        String response = call(what, token -> client.post().uri(path, mount, name).header("X-Vault-Token", token)
                .contentType(MediaType.APPLICATION_JSON).body(json(body)).retrieve().body(String.class));
        return data(what, response);
    }

    private JsonNode data(String what, String response) {
        try {
            JsonNode data = response == null ? null : mapper.readTree(response).path("data");
            if (data == null || data.isMissingNode()) {
                throw new KeyServiceException("OpenBao returned no data when asked to " + what);
            }
            return data;
        } catch (JsonProcessingException e) {
            throw new KeyServiceException("OpenBao returned an unreadable answer when asked to " + what, e);
        }
    }

    private String json(Map<String, ?> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new KeyServiceException("Could not build the OpenBao request", e);
        }
    }

    /** A request with the session's token - a refusal (403) signs in again and retries once. */
    private <T> T authorised(Function<String, T> request) {
        String token = session.token();
        try {
            return request.apply(token);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403 && session.recoverFromForbidden(token)) {
                return request.apply(session.token());
            }
            throw e;
        }
    }

    private <T> T call(String what, Function<String, T> request) {
        try {
            return authorised(request);
        } catch (RestClientException e) {
            throw failure(what, e);
        }
    }

    /**
     * Never includes the response body - it could echo key material. An HTTP
     * error is therefore not kept as the cause (its message carries the body,
     * and a logged stack trace would print it); only its status is named.
     */
    private static KeyServiceException failure(String what, RestClientException e) {
        if (e instanceof RestClientResponseException r) {
            return new KeyServiceException("OpenBao could not " + what + " (HTTP " + r.getStatusCode().value() + ")");
        }
        return new KeyServiceException("OpenBao could not " + what, e);
    }
}
