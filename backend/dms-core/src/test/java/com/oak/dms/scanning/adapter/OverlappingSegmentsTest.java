package com.oak.dms.scanning.adapter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cutting a stream too large for one clamd INSTREAM into overlapping segments. */
class OverlappingSegmentsTest {

    @Test
    void fileWithinOneSegment_isOneSegmentUnchanged() throws IOException {
        byte[] data = bytes(100);

        List<byte[]> segments = segments(data, 100, 10, 7);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0)).isEqualTo(data);
    }

    @Test
    void emptyFile_isOneEmptySegment() throws IOException {
        List<byte[]> segments = segments(new byte[0], 100, 10, 7);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0)).isEmpty();
    }

    @Test
    void largerFile_segmentsOverlapAndCoverEveryByte() throws IOException {
        byte[] data = bytes(250);

        List<byte[]> segments = segments(data, 100, 10, 7);

        // 0-99, 90-189, 180-249
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0)).isEqualTo(Arrays.copyOfRange(data, 0, 100));
        assertThat(segments.get(1)).isEqualTo(Arrays.copyOfRange(data, 90, 190));
        assertThat(segments.get(2)).isEqualTo(Arrays.copyOfRange(data, 180, 250));
    }

    @Test
    void fileEndingExactlyAtACut_hasNoOverlapOnlySegmentAfterIt() throws IOException {
        byte[] data = bytes(190);

        List<byte[]> segments = segments(data, 100, 10, 7);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(1)).isEqualTo(Arrays.copyOfRange(data, 90, 190));
    }

    @Test
    void readsOneByteAtATime_sameSegments() throws IOException {
        byte[] data = bytes(250);

        List<byte[]> segments = segments(data, 100, 10, 1);

        assertThat(segments).hasSize(3);
        assertThat(segments.get(1)).isEqualTo(Arrays.copyOfRange(data, 90, 190));
        assertThat(segments.get(2)).isEqualTo(Arrays.copyOfRange(data, 180, 250));
    }

    @Test
    void patternAcrossACut_isWholeInTheNextSegment() throws IOException {
        byte[] data = new byte[300];
        byte[] signature = "SIGNATURE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, data, 95, signature.length); // straddles the cut at 100

        List<byte[]> segments = segments(data, 100, 16, 7);

        assertThat(indexOf(segments.get(0), signature)).isEqualTo(-1);
        assertThat(indexOf(segments.get(1), signature)).isEqualTo(11);
    }

    @Test
    void overlapNotSmallerThanASegment_isRefused() {
        assertThatThrownBy(() -> new OverlappingSegments(new ByteArrayInputStream(new byte[0]), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Reads every segment in {@code readSize} pieces (1 = the single-byte read()). */
    private static List<byte[]> segments(byte[] data, long segmentBytes, int overlapBytes, int readSize)
            throws IOException {
        OverlappingSegments segments = new OverlappingSegments(new ByteArrayInputStream(data), segmentBytes,
                overlapBytes);
        List<byte[]> result = new ArrayList<>();
        InputStream segment;
        while ((segment = segments.next()) != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (readSize == 1) {
                int b;
                while ((b = segment.read()) != -1) {
                    out.write(b);
                }
            } else {
                byte[] buffer = new byte[readSize];
                int n;
                while ((n = segment.read(buffer, 0, buffer.length)) != -1) {
                    out.write(buffer, 0, n);
                }
            }
            result.add(out.toByteArray());
        }
        return result;
    }

    private static byte[] bytes(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }
}
