package com.oak.dms.scanning.adapter;

import com.oak.dms.scanning.exception.ScanEngineErrorException;
import com.oak.dms.scanning.exception.ScanEngineUnavailableException;
import com.oak.dms.scanning.exception.ScanTimeoutException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Minimal {@code clamd} TCP protocol client - just the two commands the
 * adapter needs, using the null-terminated ({@code z}-prefixed) forms:
 * <ul>
 *   <li>{@code zINSTREAM} - the content follows as chunks of
 *   {@code <4-byte big-endian length><bytes>}, terminated by a zero-length
 *   chunk; clamd answers {@code stream: OK}, {@code stream: <name> FOUND}
 *   or {@code <message> ERROR}.</li>
 *   <li>{@code zVERSION} - answers {@code ClamAV <engine>/<signatures>/<date>}.</li>
 * </ul>
 * Hand-rolled rather than a library dependency: the protocol is this small,
 * and the Java clamd clients available are unmaintained. One short-lived
 * connection per command (clamd handles one command per connection unless
 * IDSESSION is used, which this doesn't need). Package-private -
 * {@link ClamAvScanAdapter} is its only user.
 */
class ClamdClient {

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VERSION = "zVERSION\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REPLY_BYTES = 4096;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int chunkSizeBytes;

    ClamdClient(String host, int port, int connectTimeoutMs, int readTimeoutMs, int chunkSizeBytes) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.chunkSizeBytes = chunkSizeBytes;
    }

    /** Streams {@code content} to clamd and returns its raw reply (e.g. {@code stream: OK}). Does not close {@code content}. */
    String instream(InputStream content) {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(INSTREAM);
            byte[] buffer = new byte[chunkSizeBytes];
            try {
                int read;
                while ((read = readContent(content, buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    out.write(ByteBuffer.allocate(4).putInt(read).array());
                    out.write(buffer, 0, read);
                }
                out.write(new byte[4]);
                out.flush();
            } catch (IOException e) {
                // clamd closes its side as soon as the stream exceeds
                // StreamMaxLength - it has usually written its reason first.
                String reply = tryReadReply(in);
                if (reply != null) {
                    return reply;
                }
                throw e;
            }
            return readReply(in);
        } catch (SocketTimeoutException e) {
            throw new ScanTimeoutException("clamd at " + host + ":" + port + " did not answer within "
                    + readTimeoutMs + "ms", e);
        } catch (StagedContentReadException e) {
            throw new ScanEngineErrorException("Could not read the staged object to scan it", e.getCause());
        } catch (IOException e) {
            throw new ScanEngineErrorException("clamd INSTREAM failed at " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    /** Raw {@code VERSION} reply, e.g. {@code ClamAV 1.4.3/27780/Mon Sep 28 08:24:02 2026}. */
    String version() {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(VERSION);
            out.flush();
            return readReply(socket.getInputStream());
        } catch (SocketTimeoutException e) {
            throw new ScanTimeoutException("clamd at " + host + ":" + port + " did not answer VERSION", e);
        } catch (IOException e) {
            throw new ScanEngineErrorException("clamd VERSION failed at " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            return socket;
        } catch (ConnectException | UnknownHostException | NoRouteToHostException | SocketTimeoutException e) {
            socket.close();
            throw new ScanEngineUnavailableException("clamd at " + host + ":" + port + " is unreachable: "
                    + e.getMessage(), e);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static int readContent(InputStream content, byte[] buffer) {
        try {
            return content.read(buffer);
        } catch (IOException e) {
            throw new StagedContentReadException(e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            if (reply.size() >= MAX_REPLY_BYTES) {
                break;
            }
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII).trim();
    }

    private static String tryReadReply(InputStream in) {
        try {
            String reply = readReply(in);
            return reply.isEmpty() ? null : reply;
        } catch (IOException e) {
            return null;
        }
    }

    /** Distinguishes "couldn't read our own staged object" from a clamd socket failure. */
    private static final class StagedContentReadException extends RuntimeException {
        StagedContentReadException(IOException cause) {
            super(cause);
        }
    }
}
