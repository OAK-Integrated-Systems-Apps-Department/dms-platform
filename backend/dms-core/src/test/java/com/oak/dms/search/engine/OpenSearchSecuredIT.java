package com.oak.dms.search.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in: the engine against a real OpenSearch with the security plugin ON (TLS and basic authentication). Runs only when
 * {@code OPENSEARCH_SECURED_IT=true}; the node, its password and its CA file come from {@code OPENSEARCH_SECURED_URL},
 * {@code OPENSEARCH_SECURED_PASSWORD} and {@code OPENSEARCH_SECURED_CA} (set by whoever starts the disposable node, see
 * docs/search-operations.md). Without them the test is skipped, never failed: it needs a second node's worth of memory.
 */
class OpenSearchSecuredIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private String url;
    private String password;
    private String ca;

    @BeforeEach
    void configured() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("OPENSEARCH_SECURED_IT")), "OPENSEARCH_SECURED_IT is not true: skipped");
        url = System.getenv().getOrDefault("OPENSEARCH_SECURED_URL", "https://localhost:9202");
        password = System.getenv("OPENSEARCH_SECURED_PASSWORD");
        ca = System.getenv("OPENSEARCH_SECURED_CA");
        assumeTrue(password != null && ca != null, "OPENSEARCH_SECURED_PASSWORD and OPENSEARCH_SECURED_CA are required");
    }

    private OpenSearchRestEngine engine(String user, String pass, String caFile) {
        SearchProperties p = new SearchProperties();
        p.getOpensearch().setUrl(url);
        p.getOpensearch().setUsername(user);
        p.getOpensearch().setPassword(pass);
        if (caFile != null) {
            p.getOpensearch().getTls().setCaCertPath(caFile);
        }
        return new OpenSearchRestEngine(p);
    }

    @Test
    void connectsWithTlsAndBasicAuth_andDoesRealWork() throws Exception {
        OpenSearchRestEngine engine = engine("admin", password, ca);
        assertThat(engine.health()).isIn(SearchEngine.Health.GREEN, SearchEngine.Health.YELLOW);
        String index = "it-secured-" + UUID.randomUUID().toString().substring(0, 8);
        engine.createIndex(index, JSON.readTree("{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}"));
        try {
            assertThat(engine.indexExists(index)).isTrue();
            var result = engine.bulk(List.of(SearchEngine.BulkOp.index(index, "1", "r", 1, "{\"a\":\"b\"}")));
            assertThat(result.allOk()).isTrue();
            engine.refresh(index);
            assertThat(engine.count(index, null, JSON.readTree("{\"match_all\":{}}"))).isEqualTo(1);
        } finally {
            engine.deleteIndex(index);
        }
    }

    @Test
    void aWrongPasswordIsRefused_andNeverEchoed() {
        String wrong = "wrong-" + UUID.randomUUID();
        OpenSearchRestEngine engine = engine("admin", wrong, ca);
        assertThat(engine.health()).isEqualTo(SearchEngine.Health.DOWN);
        assertThatThrownBy(() -> engine.createIndex("x-" + UUID.randomUUID().toString().substring(0, 6), JSON.createObjectNode()))
                .isInstanceOf(SearchEngineException.class).hasMessageNotContaining(wrong).hasMessageNotContaining(password);
    }

    @Test
    void noCredentialsAtAllIsRefused() {
        assertThat(engine("", "", ca).health()).isEqualTo(SearchEngine.Health.DOWN);
    }

    @Test
    void aServerWhoseCertificateIsNotTrustedIsRefused() {
        // trust nothing but the JVM defaults: the demo CA is not among them
        assertThat(engine("admin", password, null).health()).isEqualTo(SearchEngine.Health.DOWN);
    }
}
