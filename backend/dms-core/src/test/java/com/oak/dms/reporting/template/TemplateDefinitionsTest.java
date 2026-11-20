package com.oak.dms.reporting.template;

import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Filter;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a stored template becomes when read, and what is left of it when an administrator has switched fields off. */
class TemplateDefinitionsTest {

    private static FieldDescriptor field(String key, boolean selectable, boolean filterable, boolean sortable, boolean groupable) {
        return new FieldDescriptor(key, key, FieldType.TEXT, FieldDescriptor.ValueKind.TEXT, false, null, null, Set.of(), selectable,
                filterable, sortable, groupable, false, null, false, null, null, FieldDescriptor.Special.NONE);
    }

    private static final ReportDefinition DEFINITION = new ReportDefinition("documents",
            List.of(new Filter("title", "contains", List.of("x")), new Filter("status", "equals", List.of("draft"))),
            List.of("title", "status", "owner"), List.of(new Sort("title", "asc"), new Sort("owner", "desc")), "status", null, null);

    @Test
    void aDefinitionSurvivesTheRoundTripThroughItsStoredJson() {
        Map<String, Object> stored = TemplateDefinitions.toStored(DEFINITION);

        assertThat(TemplateDefinitions.load(stored, TemplateDefinitions.CURRENT_VERSION)).isEqualTo(DEFINITION);
    }

    @Test
    void aRowSavedByANewerReleaseIsRefusedNotGuessedAt() {
        Map<String, Object> stored = TemplateDefinitions.toStored(DEFINITION);

        assertThatThrownBy(() -> TemplateDefinitions.load(stored, TemplateDefinitions.CURRENT_VERSION + 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("newer");
    }

    @Test
    void nothingIsUnavailableWhenEveryFieldIsStillEnabled() {
        EffectiveCatalogue all = new EffectiveCatalogue(List.of(field("title", true, true, true, true), field("status", true, true, true, true),
                field("owner", true, true, true, true)));

        assertThat(TemplateDefinitions.unavailable(DEFINITION, all)).isEmpty();
        assertThat(TemplateDefinitions.withoutUnavailable(DEFINITION, all)).isSameAs(DEFINITION);
    }

    @Test
    void aFieldTheAdministratorSwitchedOffIsReportedOnceWhereverItIsUsed() {
        // "owner" is gone altogether: it is a column and a sort key.
        EffectiveCatalogue catalogue = new EffectiveCatalogue(List.of(field("title", true, true, true, true), field("status", true, true, true, true)));

        assertThat(TemplateDefinitions.unavailable(DEFINITION, catalogue)).containsExactly("owner");
    }

    @Test
    void aFieldThatIsNoLongerFilterableIsLeftOutEverywhere_notJustFromTheFilters() {
        EffectiveCatalogue catalogue = new EffectiveCatalogue(List.of(field("title", true, true, true, true),
                field("status", true, false, true, true), field("owner", true, true, true, true)));

        ReportDefinition runnable = TemplateDefinitions.withoutUnavailable(DEFINITION, catalogue);

        assertThat(TemplateDefinitions.unavailable(DEFINITION, catalogue)).containsExactly("status");
        // The whole key goes: a field that cannot be filtered on is left out everywhere, so the run is predictable and the warning honest.
        assertThat(runnable.filtersOrEmpty()).extracting(Filter::field).containsExactly("title");
        assertThat(runnable.columnsOrEmpty()).containsExactly("title", "owner");
        assertThat(runnable.groupBy()).isNull();
    }

    @Test
    void aGroupingFieldThatCannotBeGroupedAnyMoreIsRemovedAndTheOtherFieldsAreKept() {
        EffectiveCatalogue catalogue = new EffectiveCatalogue(List.of(field("title", true, true, true, true),
                field("status", true, true, true, false), field("owner", true, true, true, true)));

        ReportDefinition runnable = TemplateDefinitions.withoutUnavailable(DEFINITION, catalogue);

        assertThat(runnable.groupBy()).isNull();
        assertThat(runnable.sortOrEmpty()).extracting(Sort::field).containsExactly("title", "owner");
    }
}
