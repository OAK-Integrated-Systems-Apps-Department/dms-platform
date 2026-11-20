package com.oak.dms.search.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.index.SearchSourceRepository.VersionRow;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * The extracted-text cache: one encrypted derived artifact per version, written through the same encrypting storage
 * wrapper as the document itself (so readable text is never at rest outside the encrypted store). It makes a
 * metadata-only change or a rebuild rebuild the index document from the cache instead of re-parsing a long PDF.
 * It is derived data: deleting it only costs a re-extraction. {@link #keyFor} is also used by the purge path
 * ({@code OcrArtifactWriter#deleteFor}) so a purged document takes its cached text with it.
 *
 * <p>The cache is keyed by an "inputs" fingerprint ({@code basis}); when the inputs change (OCR finished, the
 * preview was rebuilt) the cached text is ignored and extraction runs again.
 */
@Slf4j
@Component
public class TextArtifactStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StorageLocationResolver resolver;
    private final ObjectStorageServiceFactory storage;

    public TextArtifactStore(StorageLocationResolver resolver, ObjectStorageServiceFactory storage) {
        this.resolver = resolver;
        this.storage = storage;
    }

    public static String keyFor(UUID tenantId, UUID documentId, UUID versionId) {
        return "search/" + tenantId + "/" + documentId + "/" + versionId + "/text.json";
    }

    private StorageLocation location(UUID tenantId) {
        return resolver.resolveForTenant(tenantId, StoragePurpose.document);
    }

    /** The cached result if present and computed from the same inputs, else empty. */
    public Optional<ExtractedText> read(VersionRow v, String basis) {
        StorageLocation location = location(v.tenantId());
        try (InputStream in = storage.resolve(location)
                .download(location, keyFor(v.tenantId(), v.documentId(), v.versionId())).content()) {
            JsonNode n = JSON.readTree(in);
            if (!basis.equals(n.path("basis").asText())) {
                return Optional.empty();
            }
            return Optional.of(new ExtractedText(ExtractedText.Status.valueOf(n.path("status").asText()),
                    n.path("reason").isNull() ? null : n.path("reason").asText(null),
                    n.path("source").isNull() ? null : n.path("source").asText(null),
                    n.path("text").asText("")));
        } catch (StorageObjectNotFoundException gone) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            // A damaged cache entry is only a cache miss; never a reason to fail indexing.
            log.warn("Search text cache for version {} unreadable ({}) - extracting again", v.versionId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void write(VersionRow v, String basis, ExtractedText result) {
        ObjectNode n = JSON.createObjectNode();
        n.put("basis", basis);
        n.put("status", result.status().name());
        n.put("reason", result.reason());
        n.put("source", result.source());
        n.put("text", result.text());
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise the search text cache", e);
        }
        StorageLocation location = location(v.tenantId());
        storage.resolve(location).upload(location, keyFor(v.tenantId(), v.documentId(), v.versionId()),
                new ByteArrayInputStream(bytes), bytes.length, "application/json");
    }

    /** Test/maintenance helper; the purge path deletes through {@code OcrArtifactWriter}. */
    public void delete(VersionRow v) {
        StorageLocation location = location(v.tenantId());
        try {
            storage.resolve(location).delete(location, keyFor(v.tenantId(), v.documentId(), v.versionId()));
        } catch (StorageObjectNotFoundException ignored) {
            // nothing cached
        }
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
