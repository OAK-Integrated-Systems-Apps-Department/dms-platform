package com.oak.dms.importexport.service.impl;

import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFailuresCsvTest {

    @Test
    void cell_plainTextAndEmpty_areWrittenAsTheyAre() {
        assertThat(ImportFailuresCsv.cell(null)).isEmpty();
        assertThat(ImportFailuresCsv.cell("")).isEmpty();
        assertThat(ImportFailuresCsv.cell("invoice.pdf")).isEqualTo("invoice.pdf");
        assertThat(ImportFailuresCsv.cell("a=b+c-d@e")).isEqualTo("a=b+c-d@e");
    }

    @Test
    void cell_commaQuoteOrLineBreak_isQuotedWithQuotesDoubled() {
        assertThat(ImportFailuresCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ImportFailuresCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ImportFailuresCsv.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(ImportFailuresCsv.cell("line\rbreak")).isEqualTo("\"line\rbreak\"");
    }

    @Test
    void cell_startingLikeAFormula_isPrefixedSoASpreadsheetShowsItAsText() {
        assertThat(ImportFailuresCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(ImportFailuresCsv.cell("+1")).isEqualTo("'+1");
        assertThat(ImportFailuresCsv.cell("-1")).isEqualTo("'-1");
        assertThat(ImportFailuresCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ImportFailuresCsv.cell("\tx")).isEqualTo("'\tx");
        // Prefixed, then quoted for the CR.
        assertThat(ImportFailuresCsv.cell("\rx")).isEqualTo("\"'\rx\"");
        assertThat(ImportFailuresCsv.cell("=HYPERLINK(\"http://x\",\"y\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    }

    @Test
    void write_countsOneLinePerProblemAndOneForAFileWithNone() {
        ImportRowProblem a = new ImportRowProblem("file", "File", "SCAN_REJECTED", "Malware found", "error");
        ImportRowProblem b = new ImportRowProblem(null, null, "MANDATORY_FIELD_DEFERRED", "Left empty", "warning");

        ImportFailuresCsv.Written written = ImportFailuresCsv.write(List.of(
                new ImportRowFailure(1, "a.pdf", "a", List.of(a, b)),
                new ImportRowFailure(4, "b.pdf", "b", List.of())));

        assertThat(written.rowCount()).isEqualTo(3);
        assertThat(written.text()).isEqualTo("""
                Row,File,Field,Problem,Code
                1,a.pdf,File,Malware found,SCAN_REJECTED
                1,a.pdf,,Left empty,MANDATORY_FIELD_DEFERRED
                4,b.pdf,,,
                """);
    }

    @Test
    void write_nothingFailed_isTheHeaderOnly() {
        ImportFailuresCsv.Written written = ImportFailuresCsv.write(List.of());

        assertThat(written.rowCount()).isZero();
        assertThat(written.text()).isEqualTo("Row,File,Field,Problem,Code\n");
    }

    @Test
    void withBom_isUtf8BehindAByteOrderMark() {
        byte[] bytes = ImportFailuresCsv.withBom("ලංකාව,தமிழ்");

        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("﻿ලංකාව,தமிழ்");
    }
}
