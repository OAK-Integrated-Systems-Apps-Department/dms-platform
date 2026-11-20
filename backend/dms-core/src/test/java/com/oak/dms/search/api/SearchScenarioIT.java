package com.oak.dms.search.api;

import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alternate flows around an index that is a little behind the repository, told as one story per flow: results can be
 * incomplete or stale for a moment, but they are never wrong about who may see what, and what they show about a document
 * (its name, type, owner, dates, status) always comes from the repository, not from the index.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchScenarioIT extends SearchApiSupport {

    @Test
    void aDocumentUploadedAMomentAgoIsNotThereYet_andIsFoundOnceIndexed_withNoErrorInBetween() throws Exception {
        index(); // the organisation is searchable and its queue is empty
        UUID fresh = doc("Brand new tender", "tender.txt", "fresh words");

        JsonNode before = search(bob, "q", "tender");
        assertThat(before.get("mode").asString()).as("still real search, just not up to date").isEqualTo("search");
        assertThat(ids(before)).as("incomplete for a moment, which the page says").doesNotContain(fresh);
        assertThat(noticeCodes(before)).contains("NO_RESULTS");

        index(); // the indexer catches up
        assertThat(ids(search(bob, "q", "tender"))).containsExactly(fresh);
    }

    @Test
    void aStaleIndexEntryStillShowsTheCurrentNameFromTheRepository() throws Exception {
        UUID doc = doc("Draft policy", "policy.txt", "x");
        index();
        fx.jdbc().update("update documents set title = 'Approved policy' where document_id = ?", doc);

        // the index still thinks it is the "Draft"; the answer's facts come from the repository
        JsonNode stale = search(bob, "q", "draft");
        assertThat(ids(stale)).containsExactly(doc);
        assertThat(titles(stale)).as("the current title, not the indexed one").containsExactly("Approved policy");

        index();
        assertThat(ids(search(bob, "q", "draft"))).as("after reindexing the old name no longer matches").isEmpty();
        assertThat(ids(search(bob, "q", "approved"))).containsExactly(doc);
    }

    @Test
    void aDocumentOwnedByAnotherPersonNowShowsTheNewOwnerBeforeTheIndexKnows() throws Exception {
        UUID doc = doc("Ownership story", "o.txt", "x");
        index();
        fx.jdbc().update("update documents set owner_user_id = ? where document_id = ?", bob.getUserId(), doc);

        JsonNode item = search(alice, "q", "ownership").get("items").get(0);
        assertThat(item.get("ownerUserId").asString()).as("from the repository").isEqualTo(bob.getUserId().toString());
        // the owner filter is the index's: it follows once the document is reindexed
        assertThat(ids(search(alice, "q", "ownership", "owner", bob.getUserId()))).isEmpty();
        index();
        assertThat(ids(search(alice, "q", "ownership", "owner", bob.getUserId()))).containsExactly(doc);
        assertThat(ids(search(alice, "q", "ownership", "owner", alice.getUserId()))).isEmpty();
    }

    @Test
    void aBrokenFileDoesNotStopTheOthersFromBeingIndexedAndFound() throws Exception {
        UUID good = doc("Good file", "good.txt", "perfectly readable zebra text");
        UUID corrupt = fx.document(tenantId, reportType, alice, "Corrupt file");
        UUID corruptVersion = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, corrupt);
        fx.jdbc().update("update document_versions set original_filename = 'broken.pdf', mime_type = 'application/pdf', rendition_status = 'completed', rendition_object_key = 'rendition/missing.pdf' where version_id = ?", corruptVersion);
        index();

        assertThat(ids(search(alice, "q", "zebra"))).containsExactly(good);
        JsonNode both = search(alice, "q", "file");
        assertThat(ids(both)).containsExactlyInAnyOrder(good, corrupt);
        for (JsonNode item : both.get("items")) {
            if (item.get("documentId").asString().equals(corrupt.toString())) {
                assertThat(item.get("bodyStatus").asString()).as("said plainly, so the page can explain").isEqualTo("unavailable");
            }
        }
        assertThat(ids(search(alice, "q", "corrupt"))).as("still findable by name").containsExactly(corrupt);
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenantId)).as("nothing stuck in the queue").isZero();
    }

    @Test
    void aVeryLargeResultSetPagesAndNeverClaimsMoreThanItChecked() throws Exception {
        for (int i = 0; i < 45; i++) {
            doc("Bulk record " + String.format("%02d", i), "b" + i + ".txt", "shared");
        }
        index();
        JsonNode first = search(alice, "q", "shared", "size", 10);
        assertThat(first.get("items")).hasSize(10);
        assertThat(first.get("total").get("exact").asBoolean()).as("not every candidate was checked").isFalse();
        assertThat(first.get("total").get("value").asLong()).isGreaterThanOrEqualTo(10);
        assertThat(first.get("nextCursor").isNull()).isFalse();
    }
}
