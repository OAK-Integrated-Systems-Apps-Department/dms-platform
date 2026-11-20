package com.oak.dms.watermark;

import com.oak.dms.editing.WopiController;
import com.oak.dms.editing.WopiSaveSpool;
import com.oak.dms.editing.WopiTokenCodec;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * DOC-12 with the REAL ingestion gate (the existing WopiEditingIT stubs it): the actual {@code WopiController.putFile} on
 * PostgreSQL, real encrypted storage and real ClamAV. A save is authorised, spooled to a private file, checked for an
 * embedded signature from that file, handed to the gate from that file, stored byte for byte as a new version, audited -
 * and the spool is empty again afterwards, whatever the outcome.
 */
@SpringBootTest(properties = {
        "dms.editing.collabora-url=http://localhost:9980",
        "dms.editing.token-secret=an-editing-it-secret-that-is-only-for-tests-0123456789",
        "dms.editing.spool-dir=${java.io.tmpdir}/oak-dms-wopi-spool-it"
})
class WopiSaveSpoolIT extends WatermarkFixtures {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final long MIB = 1024 * 1024;

    @Autowired private WopiController controller;
    @Autowired private WopiTokenCodec tokens;
    @Autowired private WopiSaveSpool spool;
    @MockitoSpyBean private com.oak.dms.scanning.service.IngestionGate gate;

    private Fixture f;

