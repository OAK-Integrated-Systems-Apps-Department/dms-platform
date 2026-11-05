package com.oak.dms.editing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The editing engine's access token is the ONLY thing standing between the internet-facing-by-accident
 * /internal/v1/wopi endpoints and a document, so each way of forging or stretching one is its own test.
 */
class WopiTokenCodecTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();

    private final WopiTokenCodec codec = new WopiTokenCodec("a-test-secret-that-is-long-enough-0123456789");

    private WopiTokenCodec.Grant grant(Instant expires) {
        return new WopiTokenCodec.Grant(TENANT, USER, DOCUMENT, "Alice Perera", expires);
    }

    @Test
    void aMintedToken_saysExactlyWhatItWasMintedFor() {
        Instant expires = Instant.now().plusSeconds(600);
        String token = codec.mint(grant(expires));

        WopiTokenCodec.Grant back = codec.verify(token).orElseThrow();
        assertThat(back.tenantId()).isEqualTo(TENANT);
        assertThat(back.userId()).isEqualTo(USER);
        assertThat(back.documentId()).isEqualTo(DOCUMENT);
        assertThat(back.userName()).isEqualTo("Alice Perera");
        assertThat(back.expiresAt().getEpochSecond()).isEqualTo(expires.getEpochSecond());
    }

    @Test
    void anExpiredToken_isRefused() {
        assertThat(codec.verify(codec.mint(grant(Instant.now().minusSeconds(1))))).isEmpty();
    }

    @Test
    void changingAnyClaim_breaksTheSignature() {
        String token = codec.mint(grant(Instant.now().plusSeconds(600)));
        String[] parts = token.split("\\.");
        // Swap the payload for another document's, keep the old signature: this is the "widen it" attack.
        String otherPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (TENANT + ";" + USER + ";" + UUID.randomUUID() + ";" + Instant.now().plusSeconds(600).getEpochSecond() + ";")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(codec.verify(otherPayload + "." + parts[1])).isEmpty();
        // And lengthening the lifetime.
        String longer = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (TENANT + ";" + USER + ";" + DOCUMENT + ";" + Instant.now().plusSeconds(999_999).getEpochSecond() + ";")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(codec.verify(longer + "." + parts[1])).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherSecret_isRefused() {
        String foreign = new WopiTokenCodec("some-completely-different-secret-9876543210")
                .mint(grant(Instant.now().plusSeconds(600)));
        assertThat(codec.verify(foreign)).isEmpty();
    }

    @Test
    void garbage_isRefusedQuietly() {
        for (String bad : new String[]{null, "", ".", "abc", "abc.", ".abc", "a.b.c", "not base64!.sig"}) {
            assertThat(codec.verify(bad)).as("token %s", bad).isEmpty();
        }
    }

    @Test
    void withNoSecret_nothingCanBeMinted_andNothingVerifies() {
        WopiTokenCodec off = new WopiTokenCodec("");
        assertThat(off.isConfigured()).isFalse();
        assertThatThrownBy(() -> off.mint(grant(Instant.now().plusSeconds(60)))).isInstanceOf(IllegalStateException.class);
        // A blank secret must not mean "sign with an empty key" - a token anybody could mint.
        assertThat(off.verify(codec.mint(grant(Instant.now().plusSeconds(60))))).isEmpty();
    }
}
