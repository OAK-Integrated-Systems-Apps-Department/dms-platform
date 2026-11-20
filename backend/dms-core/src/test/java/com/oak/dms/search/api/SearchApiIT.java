package com.oak.dms.search.api;

import com.oak.dms.workflow.support.WorkflowFixtures;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search behaviour end to end through the HTTP API: keywords, phrases, prefixes, metadata and body text, ranking, snippets,
 * sorting, paging, guidance when nothing is found, and every filter alone and combined with keywords.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchApiIT extends SearchApiSupport {

    // ------------------------------------------------------------------ what can be found, and how

    @Test
    void findsByTitleFileNameBodyAndMetadata() throws Exception {
        UUID amount = metadataField("amount", "number", true);
        UUID status = metadataField("status", "dropdown", true);
        fx.jdbc().update("insert into metadata_field_options (tenant_id, field_id, option_value, option_label) values (?, ?, 'FIN', 'Finalised')",
                tenantId, status);
        UUID byTitle = doc("Quarterly revenue report", "q3.txt", "nothing relevant here");
        UUID byFileName = doc("Untitled scan", "supplier_invoice_2026.txt", "plain words");
        UUID byBody = doc("Meeting minutes", "minutes.txt", "the committee approved the zebra initiative");
        UUID byMetadata = doc("Contract 114", "c114.txt", "boilerplate");
        setMetadata(byMetadata, "{\"" + status + "\":{\"v\":\"FIN\",\"src\":\"manual\"},\"" + amount + "\":{\"v\":4200,\"src\":\"manual\"}}");
        index();

        assertThat(ids(search(alice, "q", "quarterly"))).containsExactly(byTitle);
        assertThat(ids(search(alice, "q", "invoice"))).as("file name tokens split on underscores").containsExactly(byFileName);
        assertThat(ids(search(alice, "q", "zebra"))).as("text inside the document").containsExactly(byBody);
        assertThat(ids(search(alice, "q", "finalised"))).as("a metadata option label").containsExactly(byMetadata);
        assertThat(ids(search(alice, "q", "4200"))).as("a metadata number").containsExactly(byMetadata);
    }

    @Test
    void phrasesAndPrefixesAndOperators() throws Exception {
        UUID a = doc("Annual budget review", "a.txt", "x");
        UUID b = doc("Review of the annual plan", "b.txt", "x");
        UUID c = doc("Budget only", "c.txt", "x");
        index();

        assertThat(ids(search(alice, "q", "\"annual budget\""))).as("a quoted phrase").containsExactly(a);
        assertThat(ids(search(alice, "q", "annual review"))).as("all words, any order").containsExactlyInAnyOrder(a, b);
        assertThat(ids(search(alice, "q", "budg"))).as("the last word also matches as a prefix").containsExactlyInAnyOrder(a, c);
        assertThat(ids(search(alice, "q", "budget -annual"))).as("exclusion").containsExactly(c);
        assertThat(ids(search(alice, "q", "ANNUAL"))).as("case-insensitive").containsExactlyInAnyOrder(a, b);
    }

    @Test
    void stemmingAndAccentFolding() throws Exception {
        UUID a = doc("Café invoices", "a.txt", "All invoices were paid");
        index();
        assertThat(ids(search(alice, "q", "cafe"))).containsExactly(a);
        assertThat(ids(search(alice, "q", "invoice paid"))).as("singular finds plural, across title and body").containsExactly(a);
    }

    @Test
    void titleAndFileNameOutrankMetadataWhichOutranksBodyText() throws Exception {
        UUID status = metadataField("note", "text", true);
        UUID inBody = doc("Plain one", "p1.txt", "this text mentions zebra once");
        UUID inMetadata = doc("Plain two", "p2.txt", "nothing");
        setMetadata(inMetadata, "{\"" + status + "\":{\"v\":\"zebra\",\"src\":\"manual\"}}");
        UUID inFileName = doc("Plain three", "zebra_notes.txt", "nothing");
        UUID inTitle = doc("Zebra study", "p4.txt", "nothing");
        index();

        List<UUID> ranked = ids(search(alice, "q", "zebra"));
        assertThat(ranked).hasSize(4);
        assertThat(ranked.get(0)).as("title first").isEqualTo(inTitle);
        assertThat(ranked.get(1)).as("then file name").isEqualTo(inFileName);
        assertThat(ranked.indexOf(inMetadata)).as("metadata above body").isLessThan(ranked.indexOf(inBody));
    }

    @Test
    void snippetsHighlightMatchesAndEscapeEverythingElse() throws Exception {
        UUID d = doc("Tom & Jerry <script>zebra</script>", "z.txt", "a long text where the word zebra appears <b>inside</b> markup");
        index();

        JsonNode item = search(alice, "q", "zebra").get("items").get(0);
        assertThat(item.get("documentId").asString()).isEqualTo(d.toString());
        JsonNode h = item.get("highlights");
        assertThat(h.get("title").asString()).contains("<mark>zebra</mark>").contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(h.get("body").get(0).asString()).contains("<mark>zebra</mark>").contains("&lt;b&gt;inside&lt;").doesNotContain("<b>").doesNotContain("</b>");
        // the response is the document's facts plus decoration
        assertThat(item.get("fileName").asString()).isEqualTo("z.txt");
        assertThat(item.get("lifecycleState").asString()).isEqualTo("active");
        assertThat(item.get("bodyStatus").asString()).isEqualTo("ready");
        assertThat(item.has("body")).as("body text is never returned").isFalse();
    }

    // ------------------------------------------------------------------ sorting, paging

    @Test
    void sortsByTitleCreatedAndModified_andNoKeywordsListsNewestFirst() throws Exception {
        UUID b = doc("Bravo", "b.txt", "x");
        Thread.sleep(20);
        UUID a = doc("alpha", "a.txt", "x");
        Thread.sleep(20);
        UUID c = doc("Charlie", "c.txt", "x");
        index();

        assertThat(titles(search(alice, "sort", "title", "dir", "asc"))).containsExactly("alpha", "Bravo", "Charlie");
        assertThat(titles(search(alice, "sort", "title", "dir", "desc"))).containsExactly("Charlie", "Bravo", "alpha");
        assertThat(ids(search(alice, "sort", "created", "dir", "asc"))).containsExactly(b, a, c);
        assertThat(ids(search(alice, "sort", "created", "dir", "desc"))).containsExactly(c, a, b);
        fx.jdbc().update("update documents set updated_at = now() + interval '1 hour' where document_id = ?", b);
        index();
        assertThat(ids(search(alice, "sort", "modified", "dir", "desc")).get(0)).isEqualTo(b);
        assertThat(ids(search(alice)).get(0)).as("no keywords: most recently modified first").isEqualTo(b);
    }

    @Test
    void pagesWithACursorWithoutRepeatsOrGaps_andTheTotalIsExactOnlyWhenEveryCandidateWasChecked() throws Exception {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 25; i++) { // more than one fetch window (20), so the first page cannot know the total
            all.add(doc("Page doc " + String.format("%02d", i), "p" + i + ".txt", "shared words"));
        }
        index();

        List<UUID> seen = new ArrayList<>();
        JsonNode page = search(alice, "q", "shared", "size", 3, "sort", "title", "dir", "asc");
        seen.addAll(ids(page));
        assertThat(page.get("items")).hasSize(3);
        assertThat(page.get("nextCursor").isNull()).isFalse();
        assertThat(page.get("total").get("exact").asBoolean()).as("not every candidate was checked: 'N or more'").isFalse();
        assertThat(page.get("total").get("value").asLong()).isGreaterThanOrEqualTo(3);
        int pages = 1;
        while (!page.get("nextCursor").isNull()) {
            page = search(alice, "q", "shared", "size", 3, "sort", "title", "dir", "asc", "cursor", page.get("nextCursor").asString());
            seen.addAll(ids(page));
            pages++;
        }
        assertThat(pages).isEqualTo(9);
        assertThat(seen).hasSize(25).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        assertThat(titles(search(alice, "q", "shared", "size", 3, "sort", "title", "dir", "asc"))).containsExactly("Page doc 00", "Page doc 01", "Page doc 02");
        assertThat(page.get("total").get("exact").asBoolean()).as("the last page has seen everything").isTrue();
        assertThat(page.get("total").get("value").asLong()).isEqualTo(25);
    }

    @Test
    void aSmallResultSetHasAnExactTotalOnTheFirstPage() throws Exception {
        for (int i = 0; i < 7; i++) {
            doc("Small doc " + i, "s" + i + ".txt", "shared words");
        }
        index();
        JsonNode page = search(alice, "q", "shared", "size", 3);
        assertThat(page.get("items")).hasSize(3);
        assertThat(page.get("nextCursor").isNull()).isFalse();
        assertThat(page.get("total").get("value").asLong()).isEqualTo(7);
        assertThat(page.get("total").get("exact").asBoolean()).as("the first fetch already covered every candidate").isTrue();
    }

    @Test
    void aTamperedForeignOrReusedCursorIsRejectedWithNoDetail() throws Exception {
        for (int i = 0; i < 5; i++) {
            doc("Cursor doc " + i, "c" + i + ".txt", "shared");
        }
        index();
        String cursor = search(alice, "q", "shared", "size", 2).get("nextCursor").asString();

        searchExpecting(400, alice, "q", "shared", "size", 2, "cursor", cursor + "x");
        searchExpecting(400, alice, "q", "shared", "size", 2, "cursor", "garbage");
        searchExpecting(400, alice, "q", "different words", "size", 2, "cursor", cursor);        // other criteria
        searchExpecting(400, bob, "q", "shared", "size", 2, "cursor", cursor);                    // another user's cursor
        searchExpecting(400, alice, "q", "shared", "size", 2, "sort", "title", "cursor", cursor); // other sort
        search(alice, "q", "shared", "size", 2, "cursor", cursor); // the real one still works
    }

    // ------------------------------------------------------------------ nothing found

    @Test
    void noResultsGivesGuidanceAndAnHonestTotal() throws Exception {
        doc("Contract", "c.txt", "x");
        index();
        JsonNode r = search(alice, "q", "unobtainium");
        assertThat(r.get("items")).isEmpty();
        assertThat(noticeCodes(r)).contains("NO_RESULTS");
        assertThat(r.get("total").get("value").asLong()).isZero();
        assertThat(r.get("total").get("exact").asBoolean()).isTrue();
        assertThat(r.get("nextCursor").isNull()).isTrue();
        assertThat(r.get("didYouMean").isNull()).isTrue();
    }

    @Test
    void aMisspellingGetsADidYouMeanBuiltFromDocumentsTheUserCanSee() throws Exception {
        doc("Supplier contract", "c.txt", "x");
        index();
        JsonNode r = search(alice, "q", "suplier contrct");
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("didYouMean").asString()).isEqualTo("supplier contract");
    }

    // ------------------------------------------------------------------ filters

    @Test
    void documentTypeOwnerAndDepartmentFilters() throws Exception {
        UUID memoType = fx.documentType(tenantId, "Memo");
        var finance = fx.department(tenantId, "Finance", bob);
        UUID report = doc(alice, "Alpha report", "a.txt", "shared");
        UUID bobsMemo = doc(bob, "Bravo memo", "b.txt", "shared");
        fx.jdbc().update("update documents set document_type_id = ? where document_id = ?", memoType, bobsMemo);
        fx.jdbc().update("update documents set owner_department_id = ? where document_id = ?", finance.getDepartmentId(), bobsMemo);
        index();

        assertThat(ids(search(alice, "q", "shared", "type", memoType))).containsExactly(bobsMemo);
        assertThat(ids(search(alice, "q", "shared", "type", reportType))).containsExactly(report);
        assertThat(ids(search(alice, "q", "shared", "owner", bob.getUserId()))).containsExactly(bobsMemo);
        assertThat(ids(search(alice, "owner", alice.getUserId()))).containsExactly(report);
        assertThat(ids(search(alice, "q", "shared", "department", finance.getDepartmentId()))).containsExactly(bobsMemo);
        assertThat(ids(search(alice, "q", "shared", "type", memoType, "owner", alice.getUserId()))).as("combined").isEmpty();
    }

    @Test
    void createdAndModifiedDateRanges() throws Exception {
        UUID old = doc("Old doc", "o.txt", "shared");
        UUID recent = doc("Recent doc", "r.txt", "shared");
        fx.jdbc().update("update documents set created_at = '2024-01-15T10:00:00Z', updated_at = '2024-02-01T10:00:00Z' where document_id = ?", old);
        fx.jdbc().update("update documents set created_at = '2026-06-15T10:00:00Z', updated_at = '2026-07-01T10:00:00Z' where document_id = ?", recent);
        index();

        assertThat(ids(search(alice, "q", "shared", "createdFrom", "2026-01-01"))).containsExactly(recent);
        assertThat(ids(search(alice, "q", "shared", "createdTo", "2024-01-15"))).as("an end date covers that whole day").containsExactly(old);
        assertThat(ids(search(alice, "q", "shared", "createdFrom", "2024-01-01", "createdTo", "2024-12-31"))).containsExactly(old);
        assertThat(ids(search(alice, "q", "shared", "modifiedFrom", "2026-06-30", "modifiedTo", "2026-07-02"))).containsExactly(recent);
        assertThat(ids(search(alice, "q", "shared", "modifiedFrom", "2025-01-01", "modifiedTo", "2025-12-31"))).isEmpty();
        assertThat(ids(search(alice, "q", "shared", "createdFrom", "2026-06-15T09:00:00Z", "createdTo", "2026-06-15T11:00:00Z"))).containsExactly(recent);
    }

    @Test
    void folderFilterWithAndWithoutSubfolders() throws Exception {
        UUID top = folder(null, "Legal");
        UUID sub = folder(top, "Contracts");
        UUID other = folder(null, "HR");
        UUID inTop = doc("In top", "a.txt", "shared");
        UUID inSub = doc("In sub", "b.txt", "shared");
        UUID inOther = doc("In other", "c.txt", "shared");
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", top, inTop);
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", sub, inSub);
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", other, inOther);
        index();

        assertThat(ids(search(alice, "q", "shared", "folderId", top))).as("this folder only").containsExactly(inTop);
        assertThat(ids(search(alice, "q", "shared", "folderId", top, "includeSubfolders", true))).containsExactlyInAnyOrder(inTop, inSub);
        assertThat(ids(search(alice, "q", "shared", "folderId", sub))).containsExactly(inSub);
    }

    @Test
    void metadataFiltersForEachFieldType_alone_andWithKeywords() throws Exception {
        UUID amount = metadataField("amount", "number", true);
        UUID status = metadataField("status", "dropdown", true);
        UUID due = metadataField("due", "date", true);
        UUID paid = metadataField("paid", "boolean", true);
        UUID memo = metadataField("memo", "text", true);
        UUID small = doc("Small invoice", "s.txt", "shared");
        UUID big = doc("Big invoice", "b.txt", "shared");
        setMetadata(small, "{\"" + amount + "\":{\"v\":50,\"src\":\"manual\"},\"" + status + "\":{\"v\":\"OPEN\",\"src\":\"manual\"},\""
                + due + "\":{\"v\":\"2026-02-01\",\"src\":\"manual\"},\"" + paid + "\":{\"v\":false,\"src\":\"manual\"},\""
                + memo + "\":{\"v\":\"urgent follow up\",\"src\":\"manual\"}}");
        setMetadata(big, "{\"" + amount + "\":{\"v\":9000,\"src\":\"manual\"},\"" + status + "\":{\"v\":\"PAID\",\"src\":\"manual\"},\""
                + due + "\":{\"v\":\"2026-09-01\",\"src\":\"manual\"},\"" + paid + "\":{\"v\":true,\"src\":\"manual\"},\""
                + memo + "\":{\"v\":\"routine\",\"src\":\"manual\"}}");
        index();

        assertThat(ids(search(alice, "meta", amount + ":gte:1000"))).containsExactly(big);
        assertThat(ids(search(alice, "meta", amount + ":lte:100"))).containsExactly(small);
        assertThat(ids(search(alice, "meta", amount + ":eq:50"))).containsExactly(small);
        assertThat(ids(search(alice, "meta", status + ":eq:PAID"))).containsExactly(big);
        assertThat(ids(search(alice, "meta", due + ":lte:2026-03-01"))).containsExactly(small);
        assertThat(ids(search(alice, "meta", due + ":gte:2026-06-01"))).containsExactly(big);
        assertThat(ids(search(alice, "meta", paid + ":eq:true"))).containsExactly(big);
        assertThat(ids(search(alice, "meta", memo + ":contains:FOLLOW"))).as("case-insensitive contains").containsExactly(small);
        assertThat(ids(search(alice, "q", "invoice", "meta", amount + ":gte:1000"))).as("with keywords").containsExactly(big);
        assertThat(ids(search(alice, "q", "small", "meta", amount + ":gte:1000"))).isEmpty();
        // two filters on different fields are ANDed
        assertThat(ids(search(alice, "meta", amount + ":gte:10", "meta", paid + ":eq:true"))).containsExactly(big);
        assertThat(ids(search(alice, "meta", amount + ":gte:10", "meta", paid + ":eq:false", "meta", status + ":eq:PAID"))).isEmpty();
    }

    @Test
    void aFilterThatNoLongerExistsIsDroppedWithANotice_andTheRestStillApply() throws Exception {
        UUID amount = metadataField("amount", "number", true);
        UUID hit = doc("Kept document", "k.txt", "shared");
        UUID miss = doc(bob, "Bob document", "b.txt", "shared");
        setMetadata(hit, "{\"" + amount + "\":{\"v\":10,\"src\":\"manual\"}}");
        index();

        JsonNode r = search(alice, "q", "shared", "owner", alice.getUserId(), "type", UUID.randomUUID(), "department", UUID.randomUUID(),
                "meta", UUID.randomUUID() + ":eq:x", "folderId", UUID.randomUUID());
        assertThat(ids(r)).as("the owner filter that does exist still applies").containsExactly(hit);
        assertThat(noticeCodes(r)).contains("FILTER_NO_LONGER_EXISTS");
        JsonNode notice = null;
        for (JsonNode n : r.get("notices")) {
            if ("FILTER_NO_LONGER_EXISTS".equals(n.get("code").asString())) {
                notice = n;
            }
        }
        List<String> details = new ArrayList<>();
        notice.get("details").forEach(d -> details.add(d.asString()));
        assertThat(details).containsExactlyInAnyOrder("documentType", "department", "folder", "metadata");
        assertThat(ids(search(alice, "q", "shared"))).as("without the owner filter both show").contains(hit, miss);
    }

    @Test
    void malformedInputIsA400NotA500() throws Exception {
        index();
        searchExpecting(400, alice, "status", "pending");
        searchExpecting(400, alice, "createdFrom", "last tuesday");
        searchExpecting(400, alice, "sort", "random");
        searchExpecting(400, alice, "meta", "not-a-filter");
        searchExpecting(400, alice, "meta", "not-a-uuid:eq:1");
        UUID amount = metadataField("amount", "number", true);
        searchExpecting(400, alice, "meta", amount + ":between:1");
        searchExpecting(400, alice, "meta", amount + ":eq:abc");
        searchExpecting(400, alice, "meta", amount + ":contains:5");
        UUID memo = metadataField("memo", "text", true);
        searchExpecting(400, alice, "meta", memo + ":gte:5");
        // odd but harmless text is just text
        search(alice, "q", "\"unterminated phrase");
        search(alice, "q", "((( ))) ***");
        search(alice, "q", "title:secret OR tenant_id:*");
    }

    @Test
    void emptyQueryListsDocumentsAndSizeIsClamped() throws Exception {
        for (int i = 0; i < 3; i++) {
            doc("Listed " + i, "l" + i + ".txt", "x");
        }
        index();
        JsonNode r = search(alice, "size", 1000);
        assertThat(r.get("items")).hasSize(3);
        assertThat(search(alice, "size", 0).get("items")).hasSize(1);
        assertThat(r.get("mode").asString()).isEqualTo("search");
    }

    @Test
    void searchTextIsNeverLoggedAtInfoOrAbove() throws Exception {
        doc("Quarterly", "q.txt", "x");
        index();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            search(alice, "q", "confidentialmarker quarterly");
            search(alice, "q", "confidentialmarker \"unterminated");
            searchExpecting(400, alice, "q", "confidentialmarker", "status", "bogus");
            searchExpecting(403, alice, "q", "confidentialmarker", "status", "archived");
        } finally {
            root.detachAppender(logs);
        }
        assertThat(logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)).noneMatch(m -> m.contains("confidentialmarker"));
    }

    @Test
    void theFilterPanelOptionsAreTheCallersOwnOrganisationsAndOnlySearchableFields() throws Exception {
        UUID amount = metadataField("amount", "number", true);
        UUID status = metadataField("status", "dropdown", true);
        metadataField("secret_note", "text", false);
        fx.jdbc().update("insert into metadata_field_options (tenant_id, field_id, option_value, option_label) values (?, ?, 'OPEN', 'Open item')", tenantId, status);
        fx.department(tenantId, "Finance", alice);
        UUID otherTenant = fx.tenant();
        fx.user(otherTenant, "Stranger");
        fx.documentType(otherTenant, "Foreign type");
        fx.department(otherTenant, "Foreign dept");
        UUID foreignField = fx.jdbc().queryForObject("insert into metadata_fields (tenant_id, field_key, label, data_type, is_system_field, is_searchable, status)"
                + " values (?, 'foreign', 'Foreign field', 'text', false, true, 'active') returning field_id", UUID.class, otherTenant);

        JsonNode o = call(request("/api/v1/search/filters"), fx.jwtFor(alice), null, 200);
        List<String> types = new ArrayList<>();
        o.get("documentTypes").forEach(t -> types.add(t.get("name").asString()));
        assertThat(types).containsExactly("Report");
        List<String> departments = new ArrayList<>();
        o.get("departments").forEach(t -> departments.add(t.get("name").asString()));
        assertThat(departments).containsExactly("Finance");
        List<String> owners = new ArrayList<>();
        o.get("owners").forEach(t -> owners.add(t.get("name").asString()));
        assertThat(owners).contains("Alice", "Bob").doesNotContain("Stranger");
        List<String> fields = new ArrayList<>();
        o.get("metadataFields").forEach(t -> fields.add(t.get("fieldId").asString()));
        assertThat(fields).containsExactlyInAnyOrder(amount.toString(), status.toString()).doesNotContain(foreignField.toString());
        for (JsonNode f : o.get("metadataFields")) {
            if (f.get("fieldId").asString().equals(status.toString())) {
                assertThat(f.get("dataType").asString()).isEqualTo("dropdown");
                assertThat(f.get("options").get(0).get("label").asString()).isEqualTo("Open item");
            }
        }
        call(request("/api/v1/search/filters"), fx.jwtFor(carol), null, 403);
    }

    @Test
    void anOrganisationWithNoDocumentsYetGetsARealEmptyAnswer_notAFallback() throws Exception {
        index(); // backfill finds nothing to index, but must still make the organisation searchable
        assertThat(tenantState.find(tenantId).orElseThrow().indexReady()).isTrue();
        JsonNode r = search(alice, "q", "anything");
        assertThat(r.get("mode").asString()).isEqualTo("search");
        assertThat(r.get("items")).isEmpty();
        assertThat(noticeCodes(r)).contains("NO_RESULTS").doesNotContain("FALLBACK");
        assertThat(r.get("total").get("exact").asBoolean()).isTrue();
    }
}
