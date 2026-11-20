package com.oak.dms.document;

import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DOC-02 (resource cleanup): a plain download is now the storage stream, handed straight to the client rather than
 * copied into a byte[] first, so releasing it - a file handle or an HTTP connection to the object store - depends on
 * the controller's streaming body closing it whatever goes wrong. These drive the real {@code DocumentController}
 * with a stream that fails, and a client that goes away, and check the stream is closed either way.
 * {@code DocumentService} is stubbed: what is under test is the controller's handling of the stream it is given.
 */
@SpringBootTest
class DocumentDownloadStreamCleanupIT {

    @MockitoBean private DocumentService documentService;
    /** The permission-code gate on the endpoint is a separate layer, stubbed as granted. */
    @MockitoBean private AuthorizationService authorizationService;
    @Autowired private DocumentController controller;

    @BeforeEach
    void signedIn() {
        when(authorizationService.hasPermission(anyString())).thenReturn(true);
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void signedOut() {
        TenantContext.clear();
    }

    /** Serves {@code size} bytes, failing after {@code failAfter} of them if that is >= 0, and records being closed. */
    private static final class TrackedStream extends InputStream {
        private final int size;
        private final int failAfter;
        private int position;
        final AtomicBoolean closed = new AtomicBoolean();

        TrackedStream(int size, int failAfter) {
            this.size = size;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            if (failAfter >= 0 && position >= failAfter) {
                throw new IOException("storage connection reset");
            }
            return position < size ? (position++ & 0x7f) : -1;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private StreamingResponseBody bodyFor(TrackedStream stream, int declaredSize) {
        UUID id = UUID.randomUUID();
        when(documentService.downloadDocument(id))
                .thenReturn(new DownloadedDocument(stream, declaredSize, "application/pdf", "cleanup.pdf"));
        ResponseEntity<StreamingResponseBody> response = controller.downloadDocument(id, false, null);
        assertThat(response.getHeaders().getFirst("Content-Length")).isEqualTo(String.valueOf(declaredSize));
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"cleanup.pdf\"");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        return response.getBody();
    }

    @Test
    void aStreamThatCompletes_isClosed_andEveryByteIsWritten() throws Exception {
        TrackedStream stream = new TrackedStream(100_000, -1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bodyFor(stream, 100_000).writeTo(out);
        assertThat(out.size()).isEqualTo(100_000);
        assertThat(stream.closed).isTrue();
    }

    @Test
    void aStorageStreamThatFailsPartWay_isClosed() {
        TrackedStream stream = new TrackedStream(100_000, 40_000);
        StreamingResponseBody body = bodyFor(stream, 100_000);
        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class).hasMessageContaining("storage connection reset");
        assertThat(stream.closed).isTrue();
    }

    @Test
    void aClientThatDisconnectsPartWay_leavesTheStorageStreamClosed() {
        TrackedStream stream = new TrackedStream(1_000_000, -1);
        StreamingResponseBody body = bodyFor(stream, 1_000_000);
        OutputStream disconnecting = new OutputStream() {
            private int written;

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > 100_000) {
                    throw new IOException("Broken pipe"); // what the servlet container raises when the client is gone
                }
            }
        };
        assertThatThrownBy(() -> body.writeTo(disconnecting)).isInstanceOf(IOException.class);
        assertThat(stream.closed).isTrue();
    }
}
