package com.oak.dms.encryption;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unwraps the data keys of many documents in bulk requests - one per hundred
 * keys - and puts them in the {@link DataKeyCache}, so the reads that follow
 * (a bulk export building its ZIP) don't each ask the key service. Only the
 * documents' current versions; best effort - a key it can't get is simply
 * unwrapped on its own later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyPrefetcher {

    private static final int CHUNK = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final KeyServices keys;
    private final DataKeyCache cache;

    public void prefetchDocuments(Collection<UUID> documentIds) {
        if (!cache.enabled() || documentIds.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>(documentIds);
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select k.key_provider, k.key_scope, k.wrapped_key
                    from documents d
                    join document_versions v on v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                    join storage_object_keys k on k.storage_location_id = v.storage_location_id
                                              and k.object_key = v.storage_object_key
                    where d.document_id in (:ids)
                    """, Map.of("ids", chunk));
            Map<String, List<String>> byKey = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                byKey.computeIfAbsent(row.get("key_provider") + "|" + row.get("key_scope"), k -> new ArrayList<>())
                        .add((String) row.get("wrapped_key"));
            }
            byKey.forEach(this::unwrapInto);
        }
    }

    private void unwrapInto(String providerAndScope, List<String> wrapped) {
        String[] parts = providerAndScope.split("\\|", 2);
        try {
            List<byte[]> unwrapped = keys.forProvider(parts[0]).unwrapAll(parts[1], wrapped);
            for (int i = 0; i < wrapped.size(); i++) {
                byte[] dataKey = unwrapped.get(i);
                if (dataKey != null) {
                    cache.put(parts[0], parts[1], wrapped.get(i), dataKey);
                    Arrays.fill(dataKey, (byte) 0);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not prefetch {} data keys ({}) - they'll be unwrapped one by one", wrapped.size(),
                    e.getMessage());
        }
    }
}
