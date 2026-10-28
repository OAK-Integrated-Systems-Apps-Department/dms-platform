package com.oak.dms.document;

import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A small, bounded, in-process cache of rendered page images.
 *
 * <h2>Why this exists</h2>
 * Rendering a page is ocr-worker work measured in hundreds of milliseconds.
 * A reviewer working through a document pages forward, back, forward again,
 * and zooms - and every one of those is the same page re-rendered. Without a
 * cache the review screen's latency is the render time, every time, for a
 * picture that cannot have changed: a version's bytes are immutable, so a
 * rendered page of a version is immutable too. That immutability is what
 * makes caching correct here rather than merely fast.
 *
 * <h2>Why in memory, and not Redis or storage</h2>
 * A page image is the document. Everything this system stores is encrypted at
 * rest precisely so that a copy of a document never sits in plaintext
 * somewhere outside that guarantee - and Redis in this deployment is not
 * encrypted and is shared. Writing page images to object storage would be
 * correct cryptographically but brings the whole lifecycle problem with it:
 * something has to track and delete them when the document is deleted, which
 * is exactly the "tracking table and an explicit purge hook" that persisting
 * them was deferred until. In process memory, a cached page dies with the
 * process and never outlives its document by more than a restart.
 *
 * <h2>Bounded by bytes, not entries</h2>
 * A 300-DPI A3 page and a 72-DPI thumbnail differ by two orders of magnitude,
 * so "keep 200 entries" is not a memory limit at all - it is a limit on a
 * number nobody cares about. The budget here is bytes
 * ({@code dms.ocr.page-image-cache-bytes}, default 64 MB), and an image too
 * large to be a sensible fraction of it is simply not cached rather than
 * evicting everything else to make room.
 *
 * <h2>Why a hand-written LRU</h2>
 * {@link LinkedHashMap} in access order IS an LRU, and this needs one
 * eviction rule and one size accounting. Adding a cache library for that
 * would be a dependency, a configuration surface and a set of behaviours to
 * learn, for sixty lines.
 */
@Component
@RequiredArgsConstructor
public class PageImageCache {

    // Declared rather than @Slf4j: Lombok's generated field collides with
    // this class's own field initializers under the annotation processor, and
    // one explicit line is cheaper than working out why.
    private static final Logger log = LoggerFactory.getLogger(PageImageCache.class);

    private final OcrProperties properties;

    /**
     * Tenant is part of the key even though a version id is already unique.
     * It costs nothing and means that no future change to how versions are
     * identified can turn a cache lookup into a cross-tenant read - the one
     * mistake in this class that would matter.
     */
    private record Key(UUID tenantId, UUID versionId, int page, int dpi) {
    }

    private final Map<Key, OcrWorkerClient.PageImage> entries =
            new LinkedHashMap<>(16, 0.75f, true);
    private long bytesHeld;

    /**
     * The page image, rendered only if it is not already held.
     *
     * <p>{@code render} is called OUTSIDE the lock. Holding a lock across a
     * call to ocr-worker would serialise every reviewer in the deployment
     * behind the slowest render; the cost is that two reviewers asking for
     * the same cold page may both render it, which wastes one render and is
     * otherwise harmless - the result is identical either way.
     */
    public OcrWorkerClient.PageImage get(UUID tenantId, UUID versionId, int page, int dpi,
                                         Supplier<OcrWorkerClient.PageImage> render) {
        if (!properties.isPageImageCacheEnabled()) {
            return render.get();
        }
        Key key = new Key(tenantId, versionId, page, dpi);
        synchronized (this) {
            OcrWorkerClient.PageImage hit = entries.get(key);
            if (hit != null) {
                return hit;
            }
        }
        OcrWorkerClient.PageImage rendered = render.get();
        put(key, rendered);
        return rendered;
    }

    private synchronized void put(Key key, OcrWorkerClient.PageImage image) {
        long size = image.png() == null ? 0 : image.png().length;
        long budget = properties.getPageImageCacheBytes();
        // A single image worth more than a quarter of the budget would evict
        // most of the cache to store one page. Served, not cached.
        if (size == 0 || size * 4 > budget) {
            return;
        }
        OcrWorkerClient.PageImage replaced = entries.put(key, image);
        bytesHeld += size - (replaced == null || replaced.png() == null ? 0 : replaced.png().length);
        evictDownTo(budget);
    }

    /** Oldest-accessed first, which is what the access-ordered map's iterator gives. */
    private void evictDownTo(long budget) {
        Iterator<Map.Entry<Key, OcrWorkerClient.PageImage>> it = entries.entrySet().iterator();
        while (bytesHeld > budget && it.hasNext()) {
            Map.Entry<Key, OcrWorkerClient.PageImage> oldest = it.next();
            byte[] png = oldest.getValue().png();
            bytesHeld -= png == null ? 0 : png.length;
            it.remove();
        }
    }

    /**
     * Drops everything held for one version.
     *
     * <p>Called from the purge path. A permanently deleted document must not
     * remain readable as a picture in a cache for the lifetime of the
     * process - which is the whole reason this is a method and not something
     * left to expiry.
     */
    public synchronized void invalidate(UUID versionId) {
        int dropped = 0;
        Iterator<Map.Entry<Key, OcrWorkerClient.PageImage>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, OcrWorkerClient.PageImage> entry = it.next();
            if (entry.getKey().versionId().equals(versionId)) {
                byte[] png = entry.getValue().png();
                bytesHeld -= png == null ? 0 : png.length;
                it.remove();
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("Dropped {} cached page image(s) for version {}", dropped, versionId);
        }
    }

    /** For the test and for a health/metrics read: entries held and bytes held. */
    public synchronized long bytesHeld() {
        return bytesHeld;
    }

    public synchronized int size() {
        return entries.size();
    }
}
