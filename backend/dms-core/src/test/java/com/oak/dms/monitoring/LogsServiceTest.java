package com.oak.dms.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search box ends up inside a LogQL query, so these are the cases that
 * matter: nothing a caller types may become query syntax.
 */
class LogsServiceTest {

    @Test
    void plainQueryIsJustTheServiceSelector() {
        assertThat(LogsService.buildQuery("dms-core", null, null))
                .isEqualTo("{service=\"dms-core\"}");
    }

    @Test
    void levelIsAddedOnlyFromTheFixedSet() {
        assertThat(LogsService.buildQuery("dms-core", "error", null))
                .isEqualTo("{service=\"dms-core\"} | level=\"ERROR\"");
        // Anything else is dropped, not passed through.
        assertThat(LogsService.buildQuery("dms-core", "ERROR\" or 1=1 #", null))
                .isEqualTo("{service=\"dms-core\"}");
    }

    @Test
    void searchTextIsQuotedNotInterpreted() {
        assertThat(LogsService.buildQuery("dms-core", null, "timeout"))
                .isEqualTo("{service=\"dms-core\"} |= \"timeout\"");
    }

    @Test
    void aQuoteInTheSearchCannotCloseTheString() {
        // If the quote survived unescaped, everything after it would be
        // parsed as LogQL - here, a second selector reading another service.
        String query = LogsService.buildQuery("dms-core", null, "x\" or {service=\"keycloak\"} |= \"");
        assertThat(query).isEqualTo(
                "{service=\"dms-core\"} |= \"x\\\" or {service=\\\"keycloak\\\"} |= \\\"\"");
    }

    @Test
    void aTrailingBackslashCannotEscapeTheClosingQuote() {
        // Backslash must be escaped BEFORE the quote, or this would leave
        // an unterminated string.
        assertThat(LogsService.buildQuery("dms-core", null, "path\\"))
                .isEqualTo("{service=\"dms-core\"} |= \"path\\\\\"");
    }

    @Test
    void newlinesAreFlattenedAndLengthIsBounded() {
        assertThat(LogsService.buildQuery("dms-core", null, "a\nb\rc"))
                .isEqualTo("{service=\"dms-core\"} |= \"a b c\"");
        String long300 = "z".repeat(300);
        assertThat(LogsService.buildQuery("dms-core", null, long300))
                .isEqualTo("{service=\"dms-core\"} |= \"" + "z".repeat(200) + "\"");
    }

    @Test
    void blankSearchAddsNothing() {
        assertThat(LogsService.buildQuery("dms-core", null, "   "))
                .isEqualTo("{service=\"dms-core\"}");
    }
}
