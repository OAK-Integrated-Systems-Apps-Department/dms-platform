package com.oak.dms.document;

import com.oak.dms.document.dto.DownloadedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Who owns a stored file's stream while a download is being set up (DOC-18).
 * <p>
 * A service that opens a version's content ({@code VersionContent.open}) holds a storage stream - a file handle or an
 * object-store connection - that nobody else knows exists. It hands it to its caller by returning it, and from then on
 * the caller owns it: the controller's streaming body closes it once the bytes are written (DOC-02). Until that
 * hand-over succeeds the service owns it, and two things can stop the hand-over:
 * <ol>
 *   <li><b>An exception in the method</b> after the stream was opened (the audit write, for one) - the caller never
 *       receives the stream. The method closes it ({@link #closeQuietly}) before rethrowing.</li>
 *   <li><b>A failed transaction completion after the method has returned.</b> The services are
 *       {@code @Transactional}, so the commit runs in Spring's proxy, <em>after</em> the method body: a commit (or
 *       flush) failure surfaces there, the caller gets an exception instead of the stream, and no {@code catch}
 *       inside the method can see it. {@link #closeUnlessCommitted} registers a transaction callback that checks the
 *       real completion status and closes the stream unless the transaction <em>committed</em>. On a commit the
 *       stream is left open for the caller.</li>
 * </ol>
 * The callback is registered only when the calling thread has an actual, synchronised transaction, and it ends with that
 * transaction. If the service is called inside a bigger transaction, the callback simply waits for that one to end: by
 * then the (same-thread) caller has already consumed or closed the stream, and closing it again is harmless; if that
 * transaction rolls back before the stream was consumed, closing it is exactly right. With no transaction nothing is
 * registered and behaviour is as before. Closing is idempotent for the streams used here, so a stream that was already
 * closed by its consumer is not an issue.
 */
@Slf4j
final class OpenStreamGuard {

    private OpenStreamGuard() {
    }

    /** Closes the content stream of {@code opened}, never throwing: used on a path that is already failing. */
    static void closeQuietly(DownloadedDocument opened) {
        if (opened == null) {
            return;
        }
        try (InputStream content = opened.content()) {
            // closed by try-with-resources
        } catch (IOException | RuntimeException e) {
            // Nothing more can be done for a stream that will not close, and the original failure matters more.
            log.warn("Could not close a download stream after a failed download ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * Returns {@code opened} to the caller, but if the surrounding transaction does not commit, closes it.
     * No-op without an active, synchronised transaction.
     */
    static DownloadedDocument closeUnlessCommitted(DownloadedDocument opened) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {   // rolled back, or the outcome is unknown: the caller got no stream
                        closeQuietly(opened);
                    }
                }
            });
        }
        return opened;
    }
}
