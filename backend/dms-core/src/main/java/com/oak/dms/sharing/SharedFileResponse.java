package com.oak.dms.sharing;

import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.sharing.ShareBrowser.Served;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

/**
 * How a shared file goes out. Never cached (a revoked link must not be answered from a browser or proxy cache),
 * never sniffed, never leaks the page address as a referrer; shown inline only when it is something a browser
 * renders safely, otherwise it is always a download.
 */
final class SharedFileResponse {

    private SharedFileResponse() {
    }

    static ResponseEntity<StreamingResponseBody> of(Served served) {
        DownloadedDocument file = served.file();
        StreamingResponseBody body = out -> {
            try (InputStream in = file.content()) {
                in.transferTo(out);
            }
        };
        String type = file.contentType() != null && !file.contentType().isBlank()
                ? file.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String name = file.filename() == null || file.filename().isBlank() ? "file" : file.filename();
        String ascii = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        String disposition = (served.inline() ? "inline" : "attachment") + "; filename=\"" + ascii
                + "\"; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, type)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}
