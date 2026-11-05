package com.oak.dms.editing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The connector to the editing engine (CICO-NFR-006: another engine is added here, not in the core).
 * Today that is Collabora Online, spoken to over WOPI.
 *
 * <p>It asks the engine's discovery document which file extensions it can edit and at which URL, and
 * remembers the answer for a few minutes - so "is the engine up" is one cheap cached call, not one per click.
 * The engine's URLs in that document name ITS OWN host (an internal docker name); the browser must use the
 * public address, so only the path is kept and the public base is put in front.
 */
@Slf4j
@Component
public class EditingEngine {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final String publicUrl;
    private final String internalUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private volatile Map<String, String> actionPathByExtension = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public EditingEngine(@Value("${dms.editing.collabora-url:}") String publicUrl,
                         @Value("${dms.editing.collabora-internal-url:http://collabora:9980}") String internalUrl) {
        this.publicUrl = trimSlash(publicUrl);
        this.internalUrl = trimSlash(internalUrl);
    }

    /**
     * The SERVER has an engine to talk to (its public address is set). Whether a given organisation may use it is
     * that organisation's own setting - see {@link EditingSettingsService}; nothing here is an on/off switch.
     */
    public boolean isConfigured() {
        return !publicUrl.isBlank();
    }

    /** The extensions the engine currently offers editing for - empty when it is off or unreachable. */
    public java.util.Set<String> supportedExtensions() {
        if (!isConfigured()) {
            return java.util.Set.of();
        }
        try {
            return load().keySet();
        } catch (EngineUnavailableException e) {
            return java.util.Set.of();
        }
    }

    public boolean supports(String filename) {
        String ext = extensionOf(filename);
        return ext != null && supportedExtensions().contains(ext);
    }

    /**
     * The URL the browser posts the access token to, for this file type - public host, engine path.
     *
     * @throws EngineUnavailableException when the engine cannot be reached or does not edit this type
     */
    public Optional<String> editorUrlFor(String filename) {
        String ext = extensionOf(filename);
        if (ext == null) {
            return Optional.empty();
        }
        String path = load().get(ext);
        return path == null ? Optional.empty() : Optional.of(publicUrl + path);
    }

    /** Throws {@link EngineUnavailableException} when the engine cannot be asked. */
    public void requireAvailable() {
        load();
    }

    private Map<String, String> load() {
        if (!isConfigured()) {
            throw new EngineUnavailableException("No editing engine is configured on this server.");
        }
        if (loadedAt.plus(CACHE_FOR).isAfter(Instant.now()) && !actionPathByExtension.isEmpty()) {
            return actionPathByExtension;
        }
        synchronized (this) {
            if (loadedAt.plus(CACHE_FOR).isAfter(Instant.now()) && !actionPathByExtension.isEmpty()) {
                return actionPathByExtension;
            }
            try {
                HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(internalUrl + "/hosting/discovery"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new EngineUnavailableException("The editing engine answered " + response.statusCode() + ".");
                }
                Map<String, String> parsed = parse(response.body());
                if (parsed.isEmpty()) {
                    throw new EngineUnavailableException("The editing engine offers no editable file types.");
                }
                actionPathByExtension = parsed;
                loadedAt = Instant.now();
                return parsed;
            } catch (EngineUnavailableException e) {
                throw e;
            } catch (java.io.IOException | RuntimeException e) {
                log.warn("Editing engine unavailable: {}", e.toString());
                throw new EngineUnavailableException("The editing engine cannot be reached.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineUnavailableException("The editing engine cannot be reached.");
            }
        }
    }

    /** Extension to the path+query of its "edit" action. Package-visible for the unit test. */
    static Map<String, String> parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The discovery document comes from another process: no DTDs, no external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList actions = doc.getElementsByTagName("action");
            Map<String, String> byExt = new HashMap<>();
            for (int i = 0; i < actions.getLength(); i++) {
                Element action = (Element) actions.item(i);
                if (!"edit".equals(action.getAttribute("name")) || action.getAttribute("urlsrc").isBlank()) {
                    continue;
                }
                // Some engines list an action with no extension at all (it is keyed by MIME type there): not a file
                // type anyone can pick, and not one to hand to the browser as if it were.
                if (action.getAttribute("ext").isBlank()) {
                    continue;
                }
                URI src = URI.create(action.getAttribute("urlsrc").trim());
                // Leave the path ending so that "WOPISrc=..." can simply be appended: "?" or "?x=1&".
                String query = src.getRawQuery();
                String path = src.getRawPath() + (query == null || query.isEmpty() ? "?"
                        : "?" + query + (query.endsWith("&") ? "" : "&"));
                byExt.putIfAbsent(action.getAttribute("ext").toLowerCase(Locale.ROOT), path);
            }
            return Map.copyOf(byExt);
        } catch (Exception e) {
            throw new EngineUnavailableException("The editing engine's capabilities could not be read.");
        }
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? null : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** The engine is off, down or does not edit this type - the caller falls back to check-out, download, re-upload. */
    public static class EngineUnavailableException extends RuntimeException {
        public EngineUnavailableException(String message) {
            super(message);
        }
    }
}
