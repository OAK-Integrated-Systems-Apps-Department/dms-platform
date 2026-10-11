package com.oak.dms.importexport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V53 deprecates the two bulk export codes nothing checks, the same way V50
 * did for bulk import: only the shared (tenant_id IS NULL) rows, only those
 * two codes, never execute-export. Reads the migration's SQL; running it
 * against a database is the job of the Flyway ITs.
 */
class V53DeprecateBulkExportPermissionsMigrationTest {

    private static final String MIGRATION = "db/migration/V53__deprecate_unused_bulk_export_permissions.sql";

    @Test
    void deprecatesExactlyTheTwoUncheckedSharedCodes() throws IOException {
        String sql = statements(read());

        Matcher update = Pattern.compile(
                "UPDATE permissions SET is_deprecated = true WHERE permission_code IN \\((.*?)\\) AND tenant_id IS NULL;")
                .matcher(sql);
        assertThat(update.find()).as("the V50-style UPDATE").isTrue();
        assertThat(update.group(1).split(","))
                .extracting(String::trim)
                .containsExactlyInAnyOrder("'fie.bulk-export-documents.select-documents'",
                        "'fie.bulk-export-documents.configure-format'");
        assertThat(update.find()).as("a single statement").isFalse();
    }

    @Test
    void leavesExecuteExportAlone() throws IOException {
        assertThat(statements(read())).doesNotContain("execute-export");
    }

    private static String read() throws IOException {
        try (InputStream in = V53DeprecateBulkExportPermissionsMigrationTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The SQL without comments, whitespace collapsed to single spaces. */
    private static String statements(String sql) {
        return sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")")
                .trim();
    }
}
