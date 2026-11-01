package com.oak.dms.security.auth;

import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How long the session cookies live: "Remember me" decides whether the refresh cookie outlasts the browser. */
class AuthCookieServiceTest {

    private final AuthCookieProperties properties = new AuthCookieProperties();
    private final AuthCookieService service = new AuthCookieService(properties);

    private String refreshCookie(KeycloakTokenResponse tokens, boolean rememberMe) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writeSessionCookies(response, tokens, rememberMe);
        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        return cookies.stream().filter(c -> c.startsWith(AuthCookieService.REFRESH_TOKEN_COOKIE + "=")).findFirst()
                .orElseThrow();
    }

    private static KeycloakTokenResponse tokens(long refreshExpiresIn) {
        return new KeycloakTokenResponse("access", 1800, "refresh", refreshExpiresIn, "Bearer");
    }

    @Test
    void withoutRememberMe_theRefreshCookieIsASessionCookie() {
        assertThat(refreshCookie(tokens(1800), false)).doesNotContain("Max-Age");
    }

    @Test
    void rememberMe_withAnExpiringToken_usesTheTokensOwnLifetime() {
        assertThat(refreshCookie(tokens(1800), true)).contains("Max-Age=1800");
    }

    @Test
    void rememberMe_withAnOfflineToken_usesTheConfiguredLifetime_notZero() {
        // An offline token reports refresh_expires_in 0; a Max-Age of 0 would delete the cookie at once.
        properties.setRememberMeMaxAge(Duration.ofDays(30));

        assertThat(refreshCookie(tokens(0), true)).contains("Max-Age=" + Duration.ofDays(30).toSeconds());
    }
}
