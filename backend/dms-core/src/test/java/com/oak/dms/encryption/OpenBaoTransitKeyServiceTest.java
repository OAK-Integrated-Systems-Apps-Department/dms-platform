package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.encryption.KeyEncryptionService.DataKey;
import com.oak.dms.encryption.KeyEncryptionService.KeyServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;

import static com.oak.dms.encryption.KeyEncryptionService.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The OpenBao Transit calls: per-tenant keys, paths, token, parsing - and errors never echo the body. */
class OpenBaoTransitKeyServiceTest {

    private static final String BASE = "http://bao:8200/v1/transit";
    private static final String TENANT = "6b1f0d5e-0000-4000-8000-000000000001";
    private static final String KEY_OK = "{\"data\":{\"latest_version\":1,\"min_decryption_version\":1}}";
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        EncryptionProperties.OpenBao config = new EncryptionProperties.OpenBao();
        config.setAddress("http://bao:8200/");
        config.setToken("s.test-token");
        this.config = config;
        this.builder = builder;
        // A fixed token is looked up once, to learn whether (and when) it needs renewing.
        server.expect(requestTo("http://bao:8200/v1/auth/token/lookup-self"))
                .andExpect(header("X-Vault-Token", "s.test-token"))
                .andRespond(withSuccess("{\"data\":{\"ttl\":0,\"renewable\":false}}", MediaType.APPLICATION_JSON));
    }

    private EncryptionProperties.OpenBao config;
    private RestClient.Builder builder;

    /** Built after a test has declared its expected requests (the mock server needs them all first). */
    private OpenBaoTransitKeyService keys() {
        return new OpenBaoTransitKeyService(builder, new ObjectMapper(), config, false);
    }

    @Test
    void eachTenantHasItsOwnTransitKey_createdOnFirstUse() {
        byte[] dataKey = new byte[32];
        dataKey[0] = 7;
        String b64 = Base64.getEncoder().encodeToString(dataKey);
        String tenantKey = BASE + "/keys/dms-documents-" + TENANT;
        server.expect(requestTo(tenantKey)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(tenantKey)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"type\":\"aes256-gcm96\"}")).andRespond(withSuccess());
        server.expect(requestTo(BASE + "/datakey/plaintext/dms-documents-" + TENANT))
                .andExpect(header("X-Vault-Token", "s.test-token"))
                .andExpect(content().json("{\"bits\":256}"))
                .andRespond(withSuccess("{\"data\":{\"plaintext\":\"" + b64 + "\",\"ciphertext\":\"vault:v1:abc\","
                        + "\"key_version\":1}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/decrypt/dms-documents-" + TENANT))
                .andExpect(content().json("{\"ciphertext\":\"vault:v1:abc\"}"))
                .andRespond(withSuccess("{\"data\":{\"plaintext\":\"" + b64 + "\"}}", MediaType.APPLICATION_JSON));

        OpenBaoTransitKeyService keys = keys();

        DataKey generated = keys.generateDataKey(TENANT);
        assertThat(generated.plaintext()).isEqualTo(dataKey);
        assertThat(generated.wrapped()).isEqualTo("vault:v1:abc");
        assertThat(keys.unwrap(TENANT, "vault:v1:abc")).isEqualTo(dataKey); // key known now - no second lookup
        server.verify();
    }

    @Test
    void rewrapsWrapsRotatesAndReports_thePlatformKeyByItsPlainName() {
        server.expect(requestTo(BASE + "/keys/dms-documents")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(KEY_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/rewrap/dms-documents"))
                .andRespond(withSuccess("{\"data\":{\"ciphertext\":\"vault:v4:def\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/encrypt/dms-documents"))
                .andExpect(content().json("{\"plaintext\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"}"))
                .andRespond(withSuccess("{\"data\":{\"ciphertext\":\"vault:v4:ghi\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/keys/dms-documents/rotate")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/keys/dms-documents")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{\"latest_version\":5,\"min_decryption_version\":2}}",
                        MediaType.APPLICATION_JSON));

        OpenBaoTransitKeyService keys = keys();

        assertThat(KeyEncryptionService.versionOf(keys.rewrap(PLATFORM, "vault:v3:abc"))).isEqualTo(4);
        assertThat(keys.wrap(PLATFORM, new byte[32])).isEqualTo("vault:v4:ghi");
        KeyEncryptionService.KeyInfo info = keys.rotate(PLATFORM);
        assertThat(info.latestVersion()).isEqualTo(5);
        assertThat(info.minDecryptionVersion()).isEqualTo(2);
        assertThat(info.keyName()).isEqualTo("transit/dms-documents");
        server.verify();
    }

    @Test
    void anError_namesTheStatus_butNeverTheBody() {
        server.expect(requestTo(BASE + "/keys/dms-documents"))
                .andRespond(withSuccess(KEY_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/decrypt/dms-documents"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"errors\":[\"permission denied vault:v1:SECRET\"]}"));

        OpenBaoTransitKeyService keys = keys();

        assertThatThrownBy(() -> keys.unwrap(PLATFORM, "vault:v1:x"))
                .isInstanceOf(KeyServiceException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("SECRET");
    }

    @Test
    void refusesToStartWithoutAnAddressOrToken() {
        assertThatThrownBy(() -> new OpenBaoTransitKeyService(RestClient.builder(), new ObjectMapper(),
                new EncryptionProperties.OpenBao()))
                .isInstanceOf(KeyServiceException.class).hasMessageContaining("ENCRYPTION_OPENBAO_ADDR");
    }
}
