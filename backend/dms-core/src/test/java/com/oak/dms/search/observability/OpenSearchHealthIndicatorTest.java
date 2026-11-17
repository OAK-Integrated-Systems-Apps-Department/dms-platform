package com.oak.dms.search.observability;

import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stopped or red search engine shows as DOWN on the dashboard; it never throws and never leaks a URL or credential. */
class OpenSearchHealthIndicatorTest {

    private Health healthWith(SearchEngine.Health engineHealth) {
        SearchEngine engine = mock(SearchEngine.class);
        when(engine.health()).thenReturn(engineHealth);
        return new OpenSearchHealthIndicator(engine, new SearchProperties()).health();
    }

    @Test
    void greenAndYellowAreUp_redAndUnreachableAreDown() {
        assertThat(healthWith(SearchEngine.Health.GREEN).getStatus()).isEqualTo(Status.UP);
        assertThat(healthWith(SearchEngine.Health.YELLOW).getStatus()).isEqualTo(Status.UP);
        assertThat(healthWith(SearchEngine.Health.RED).getStatus()).isEqualTo(Status.DOWN);
        Health down = healthWith(SearchEngine.Health.DOWN);
        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails().toString()).doesNotContain("http").doesNotContain("password");
    }

    @Test
    void searchSwitchedOffIsNotAFault() {
        SearchProperties off = new SearchProperties();
        off.setEnabled(false);
        Health h = new OpenSearchHealthIndicator(mock(SearchEngine.class), off).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("search", "disabled");
    }
}
