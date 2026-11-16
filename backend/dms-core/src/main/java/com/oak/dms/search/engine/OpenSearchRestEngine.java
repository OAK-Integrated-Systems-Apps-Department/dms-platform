package com.oak.dms.search.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenSearch over plain REST using Spring's {@link RestClient}: no extra dependency, and
 * the handful of endpoints we need (index/alias admin, {@code _bulk}, {@code _search},
 * {@code _count}, {@code _delete_by_query}) are small. Bodies are exchanged as strings and
 * parsed with a private Jackson 2 mapper, the mapper family the neighbouring modules use,
 * so no message-converter configuration can change how the engine is spoken to.
 *
 * <p>Failures: connection problems, timeouts and 5xx/429 raise {@link SearchEngineException}
 * and feed the circuit breaker; 4xx are rejections (our bug or a stale mapping) and raise the
 * same exception type without opening the breaker. Messages carry status and engine error
 * type only, never request bodies, so document or query text cannot reach a log.
 */
@Slf4j
@Component
public class OpenSearchRestEngine implements SearchEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final RestClient bulkClient;
    private final SearchCircuitBreaker breaker;

    public OpenSearchRestEngine(SearchProperties props) {
        SearchProperties.OpenSearch os = props.getOpensearch();
        this.client = build(os.getUrl(), os.getConnectTimeout(), os.getReadTimeout());
        this.bulkClient = build(os.getUrl(), os.getConnectTimeout(), os.getBulkTimeout());
        this.breaker = new SearchCircuitBreaker(props.getQuery().getBreakerFailures(), props.getQuery().getBreakerOpen());
    }

    private static RestClient build(String url, java.time.Duration connect, java.time.Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    private record Resp(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private Resp exec(RestClient c, HttpMethod method, String path, String body, MediaType type) {
        if (breaker.isOpen()) {
            throw new SearchEngineException("search engine unavailable (circuit open)");
        }
        try {
            RestClient.RequestBodySpec spec = c.method(method).uri(path);
            if (body != null) {
                // Always explicit UTF-8 bytes: Spring's String converter defaults to ISO-8859-1 for
                // non-JSON types (application/x-ndjson), which would silently corrupt Sinhala/Tamil/accents.
                spec.contentType(type).body(body.getBytes(StandardCharsets.UTF_8));
            }
            Resp resp = spec.exchange((req, res) -> {
                byte[] bytes = res.getBody().readAllBytes();
                JsonNode node = parse(bytes);
                return new Resp(res.getStatusCode().value(), node);
            });
            if (resp.status() >= 500 || resp.status() == 429) {
                breaker.onFailure();
                throw new SearchEngineException("search engine error status " + resp.status());
            }
            breaker.onSuccess();
            return resp;
        } catch (RestClientException | IllegalStateException ex) {
            breaker.onFailure();
            throw new SearchEngineException("search engine unreachable: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private static JsonNode parse(byte[] bytes) throws IOException {
        if (bytes.length == 0) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise search request", e);
        }
    }

    private Resp json(HttpMethod m, String path, JsonNode body) {
        return exec(client, m, path, body == null ? null : write(body), MediaType.parseMediaType("application/json;charset=UTF-8"));
    }

    private static void requireOk(Resp r, String what) {
        if (!r.ok()) {
            String type = r.body().path("error").path("type").asText("");
            throw new SearchEngineException("search engine rejected " + what + " (status " + r.status()
                    + (type.isEmpty() ? "" : ", " + type) + ")");
        }
    }

    @Override
    public Health health() {
        try {
            Resp r = exec(client, HttpMethod.GET, "/_cluster/health", null, null);
            if (!r.ok()) {
                return Health.DOWN;
            }
            return switch (r.body().path("status").asText("")) {
                case "green" -> Health.GREEN;
                case "yellow" -> Health.YELLOW;
                case "red" -> Health.RED;
                default -> Health.DOWN;
            };
        } catch (SearchEngineException e) {
            return Health.DOWN;
        }
    }

    @Override
    public boolean indexExists(String index) {
        Resp r = exec(client, HttpMethod.HEAD, "/" + index, null, null);
        return r.status() == 200;
    }

    @Override
    public void createIndex(String index, JsonNode body) {
        Resp r = json(HttpMethod.PUT, "/" + index, body);
        // 400 resource_already_exists_exception: someone else won the race, which is fine.
        if (r.status() == 400 && "resource_already_exists_exception".equals(r.body().path("error").path("type").asText())) {
            return;
        }
        requireOk(r, "create index");
    }

    @Override
    public void deleteIndex(String index) {
        Resp r = json(HttpMethod.DELETE, "/" + index, null);
        if (r.status() != 404) {
            requireOk(r, "delete index");
        }
    }

    @Override
    public List<String> indicesForAlias(String alias) {
        Resp r = json(HttpMethod.GET, "/_alias/" + alias, null);
        if (r.status() == 404) {
            return List.of();
        }
        requireOk(r, "read alias");
        List<String> out = new ArrayList<>();
        r.body().fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Override
    public JsonNode aliases(String pattern) {
        Resp r = json(HttpMethod.GET, "/_alias/" + pattern, null);
        if (r.status() == 404) {
            return JSON.createObjectNode();
        }
        requireOk(r, "read aliases");
        return r.body();
    }

    @Override
    public void updateAliases(JsonNode actions) {
        ObjectNode body = JSON.createObjectNode();
        body.set("actions", actions);
        requireOk(json(HttpMethod.POST, "/_aliases", body), "update aliases");
    }

    @Override
    public BulkResult bulk(List<BulkOp> ops) {
        if (ops.isEmpty()) {
            return new BulkResult(List.of());
        }
        StringBuilder nd = new StringBuilder();
        for (BulkOp op : ops) {
            ObjectNode meta = JSON.createObjectNode();
            ObjectNode m = meta.putObject(op.kind() == BulkOp.Kind.INDEX ? "index" : "delete");
            m.put("_index", op.index());
            m.put("_id", op.id());
            if (op.routing() != null) {
                m.put("routing", op.routing());
            }
            m.put("version", op.version());
            m.put("version_type", "external_gte");
            nd.append(write(meta)).append('\n');
            if (op.kind() == BulkOp.Kind.INDEX) {
                nd.append(op.sourceJson()).append('\n');
            }
        }
        Resp r = exec(bulkClient, HttpMethod.POST, "/_bulk", nd.toString(), MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"));
        requireOk(r, "bulk");
        List<BulkItemResult> results = new ArrayList<>();
        for (JsonNode item : r.body().path("items")) {
            JsonNode body = item.elements().next();
            int status = body.path("status").asInt();
            boolean conflict = status == 409;
            boolean notFoundDelete = status == 404 && item.has("delete");
            boolean ok = (status >= 200 && status < 300) || conflict || notFoundDelete;
            String err = ok ? null : body.path("error").path("type").asText("error");
            results.add(new BulkItemResult(body.path("_index").asText(), body.path("_id").asText(), ok, conflict, status, err));
        }
        return new BulkResult(results);
    }

    private static String routed(String path, String routing) {
        return routing == null ? path : path + (path.contains("?") ? "&" : "?") + "routing=" + routing;
    }

    @Override
    public JsonNode search(String target, String routing, JsonNode body) {
        Resp r = json(HttpMethod.POST, routed("/" + target + "/_search", routing), body);
        requireOk(r, "search");
        return r.body();
    }

    @Override
    public long count(String target, String routing, JsonNode query) {
        ObjectNode body = JSON.createObjectNode();
        body.set("query", query);
        Resp r = json(HttpMethod.POST, routed("/" + target + "/_count", routing), body);
        requireOk(r, "count");
        return r.body().path("count").asLong();
    }

    @Override
    public long deleteByQuery(String target, String routing, JsonNode query) {
        ObjectNode body = JSON.createObjectNode();
        body.set("query", query);
        String path = routed("/" + target + "/_delete_by_query", routing);
        path += (path.contains("?") ? "&" : "?") + "conflicts=proceed&refresh=true";
        Resp r = exec(bulkClient, HttpMethod.POST, path, write(body), MediaType.parseMediaType("application/json;charset=UTF-8"));
        requireOk(r, "delete by query");
        return r.body().path("deleted").asLong();
    }

    @Override
    public void refresh(String target) {
        requireOk(json(HttpMethod.POST, "/" + target + "/_refresh", null), "refresh");
    }

    @Override
    public void putSettings(String index, JsonNode settings) {
        requireOk(json(HttpMethod.PUT, "/" + index + "/_settings", settings), "put settings");
    }

    /** Test/diagnostic helper: the actions array builder used by index admin. */
    public static ArrayNode newActions() {
        return JSON.createArrayNode();
    }
}