    @BeforeEach
    void editorWorld() {
        // a previous test's leftovers must not hide a leak in this one
        if (Files.isDirectory(spool.directory())) {
            try (Stream<Path> list = Files.list(spool.directory())) {
                list.forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void editable(Fixture fixture) {
        jdbc.update("insert into tenant_platform_settings (tenant_id, online_editing_allowed) values (?, true) "
                + "on conflict (tenant_id) do update set online_editing_allowed = true", fixture.tenantId());
        jdbc.update("insert into tenant_editing_settings (tenant_id, enabled, mode) values (?, true, 'exclusive') "
                + "on conflict (tenant_id) do update set enabled = true, mode = 'exclusive'", fixture.tenantId());
        jdbc.update("update documents set checked_out_by = ?, checked_out_at = now() - interval '1 minute', "
                + "checkout_expires_at = now() + interval '2 hours' where document_id = ?", fixture.userId(),
                fixture.documentId());
    }

    private String token(Fixture fixture) {
        return tokens.mint(new WopiTokenCodec.Grant(fixture.tenantId(), fixture.userId(), fixture.documentId(), "Tester",
                Instant.now().plusSeconds(3600)));
    }

    private static byte[] zip(String... entryNames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (String name : entryNames) {
                z.putNextEntry(new ZipEntry(name));
                z.write(("<x name=\"" + name + "\"/>").getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] docx(String... extra) throws IOException {
        String[] names = new String[2 + extra.length];
        names[0] = "[Content_Types].xml";
        names[1] = "word/document.xml";
        System.arraycopy(extra, 0, names, 2, extra.length);
        return zip(names);
    }

    private static byte[] odt(String... extra) throws IOException {
        String[] names = new String[2 + extra.length];
        names[0] = "mimetype";
        names[1] = "content.xml";
        System.arraycopy(extra, 0, names, 2, extra.length);
        return zip(names);
    }

    private Object save(Fixture fixture, byte[] body) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        try {
            return controller.putFile(fixture.documentId(), token(fixture), null, null, request);
        } finally {
            TenantContext.clear();
        }
    }

    private long versions(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from document_versions where document_id = ?", Long.class,
                fixture.documentId());
    }

    private long scans(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from scan_results where tenant_id = ?", Long.class,
                fixture.tenantId());
    }

    private long spoolFiles() throws IOException {
        if (!Files.isDirectory(spool.directory())) {
            return 0;
        }
        try (Stream<Path> list = Files.list(spool.directory())) {
            return list.count();
        }
    }

    private static int status(Throwable t) {
        return ((ResponseStatusException) t).getStatusCode().value();
    }

    /** A body generated on the fly that counts what is read from it. */
    private static final class Generated extends HttpServletRequestWrapper {
        final AtomicLong read = new AtomicLong();
        private final long size;
        private final boolean declared;

        Generated(long size, boolean declared) {
            super(new MockHttpServletRequest());
            this.size = size;
            this.declared = declared;
        }

        @Override
        public long getContentLengthLong() {
            return declared ? size : -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private long left = size;

                @Override
                public boolean isFinished() {
                    return left == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }

                @Override
                public int read() {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : one[0];
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (left == 0) {
                        return -1;
                    }
                    int n = (int) Math.min(len, left);
                    java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                    left -= n;
                    read.addAndGet(n);
                    return n;
                }
            };
        }
    }

    // ------------------------------------------------------------------------------------------ the real save

    @Test
    void aSave_isSpooled_checked_storedByteForByte_asANewVersion_audited_andTheSpoolIsEmptied() throws Exception {
        f = fixtureWithContent("memo.docx", DOCX, docx());
        editable(f);
        byte[] edited = docx("word/styles.xml", "word/numbering.xml");

        save(f, edited);

        assertThat(versions(f)).isEqualTo(2);
        var row = jdbc.queryForMap("select version_id, size_bytes, file_hash, storage_location_id, storage_object_key, "
                + "scan_status, created_reason from document_versions where document_id = ? and version_number = 2",
                f.documentId());
        assertThat(((Number) row.get("size_bytes")).longValue()).as("the counted size").isEqualTo(edited.length);
        assertThat(row.get("file_hash")).isEqualTo(sha256(edited));
        assertThat(row.get("scan_status")).isEqualTo("clean");
        assertThat(row.get("created_reason")).isEqualTo("replacement_upload");
        StorageLocation location = storageLocationRepository.findById((UUID) row.get("storage_location_id")).orElseThrow();
        try (InputStream stored = storage.resolve(location).download(location, (String) row.get("storage_object_key"))
                .content()) {
            assertThat(stored.readAllBytes()).as("what the engine sent is what is stored").isEqualTo(edited);
        }
        assertThat(jdbc.queryForObject("select current_version_id from documents where document_id = ?", UUID.class,
                f.documentId())).isEqualTo(row.get("version_id"));
        assertThat(auditCount("DOCUMENT_EDIT_SESSION_SAVED", f.documentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select checked_out_by from documents where document_id = ?", UUID.class,
                f.documentId())).as("saving is not checking in").isEqualTo(f.userId());
        assertThat(spoolFiles()).as("the spool file is gone").isZero();
    }

    @Test
    void aSaveThatCarriesASignature_isRefused_andNothingReachesTheGate_forOoxmlOdfAndPdf() throws Exception {
        Fixture word = fixtureWithContent("memo.docx", DOCX, docx());
        editable(word);
        byte[] signedPdf = "%PDF-1.4\n1 0 obj << /Type /Sig /ByteRange [0 10 20 30] /Contents <00> >> endobj\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        Fixture pdf = fixtureWithContent("report.pdf", "application/pdf", minimalPdf());
        editable(pdf);
        Fixture odf = fixtureWithContent("memo.odt", "application/vnd.oasis.opendocument.text", odt());
        editable(odf);

        assertThatThrownBy(() -> save(word, docx("_xmlsignatures/sig1.xml"))).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
        assertThatThrownBy(() -> save(pdf, signedPdf)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
        assertThatThrownBy(() -> save(odf, odt("META-INF/documentsignatures.xml"))).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));

        for (Fixture each : new Fixture[]{word, pdf, odf}) {
            assertThat(versions(each)).as("no version was added").isEqualTo(1);
            assertThat(scans(each)).as("the gate was never reached: only the seeded scan row exists").isEqualTo(1);
            assertThat(auditCount("DOCUMENT_EDIT_SESSION_SAVED", each.documentId())).isZero();
        }
        assertThat(spoolFiles()).isZero();
    }

    @Test
    void anUnsignedOdfAndAnOrdinaryPdf_areStillSaved() throws Exception {
        Fixture odf = fixtureWithContent("memo.odt", "application/vnd.oasis.opendocument.text", odt());
        editable(odf);
        save(odf, odt("styles.xml"));
        assertThat(versions(odf)).isEqualTo(2);

        Fixture pdf = fixtureWithContent("report.pdf", "application/pdf", minimalPdf());
        editable(pdf);
        byte[] edited = (new String(minimalPdf(), StandardCharsets.ISO_8859_1) + "% edited in the online editor\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        save(pdf, edited);
        assertThat(versions(pdf)).isEqualTo(2);
        assertThat(spoolFiles()).isZero();
    }

    @Test
    void aSaveTheGateRefuses_isNotReportedSaved_noVersion_andTheSpoolIsEmptied() throws Exception {
        // A password-protected PDF: the real gate marks it unscannable and the save is refused. (The EICAR test string is not
        // used here: this development host's own antivirus refuses to let it be written to any file.)
        f = fixtureWithContent("report.pdf", "application/pdf", minimalPdf());
        editable(f);
        byte[] encrypted = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Encrypt 2 0 R /Root 1 0 R >>\n%%EOF\n"
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> save(f, encrypted)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(422));

        assertThat(versions(f)).isEqualTo(1);
        assertThat(auditCount("DOCUMENT_EDIT_SESSION_SAVED", f.documentId())).isZero();
        assertThat(spoolFiles()).isZero();
    }

