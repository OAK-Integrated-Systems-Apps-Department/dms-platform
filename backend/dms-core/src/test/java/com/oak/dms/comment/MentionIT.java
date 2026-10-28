package com.oak.dms.comment;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** @mentions (Phase 2 S6, decision 10, A5) - including users who can't open the document. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class MentionIT extends WorkflowApiSupport {

    private ContractWorld w;
    private User outsider;
    private User reader;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Commenter " + UUID.randomUUID(), DocumentCommentIT.COMMENTER, w.chamath());
        outsider = fx.user(w.tenantId(), "Outsider");
        reader = fx.user(w.tenantId(), "Reader");
        fx.role(w.tenantId(), "Reader " + UUID.randomUUID(), new String[]{"docm.documents.view"}, reader);
    }

    private static String token(User u) {
        return "@[" + u.getDisplayName() + "](user:" + u.getUserId() + ")";
    }

    private UUID post(String body, int expected) throws Exception {
        JsonNode created = postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.chamath(),
                Map.of("body", body), expected);
        return expected == 201 ? id(created, "commentId") : null;
    }

    @Test
    void mentionWithoutDocumentAccess_readsTheCommentButCantOpenTheDocument() throws Exception {
        UUID comment = post("Please check " + token(outsider) + " and " + token(reader), 201);
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ? and mentioned_user_id = ?"
                + " and had_document_access = false", comment, outsider.getUserId())).isEqualTo(1);
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ? and mentioned_user_id = ?"
                + " and had_document_access = true", comment, reader.getUserId())).isEqualTo(1);

        JsonNode mine = get200("/api/v1/mentions/my", outsider).get("content");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("canOpenDocument").asBoolean()).isFalse();
        assertThat(mine.get(0).get("comment").get("body").asString()).contains("Please check");
        assertThat(get200("/api/v1/comments/" + comment, outsider).get("root").get("commentId").asString())
                .isEqualTo(comment.toString());
        call(get("/api/v1/documents/" + w.contractDocumentId()), fx.jwtFor(outsider), null, 403);
        call(get("/api/v1/documents/" + w.contractDocumentId() + "/comments"), fx.jwtFor(outsider), null, 403);

        // Not mentioned, no access: the comment stays private.
        call(get("/api/v1/comments/" + comment), fx.jwtFor(w.saman()), null, 403);
        assertThat(get200("/api/v1/mentions/my", reader).get("content").get(0).get("canOpenDocument").asBoolean())
                .isTrue();
    }

    @Test
    void anEditNotifiesOnlyNewlyMentionedPeople_andRejectsUnknownIds() throws Exception {
        UUID comment = post("cc " + token(reader), 201);
        call(put("/api/v1/comments/" + comment), fx.jwtFor(w.chamath()),
                Map.of("body", "cc " + token(reader) + " " + token(outsider)), 200);
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ?", comment)).isEqualTo(2);
        // Dropping a token keeps the row as history.
        call(put("/api/v1/comments/" + comment), fx.jwtFor(w.chamath()), Map.of("body", "never mind"), 200);
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ?", comment)).isEqualTo(2);

        ContractWorld other = ContractWorld.create(fx);
        assertThat(postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.chamath(),
                Map.of("body", "hi " + token(other.nimal())), 422).get("code").asString())
                .isEqualTo("COMMENT_INVALID_MENTION");
        assertThat(postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.chamath(),
                Map.of("body", "hi @[Ghost](user:" + UUID.randomUUID() + ")"), 422).get("code").asString())
                .isEqualTo("COMMENT_INVALID_MENTION");
        // Mentioning yourself is ignored.
        UUID self = post("note to " + token(w.chamath()), 201);
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ?", self)).isZero();
    }

    @Test
    void peopleLookup_flagsDocumentAccess_andNeedsAPermission() throws Exception {
        JsonNode people = get200("/api/v1/people/lookup?search=" + outsider.getFirstName().toLowerCase(), w.chamath());
        assertThat(people).hasSize(1);
        assertThat(people.get(0).get("hasDocumentAccess").asBoolean()).isFalse();
        assertThat(get200("/api/v1/people/lookup?search=" + reader.getFirstName(), w.chamath()).get(0)
                .get("hasDocumentAccess").asBoolean()).isTrue();
        call(get("/api/v1/people/lookup"), fx.jwtFor(outsider), null, 403);
    }
}
