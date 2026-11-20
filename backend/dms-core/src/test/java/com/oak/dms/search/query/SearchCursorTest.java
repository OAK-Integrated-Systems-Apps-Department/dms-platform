package com.oak.dms.search.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.search.config.SearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCursorTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private static SearchCursor cursor(String secret) {
        SearchProperties p = new SearchProperties();
        p.setCursorSecret(secret);
        return new SearchCursor(p);
    }

    private static SearchRequest request(String q) {
        return new SearchRequest(q, null, null, null, null, null, null, null, Set.of("active"), null, false, List.of(), "relevance", false, 20, null);
    }

    @Test
    void roundTripsItsContents() throws Exception {
        SearchCursor c = cursor("secret-1");
        String fp = SearchCursor.fingerprint(tenant, user, request("zebra"));
        var after = new ObjectMapper().readTree("[1.5, 1700000000000, \"abc\"]");
        String token = c.encode(new SearchCursor.State(after, 40, 0, "search"), fp);
        SearchCursor.State state = c.decode(token, fp);
        assertThat(state.searchAfter()).isEqualTo(after);
        assertThat(state.verifiedBefore()).isEqualTo(40);
        assertThat(state.mode()).isEqualTo("search");
    }

    @Test
    void anyChangeToTheTokenOrTheCriteriaIsRejected() {
        SearchCursor c = cursor("secret-1");
        String fp = SearchCursor.fingerprint(tenant, user, request("zebra"));
        String token = c.encode(new SearchCursor.State(null, 0, 2, "basic"), fp);
        assertThatThrownBy(() -> c.decode(token + "A", fp)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.decode("x" + token, fp)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.decode(token, SearchCursor.fingerprint(tenant, user, request("other")))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.decode(token, SearchCursor.fingerprint(tenant, UUID.randomUUID(), request("zebra")))).as("another user")
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.decode(token, SearchCursor.fingerprint(UUID.randomUUID(), user, request("zebra")))).as("another tenant")
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.decode("", fp)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> c.decode("...", fp)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        String fp = SearchCursor.fingerprint(tenant, user, request("zebra"));
        String token = cursor("secret-1").encode(new SearchCursor.State(null, 0, 0, "search"), fp);
        assertThatThrownBy(() -> cursor("secret-2").decode(token, fp)).isInstanceOf(ResponseStatusException.class);
        assertThat(cursor("secret-1").decode(token, fp).mode()).isEqualTo("search");
    }

    @Test
    void theFingerprintDependsOnEveryCriterionButNotOnThePage() {
        SearchRequest base = request("zebra");
        String fp = SearchCursor.fingerprint(tenant, user, base);
        assertThat(SearchCursor.fingerprint(tenant, user, base)).isEqualTo(fp);
        assertThat(SearchCursor.fingerprint(tenant, user, new SearchRequest("zebra", null, null, null, null, null, null, null,
                Set.of("active"), null, false, List.of(), "relevance", false, 50, "some-cursor"))).as("size and cursor are not criteria").isEqualTo(fp);
        assertThat(SearchCursor.fingerprint(tenant, user, new SearchRequest("zebra", UUID.randomUUID(), null, null, null, null, null, null,
                Set.of("active"), null, false, List.of(), "relevance", false, 20, null))).isNotEqualTo(fp);
        assertThat(SearchCursor.fingerprint(tenant, user, new SearchRequest("zebra", null, null, null, Instant.now(), null, null, null,
                Set.of("active"), null, false, List.of(), "relevance", false, 20, null))).isNotEqualTo(fp);
        assertThat(SearchCursor.fingerprint(tenant, user, new SearchRequest("zebra", null, null, null, null, null, null, null,
                Set.of("active", "archived"), null, false, List.of(), "relevance", false, 20, null))).isNotEqualTo(fp);
        assertThat(SearchCursor.fingerprint(tenant, user, new SearchRequest("zebra", null, null, null, null, null, null, null,
                Set.of("active"), null, false, List.of(), "title", true, 20, null))).isNotEqualTo(fp);
    }

    @Test
    void withoutASecretEachInstanceGetsItsOwnRandomKey() {
        String fp = SearchCursor.fingerprint(tenant, user, request("zebra"));
        String token = cursor("").encode(new SearchCursor.State(null, 0, 0, "search"), fp);
        assertThatThrownBy(() -> cursor("").decode(token, fp)).isInstanceOf(ResponseStatusException.class);
    }
}
