package com.oak.dms.search.engine;

import com.oak.dms.search.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;

/**
 * Builds the HTTP client for the search engine: plain HTTP for the local stack, and for production TLS with a private CA
 * (a PEM file or a truststore) with the host name always verified, plus HTTP basic authentication.
 *
 * <p>Secrets: the password is read from configuration that comes from the environment, is put only in the
 * {@code Authorization} header, and appears in no message, log line or health detail. A failure to read a CA file names
 * the file, never a secret. Turning host name verification off is refused unless {@code allow-insecure} is also set, which
 * trusts anything and logs a warning at startup: for disposable test nodes only.
 */
public final class OpenSearchClientFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchClientFactory.class);

    private OpenSearchClientFactory() {
    }

    public static RestClient build(SearchProperties.OpenSearch os, Duration connect, Duration read) {
        HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(connect);
        if (os.getUrl() != null && os.getUrl().toLowerCase().startsWith("https://")) {
            http.sslContext(sslContext(os.getTls()));
        } else if (hasTlsSettings(os.getTls())) {
            log.warn("TLS settings are configured for the search engine but its url is not https: they are ignored.");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(read);
        RestClient.Builder builder = RestClient.builder().baseUrl(os.getUrl()).requestFactory(factory);
        if (os.getUsername() != null && !os.getUsername().isBlank()) {
            String user = os.getUsername();
            String password = os.getPassword() == null ? "" : os.getPassword();
            builder.defaultHeaders(h -> h.setBasicAuth(user, password));
        }
        return builder.build();
    }

    static SSLContext sslContext(SearchProperties.Tls tls) {
        try {
            if (!tls.isVerifyHostname() && !tls.isAllowInsecure()) {
                throw new IllegalStateException("Search engine TLS: host name verification can only be turned off together with "
                        + "dms.search.opensearch.tls.allow-insecure (for disposable test nodes). Refusing to start insecurely.");
            }
            SSLContext context = SSLContext.getInstance("TLS");
            if (tls.isAllowInsecure() || !tls.isVerifyHostname()) {
                log.warn("Search engine TLS is configured to trust ANY certificate and host name. Never use this in production.");
                context.init(null, new TrustManager[]{new TrustAll()}, null);
                return context;
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (!blank(tls.getCaCertPath())) {
                factory.init(keyStoreOf(Path.of(tls.getCaCertPath())));
            } else if (!blank(tls.getTruststorePath())) {
                factory.init(truststore(Path.of(tls.getTruststorePath()), tls.getTruststorePassword()));
            } else {
                factory.init((KeyStore) null); // the JVM's default trust: certificates from a public CA
            }
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Search engine TLS could not be set up: " + e.getClass().getSimpleName()
                    + " (check the CA certificate or truststore path)", e);
        }
    }

    private static KeyStore keyStoreOf(Path pem) throws GeneralSecurityException, IOException {
        Collection<? extends Certificate> certificates;
        try (InputStream in = Files.newInputStream(pem)) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
        if (certificates.isEmpty()) {
            throw new IOException("no certificate found in " + pem.getFileName());
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int i = 0;
        for (Certificate certificate : certificates) {
            store.setCertificateEntry("ca-" + i++, certificate);
        }
        return store;
    }

    private static KeyStore truststore(Path path, String password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password == null ? null : password.toCharArray());
        }
        return store;
    }

    private static boolean hasTlsSettings(SearchProperties.Tls tls) {
        return !blank(tls.getCaCertPath()) || !blank(tls.getTruststorePath());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Accepts everything, including any host name (being an extended manager it also bypasses the endpoint check). */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
