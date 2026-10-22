package com.oak.dms.relationship.helper;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Walks the active links of one type to see whether a document can be reached from another - the loop
 * check for Supersedes, Replaces and Parent -> Child. Depth-limited: a walk still going after
 * {@link #MAX_DEPTH} hops (or past {@link #MAX_VISITED} documents) is reported as truncated rather than
 * guessed at.
 * <p>
 * A level-by-level search, one indexed query per level over the documents just reached, remembering
 * which documents it has already seen. A recursive query that carries each path along would be the
 * obvious way to write this, but the number of paths through a graph with shared documents is
 * exponential in its depth; this visits each document once, so the cost follows the links, not the paths.
 * Runs inside the caller's transaction (JdbcTemplate joins it), after the caller has taken the locks in
 * {@link RelationshipLocks}.
 */
@Component
@RequiredArgsConstructor
public class CycleDetector {

    public static final int MAX_DEPTH = 50;
    /** Documents one search may visit before it gives up. */
    public static final int MAX_VISITED = 200_000;
    /** Links an admin-triggered whole-type check will load. */
    public static final int MAX_LINKS_CHECKED = 500_000;
    private static final int CHUNK = 500;

    /** {@code path} runs from the start to the goal, both included; empty when there is none. */
    public record Walk(List<UUID> path, boolean truncated) {
        public boolean found() {
            return !path.isEmpty();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** Is there already a chain {@code from} => ... => {@code to} of this type? The shortest one if so. */
    public Walk findPath(UUID tenantId, UUID typeId, UUID from, UUID to) {
        Map<UUID, UUID> parent = new HashMap<>();
        parent.put(from, null);
        List<UUID> frontier = List.of(from);
        int depth = 0;
        while (!frontier.isEmpty()) {
            if (depth >= MAX_DEPTH || parent.size() > MAX_VISITED) {
                return new Walk(List.of(), true);
            }
            depth++;
            List<UUID> next = new ArrayList<>();
            for (int i = 0; i < frontier.size(); i += CHUNK) {
                List<UUID> chunk = frontier.subList(i, Math.min(i + CHUNK, frontier.size()));
                List<UUID[]> edges = jdbc.query("""
                        select source_document_id, target_document_id from document_relationships
                         where tenant_id = :tenantId and relationship_type_id = :typeId and removed_at is null
                           and source_document_id in (:ids)
                        """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("typeId", typeId)
                        .addValue("ids", chunk), (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
                for (UUID[] edge : edges) {
                    if (!parent.containsKey(edge[1])) {
                        parent.put(edge[1], edge[0]);
                        if (edge[1].equals(to)) {
                            return new Walk(pathTo(parent, to), false);
                        }
                        next.add(edge[1]);
                    }
                }
            }
            frontier = next;
        }
        return new Walk(List.of(), false);
    }

    private static List<UUID> pathTo(Map<UUID, UUID> parent, UUID to) {
        List<UUID> path = new ArrayList<>();
        for (UUID at = to; at != null; at = parent.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Does the type already contain a loop among its active links? One loop (a path that returns to its
     * start), if so. Loads the type's links into memory, so it is for the rare admin action of turning loop
     * prevention on - and refuses (empty, truncated) past {@link #MAX_LINKS_CHECKED} links.
     */
    public Optional<List<UUID>> findAnyCycle(UUID tenantId, UUID typeId) {
        Map<UUID, List<UUID>> out = new HashMap<>();
        int[] count = {0};
        jdbc.query("select source_document_id, target_document_id from document_relationships where tenant_id = :tenantId"
                        + " and relationship_type_id = :typeId and removed_at is null",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("typeId", typeId), rs -> {
                    if (count[0]++ < MAX_LINKS_CHECKED) {
                        out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class));
                    }
                });
        if (count[0] > MAX_LINKS_CHECKED) {
            throw new IllegalStateException("Too many links of this type to check for loops (" + count[0] + ")");
        }
        Set<UUID> done = new HashSet<>();
        for (UUID start : new ArrayList<>(out.keySet())) {
            if (done.contains(start)) {
                continue;
            }
            // Iterative depth-first search: the path being walked is the stack.
            List<UUID> stack = new ArrayList<>();
            List<Integer> nextChild = new ArrayList<>();
            Set<UUID> onStack = new HashSet<>();
            stack.add(start);
            nextChild.add(0);
            onStack.add(start);
            while (!stack.isEmpty()) {
                int top = stack.size() - 1;
                UUID node = stack.get(top);
                List<UUID> children = out.getOrDefault(node, List.of());
                int index = nextChild.get(top);
                if (index >= children.size()) {
                    stack.remove(top);
                    nextChild.remove(top);
                    onStack.remove(node);
                    done.add(node);
                    continue;
                }
                nextChild.set(top, index + 1);
                UUID child = children.get(index);
                if (onStack.contains(child)) {
                    List<UUID> loop = new ArrayList<>(stack.subList(stack.indexOf(child), stack.size()));
                    loop.add(child);
                    return Optional.of(loop);
                }
                if (!done.contains(child)) {
                    stack.add(child);
                    nextChild.add(0);
                    onStack.add(child);
                }
            }
        }
        return Optional.empty();
    }
}
