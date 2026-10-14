package com.oak.dms.common.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikePatternsTest {

    @Test
    void wildcardsAndTheEscapeCharacterBecomeLiteral() {
        assertThat(LikePatterns.escape("50%")).isEqualTo("50\\%");
        assertThat(LikePatterns.escape("a_b")).isEqualTo("a\\_b");
        assertThat(LikePatterns.escape("c:\\temp")).isEqualTo("c:\\\\temp");
    }

    @Test
    void theBackslashIsEscapedFirst_soTheOthersAreNotEscapedTwice() {
        // "%" -> "\%", and that backslash must not itself be doubled.
        assertThat(LikePatterns.escape("%_")).isEqualTo("\\%\\_");
    }

    @Test
    void containsIsLowerCasedAndWrapped() {
        assertThat(LikePatterns.contains("Invoice 100%")).isEqualTo("%invoice 100\\%%");
    }

    @Test
    void textWithoutSpecialCharactersIsUnchanged() {
        assertThat(LikePatterns.escape("annual report 2026")).isEqualTo("annual report 2026");
    }
}
