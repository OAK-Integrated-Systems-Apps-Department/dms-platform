package com.oak.dms.reporting.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CellSanitizerTest {

    @Test
    void textThatStartsLikeAFormulaGetsALeadingQuote() {
        assertThat(CellSanitizer.sanitize("=HYPERLINK(\"http://evil\",\"x\")")).isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
        assertThat(CellSanitizer.sanitize("+1+1")).isEqualTo("'+1+1");
        assertThat(CellSanitizer.sanitize("-2+3")).isEqualTo("'-2+3");
        assertThat(CellSanitizer.sanitize("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CellSanitizer.sanitize("\tcmd")).isEqualTo("'\tcmd");
        assertThat(CellSanitizer.sanitize("\rcmd")).isEqualTo("'\rcmd");
    }

    @Test
    void ordinaryTextIsUntouched_evenWithTheDangerousCharacterLaterOn() {
        assertThat(CellSanitizer.sanitize("Annual report")).isEqualTo("Annual report");
        assertThat(CellSanitizer.sanitize("a=b")).isEqualTo("a=b");
        assertThat(CellSanitizer.sanitize("x - y")).isEqualTo("x - y");
        assertThat(CellSanitizer.sanitize(" =leading space")).isEqualTo(" =leading space");
        assertThat(CellSanitizer.sanitize("සිංහල")).isEqualTo("සිංහල");
    }

    @Test
    void emptyAndNullPassThrough() {
        assertThat(CellSanitizer.sanitize("")).isEmpty();
        assertThat(CellSanitizer.sanitize(null)).isNull();
    }

    @Test
    void sanitisingTwiceIsHarmless() {
        String once = CellSanitizer.sanitize("=1");
        assertThat(CellSanitizer.sanitize(once)).isEqualTo(once); // a leading quote is not a trigger character
    }
}
