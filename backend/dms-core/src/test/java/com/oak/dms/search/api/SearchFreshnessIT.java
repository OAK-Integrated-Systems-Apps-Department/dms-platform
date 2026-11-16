package com.oak.dms.search.api;

import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.workflow.support.WorkflowFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freshness (D8): how long after a change in PostgreSQL it is searchable through the API, with the indexer running on its
 * real poll cadence (the scheduler is switched off in tests, so a thread plays its part: {@code runOnce} every
 * {@code poll-interval}, draining while there is work, exactly as {@code SearchIndexScheduler} does). Title, file name and
 * metadata must be searchable within seconds; text inside the file within 60 seconds. The measured times are printed so
 * a run documents the margin, and the lag gauge is checked too.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchFreshnessIT extends SearchApiSupport {

    private static final Duration TITLE_AND_METADATA_WINDOW = Duration.ofSeconds(10);
    private static final Duration BODY_WINDOW = Duration.ofSeconds(60);

    @Autowired
    private DocumentVersionRepository versions;
    @Autowired
    private StorageLocationResolver resolver;
    @Autowired
    private ObjectStorageServiceFactory storage;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private SearchProperties props;

    private final AtomicBoolean running = new AtomicBoolean();
    private Thread scheduler;
    private final List<String> measured = new ArrayList<>();

    @BeforeEach
    void startScheduler() {
        index(); // the tenant is ready and the queue is empty before the clock starts
        running.set(true);
        scheduler = new Thread(() -> {
            while (running.get()) {
                try {
                    for (int i = 0; i < 20 && indexer.runOnce() > 0; i++) {
                        // drain while there is work, as the scheduler does
                    }
                    Thread.sleep(props.getIndexer().getPollInterval().toMillis());
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // a failed tick is retried on the next one, as in the scheduler
                }
            }
        }, "test-search-scheduler");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    @AfterEach
    void stopScheduler() throws InterruptedException {
        running.set(false);
        scheduler.interrupt();
        scheduler.join(5000);
        measured.forEach(line -> System.out.println("[search-freshness] " + line));
    }

    /** Seconds from now until {@code found} is true (polling every 100 ms), or fails at {@code limit}. */
    private double secondsUntil(String what, Duration limit, Supplier<Boolean> found) throws Exception {
        long start = System.nanoTime();
        while (Duration.ofNanos(System.nanoTime() - start).compareTo(limit) < 0) {
            if (found.get()) {
                double seconds = (System.nanoTime() - start) / 1e9;
                measured.add(String.format("%-52s %6.2f s (window %d s)", what, seconds, limit.toSeconds()));
                return seconds;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(what + " was not searchable within " + limit.toSeconds() + " seconds");
    }

    private boolean finds(String word, UUID document) {
        try {
            return ids(search(alice, "q", word)).contains(document);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aNewDocumentsTitleFileNameAndTextAreSearchableWithinTheWindow() throws Exception {
        UUID doc = doc("Freshtitle onboarding plan", "freshfile_notes.txt", "contents mention freshbody words");
        assertThat(secondsUntil("new document found by title", TITLE_AND_METADATA_WINDOW, () -> finds("freshtitle", doc)))
                .isLessThan(TITLE_AND_METADATA_WINDOW.toSeconds());
        assertThat(finds("freshfile", doc)).as("file name").isTrue();
        assertThat(secondsUntil("new document found by text inside it", BODY_WINDOW, () -> finds("freshbody", doc)))
                .isLessThan(BODY_WINDOW.toSeconds());
    }

    @Test
    void aRenameAndAMetadataEditAreSearchableWithinTheWindow() throws Exception {
        UUID note = metadataField("note", "text", true);
        UUID doc = doc("Original name", "n.txt", "x");
        secondsUntil("setup: document indexed", TITLE_AND_METADATA_WINDOW, () -> finds("original", doc));

        fx.jdbc().update("update documents set title = 'Renamedfresh title' where document_id = ?", doc);
        secondsUntil("rename found by the new title", TITLE_AND_METADATA_WINDOW, () -> finds("renamedfresh", doc));
        assertThat(finds("original", doc)).as("the old title no longer matches").isFalse();

        setMetadata(doc, "{\"" + note + "\":{\"v\":\"metafresh remark\",\"src\":\"manual\"}}");
        secondsUntil("metadata edit found by its text", TITLE_AND_METADATA_WINDOW, () -> finds("metafresh", doc));
    }

    @Test
    void aNewVersionsTextReplacesTheOldWithinTheWindow() throws Exception {
        UUID doc = doc("Versioned fresh", "v1.txt", "firstedition wording");
        secondsUntil("setup: first version's text", BODY_WINDOW, () -> finds("firstedition", doc));

        UUID v2 = fx.newVersion(tenantId, doc, alice);
        byte[] bytes = "secondedition wording".getBytes();
        fx.jdbc().update("update document_versions set original_filename = 'v2.txt', mime_type = 'text/plain', size_bytes = ? where version_id = ?", bytes.length, v2);
        fx.putVersionContent(v2, bytes);
        // the version row was written before its file, so wait for the change that makes it complete (any later job re-reads)
        fx.jdbc().update("update document_versions set size_bytes = size_bytes where version_id = ?", v2);
        fx.jdbc().update("update documents set title = 'Versioned fresh two' where document_id = ?", doc);
        secondsUntil("new version's text found", BODY_WINDOW, () -> finds("secondedition", doc));
        assertThat(finds("firstedition", doc)).isFalse();
    }

    @Test
    void textBecomesSearchableWithinTheWindowOfThePreviewFinishing() throws Exception {
        UUID doc = fx.document(tenantId, reportType, alice, "Slides fresh deck");
        UUID version = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, doc);
        fx.jdbc().update("update document_versions set original_filename = 'deck.pptx', mime_type = 'application/vnd.ms-powerpoint', rendition_status = 'processing' where version_id = ?", version);
        secondsUntil("setup: found by title while the preview is pending", TITLE_AND_METADATA_WINDOW, () -> finds("slides", doc));
        assertThat(finds("deckwords", doc)).isFalse();

        // the preview PDF is produced; the rendition trigger re-queues the document
        DocumentVersion v = versions.findById(version).orElseThrow();
        StorageLocation loc = resolver.resolveForTenant(tenantId, StoragePurpose.document);
        byte[] pdf = pdf("deckwords quarterly projections");
        String key = "rendition/" + version + "/preview.pdf";
        storage.resolve(loc).upload(loc, key, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        fx.jdbc().update("update document_versions set rendition_status = 'completed', rendition_object_key = ? where version_id = ?", key, version);
        secondsUntil("text found after the preview finished", BODY_WINDOW, () -> finds("deckwords", doc));
        assertThat(v).isNotNull();
    }

    @Test
    void theLagGaugeShowsAWaitingChangeAndReturnsToZeroWhenTheQueueIsDrained() throws Exception {
        running.set(false); // pause the "scheduler" so a change can wait
        scheduler.interrupt();
        scheduler.join(5000);
        UUID doc = doc("Lagging doc", "l.txt", "x");
        fx.jdbc().update("update search_index_jobs set next_attempt_at = now() - interval '30 seconds'");
        Thread.sleep(5200); // the gauge refreshes at most every five seconds
        double lag = meters.get("dms.search.index.lag.seconds").gauge().value();
        assertThat(lag).as("a change that has waited ~30s").isBetween(25.0, 120.0);
        assertThat(meters.get("dms.search.jobs.pending").gauge().value()).isGreaterThanOrEqualTo(1);

        for (int i = 0; i < 20 && indexer.runOnce() > 0; i++) {
            // drain
        }
        Thread.sleep(5200);
        assertThat(meters.get("dms.search.index.lag.seconds").gauge().value()).as("drained").isZero();
        assertThat(meters.get("dms.search.jobs.pending").gauge().value()).isZero();
        assertThat(doc).isNotNull();
    }

    private static byte[] pdf(String text) throws IOException {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            d.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            d.save(out);
            return out.toByteArray();
        }
    }
}
