package com.oak.dms.scanning.adapter;

import com.oak.dms.scanning.dto.ScanTarget;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanVerdictStatus;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** clamd verdicts, including files sent as several segments because one INSTREAM can't hold them. */
class ClamAvScanAdapterTest {

    private final ScanProperties properties = new ScanProperties();
    private final ObjectStorageServiceFactory storageFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ClamAvScanAdapter adapter = new ClamAvScanAdapter(properties, storageFactory);
    private final ScanEngine engine = ScanEngine.builder().scanEngineId(UUID.randomUUID()).timeoutSeconds(60).build();
    private final StubClamd clamd = new StubClamd();

    @BeforeEach
    void setUp() {
        properties.getClamav().setSegmentBytes(100);
        properties.getClamav().setSegmentOverlapBytes(10);
        when(storageFactory.resolve(any())).thenReturn(storage);
        adapter.clients.put(engine.getScanEngineId(), clamd);
    }

    @Test
    void fileWithinOneSegment_oneInstream() {
        staged(50);
        clamd.replies("stream: OK");

        ScanVerdict verdict = adapter.scan(engine, target());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.CLEAN);
        assertThat(clamd.received).extracting(b -> b.length).containsExactly(50);
    }

    @Test
    void largeFile_everySegmentClean_clean() {
        staged(250);
        clamd.replies("stream: OK", "stream: OK", "stream: OK");

        ScanVerdict verdict = adapter.scan(engine, target());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.CLEAN);
        assertThat(clamd.received).extracting(b -> b.length).containsExactly(100, 100, 70);
    }

    @Test
    void largeFile_detectionInALaterSegment_infected() {
        staged(250);
        clamd.replies("stream: OK", "stream: Win.Test.EICAR_HDB-1 FOUND", "stream: OK");

        ScanVerdict verdict = adapter.scan(engine, target());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.INFECTED);
        assertThat(verdict.threatName()).isEqualTo("Win.Test.EICAR_HDB-1");
        assertThat(clamd.received).hasSize(2); // stops at the first detection
    }

    @Test
    void largeFile_unscannableSegmentThenDetection_infectedWins() {
        staged(250);
        clamd.replies("stream: Heuristics.Limits.Exceeded.MaxFileSize FOUND", "stream: Win.Test.EICAR_HDB-1 FOUND");

        assertThat(adapter.scan(engine, target()).status()).isEqualTo(ScanVerdictStatus.INFECTED);
    }

    @Test
    void largeFile_unscannableSegmentOtherwiseClean_unscannable() {
        staged(250);
        clamd.replies("stream: OK", "stream: Heuristics.Encrypted.Zip FOUND", "stream: OK");

        ScanVerdict verdict = adapter.scan(engine, target());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.UNSCANNABLE);
        assertThat(verdict.threatName()).isEqualTo("Heuristics.Encrypted.Zip");
        assertThat(clamd.received).hasSize(3);
    }

    @Test
    void zipClamdCanOpenWhole_sentAsItIs() throws IOException {
        properties.getClamav().setSegmentBytes(10_000);
        byte[] zip = zip(entry("a.txt", 20), entry("b.txt", 20));
        staged(zip);
        clamd.replies("stream: OK");

        ScanVerdict verdict = adapter.scan(engine, zipTarget());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.CLEAN);
        assertThat(clamd.received).containsExactly(zip);
    }

    @Test
    void zipUnpackingPastClamdsLimit_archiveThenEachFileScanned() throws IOException {
        properties.getClamav().setSegmentBytes(10_000);
        properties.getClamav().setArchiveScanBytes(100);
        byte[] zip = zip(entry("a.txt", 80), entry("b.txt", 80));
        staged(zip);
        // The archive pass hitting clamd's limit is expected - its files are checked one by one.
        clamd.replies("stream: Heuristics.Limits.Exceeded.MaxScanSize FOUND", "stream: OK", "stream: OK");

        ScanVerdict verdict = adapter.scan(engine, zipTarget());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.CLEAN);
        assertThat(clamd.received).hasSize(3);
        assertThat(clamd.received.get(0)).isEqualTo(zip);
        assertThat(clamd.received.get(1)).isEqualTo(content("a.txt", 80));
        assertThat(clamd.received.get(2)).isEqualTo(content("b.txt", 80));
    }

    @Test
    void zipWithMoreFilesThanClamdOpens_scannedFileByFile() throws IOException {
        properties.getClamav().setSegmentBytes(10_000);
        properties.getClamav().setArchiveScanFiles(1);
        staged(zip(entry("a.txt", 10), entry("b.txt", 10)));
        clamd.replies("stream: OK", "stream: OK", "stream: OK");

        assertThat(adapter.scan(engine, zipTarget()).status()).isEqualTo(ScanVerdictStatus.CLEAN);
        assertThat(clamd.received).hasSize(3);
    }

    @Test
    void largeZip_detectionInOneFile_infected() throws IOException {
        properties.getClamav().setSegmentBytes(10_000);
        properties.getClamav().setArchiveScanBytes(100);
        staged(zip(entry("a.txt", 80), entry("b.txt", 80)));
        clamd.replies("stream: OK", "stream: Win.Test.EICAR_HDB-1 FOUND", "stream: OK");

        ScanVerdict verdict = adapter.scan(engine, zipTarget());

        assertThat(verdict.status()).isEqualTo(ScanVerdictStatus.INFECTED);
        assertThat(verdict.threatName()).isEqualTo("Win.Test.EICAR_HDB-1");
        assertThat(clamd.received).hasSize(2);
    }

    @Test
    void largeZip_limitsAlertOnAFileItself_unscannable() throws IOException {
        properties.getClamav().setSegmentBytes(10_000);
        properties.getClamav().setArchiveScanBytes(100);
        staged(zip(entry("nested.zip", 80), entry("b.txt", 80)));
        // A nested archive clamd can't finish is still unknown, not passed.
        clamd.replies("stream: OK", "stream: Heuristics.Limits.Exceeded.MaxScanSize FOUND", "stream: OK");

        assertThat(adapter.scan(engine, zipTarget()).status()).isEqualTo(ScanVerdictStatus.UNSCANNABLE);
    }

    private void staged(int size) {
        staged(new byte[size]);
    }

    private void staged(byte[] content) {
        when(storage.download(any(), anyString()))
                .thenReturn(new DownloadedObject(new ByteArrayInputStream(content), content.length, null));
    }

    private ScanTarget target() {
        return new ScanTarget(UUID.randomUUID(), new StorageLocation(), "staging/x", 0, null, null, null);
    }

    private ScanTarget zipTarget() {
        return new ScanTarget(UUID.randomUUID(), new StorageLocation(), "staging/x", 0, "application/zip", null,
                null);
    }

    private static String entry(String name, int size) {
        return name + ":" + size;
    }

    /** Distinct, compressible content per entry, so each file sent to clamd can be told apart. */
    private static byte[] content(String name, int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) name.charAt(i % name.length());
        }
        return data;
    }

    private static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                String name = entry.substring(0, entry.indexOf(':'));
                out.putNextEntry(new ZipEntry(name));
                out.write(content(name, Integer.parseInt(entry.substring(entry.indexOf(':') + 1))));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** clamd stand-in: records what each INSTREAM carried and answers from a script. */
    private static final class StubClamd extends ClamdClient {
        private final Deque<String> replies = new ArrayDeque<>();
        private final List<byte[]> received = new ArrayList<>();

        StubClamd() {
            super("localhost", 0, 0, 0, 1);
        }

        void replies(String... scripted) {
            replies.addAll(List.of(scripted));
        }

        @Override
        String instream(InputStream content) {
            try {
                received.add(content.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return replies.removeFirst();
        }

        @Override
        String version() {
            return "ClamAV 1.4.3/27780/Mon Sep 28 08:24:02 2026";
        }
    }
}
