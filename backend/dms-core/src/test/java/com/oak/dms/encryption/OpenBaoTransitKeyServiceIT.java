package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.encryption.KeyEncryptionService.DataKey;
import com.oak.dms.encryption.KeyEncryptionService.KeyServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Against a REAL OpenBao (Transit engine enabled at "transit"): set
 * OPENBAO_IT_ADDR and OPENBAO_IT_TOKEN to run it, e.g. a throwaway dev server:
 * <pre>
 *   docker run -d --name bao-it -p 18200:8200 -e BAO_DEV_ROOT_TOKEN_ID=$TOKEN openbao/openbao:2.6.3 server -dev
 *   docker exec -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=$TOKEN bao-it bao secrets enable transit
 *   OPENBAO_IT_ADDR=http://localhost:18200 OPENBAO_IT_TOKEN=$TOKEN mvn test -Dtest=OpenBaoTransitKeyServiceIT
 * </pre>
 * Skipped otherwise. Every run uses fresh tenant scopes, so it can repeat.
 */
class OpenBaoTransitKeyServiceIT {

    private OpenBaoTransitKeyService keys;
    private final String tenantA = UUID.randomUUID().toString();
    private final String tenantB = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        String address = System.getenv("OPENBAO_IT_ADDR");
        String token = System.getenv("OPENBAO_IT_TOKEN");
        assumeTrue(address != null && !address.isBlank() && token != null && !token.isBlank(),
                "OPENBAO_IT_ADDR / OPENBAO_IT_TOKEN not set - no OpenBao to test against");
        EncryptionProperties.OpenBao config = new EncryptionProperties.OpenBao();
        config.setAddress(address);
        config.setToken(token);
        config.setKeyName("dms-it");
        keys = new OpenBaoTransitKeyService(RestClient.builder(), new ObjectMapper(), config);
    }

    @Test
    void perTenantKeys_areCreatedOnFirstUse_andRoundTrip() {
        DataKey dataKey = keys.generateDataKey(tenantA);

        assertThat(dataKey.plaintext()).hasSize(32);
        assertThat(dataKey.wrapped()).startsWith("vault:v1:");
        assertThat(keys.unwrap(tenantA, dataKey.wrapped())).isEqualTo(dataKey.plaintext());
        assertThat(keys.info(tenantA).keyName()).isEqualTo("transit/dms-it-" + tenantA);
        assertThat(keys.info(tenantA).managed()).isTrue();
    }

    @Test
    void rotation_keepsOldKeysReadable_andRewrapMovesThemOn_withoutExposingTheDataKey() {
        DataKey old = keys.generateDataKey(tenantA);

        assertThat(keys.rotate(tenantA).latestVersion()).isEqualTo(2);
        assertThat(keys.unwrap(tenantA, old.wrapped())).isEqualTo(old.plaintext());

        String rewrapped = keys.rewrap(tenantA, old.wrapped());
        assertThat(rewrapped).startsWith("vault:v2:");
        assertThat(keys.unwrap(tenantA, rewrapped)).isEqualTo(old.plaintext());
        assertThat(keys.generateDataKey(tenantA).version()).isEqualTo(2);
    }

    @Test
    void oneTenantsKey_neverOpensAnothers_andAKeyCanBeMovedExplicitly() {
        DataKey forA = keys.generateDataKey(tenantA);
        keys.generateDataKey(tenantB);

        assertThatThrownBy(() -> keys.unwrap(tenantB, forA.wrapped())).isInstanceOf(KeyServiceException.class);

        String moved = keys.wrap(tenantB, forA.plaintext());
        assertThat(keys.unwrap(tenantB, moved)).isEqualTo(forA.plaintext());
    }

    @Test
    void anObject_encryptedWithOpenBaoKeys_comesBackIntact() throws Exception {
        ObjectCipher cipher = new ObjectCipher(4096);
        DataKey dataKey = keys.generateDataKey(tenantA);
        byte[] plaintext = "Contract - CONFIDENTIAL".repeat(1000).getBytes();
        byte[] aad = ObjectCipher.associatedData("loc", "docs/x.pdf");

        byte[] stored;
        try (InputStream in = cipher.encrypting(new ByteArrayInputStream(plaintext), dataKey.plaintext(), aad)) {
            stored = in.readAllBytes();
        }
        byte[] unwrapped = keys.unwrap(tenantA, dataKey.wrapped()); // as a later download would
        try (InputStream in = cipher.decrypting(new ByteArrayInputStream(stored), unwrapped, aad)) {
            assertThat(in.readAllBytes()).isEqualTo(plaintext);
        }
    }
}
