package com.oak.dms.search;

import com.oak.dms.search.config.SearchProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Shared setup for the tests that need a real OpenSearch node (the disposable
 * {@code dms-search-it} container on port 9201: no volume, security off).
 *
 * <p>If the node is unreachable the tests <b>skip</b> (JUnit assumption), so CI, which has no
 * OpenSearch container yet, stays green. Set {@code OPENSEARCH_IT_REQUIRED=true} (as local runs
 * do) to make an unreachable node a hard failure instead, so a missing container can never
 * pass as "tests skipped". Every run uses its own unique index prefix and removes what it made.
 */
public final class SearchIntegrationTestSupport {

    private SearchIntegrationTestSupport() {
    }

    public static String url() {
        String v = System.getenv("OPENSEARCH_IT_URL");
        return v == null || v.isBlank() ? "http://localhost:9201" : v;
    }

    public static boolean required() {
        return "true".equalsIgnoreCase(System.getenv("OPENSEARCH_IT_REQUIRED"));
    }

    /** Skips (or fails, when required) unless the test node answers. Call from {@code @BeforeAll}/{@code @BeforeEach}. */
    public static void assumeAvailable() {
        boolean up = reachable();
        if (!up && required()) {
            Assertions.fail("OPENSEARCH_IT_REQUIRED=true but no OpenSearch at " + url()
                    + ". Start it: docker run -d --rm --name dms-search-it -p 9201:9200 -e discovery.type=single-node "
                    + "-e DISABLE_SECURITY_PLUGIN=true -e DISABLE_INSTALL_DEMO_CONFIG=true dms-opensearch:3.7.0-icu");
        }
        Assumptions.assumeTrue(up, "no OpenSearch at " + url() + " - search ITs skipped (set OPENSEARCH_IT_REQUIRED=true to fail instead)");
    }

    public static boolean reachable() {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url() + "/_cluster/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Properties pointing at the test node with a unique prefix (never collides with another run or the dev index). */
    public static SearchProperties props() {
        SearchProperties p = new SearchProperties();
        p.getOpensearch().setUrl(url());
        p.getIndex().setPrefix("it" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        p.getIndex().setShards(1);
        p.getIndex().setReplicas(0);
        return p;
    }

    /** Drops every index this run created (the prefix is unique, so nothing else can match). */
    public static void cleanup(SearchProperties p) {
        try {
            HttpClient c = HttpClient.newHttpClient();
            c.send(HttpRequest.newBuilder(URI.create(url() + "/" + p.getIndex().getPrefix() + "-*"))
                    .timeout(Duration.ofSeconds(10)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // best effort: the container has no volume and is removed after the run
        }
    }
}
