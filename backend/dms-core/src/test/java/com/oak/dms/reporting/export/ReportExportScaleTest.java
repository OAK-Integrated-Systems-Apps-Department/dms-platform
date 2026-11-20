package com.oak.dms.reporting.export;

import com.oak.dms.reporting.run.ReportGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exports at real volume. The big ones run in a child JVM with a 192 MB heap, so memory that grew with the report would be an
 * OutOfMemoryError, not a measurement. A second test changes the documents in the middle of a run and shows the file still describes
 * the moment the run started.
 */
class ReportExportScaleTest {

    private static final int DOCUMENTS = 100_000;
    private static final String HEAP = "-Xmx192m";

    private static ScaleFixture big;
    private static Path work;

    @BeforeAll
    static void load() throws Exception {
        big = ScaleFixture.create(DOCUMENTS);
        work = Files.createTempDirectory("crg-scale-");
    }

    @AfterAll
    static void clean() throws Exception {
        if (big != null) {
            big.drop();
        }
        if (work != null) {
            try (var files = Files.list(work)) {
                files.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(work);
        }
    }

    private String exportInChildJvm(String format, long limit, Path out) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, HEAP, "-cp", System.getProperty("java.class.path"), ScaleExportMain.class.getName(),
                big.url, ScaleFixture.DB_USERNAME, ScaleFixture.DB_PASSWORD, big.tenant.toString(), format, Long.toString(limit), out.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.MINUTES)).as("child finished").isTrue();
        assertThat(p.exitValue()).as("child exit (heap " + HEAP + ")\n" + tail(output)).isZero();
        return output;
    }

    private static String tail(String s) {
        return s.length() > 3000 ? s.substring(s.length() - 3000) : s;
    }

    @Test
    void aHundredThousandRowsAsCsvInASmallHeap() throws Exception {
        Path out = work.resolve("big.csv");
        String result = exportInChildJvm("csv", DOCUMENTS, out);
        assertThat(result).contains("OK total=" + DOCUMENTS + " written=" + DOCUMENTS);
        try (var lines = Files.lines(out, StandardCharsets.UTF_8)) {
            assertThat(lines.filter(l -> l.contains("Document 0")).count()).isEqualTo(DOCUMENTS);
        }
    }

    @Test
    void aHundredThousandRowsAsExcelInASmallHeap() throws Exception {
        Path out = work.resolve("big.xlsx");
        String result = exportInChildJvm("xlsx", DOCUMENTS, out);
        assertThat(result).contains("OK total=" + DOCUMENTS + " written=" + DOCUMENTS);
        assertThat(Files.size(out)).isGreaterThan(1_000_000L);
    }

    @Test
    void aPdfAtItsOwnLowerLimitInASmallHeap() throws Exception {
        Path out = work.resolve("big.pdf");
        String result = exportInChildJvm("pdf", 5_000, out);
        assertThat(result).contains("total=" + DOCUMENTS + " written=5000");
        byte[] head = Files.readAllBytes(out);
        assertThat(new String(head, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void documentsChangingMidRunDoNotChangeWhatTheFileSays() throws Exception {
        ScaleFixture f = ScaleFixture.create(12_000);
        try {
            Path out = work.resolve("consistent.csv");
            AtomicBoolean changed = new AtomicBoolean();
            ReportGenerator.Outcome outcome = ScaleExportMain.export(f.url, ScaleFixture.DB_USERNAME, ScaleFixture.DB_PASSWORD, f.tenant,
                    "csv", 100_000, out, rows -> {
                        if (changed.compareAndSet(false, true)) {
                            try (Connection other = DriverManager.getConnection(f.url, ScaleFixture.DB_USERNAME, ScaleFixture.DB_PASSWORD);
                                 Statement s = other.createStatement()) {
                                s.execute("update documents set title = 'CHANGED ' || title where tenant_id = '" + f.tenant + "'");
                                s.execute("insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, current_version_id, owner_user_id, created_by, "
                                        + "access_mode, lifecycle_state) select gen_random_uuid(), tenant_id, document_type_id, 'LATE ARRIVAL', latest_version_id, current_version_id, "
                                        + "owner_user_id, created_by, 'inherit', 'active' from documents where tenant_id = '" + f.tenant + "' limit 100");
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    }, () -> false);
            assertThat(changed).as("the change happened while the run was streaming").isTrue();
            assertThat(outcome.total()).isEqualTo(12_000);
            assertThat(outcome.written()).isEqualTo(12_000);

            List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
            long original = lines.stream().filter(l -> l.contains("Document 0")).count();
            assertThat(original).as("every row is as it was when the run started").isEqualTo(12_000);
            assertThat(lines.stream().filter(l -> l.contains("CHANGED")).count()).isZero();
            assertThat(lines.stream().filter(l -> l.contains("LATE ARRIVAL")).count()).isZero();
            // the database really did change underneath
            try (Connection c = DriverManager.getConnection(f.url, ScaleFixture.DB_USERNAME, ScaleFixture.DB_PASSWORD);
                 Statement s = c.createStatement();
                 var rs = s.executeQuery("select count(*) from documents where title like 'CHANGED%'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(12_000);
            }
        } finally {
            f.drop();
            new File(work.resolve("consistent.csv").toString()).delete();
        }
    }
}
