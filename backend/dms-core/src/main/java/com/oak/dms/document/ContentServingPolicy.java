package com.oak.dms.document;

import org.springframework.http.ResponseEntity;

import java.util.Locale;
import java.util.Set;

/**
 * What a response that serves a stored document's own bytes may tell the browser to do with them (DOC-04).
 * <p>
 * A stored file is user-supplied content served from the API's own origin. Most types are harmless there, but a
 * browser opening an XML document from its address bar picks a renderer from its namespace - an XHTML or SVG
 * document stored as {@code .xml} (the upload check only requires the text to start with {@code <}) runs its
 * scripts with the application's origin, whatever {@code X-Content-Type-Options} says. So:
 * <ul>
 *   <li><b>Disposition.</b> A response is {@code inline} only when it was asked to be <em>and</em> the type is one
 *       the browser shows passively: PDF and the raster image formats below. Everything else - XML, JSON, text,
 *       HTML/XHTML, SVG ({@code image/svg+xml} is deliberately not an allowed image), Office files, unknown types -
 *       is sent as an {@code attachment}. The allow-list is closed: a type not named here is an attachment, so a
 *       new type added to the upload allow-list later is safe until someone chooses to list it.</li>
 *   <li><b>Sandbox.</b> Every response except a PDF also carries {@code Content-Security-Policy: sandbox;
 *       default-src 'none'}, which stops any script in it running even if something still opens it inline. It is
 *       left off PDFs, which Chrome's built-in viewer cannot show under a sandbox policy.</li>
 * </ul>
 * The type used is always the stored, server-determined one (derived from the file's extension and verified against
 * its content at upload), never anything the client sent. Whether the in-app preview asked for {@code inline} only
 * affects the disposition: the frontend reads these files with {@code fetch}, which ignores both headers.
 */
public final class ContentServingPolicy {

    /** The CSP sent with every non-PDF response. */
    public static final String SANDBOX_CSP = "sandbox; default-src 'none'";

    /** The only types ever sent inline: PDF, and raster images that cannot carry script. Not SVG. */
    private static final Set<String> INLINE_TYPES = Set.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private ContentServingPolicy() {
    }

    /** {@code "inline"} when {@code inlineRequested} and the type is a passive one; otherwise {@code "attachment"}. */
    public static String dispositionType(boolean inlineRequested, String contentType) {
        return inlineRequested && INLINE_TYPES.contains(baseType(contentType)) ? "inline" : "attachment";
    }

    /** Whether the {@link #SANDBOX_CSP} goes on a response of this type: every type but PDF. */
    public static boolean needsSandbox(String contentType) {
        return !"application/pdf".equals(baseType(contentType));
    }

    /** {@code builder} with the sandbox CSP added when the type needs it; its other headers are untouched. */
    public static ResponseEntity.BodyBuilder withSandbox(ResponseEntity.BodyBuilder builder, String contentType) {
        return needsSandbox(contentType) ? builder.header("Content-Security-Policy", SANDBOX_CSP) : builder;
    }

    /** The media type without parameters, lower-case; empty for a missing type (which is then treated as unsafe). */
    private static String baseType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
    }
}
