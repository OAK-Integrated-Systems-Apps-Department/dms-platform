package com.oak.dms.search.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The only door to the search engine. Everything above this interface (indexer, query
 * service, admin) is engine-neutral enough to be tested against a fake, and the OpenSearch
 * specifics (REST paths, NDJSON bulk, external versioning) live in one implementation.
 *
 * <p>All methods throw {@link SearchEngineException} when the engine is unusable.
 */
public interface SearchEngine {

    enum Health { GREEN, YELLOW, RED, DOWN }

    /** One bulk operation. Versions are external (the job's change counter). */
    record BulkOp(Kind kind, String index, String id, String routing, long version, String sourceJson) {
        public enum Kind { INDEX, DELETE }

        public static BulkOp index(String index, String id, String routing, long version, String sourceJson) {
            return new BulkOp(Kind.INDEX, index, id, routing, version, sourceJson);
        }

        public static BulkOp delete(String index, String id, String routing, long version) {
            return new BulkOp(Kind.DELETE, index, id, routing, version, null);
        }
    }

    /** Per-item outcome; a version conflict means a newer version is already there, which is success. */
    record BulkItemResult(String index, String id, boolean ok, boolean versionConflict, int status, String error) {
    }

    record BulkResult(List<BulkItemResult> items) {
        public boolean allOk() {
            return items.stream().allMatch(BulkItemResult::ok);
        }
    }

    /** Never throws: an unreachable engine is {@link Health#DOWN}. */
    Health health();

    boolean indexExists(String index);

    void createIndex(String index, JsonNode body);

    void deleteIndex(String index);

    /** Concrete indices behind an alias; empty when the alias does not exist. */
    List<String> indicesForAlias(String alias);

    /** Raw {@code GET _alias/<pattern>} response ({@code {index:{aliases:{name:{...}}}}}); empty object when none match. */
    JsonNode aliases(String pattern);

    /** Atomic {@code POST _aliases} with the given {@code actions} array. */
    void updateAliases(JsonNode actions);

    BulkResult bulk(List<BulkOp> ops);

    /** Search a concrete index or an alias; {@code routing} may be null. */
    JsonNode search(String target, String routing, JsonNode body);

    long count(String target, String routing, JsonNode query);

    /** Deletes matching documents; returns how many were deleted. */
    long deleteByQuery(String target, String routing, JsonNode query);

    void refresh(String target);

    void putSettings(String index, JsonNode settings);
}
