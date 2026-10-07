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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

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

    private void staged(int size) {
        when(storage.download(any(), anyString()))
                .thenReturn(new DownloadedObject(new ByteArrayInputStream(new byte[size]), size, null));
    }

    private ScanTarget target() {
        return new ScanTarget(UUID.randomUUID(), new StorageLocation(), "staging/x", 0, null, null, null);
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
