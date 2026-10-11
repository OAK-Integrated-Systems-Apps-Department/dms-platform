package com.oak.dms.importexport.csv;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvCellsTest {

    @Test
    void cell_quotesAndGuardsAgainstFormulas() {
        assertThat(CsvCells.cell(null)).isEmpty();
        assertThat(CsvCells.cell("plain")).isEqualTo("plain");
        assertThat(CsvCells.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvCells.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvCells.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvCells.cell("-5")).isEqualTo("'-5");
        assertThat(CsvCells.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvCells.cell("=HYPERLINK(\"http://x\",\"y\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    }

    @Test
    void line_joinsEscapedCellsAndEndsWithNewline() {
        StringBuilder csv = new StringBuilder();
        CsvCells.line(csv, Arrays.asList("a", null, "b,c", "+x"));
        CsvCells.line(csv, List.of("z"));

        assertThat(csv.toString()).isEqualTo("a,,\"b,c\",'+x\nz\n");
    }

    @Test
    void withBom_isUtf8BehindAByteOrderMark() {
        byte[] bytes = CsvCells.withBom("ලංකාව");

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo("ලංකාව");
    }
}
