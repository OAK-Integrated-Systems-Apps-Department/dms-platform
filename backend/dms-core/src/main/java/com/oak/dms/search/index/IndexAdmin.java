package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index and alias administration. One versioned index ({@code <prefix>-documents-vN}) sits
 * behind a write alias; every tenant reads through its own alias carrying a mandatory
 * {@code tenant_id} filter and the tenant routing, so the API never queries the base index.
 *
 * <p>During a global rebuild a second index is also reachable through the
 * {@code <prefix>-documents-rebuilding} alias. A write alias can have only one write index,
 * so {@link #writeTargets()} returns both concrete names and the indexer writes to each
 * explicitly until {@link #swap} moves everything atomically.
 */
@Component
public class IndexAdmin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("-v(\\d+)$");

    private final SearchEngine engine;
    private final SearchProperties props;
    private final Set<UUID> knownTenantAliases = ConcurrentHashMap.newKeySet();

    public IndexAdmin(SearchEngine engine, SearchProperties props) {
        this.engine = engine;
        this.props = props;
    }

    // ---- names -------------------------------------------------------------------------

    private String prefix() {
        return props.getIndex().getPrefix();
    }

    public String writeAlias() {
        return prefix() + "-documents-write";
    }

    public String rebuildingAlias() {
        return prefix() + "-documents-rebuilding";
    }

    public String indexName(int version) {
        return prefix() + "-documents-v" + version;
    }

    public String readAlias(UUID tenantId) {
        return prefix() + "-documents-read-" + tenantId;
    }

    public String readAliasPattern() {
        return prefix() + "-documents-read-*";
    }

    // ---- index lifecycle -----------------------------------------------------------------

    /** The index definition (settings + mapping) with shards, replicas and routing partition applied. */
    public ObjectNode indexBody() {
        try (InputStream in = new ClassPathResource("search/mapping-v1.json").getInputStream()) {
            ObjectNode body = (ObjectNode) JSON.readTree(in);
            ObjectNode index = body.withObject("/settings/index");
            index.put("number_of_shards", props.getIndex().getShards());
            index.put("number_of_replicas", props.getIndex().getReplicas());
            Integer partition = props.getIndex().getRoutingPartitionSize();
            if (partition != null && props.getIndex().getShards() > 2) {
                index.put("routing_partition_size", partition);
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("search mapping resource is missing or unreadable", e);
        }
    }

    /** Creates {@code <prefix>-documents-v1} behind the write alias when nothing is there yet. Safe to call concurrently. */
    public void ensureIndex() {
        if (!engine.indicesForAlias(writeAlias()).isEmpty()) {
            return;
        }
        String first = indexName(1);
        if (engine.indexExists(first)) {
            ArrayNode actions = JSON.createArrayNode();
            actions.add(addAction(first, writeAlias(), true));
            engine.updateAliases(actions);
            return;
        }
        ObjectNode body = indexBody();
        body.putObject("aliases").putObject(writeAlias()).put("is_write_index", true);
        engine.createIndex(first, body);
    }

    /** True when the engine has a usable current index. Never creates anything. */
    public boolean isReady() {
        return !engine.indicesForAlias(writeAlias()).isEmpty();
    }

    public String currentIndex() {
        List<String> indices = engine.indicesForAlias(writeAlias());
        if (indices.isEmpty()) {
            throw new IllegalStateException("no current search index");
        }
        return indices.get(0);
    }

    /** Every concrete index a live change must be written to (current, plus the rebuild target when one is loading). */
    public List<String> writeTargets() {
        Set<String> targets = new LinkedHashSet<>(engine.indicesForAlias(writeAlias()));
        targets.addAll(engine.indicesForAlias(rebuildingAlias()));
        return new ArrayList<>(targets);
    }

    // ---- tenant aliases ------------------------------------------------------------------

    /** Creates the tenant's filtered, routed read alias on the current index once (cached afterwards). */
    public void ensureTenantAlias(UUID tenantId) {
        if (knownTenantAliases.contains(tenantId)) {
            return;
        }
        ArrayNode actions = JSON.createArrayNode();
        for (String index : engine.indicesForAlias(writeAlias())) {
            actions.add(tenantAliasAction(index, tenantId));
        }
        if (actions.isEmpty()) {
            return;
        }
        engine.updateAliases(actions);
        knownTenantAliases.add(tenantId);
    }

    /** Removes a tenant's read alias from every index (tenant removed). Quiet when it does not exist. */
    public void dropTenantAlias(UUID tenantId) {
        ArrayNode actions = JSON.createArrayNode();
        for (String index : engine.indicesForAlias(readAlias(tenantId))) {
            actions.add(removeAction(index, readAlias(tenantId)));
        }
        if (!actions.isEmpty()) {
            engine.updateAliases(actions);
        }
        knownTenantAliases.remove(tenantId);
    }

    public boolean tenantAliasExists(UUID tenantId) {
        return !engine.indicesForAlias(readAlias(tenantId)).isEmpty();
    }

    /** Forget the in-memory alias cache (after an index was dropped or recreated, and by tests). */
    public void forgetTenantAliases() {
        knownTenantAliases.clear();
    }

    // ---- global rebuild primitives (used by IndexRebuildService) ------------------------------

    /** The next version number after the current index. */
    public int nextVersion() {
        Matcher m = VERSION.matcher(currentIndex());
        return m.find() ? Integer.parseInt(m.group(1)) + 1 : 2;
    }

    /** Creates the rebuild target (refresh off, no replicas while loading) and exposes it through the rebuilding alias. */
    public String startRebuild() {
        int version = nextVersion();
        String target = indexName(version);
        ObjectNode body = indexBody();
        ObjectNode index = body.withObject("/settings/index");
        index.put("refresh_interval", "-1");
        index.put("number_of_replicas", 0);
        body.putObject("aliases").putObject(rebuildingAlias());
        engine.createIndex(target, body);
        return target;
    }

    /** Restores normal refresh and replica settings on the freshly loaded index. */
    public void finishLoad(String target) {
        ObjectNode settings = JSON.createObjectNode();
        ObjectNode index = settings.putObject("index");
        index.put("refresh_interval", "1s");
        index.put("number_of_replicas", props.getIndex().getReplicas());
        engine.putSettings(target, settings);
        engine.refresh(target);
    }

    /**
     * One atomic {@code _aliases} call: the write alias and every tenant read alias move from the
     * old index to {@code target}, and the rebuilding marker is dropped.
     */
    public void swap(String target) {
        List<String> oldIndices = engine.indicesForAlias(writeAlias());
        ArrayNode actions = JSON.createArrayNode();
        for (String old : oldIndices) {
            actions.add(removeAction(old, writeAlias()));
        }
        actions.add(addAction(target, writeAlias(), true));

        JsonNode tenantAliases = engine.aliases(readAliasPattern());
        tenantAliases.fieldNames().forEachRemaining(old -> {
            if (old.equals(target)) {
                return;
            }
            JsonNode aliases = tenantAliases.path(old).path("aliases");
            aliases.fieldNames().forEachRemaining(alias -> {
                JsonNode def = aliases.path(alias);
                actions.add(removeAction(old, alias));
                ObjectNode add = JSON.createObjectNode();
                ObjectNode a = add.putObject("add");
                a.put("index", target).put("alias", alias);
                if (def.has("filter")) {
                    a.set("filter", def.get("filter"));
                }
                if (def.has("index_routing")) {
                    a.set("index_routing", def.get("index_routing"));
                }
                if (def.has("search_routing")) {
                    a.set("search_routing", def.get("search_routing"));
                }
                actions.add(add);
            });
        });
        actions.add(removeAction(target, rebuildingAlias()));
        engine.updateAliases(actions);
    }

    public void dropIndex(String index) {
        engine.deleteIndex(index);
    }

    /** Removes a failed rebuild target and its marker. */
    public void abortRebuild() {
        for (String idx : engine.indicesForAlias(rebuildingAlias())) {
            engine.deleteIndex(idx);
        }
    }

    // ---- action builders -----------------------------------------------------------------

    private ObjectNode addAction(String index, String alias, boolean writeIndex) {
        ObjectNode n = JSON.createObjectNode();
        ObjectNode a = n.putObject("add");
        a.put("index", index).put("alias", alias);
        if (writeIndex) {
            a.put("is_write_index", true);
        }
        return n;
    }

    private ObjectNode removeAction(String index, String alias) {
        ObjectNode n = JSON.createObjectNode();
        n.putObject("remove").put("index", index).put("alias", alias);
        return n;
    }

    private ObjectNode tenantAliasAction(String index, UUID tenantId) {
        ObjectNode n = JSON.createObjectNode();
        ObjectNode a = n.putObject("add");
        a.put("index", index).put("alias", readAlias(tenantId));
        a.putObject("filter").putObject("term").put("tenant_id", tenantId.toString());
        a.put("routing", tenantId.toString());
        return n;
    }
}
