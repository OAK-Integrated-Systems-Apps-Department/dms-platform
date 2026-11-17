package com.oak.dms.search.preset;

import com.oak.dms.search.api.SearchApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stored shape: strict parsing, what counts as a criterion, and the version rule (a v1 fixture is kept for good). */
class PresetCriteriaTest {

    @Test
    void whatCountsAsACriterion() {
        assertThat(PresetCriteria.parse(Map.of("q", "invoice")).hasCriteria()).isTrue();
        assertThat(PresetCriteria.parse(Map.of("documentTypeId", UUID.randomUUID().toString())).hasCriteria()).isTrue();
        assertThat(PresetCriteria.parse(Map.of("createdTo", "2026-01-01T00:00:00Z")).hasCriteria()).isTrue();
        assertThat(PresetCriteria.parse(Map.of("statuses", List.of("archived"))).hasCriteria()).isTrue();
        assertThat(PresetCriteria.parse(Map.of("meta", List.of(Map.of("fieldId", UUID.randomUUID().toString(), "op", "eq", "value", "x")))).hasCriteria()).isTrue();
        // not criteria
        assertThat(PresetCriteria.parse(Map.of()).hasCriteria()).isFalse();
        assertThat(PresetCriteria.parse(Map.of("q", "  ")).hasCriteria()).isFalse();
        assertThat(PresetCriteria.parse(Map.of("sort", "title", "ascending", true)).hasCriteria()).isFalse();
        assertThat(PresetCriteria.parse(Map.of("statuses", List.of("active"))).hasCriteria()).isFalse();
        assertThat(PresetCriteria.parse(Map.of("includeSubfolders", true)).hasCriteria()).isFalse();
    }

    @Test
    void unknownKeysAndBadValuesAreRefused() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("documentId", "x"), Map.of("size", 10), Map.of("cursor", "abc"), Map.of("sort", "magic"),
                Map.of("statuses", List.of("deleted")), Map.of("documentTypeId", "nope"), Map.of("createdFrom", "yesterday"),
                Map.of("meta", List.of(Map.of("fieldId", UUID.randomUUID().toString(), "op", "like", "value", "x"))),
                Map.of("meta", List.of(Map.of("fieldId", UUID.randomUUID().toString(), "op", "eq", "value", " "))),
                Map.of("meta", List.of(Map.of("fieldId", UUID.randomUUID().toString(), "op", "eq", "value", "x", "extra", 1))),
                Map.of("q", 5))) {
            assertThatThrownBy(() -> PresetCriteria.parse(bad)).as(bad.toString()).isInstanceOf(SearchApiException.class);
        }
    }

    @Test
    void theStoredFormCarriesOnlyWhatIsSet_andRoundTrips() {
        UUID type = UUID.randomUUID();
        PresetCriteria c = PresetCriteria.parse(Map.of("q", " invoice ", "documentTypeId", type.toString(),
                "statuses", List.of("active", "archived"), "sort", "modified", "ascending", true));
        Map<String, Object> stored = c.toMap();
        assertThat(stored.keySet()).containsExactly("schemaVersion", "q", "documentTypeId", "statuses", "sort", "ascending");
        assertThat(stored.get("schemaVersion")).isEqualTo(1);
        assertThat(stored.get("q")).isEqualTo("invoice");
        assertThat(PresetCriteria.parse(stored)).isEqualTo(c);
        assertThat(c.fieldNames()).containsExactly("q", "documentTypeId", "statuses", "sort", "ascending");
        // the values never appear in toString (Spring logs bodies at DEBUG)
        assertThat(c.toString()).doesNotContain("invoice").doesNotContain(type.toString());
    }

    @Test
    void subfoldersOnlyMeanSomethingWithAFolder() {
        UUID folder = UUID.randomUUID();
        assertThat(PresetCriteria.parse(Map.of("q", "x", "includeSubfolders", true)).toMap()).doesNotContainKey("includeSubfolders");
        assertThat(PresetCriteria.parse(Map.of("folderId", folder.toString(), "includeSubfolders", true)).toMap())
                .containsEntry("includeSubfolders", true);
    }

    @Test
    void theVersionRule() {
        Map<String, Object> v1 = Map.of("q", "x", "schemaVersion", 1);
        assertThat(PresetCriteriaMigrator.migrate(v1, 1)).containsEntry("schemaVersion", PresetCriteria.CURRENT_VERSION);
        assertThat(PresetCriteriaMigrator.isSupported(PresetCriteria.CURRENT_VERSION)).isTrue();
        assertThat(PresetCriteriaMigrator.isSupported(PresetCriteria.CURRENT_VERSION + 1)).isFalse();
        assertThat(PresetCriteriaMigrator.isSupported(0)).isFalse();
        assertThatThrownBy(() -> PresetCriteriaMigrator.migrate(v1, PresetCriteria.CURRENT_VERSION + 1))
                .isInstanceOf(SearchApiException.class)
                .satisfies(e -> assertThat(((SearchApiException) e).getCode()).isEqualTo("PRESET_VERSION_UNSUPPORTED"));
    }

    @Test
    void summariesNameTheKeywordsAndCountTheFilters() {
        assertThat(PresetCriteria.parse(Map.of("q", "invoice")).summary()).isEqualTo("\"invoice\"");
        assertThat(PresetCriteria.parse(Map.of("q", "invoice", "createdFrom", "2026-01-01T00:00:00Z")).summary())
                .isEqualTo("\"invoice\", 1 filter");
        assertThat(PresetCriteria.parse(Map.of("createdFrom", "2026-01-01T00:00:00Z", "ownerId", UUID.randomUUID().toString(), "sort", "title")).summary())
                .isEqualTo("2 filters");
        assertThat(PresetCriteria.parse(Map.of()).summary()).isEqualTo("No criteria");
    }
}
