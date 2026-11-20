package com.oak.dms.importexport.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOC-10: the stream that hands storage exactly the declared number of bytes, and counts them. */
class ExactLengthInputStreamTest {

    private static InputStream source(int bytes) {
        return new ByteArrayInputStream(new byte[bytes]);
    }

    @Test
    void actualSizeEqualToDeclared_isDeliveredWhole_andExactlyConsumed() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(1000), 1000);

        assertThat(in.readAllBytes()).hasSize(1000);

        assertThat(in.count()).isEqualTo(1000);
        assertThat(in.sourceEndedEarly()).isFalse();
        assertThat(in.isExactlyConsumed()).isTrue();
    }

    @Test
    void actualSizeLargerThanDeclared_neverDeliversTheExcess_andIsNotExactlyConsumed() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(8 * 1024 * 1024), 100);

        assertThat(in.readAllBytes()).as("the adapter sees only what was declared").hasSize(100);
        assertThat(in.read()).as("and end-of-stream after that, however often it asks").isEqualTo(-1);
        assertThat(in.read(new byte[10], 0, 10)).isEqualTo(-1);

        assertThat(in.count()).isEqualTo(100);
        assertThat(in.isExactlyConsumed()).as("the excess is found afterwards").isFalse();
    }

    @Test
    void anAdapterThatStopsAtTheDeclaredLength_withoutAskingForMore_isStillCaught() throws IOException {
        // like an S3 client: reads contentLength bytes and never looks for end-of-stream
        ExactLengthInputStream in = new ExactLengthInputStream(source(5000), 1000);
        in.readNBytes(1000);

        assertThat(in.count()).isEqualTo(1000);
        assertThat(in.isExactlyConsumed()).isFalse();
    }

    @Test
    void actualSizeShorterThanDeclared_isFlagged_andNotExactlyConsumed() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(40), 1000);

        assertThat(in.readAllBytes()).hasSize(40);

        assertThat(in.count()).isEqualTo(40);
        assertThat(in.sourceEndedEarly()).isTrue();
        assertThat(in.isExactlyConsumed()).isFalse();
    }

    @Test
    void aConsumerThatStopsEarly_leavesATruncatedObject_thatIsNotAccepted() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(1000), 1000);
        in.readNBytes(600);

        assertThat(in.sourceEndedEarly()).isFalse();
        assertThat(in.isExactlyConsumed()).isFalse();
    }

    @Test
    void zeroDeclared_matchesAnEmptySourceOnly() throws IOException {
        assertThat(new ExactLengthInputStream(source(0), 0).isExactlyConsumed()).isTrue();
        assertThat(new ExactLengthInputStream(source(1), 0).isExactlyConsumed()).isFalse();
        assertThat(new ExactLengthInputStream(source(1), 0).read()).isEqualTo(-1);
    }

    @Test
    void aNegativeDeclaredLength_isRefused() {
        assertThatThrownBy(() -> new ExactLengthInputStream(source(1), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHugeDeclaredLength_doesNotOverflow_andAShortSourceIsStillFlagged() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(10), Long.MAX_VALUE);

        assertThat(in.readAllBytes()).hasSize(10);

        assertThat(in.count()).isEqualTo(10);
        assertThat(in.sourceEndedEarly()).isTrue();
        assertThat(in.isExactlyConsumed()).isFalse();
        assertThat(in.available()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void singleByteReads_andBulkReadsAreCountedTheSame() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(50), 50);
        int single = 0;
        while (in.read() != -1 && single < 20) {
            single++;
        }
        in.transferTo(OutputStream.nullOutputStream());

        assertThat(in.count()).isEqualTo(50);
        assertThat(in.isExactlyConsumed()).isTrue();
    }

    @Test
    void skippedBytesCount_andCannotPassTheDeclaredLength() throws IOException {
        ExactLengthInputStream in = new ExactLengthInputStream(source(100), 30);

        assertThat(in.skip(1000)).isEqualTo(30);

        assertThat(in.count()).isEqualTo(30);
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void markAndReset_areNotSupported_soTheCountStaysTrue() {
        ExactLengthInputStream in = new ExactLengthInputStream(source(10), 10);

        assertThat(in.markSupported()).isFalse();
        assertThatThrownBy(in::reset).isInstanceOf(IOException.class);
    }
}
