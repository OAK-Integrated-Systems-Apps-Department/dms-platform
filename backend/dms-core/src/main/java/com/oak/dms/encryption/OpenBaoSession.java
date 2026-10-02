package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.encryption.KeyEncryptionService.KeyServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * dms-core's sign-in to OpenBao, kept alive:
 * <ul>
 *   <li><b>approle</b> (production) - logs in with a role id and secret id,
 *       renews its token before it expires, and logs in again when renewal is
 *       no longer possible (max TTL reached) or a request is refused;</li>
 *   <li><b>token</b> - a fixed token (development); renewed when renewable.</li>
 * </ul>
 * A background check renews ahead of expiry, so no request waits on it. A
 * failure is logged as ERROR every time - an expired token means no document
 * can be opened - and the next request tries again.
 */
@Slf4j
public class OpenBaoSession implements AutoCloseable {

    /** Renew once less than this share of the token's lifetime is left. */
    private static final double RENEW_AT = 1.0 / 3;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final EncryptionProperties.OpenBao config;
    private final Clock clock;
    private final ScheduledExecutorService renewer;

    private volatile String token;
    /** null = never expires (or unknown) */
    private volatile Instant expiresAt;
    private volatile Duration lifetime;
    private volatile boolean renewable;

    public OpenBaoSession(RestClient client, ObjectMapper mapper, EncryptionProperties.OpenBao config, Clock clock,
                          boolean background) {
        this.client = client;
        this.mapper = mapper;
        this.config = config;
        this.clock = clock;
        if (isAppRole()) {
            if (blank(config.getRoleId()) || blank(config.getSecretId())) {
                throw new KeyServiceException("OpenBao AppRole sign-in needs dms.encryption.openbao.role-id and "
                        + ".secret-id (ENCRYPTION_OPENBAO_ROLE_ID / ENCRYPTION_OPENBAO_SECRET_ID)");
            }
            login();
        } else {
            if (blank(config.getToken())) {
                throw new KeyServiceException("dms.encryption.provider=openbao needs a token (ENCRYPTION_OPENBAO_TOKEN) "
                        + "or AppRole sign-in (ENCRYPTION_OPENBAO_AUTH=approle)");
            }
            token = config.getToken();
            lookupSelf();
        }
        if (background) {
            renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "openbao-token-renewer");
                thread.setDaemon(true);
                return thread;
            });
            renewer.scheduleWithFixedDelay(this::renewIfDue, 30, 30, TimeUnit.SECONDS);
        } else {
            renewer = null;
        }
    }

    /** The current token, renewed first if it's about to expire. */
    public String token() {
        if (dueForRenewal()) {
            renewIfDue();
        }
        return token;
    }

    /** A request was refused (403): the token may have been revoked - sign in again if we can. */
    public synchronized boolean recoverFromForbidden(String rejected) {
        if (!isAppRole() || !rejected.equals(token)) {
            return isAppRole(); // someone else already refreshed it - retry with the new one
        }
        log.warn("OpenBao refused dms-core's token - signing in again");
        login();
        return true;
    }

    /** When the current token expires - null if it doesn't. For the key screen and tests. */
    public Instant expiresAt() {
        return expiresAt;
    }

    synchronized void renewIfDue() {
        if (!dueForRenewal()) {
            return;
        }
        try {
            if (renewable) {
                JsonNode auth = post("/auth/token/renew-self", Map.of(), token).path("auth");
                applyLease(auth.path("lease_duration").asLong(0), auth.path("renewable").asBoolean(false));
                // A renewal capped by the max TTL leaves too little time: sign in afresh while we still can.
                if (isAppRole() && lifetime != null && lifetime.compareTo(Duration.ofMinutes(2)) < 0) {
                    login();
                }
            } else if (isAppRole()) {
                login();
            }
        } catch (RuntimeException e) {
            log.error("Could not renew dms-core's OpenBao token ({}) - documents can't be opened once it expires "
                    + "at {}", e.getMessage(), expiresAt);
            if (isAppRole()) {
                try {
                    login();
                } catch (RuntimeException again) {
                    log.error("OpenBao sign-in failed too: {}", again.getMessage());
                }
            }
        }
    }

    private boolean dueForRenewal() {
        Instant expiry = expiresAt;
        Duration span = lifetime;
        if (expiry == null || span == null) {
            return false;
        }
        long remaining = Duration.between(clock.instant(), expiry).toMillis();
        return remaining < span.toMillis() * RENEW_AT;
    }

    private synchronized void login() {
        String path = "/auth/" + config.getAppRoleMount() + "/login";
        JsonNode auth = post(path, Map.of("role_id", config.getRoleId(), "secret_id", config.getSecretId()), null)
                .path("auth");
        String fresh = auth.path("client_token").asText(null);
        if (blank(fresh)) {
            throw new KeyServiceException("OpenBao AppRole sign-in returned no token");
        }
        token = fresh;
        applyLease(auth.path("lease_duration").asLong(0), auth.path("renewable").asBoolean(false));
        log.info("Signed in to OpenBao with AppRole (token valid {})", lifetime == null ? "indefinitely" : lifetime);
    }

    private void lookupSelf() {
        try {
            String body = client.get().uri("/auth/token/lookup-self").header("X-Vault-Token", token)
                    .retrieve().body(String.class);
            JsonNode data = mapper.readTree(body == null ? "{}" : body).path("data");
            applyLease(data.path("ttl").asLong(0), data.path("renewable").asBoolean(false));
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Not fatal: some tokens may not look themselves up; the first real request will tell.
            log.warn("Could not look up dms-core's OpenBao token: {}", e.getMessage());
        }
    }

    private void applyLease(long seconds, boolean canRenew) {
        renewable = canRenew;
        if (seconds <= 0) {
            expiresAt = null;
            lifetime = null;
        } else {
            lifetime = Duration.ofSeconds(seconds);
            expiresAt = clock.instant().plusSeconds(seconds);
        }
    }

    private JsonNode post(String path, Map<String, Object> body, String withToken) {
        try {
            var request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body));
            if (withToken != null) {
                request = request.header("X-Vault-Token", withToken);
            }
            String response = request.retrieve().body(String.class);
            return mapper.readTree(response == null ? "{}" : response);
        } catch (RestClientException e) {
            // An HTTP error isn't kept as the cause: its message carries the body, which can hold a token.
            if (e instanceof org.springframework.web.client.RestClientResponseException r) {
                throw new KeyServiceException("OpenBao refused " + path + " (HTTP " + r.getStatusCode().value() + ")");
            }
            throw new KeyServiceException("OpenBao refused " + path, e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new KeyServiceException("OpenBao returned an unreadable answer for " + path, e);
        }
    }

    private boolean isAppRole() {
        return "approle".equalsIgnoreCase(config.getAuth());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void close() {
        if (renewer != null) {
            renewer.shutdownNow();
        }
    }
}
