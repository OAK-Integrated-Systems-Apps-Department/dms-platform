package com.oak.dms.document;

import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The cache's four rules: serve, bound, isolate, forget. */
class PageImageCacheTest {

    private final OcrProperties properties = new OcrProperties();
    private final PageImageCache cache = new PageImageCache(properties);

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private static OcrWorkerClient.PageImage image(int bytes) {
        return new OcrWorkerClient.PageImage(new byte[bytes], 800, 1000, 50);
    }

    @Test
    void theSamePageIsRenderedOnce_andADifferentPageOrDpiIsNot() {
        AtomicInteger renders = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            cache.get(tenantA, versionId, 1, 150, () -> {
                renders.incrementAndGet();
                return image(1024);
            });
        }
        assertThat(renders).hasValue(1);

        // A different page, and the same page at a different resolution, are
        // different pictures - neither may be served from the other's entry.
        cache.get(tenantA, versionId, 2, 150, () -> image(renders.incrementAndGet()));
        cache.get(tenantA, versionId, 1, 300, () -> image(renders.incrementAndGet()));
        assertThat(renders).hasValue(3);
    }

    /**
     * The tenant is part of the key. This can only fail if version ids ever
     * stop being globally unique - which is exactly the change that would
     * otherwise turn a cache hit into a cross-tenant read, so it is worth a
     * test that fails loudly rather than a comment.
     */
    @Test
    void twoTenantsNeverShareAnEntry() {
        cache.get(tenantA, versionId, 1, 150, () -> image(1024));
        AtomicInteger rendersForB = new AtomicInteger();

        cache.get(tenantB, versionId, 1, 150, () -> {
            rendersForB.incrementAndGet();
            return image(1024);
        });

        assertThat(rendersForB).as("tenant B must not be served tenant A's cached page").hasValue(1);
    }

    @Test
    void theBudgetIsInBytes_andTheOldestEntriesGoFirst() {
        properties.setPageImageCacheBytes(10_000);

        // Five 2 KB pages fit; the sixth pushes the total over and evicts the
        // least recently used.
        for (int page = 1; page <= 6; page++) {
            cache.get(tenantA, versionId, page, 150, () -> image(2_000));
        }

        assertThat(cache.bytesHeld()).isLessThanOrEqualTo(10_000);
        assertThat(cache.size()).isEqualTo(5);

        // Page 1 was the oldest, so it is gone and renders again.
        AtomicInteger renders = new AtomicInteger();
        cache.get(tenantA, versionId, 1, 150, () -> {
            renders.incrementAndGet();
            return image(2_000);
        });
        assertThat(renders).hasValue(1);
    }

    /**
     * One oversized image must not be able to empty the cache to store
     * itself. It is served - the reviewer still sees their page - and simply
     * not kept.
     */
    @Test
    void anImageTooBigForTheBudget_isServedButNotKept() {
        properties.setPageImageCacheBytes(10_000);
        cache.get(tenantA, versionId, 1, 150, () -> image(2_000));

        OcrWorkerClient.PageImage huge = cache.get(tenantA, versionId, 2, 150, () -> image(9_000));

        assertThat(huge.png()).hasSize(9_000);
        assertThat(cache.size()).as("the small page is still there").isEqualTo(1);
        assertThat(cache.bytesHeld()).isEqualTo(2_000);
    }

    /** The purge hook: a destroyed document leaves no picture of itself behind. */
    @Test
    void invalidateDropsEveryPageOfOneVersion_andLeavesOthersAlone() {
        UUID otherVersion = UUID.randomUUID();
        cache.get(tenantA, versionId, 1, 150, () -> image(1_000));
        cache.get(tenantA, versionId, 2, 150, () -> image(1_000));
        cache.get(tenantA, otherVersion, 1, 150, () -> image(1_000));

        cache.invalidate(versionId);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.bytesHeld()).isEqualTo(1_000);
        AtomicInteger renders = new AtomicInteger();
        cache.get(tenantA, versionId, 1, 150, () -> {
            renders.incrementAndGet();
            return image(1_000);
        });
        assertThat(renders).as("a purged version's page must be re-fetched, not served from memory").hasValue(1);
    }

    @Test
    void withTheCacheOff_everyReadRenders() {
        properties.setPageImageCacheEnabled(false);
        AtomicInteger renders = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            cache.get(tenantA, versionId, 1, 150, () -> {
                renders.incrementAndGet();
                return image(1_000);
            });
        }

        assertThat(renders).hasValue(3);
        assertThat(cache.size()).isZero();
    }
}
