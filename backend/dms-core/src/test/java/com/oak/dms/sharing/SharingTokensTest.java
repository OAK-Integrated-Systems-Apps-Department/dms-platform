package com.oak.dms.sharing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The secrets of external sharing: unguessable tokens, one-way storage, signed bound access tokens. */
class SharingTokensTest {

    private SharingTokens tokens;

    @BeforeEach
    void setUp() {
        tokens = new SharingTokens("a-test-secret-that-is-long-enough");
        tokens.init();
    }

    @Test
    void tokensAreLongRandomAndUnique() {
        String a = tokens.newToken();
        String b = tokens.newToken();
        assertThat(a).isNotEqualTo(b).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void theStoredHashIsStableOneWayAndNotTheToken() {
        String token = tokens.newToken();
        assertThat(tokens.hash(token)).isEqualTo(tokens.hash(token)).hasSize(64).isNotEqualTo(token);
        assertThat(tokens.hash(token)).isNotEqualTo(tokens.hash(token + "x"));
    }

    @Test
    void passwordsAreBcryptHashed_andOnlyTheRightOneMatches() {
        String hash = tokens.hashPassword("correct-horse");
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse");
        assertThat(tokens.matches("correct-horse", hash)).isTrue();
        assertThat(tokens.matches("wrong", hash)).isFalse();
        assertThat(tokens.matches(null, hash)).isFalse();
        assertThat(tokens.matches("correct-horse", null)).isFalse();
    }

    @Test
    void aPasswordBcryptWouldTruncateIsRefused() {
        String tooLong = "x".repeat(SharingTokens.MAX_PASSWORD_BYTES + 1);
        assertThatThrownBy(() -> tokens.hashPassword(tooLong)).isInstanceOf(SharingException.class);
        assertThat(tokens.matches(tooLong, tokens.hashPassword("short-one"))).isFalse();
    }

    @Test
    void aLinkAccessTokenIsBoundToItsLink_andCannotBeForgedOrAltered() {
        UUID link = UUID.randomUUID();
        String token = tokens.mintLinkAccess(link).token();
        assertThat(tokens.verifyLinkAccess(token)).contains(link);

        assertThat(tokens.verifyLinkAccess(token + "x")).isEmpty();
        assertThat(tokens.verifyLinkAccess("x" + token)).isEmpty();
        assertThat(tokens.verifyLinkAccess(null)).isEmpty();
        assertThat(tokens.verifyLinkAccess("no-dot")).isEmpty();
        assertThat(tokens.verifyLinkAccess("")).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        SharingTokens other = new SharingTokens("a-different-secret-entirely-here");
        other.init();
        assertThat(tokens.verifyLinkAccess(other.mintLinkAccess(UUID.randomUUID()).token())).isEmpty();
    }

    @Test
    void aGuestTokenIsNotALinkToken_andTheOtherWayRound() {
        UUID tenant = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        String guestToken = tokens.mintGuest(tenant, guest).token();
        String linkToken = tokens.mintLinkAccess(UUID.randomUUID()).token();

        assertThat(tokens.verifyGuest(guestToken)).contains(new SharingTokens.GuestClaim(tenant, guest));
        assertThat(tokens.verifyLinkAccess(guestToken)).isEmpty();
        assertThat(tokens.verifyGuest(linkToken)).isEmpty();
    }

    @Test
    void withNoConfiguredSecretItStillSignsAndVerifies_withAKeyMadeAtStartUp() {
        SharingTokens random = new SharingTokens("");
        random.init();
        UUID link = UUID.randomUUID();
        assertThat(random.verifyLinkAccess(random.mintLinkAccess(link).token())).contains(link);
        // ...but a second instance has its own key, so a token from one is not honoured by the other.
        SharingTokens second = new SharingTokens("");
        second.init();
        assertThat(second.verifyLinkAccess(random.mintLinkAccess(link).token())).isEmpty();
    }
}