    @Test
    void aGateThatFails_leavesNoSpoolFile_andTheErrorReachesTheCaller() throws Exception {
        f = fixtureWithContent("memo.docx", DOCX, docx());
        editable(f);
        Mockito.doThrow(new IllegalStateException("gate down")).when(gate).submit(any());

        assertThatThrownBy(() -> save(f, docx("word/styles.xml"))).isInstanceOf(IllegalStateException.class)
                .hasMessage("gate down");

        assertThat(versions(f)).isEqualTo(1);
        assertThat(spoolFiles()).isZero();
    }

    // ------------------------------------------------------------------------------------------ nothing is read before authorisation

    @Test
    void anInvalidExpiredOrWrongDocumentToken_isRefusedBeforeTheBodyIsReadOrAnythingIsSpooled() throws Exception {
        f = fixtureWithContent("memo.docx", DOCX, docx());
        editable(f);
        Generated body = new Generated(10 * MIB, true);

        assertThatThrownBy(() -> controller.putFile(f.documentId(), "garbage", null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(401));
        String expired = tokens.mint(new WopiTokenCodec.Grant(f.tenantId(), f.userId(), f.documentId(), "T",
                Instant.now().minusSeconds(5)));
        assertThatThrownBy(() -> controller.putFile(f.documentId(), expired, null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(401));
        String other = tokens.mint(new WopiTokenCodec.Grant(f.tenantId(), f.userId(), UUID.randomUUID(), "T",
                Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> controller.putFile(f.documentId(), other, null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(401));

        assertThat(body.read.get()).isZero();
        assertThat(spoolFiles()).isZero();
        TenantContext.clear();
    }

    @Test
    void aLockLostMidSession_aDeletedDocument_andAnAutosave_neverTouchTheSpool() throws Exception {
        f = fixtureWithContent("memo.docx", DOCX, docx());
        editable(f);
        Generated body = new Generated(10 * MIB, true);
        String token = token(f);

        jdbc.update("update documents set checked_out_by = null, checked_out_at = null, checkout_expires_at = null "
                + "where document_id = ?", f.documentId());
        assertThatThrownBy(() -> controller.putFile(f.documentId(), token, null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(409));
        editable(f);

        controller.putFile(f.documentId(), token, "true", null, body);              // an autosave: acknowledged, body unread
        assertThat(body.read.get()).isZero();
        assertThat(versions(f)).isEqualTo(1);

        jdbc.update("update documents set is_deleted = true where document_id = ?", f.documentId());
        assertThatThrownBy(() -> controller.putFile(f.documentId(), token, null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(404));

        assertThat(body.read.get()).isZero();
        assertThat(spoolFiles()).isZero();
        TenantContext.clear();
    }

    // ------------------------------------------------------------------------------------------ the limit

    @Test
    void aDeclaredOversizeSave_isRefused413_withoutReadingOrCreatingAnything() throws Exception {
        f = fixtureWithContent("memo.docx", DOCX, docx());
        editable(f);
        Generated body = new Generated(201 * MIB, true);

        assertThatThrownBy(() -> controller.putFile(f.documentId(), token(f), null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(413));

        assertThat(body.read.get()).isZero();
        assertThat(spoolFiles()).isZero();
        TenantContext.clear();
    }

    @Test
    void aChunkedOversizeSave_isRefused413_afterAboutTheCap_andTheSpoolFileIsRemoved() throws Exception {
        f = fixtureWithContent("memo.docx", DOCX, docx());
        editable(f);
        Generated body = new Generated(260 * MIB, false);

        assertThatThrownBy(() -> controller.putFile(f.documentId(), token(f), null, null, body))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(status(e)).isEqualTo(413));

        assertThat(body.read.get()).as("reading stopped at the first buffer past 200 MiB")
                .isBetween(200 * MIB + 1, 200 * MIB + 64 * 1024);
        assertThat(versions(f)).isEqualTo(1);
        assertThat(spoolFiles()).isZero();
        TenantContext.clear();
    }
}
