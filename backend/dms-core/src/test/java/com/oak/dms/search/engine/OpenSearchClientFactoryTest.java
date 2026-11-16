package com.oak.dms.search.engine;

import com.oak.dms.search.config.SearchProperties;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production client configuration, proven against real TLS servers (JDK HTTPS servers with certificates made by
 * keytool for this test): a private CA is trusted, anything else is rejected, the host name is always checked, basic
 * authentication is sent only when configured, and the password never appears in a message.
 */
class OpenSearchClientFactoryTest {

    private static final String SECRET = "s3cr3t-pa55-never-logged";
    private static Path dir;
    private static HttpsServer good;       // CN=localhost, SAN localhost/127.0.0.1, signed by itself (its own CA)
    private static HttpsServer wrongHost;  // certificate valid for another.example only
    private static final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void servers() throws Exception {
        dir = Files.createTempDirectory("os-tls");
        keystore("good", "dns:localhost,ip:127.0.0.1");
        keystore("wrong", "dns:another.example");
        keystore("other", "dns:localhost,ip:127.0.0.1"); // a different certificate for the same name: a different CA
        good = start("good");
        wrongHost = start("wrong");
    }

    @AfterAll
    static void stop() {
        good.stop(0);
        wrongHost.stop(0);
    }

    // ---- tests ----------------------------------------------------------------------------------------------------

    @Test
    void aPrivateCaGivenAsAPemFileIsTrusted() {
        SearchProperties.OpenSearch os = props(good, "good.pem");
        assertThat(get(os)).contains("green");
    }

    @Test
    void aPrivateCaGivenAsATruststoreIsTrusted() throws Exception {
        run("keytool", "-importcert", "-noprompt", "-alias", "ca", "-file", "good.pem", "-keystore", "trust.p12", "-storetype",
                "PKCS12", "-storepass", "trustpw");
        SearchProperties.OpenSearch os = props(good, "");
        os.getTls().setTruststorePath(dir.resolve("trust.p12").toString());
        os.getTls().setTruststorePassword("trustpw");
        assertThat(get(os)).contains("green");
    }

    @Test
    void withoutThePrivateCaTheServerIsRejected_thePublicTrustDoesNotKnowIt() {
        assertThatThrownBy(() -> get(props(good, ""))).hasMessageNotContaining(SECRET);
    }

    @Test
    void anotherCaIsRejected() {
        assertThatThrownBy(() -> get(props(good, "other.pem"))).hasMessageNotContaining(SECRET);
    }

    @Test
    void theHostNameIsAlwaysVerified_evenWhenTheCertificateIsTrusted() {
        // wrong.pem is trusted, but the certificate is for another.example and we connect to localhost
        assertThatThrownBy(() -> get(props(wrongHost, "wrong.pem"))).hasMessageNotContaining(SECRET);
    }

    @Test
    void turningVerificationOffAloneIsRefused_itNeedsAllowInsecureToo() {
        SearchProperties.OpenSearch os = props(wrongHost, "wrong.pem");
        os.getTls().setVerifyHostname(false);
        assertThatThrownBy(() -> OpenSearchClientFactory.build(os, Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-insecure");
    }

    @Test
    void allowInsecureTrustsAnythingForDisposableNodes() {
        SearchProperties.OpenSearch os = props(wrongHost, "");
        os.getTls().setAllowInsecure(true);
        assertThat(get(os)).contains("green");
    }

    @Test
    void basicAuthIsSentOnlyWhenConfigured_andThePasswordIsNeverInAMessageOrToString() {
        authHeaders.clear();
        SearchProperties.OpenSearch anonymous = props(good, "good.pem");
        get(anonymous);
        assertThat(authHeaders).last().isEqualTo("none");

        SearchProperties.OpenSearch authed = props(good, "good.pem");
        authed.setUsername("dms-search");
        authed.setPassword(SECRET);
        get(authed);
        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(("dms-search:" + SECRET).getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders).last().isEqualTo(expected);
        assertThat(authed.toString()).doesNotContain(SECRET);
        assertThat(new SearchProperties().toString()).doesNotContain(SECRET);

        // a failure never echoes it
        SearchProperties.OpenSearch failing = props(good, "other.pem");
        failing.setUsername("dms-search");
        failing.setPassword(SECRET);
        assertThatThrownBy(() -> get(failing)).satisfies(e -> {
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(SECRET);
            }
        });
    }

    @Test
    void aMissingCaFileFailsAtStartupNamingNoSecret() {
        SearchProperties.OpenSearch os = props(good, "does-not-exist.pem");
        os.setPassword(SECRET);
        assertThatThrownBy(() -> OpenSearchClientFactory.build(os, Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(SECRET).hasMessageContaining("TLS");
    }

    @Test
    void theEngineTalksToATlsServerAndReportsDownRatherThanThrowingWhenTrustFails() {
        SearchProperties p = new SearchProperties();
        p.getOpensearch().setUrl(url(good));
        p.getOpensearch().getTls().setCaCertPath(dir.resolve("good.pem").toString());
        p.getOpensearch().setUsername("u");
        p.getOpensearch().setPassword(SECRET);
        assertThat(new OpenSearchRestEngine(p).health()).isEqualTo(SearchEngine.Health.GREEN);

        SearchProperties untrusted = new SearchProperties();
        untrusted.getOpensearch().setUrl(url(good));
        untrusted.getOpensearch().getTls().setCaCertPath(dir.resolve("other.pem").toString());
        assertThat(new OpenSearchRestEngine(untrusted).health()).isEqualTo(SearchEngine.Health.DOWN);
    }

    @Test
    void plainHttpIsUnchanged() {
        SearchProperties.OpenSearch os = new SearchProperties.OpenSearch();
        os.setUrl("http://localhost:1");
        os.getTls().setCaCertPath("ignored.pem");
        RestClient client = OpenSearchClientFactory.build(os, Duration.ofMillis(200), Duration.ofMillis(200));
        assertThat(client).isNotNull();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static SearchProperties.OpenSearch props(HttpsServer server, String caFile) {
        SearchProperties.OpenSearch os = new SearchProperties.OpenSearch();
        os.setUrl(url(server));
        if (!caFile.isEmpty()) {
            os.getTls().setCaCertPath(dir.resolve(caFile).toString());
        }
        return os;
    }

    private static String url(HttpsServer server) {
        return "https://localhost:" + server.getAddress().getPort();
    }

    private static String get(SearchProperties.OpenSearch os) {
        RestClient client = OpenSearchClientFactory.build(os, Duration.ofSeconds(2), Duration.ofSeconds(5));
        return client.get().uri("/_cluster/health").retrieve().body(String.class);
    }

    private static HttpsServer start(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve(name + ".p12"))) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth == null ? "none" : auth);
            byte[] body = "{\"status\":\"green\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void keystore(String name, String san) throws Exception {
        run("keytool", "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname",
                "CN=localhost", "-ext", "SAN=" + san, "-keystore", name + ".p12", "-storetype", "PKCS12", "-storepass", "changeit",
                "-keypass", "changeit");
        run("keytool", "-exportcert", "-rfc", "-alias", name, "-keystore", name + ".p12", "-storepass", "changeit", "-file", name + ".pem");
    }

    private static void run(String tool, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", tool).toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(tool + " " + String.join(" ", args) + "\n" + out).isZero();
    }
}
