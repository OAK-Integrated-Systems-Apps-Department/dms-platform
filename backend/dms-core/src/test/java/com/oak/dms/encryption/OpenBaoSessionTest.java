package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.encryption.KeyEncryptionService.KeyServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static com.oak.dms.encryption.KeyEncryptionService.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Signing in to OpenBao: AppRole login, renewal before expiry, signing in again - and the audit-device check. */
class OpenBaoSessionTest {

    private static final String BASE = "http://bao:8200/v1";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private final EncryptionProperties.OpenBao config = new EncryptionProperties.OpenBao();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        config.setAddress("http://bao:8200");
        config.setAuth("approle");
        config.setRoleId("role-1");
        config.setSecretId("secret-1");
    }

    @Test
    void approle_signsIn_renewsBeforeExpiry_andSignsInAgainWhenRenewalFails() {
        // The mock server needs every request declared before the first one is made.
        expectLogin("s.first", 3600, true);
        server.expect(requestTo(BASE + "/auth/token/renew-self")).andExpect(header("X-Vault-Token", "s.first"))
                .andRespond(withSuccess("{\"auth\":{\"lease_duration\":3600,\"renewable\":true}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/auth/token/renew-self")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectLogin("s.second", 3600, true);

        try (OpenBaoSession session = new OpenBaoSession(builder.build(), new ObjectMapper(), config, clock, false)) {
            assertThat(session.token()).isEqualTo("s.first");

            // Two thirds of the hour gone: due for renewal.
            now.set(now.get().plus(Duration.ofMinutes(45)));
            assertThat(session.token()).isEqualTo("s.first");
            assertThat(session.expiresAt()).isEqualTo(now.get().plusSeconds(3600));

            // The token can't be renewed any more (revoked, max TTL): sign in afresh.
            now.set(now.get().plus(Duration.ofMinutes(45)));
            assertThat(session.token()).isEqualTo("s.second");
        }
        server.verify();
    }

    @Test
    void aRefusedRequest_signsInAgain_andIsRetriedOnce() {
        expectLogin("s.first", 3600, true);
        server.expect(requestTo(BASE + "/transit/keys/dms-documents")).andExpect(header("X-Vault-Token", "s.first"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectLogin("s.second", 3600, true);
        server.expect(requestTo(BASE + "/transit/keys/dms-documents")).andExpect(header("X-Vault-Token", "s.second"))
                .andRespond(withSuccess("{\"data\":{\"latest_version\":1}}", MediaType.APPLICATION_JSON));
        OpenBaoTransitKeyService keys = keys();

        keys.ensureKey(PLATFORM);
        server.verify();
    }

    @Test
    void approle_withoutItsIds_refusesToStart() {
        config.setSecretId("");
        assertThatThrownBy(() -> new OpenBaoSession(builder.build(), new ObjectMapper(), config, clock, false))
                .isInstanceOf(KeyServiceException.class).hasMessageContaining("ENCRYPTION_OPENBAO_SECRET_ID");
    }

    @Test
    void theAuditDeviceCheck_refusesWithoutOne_whenRequired() {
        expectLogin("s.t", 3600, true);
        server.expect(requestTo(BASE + "/sys/audit")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/sys/audit"))
                .andRespond(withSuccess("{\"data\":{\"file/\":{\"type\":\"file\"}}}", MediaType.APPLICATION_JSON));
        OpenBaoTransitKeyService keys = keys();

        assertThatThrownBy(() -> EncryptionConfig.checkAuditDevice(keys, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no audit device");
        EncryptionConfig.checkAuditDevice(keys, true); // now there is one
        server.verify();
    }

    @Test
    void bulkUnwrap_isOneRequestForManyKeys_andAFailedKeyComesBackNull() {
        expectLogin("s.t", 3600, true);
        server.expect(requestTo(BASE + "/transit/keys/dms-documents"))
                .andRespond(withSuccess("{\"data\":{\"latest_version\":1}}", MediaType.APPLICATION_JSON));
        String a = java.util.Base64.getEncoder().encodeToString(new byte[]{1});
        server.expect(requestTo(BASE + "/transit/decrypt/dms-documents"))
                .andExpect(content().json("{\"batch_input\":[{\"ciphertext\":\"vault:v1:a\"},{\"ciphertext\":\"vault:v1:b\"}]}"))
                .andRespond(withSuccess("{\"data\":{\"batch_results\":[{\"plaintext\":\"" + a + "\"},"
                        + "{\"error\":\"cipher: message authentication failed\"}]}}", MediaType.APPLICATION_JSON));
        OpenBaoTransitKeyService keys = keys();

        var unwrapped = keys.unwrapAll(PLATFORM, java.util.List.of("vault:v1:a", "vault:v1:b"));

        assertThat(unwrapped.get(0)).containsExactly(1);
        assertThat(unwrapped.get(1)).isNull();
        server.verify();
    }

    private OpenBaoTransitKeyService opened;

    @AfterEach
    void closeKeys() {
        if (opened != null) {
            opened.close();
        }
    }

    /** Built after a test has declared all its expected requests - it signs in straight away. */
    private OpenBaoTransitKeyService keys() {
        opened = new OpenBaoTransitKeyService(builder, new ObjectMapper(), config, false);
        return opened;
    }

    private void expectLogin(String token, int seconds, boolean renewable) {
        server.expect(requestTo(BASE + "/auth/approle/login")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"role_id\":\"role-1\",\"secret_id\":\"secret-1\"}"))
                .andRespond(withSuccess("{\"auth\":{\"client_token\":\"" + token + "\",\"lease_duration\":" + seconds
                        + ",\"renewable\":" + renewable + "}}", MediaType.APPLICATION_JSON));
    }
}
