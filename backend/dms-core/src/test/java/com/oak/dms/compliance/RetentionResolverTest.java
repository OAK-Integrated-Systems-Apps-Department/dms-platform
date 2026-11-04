package com.oak.dms.compliance;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which rule applies and what dates it gives (CRM-FR-006, 008, 011 and the conflicting-rules alternate flow). */
class RetentionResolverTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2020, 1, 31, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID DEPT = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();
    private static final Map<UUID, UUID> FOLDERS = java.util.Collections.unmodifiableMap(new java.util.HashMap<>() {{
        put(PARENT, null);
        put(CHILD, PARENT);
    }});

    private static RetentionPolicy rule(String name, String scope, UUID scopeId, int active, String activeUnit, int archive,
                                       String archiveUnit) {
        return RetentionPolicy.builder().policyId(UUID.randomUUID()).tenantId(UUID.randomUUID()).name(name).scopeType(scope)
                .scopeId(scopeId).activeAmount(active).activeUnit(activeUnit).archiveAmount(archive).archiveUnit(archiveUnit).build();
    }

    private static RetentionResolver.Facts doc(UUID folder) {
        return new RetentionResolver.Facts(TYPE, DEPT, folder, T0, T0, null, null);
    }

    private static RetentionPolicy org(int years, int archiveYears) {
        return rule("Org", RetentionPolicy.SCOPE_ORGANISATION, null, years, "years", archiveYears, "years");
    }

    @Test
    void theDatesAreActivePlusArchive_inWhateverUnitsTheRuleUses() {
        var r = RetentionResolver.resolve(doc(null), List.of(rule("Invoices", "organisation", null, 1, "years", 6, "years")),
                RetentionResolver.LONGEST, FOLDERS).orElseThrow();
        assertThat(r.archiveDue()).isEqualTo(OffsetDateTime.of(2021, 1, 31, 9, 0, 0, 0, ZoneOffset.UTC)); // one year active
        assertThat(r.reviewDue()).isEqualTo(OffsetDateTime.of(2027, 1, 31, 9, 0, 0, 0, ZoneOffset.UTC));  // plus six archived

        var months = RetentionResolver.resolve(doc(null), List.of(rule("Short", "organisation", null, 1, "months", 90, "days")),
                RetentionResolver.LONGEST, FOLDERS).orElseThrow();
        assertThat(months.archiveDue()).isEqualTo(OffsetDateTime.of(2020, 2, 29, 9, 0, 0, 0, ZoneOffset.UTC)); // calendar month end
        assertThat(months.reviewDue()).isEqualTo(months.archiveDue().plusDays(90));
    }

    @Test
    void noArchivePeriod_meansItIsReviewedAsSoonAsItArchives() {
        var r = RetentionResolver.resolve(doc(null), List.of(rule("X", "organisation", null, 2, "years", 0, "years")),
                RetentionResolver.LONGEST, FOLDERS).orElseThrow();
        assertThat(r.reviewDue()).isEqualTo(r.archiveDue());
    }

    @Test
    void aRuleOnlyCoversWhatItsScopeNames() {
        var forOtherType = rule("Other type", "document_type", UUID.randomUUID(), 1, "years", 1, "years");
        var forOtherDept = rule("Other dept", "department", UUID.randomUUID(), 1, "years", 1, "years");
        var forOtherFolder = rule("Other folder", "folder", UUID.randomUUID(), 1, "years", 1, "years");
        assertThat(RetentionResolver.resolve(doc(CHILD), List.of(forOtherType, forOtherDept, forOtherFolder),
                RetentionResolver.LONGEST, FOLDERS)).isEmpty();

        var mine = rule("My type", "document_type", TYPE, 1, "years", 1, "years");
        assertThat(RetentionResolver.resolve(doc(null), List.of(forOtherType, mine), RetentionResolver.LONGEST, FOLDERS)
                .orElseThrow().policy()).isSameAs(mine);
    }

    @Test
    void aDisabledRule_isIgnored() {
        var off = rule("Off", "organisation", null, 1, "years", 1, "years");
        off.setEnabled(false);
        assertThat(RetentionResolver.resolve(doc(null), List.of(off), RetentionResolver.LONGEST, FOLDERS)).isEmpty();
    }

    @Test
    void aFolderRuleCoversSubfolders_unlessItIsSwitchedOff() {
        var rule = rule("Parent folder", "folder", PARENT, 1, "years", 1, "years");
        assertThat(RetentionResolver.resolve(doc(CHILD), List.of(rule), RetentionResolver.LONGEST, FOLDERS)).isPresent();

        rule.setIncludeSubfolders(false);
        assertThat(RetentionResolver.resolve(doc(CHILD), List.of(rule), RetentionResolver.LONGEST, FOLDERS)).isEmpty();
        assertThat(RetentionResolver.resolve(doc(PARENT), List.of(rule), RetentionResolver.LONGEST, FOLDERS)).isPresent();
    }

    @Test
    void whenRulesOverlap_theLongestRetentionWins_byDefault() {
        var type = rule("Type 3+3", "document_type", TYPE, 3, "years", 3, "years");   // 6 years total
        var dept = rule("Dept 1+10", "department", DEPT, 1, "years", 10, "years");     // 11 years total
        var org = org(2, 2);                                                            // 4 years total
        assertThat(RetentionResolver.resolve(doc(null), List.of(type, dept, org), RetentionResolver.LONGEST, FOLDERS)
                .orElseThrow().policy()).isSameAs(dept);
    }

    @Test
    void orTheMostSpecificRuleWins_whenTheOrganisationChoosesThat() {
        var type = rule("Type", "document_type", TYPE, 3, "years", 3, "years");
        var dept = rule("Dept", "department", DEPT, 1, "years", 10, "years");
        var org = org(20, 20);
        var parentFolder = rule("Parent", "folder", PARENT, 5, "years", 5, "years");
        var childFolder = rule("Child", "folder", CHILD, 4, "years", 4, "years");

        // Without a folder rule: document type beats department beats the default - however long the others are.
        assertThat(RetentionResolver.resolve(doc(null), List.of(dept, org, type), RetentionResolver.MOST_SPECIFIC, FOLDERS)
                .orElseThrow().policy()).isSameAs(type);
        assertThat(RetentionResolver.resolve(doc(null), List.of(dept, org), RetentionResolver.MOST_SPECIFIC, FOLDERS)
                .orElseThrow().policy()).isSameAs(dept);
        // A folder beats all of those, and the nearer folder beats the one above it.
        assertThat(RetentionResolver.resolve(doc(CHILD), List.of(dept, org, type, parentFolder), RetentionResolver.MOST_SPECIFIC,
                FOLDERS).orElseThrow().policy()).isSameAs(parentFolder);
        assertThat(RetentionResolver.resolve(doc(CHILD), List.of(parentFolder, childFolder, type), RetentionResolver.MOST_SPECIFIC,
                FOLDERS).orElseThrow().policy()).isSameAs(childFolder);
    }

    @Test
    void aTie_isBrokenTheSameWayEveryTime() {
        var type = rule("Type", "document_type", TYPE, 2, "years", 2, "years");
        var org = org(2, 2); // same total
        var a = RetentionResolver.resolve(doc(null), List.of(type, org), RetentionResolver.LONGEST, FOLDERS).orElseThrow();
        var b = RetentionResolver.resolve(doc(null), List.of(org, type), RetentionResolver.LONGEST, FOLDERS).orElseThrow();
        assertThat(a.policy()).isSameAs(type).isSameAs(b.policy()); // the more specific one, whatever the order
    }

    @Test
    void theClockCanStartAtTheLastChange_insteadOfCreation() {
        var changed = T0.plusYears(3);
        var facts = new RetentionResolver.Facts(TYPE, DEPT, null, T0, changed, null, null);
        var fromCreation = rule("Created", "organisation", null, 1, "years", 0, "years");
        var fromChange = rule("Changed", "organisation", null, 1, "years", 0, "years");
        fromChange.setClockStart(RetentionPolicy.CLOCK_LAST_MODIFIED);

        assertThat(RetentionResolver.resolve(facts, List.of(fromCreation), RetentionResolver.LONGEST, FOLDERS).orElseThrow()
                .archiveDue()).isEqualTo(T0.plusYears(1));
        assertThat(RetentionResolver.resolve(facts, List.of(fromChange), RetentionResolver.LONGEST, FOLDERS).orElseThrow()
                .archiveDue()).isEqualTo(changed.plusYears(1));
    }

    @Test
    void aReinstatedDocument_getsAFreshActivePeriod_insteadOfBeingArchivedAgainAtOnce() {
        var reinstated = T0.plusYears(5);
        var facts = new RetentionResolver.Facts(TYPE, DEPT, null, T0, T0, reinstated, null);
        var r = RetentionResolver.resolve(facts, List.of(rule("X", "organisation", null, 1, "years", 1, "years")),
                RetentionResolver.LONGEST, FOLDERS).orElseThrow();
        assertThat(r.archiveDue()).isEqualTo(reinstated.plusYears(1));
        assertThat(r.reviewDue()).isEqualTo(reinstated.plusYears(2));
    }

    @Test
    void anExtension_onlyEverPushesTheReviewDateLater() {
        var base = rule("X", "organisation", null, 1, "years", 1, "years"); // review due T0 + 2y
        var later = new RetentionResolver.Facts(TYPE, DEPT, null, T0, T0, null, T0.plusYears(9));
        var earlier = new RetentionResolver.Facts(TYPE, DEPT, null, T0, T0, null, T0.plusMonths(1));
        assertThat(RetentionResolver.resolve(later, List.of(base), RetentionResolver.LONGEST, FOLDERS).orElseThrow().reviewDue())
                .isEqualTo(T0.plusYears(9));
        assertThat(RetentionResolver.resolve(earlier, List.of(base), RetentionResolver.LONGEST, FOLDERS).orElseThrow().reviewDue())
                .isEqualTo(T0.plusYears(2));
    }

    @Test
    void aFolderCycleInBadData_doesNotLoopForEver() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, UUID> cyclic = new java.util.HashMap<>();
        cyclic.put(a, b);
        cyclic.put(b, a);
        assertThat(RetentionResolver.folderChain(a, cyclic)).containsExactly(a, b);
    }
}
