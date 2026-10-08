package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.format.FontProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FontProvider} is a singleton shared by every watermark job, and the
 * job executor runs several at once, so its two caches are touched
 * concurrently from the first request onwards.
 * <p>
 * Both are written to be safe - the per-size font cache is a
 * {@link ConcurrentHashMap}, and the all-installed-families list is a
 * {@code volatile} field under double-checked locking - but "written to be
 * safe" is an intention, not a fact. The expensive one is the families list:
 * it is built lazily on the rare fallback path, which is exactly the kind of
 * initialisation that works in every single-threaded test and then races in
 * production the one time two CJK jobs arrive together.
 */
class FontProviderConcurrencyTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void concurrentLookupsAgreeOnTheSameFontAndNeverFail() throws Exception {
        FontProvider provider = new FontProvider(new WatermarkProperties());
        int threads = 16;
        int perThread = 50;

        Set<String> familiesSeen = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            Font font = provider.resolve("CONFIDENTIAL").at(40f);
                            familiesSeen.add(font.getFamily() + "/" + font.getSize2D());
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get()).isNull();
        // One answer, not one per thread - a torn cache would show up as
        // several different families for identical input.
        assertThat(familiesSeen).hasSize(1);
    }

    @Test
    void theLazyAllFamiliesListIsBuiltOnceUnderConcurrentFallback() throws Exception {
        // Forces the fallback path on every call: the configured list is one
        // family that cannot draw the text, so each thread has to consult
        // the lazily-built list of everything installed.
        WatermarkProperties properties = new WatermarkProperties();
        properties.setFontFamilies(List.of("No Such Font Anywhere"));
        FontProvider provider = new FontProvider(properties);

        String text = "李娜 Αθήνα";
        Set<String> familiesSeen = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(12);

        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            for (int t = 0; t < 12; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 20; i++) {
                            familiesSeen.add(provider.resolve(text).at(32f).getFamily());
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get()).isNull();
        assertThat(familiesSeen)
                .describedAs("every thread must reach the same conclusion")
                .hasSize(1);
    }

    @Test
    void theSecondLookupIsServedFromCacheRatherThanRebuildingTheFontList() {
        FontProvider provider = new FontProvider(new WatermarkProperties());
        provider.resolve("CONFIDENTIAL").at(40f);

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            provider.resolve("CONFIDENTIAL").at(40f);
        }
        long microsEach = (System.nanoTime() - start) / 1000 / 1000;

        // Not a benchmark - a guard against someone making this allocate a
        // Font or re-enumerate families on every single call.
        assertThat(microsEach).describedAs("%dus per cached lookup", microsEach).isLessThan(500);
    }
}
