package com.oak.dms.pin;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A pin has an audience (4.3 Pin Important Documents): the pinner chooses who sees it, only the pinner can
 * unpin it, and it never shows anyone a document they could not open anyway (PID-NFR-003).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class PinAudienceIT extends WorkflowApiSupport {

    static final String[] PINNER = {"docm.documents.view", "pid.pin-unpin-documents.pin",
            "pid.pin-unpin-documents.unpin"};
    static final String[] READER = {"docm.documents.view"};

    private UUID tenantId;
    private UUID typeId;
    private User pinner;
    private User alice;
    private User bob;
    private UUID contract;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Contract");
        pinner = fx.user(tenantId, "Pinner");
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
        fx.role(tenantId, "Pinner " + UUID.randomUUID(), PINNER, pinner);
        fx.role(tenantId, "Reader " + UUID.randomUUID(), READER, alice, bob);
        contract = fx.document(tenantId, typeId, pinner, "Supplier contract");
    }

    private static Map<String, Object> audience(String mode, UUID... users) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("userIds", List.of(users));
        return body;
    }

    private void pin(User as, UUID document, Map<String, Object> body, int expected) throws Exception {
        call(put("/api/v1/pins/" + document), fx.jwtFor(as), body, expected);
    }

    private void unpin(User as, UUID document) throws Exception {
        call(delete("/api/v1/pins/" + document), fx.jwtFor(as), null, 204);
    }

    /** The ids of the documents this person sees pinned. */
    private List<String> seen(User as) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode pin : get200("/api/v1/pins", as).get("pins")) {
            ids.add(pin.get("document").get("documentId").asString());
        }
        return ids;
    }

    private void giveReaderRole(User user) {
        fx.role(tenantId, "Reader " + UUID.randomUUID(), READER, user);
    }

    @Test
    void allUsersExceptSome_isARule_soPeopleWhoJoinLaterSeeIt() throws Exception {
        pin(pinner, contract, audience("ALL", bob.getUserId()), 204);

        assertThat(seen(alice)).containsExactly(contract.toString());
        assertThat(seen(bob)).as("unticked").isEmpty();
        assertThat(seen(pinner)).as("the pinner always sees their own pin").containsExactly(contract.toString());

        User carol = fx.user(tenantId, "Carol");
        giveReaderRole(carol);
        assertThat(seen(carol)).as("joined after the pin was made").containsExactly(contract.toString());
    }

    @Test
    void specificUsers_seeItAndNobodyElseDoes() throws Exception {
        pin(pinner, contract, audience("USERS", alice.getUserId()), 204);

        assertThat(seen(alice)).containsExactly(contract.toString());
        assertThat(seen(bob)).isEmpty();
        assertThat(seen(pinner)).containsExactly(contract.toString());

        User carol = fx.user(tenantId, "Carol");
        giveReaderRole(carol);
        assertThat(seen(carol)).as("not named, joined later").isEmpty();
    }

    @Test
    void theListSaysWhoPinnedItAndShowsTheAudienceOnlyToThePinner() throws Exception {
        pin(pinner, contract, audience("ALL", bob.getUserId()), 204);

        JsonNode mine = get200("/api/v1/pins", pinner).get("pins").get(0);
        assertThat(mine.get("mine").asBoolean()).isTrue();
        assertThat(mine.get("audience").get("mode").asString()).isEqualTo("ALL");
        assertThat(mine.get("audience").get("count").asInt()).isEqualTo(1);

        JsonNode theirs = get200("/api/v1/pins", alice).get("pins").get(0);
        assertThat(theirs.get("mine").asBoolean()).isFalse();
        assertThat(theirs.get("pinnedBy").asString()).isEqualTo(pinner.getUserId().toString());
        assertThat(theirs.get("pinnedByName").asString()).isEqualTo("Pinner");
        assertThat(theirs.get("audience").isNull()).as("someone else's audience isn't shown").isTrue();
    }

    @Test
    void onlyThePinnerCanUnpin() throws Exception {
        fx.role(tenantId, "Pinner " + UUID.randomUUID(), PINNER, alice);
        pin(pinner, contract, audience("ALL"), 204);

        unpin(alice, contract);
        assertThat(seen(alice)).as("a recipient's unpin removes nothing").containsExactly(contract.toString());
        assertThat(seen(pinner)).containsExactly(contract.toString());

        unpin(pinner, contract);
        assertThat(seen(alice)).isEmpty();
        assertThat(seen(pinner)).isEmpty();
    }

    @Test
    void aRecipientUnpinningTheirOwnPinLeavesTheOtherPersonsAlone() throws Exception {
        fx.role(tenantId, "Pinner " + UUID.randomUUID(), PINNER, alice);
        pin(pinner, contract, audience("ALL"), 204);
        pin(alice, contract, audience("USERS", alice.getUserId()), 204);

        unpin(alice, contract);

        assertThat(seen(pinner)).containsExactly(contract.toString());
        assertThat(seen(bob)).as("still the pinner's pin").containsExactly(contract.toString());
    }

    @Test
    void aPinNeverShowsAPersonADocumentTheyCannotOpen_andIsNotDeletedForIt() throws Exception {
        // Restricted: only the pinner may open it.
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", contract);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, "
                + "access_level) values (?, ?, 'user', ?, 'manage')", tenantId, contract, pinner.getUserId());
        pin(pinner, contract, audience("ALL"), 204);

        assertThat(seen(alice)).as("can't open it, so the pin isn't shown").isEmpty();
        assertThat(fx.count("select count(*) from document_pin_assignments where tenant_id = ? and document_id = ?",
                tenantId, contract)).as("hidden for her, not deleted").isEqualTo(1);

        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, "
                + "access_level) values (?, ?, 'user', ?, 'view')", tenantId, contract, alice.getUserId());
        assertThat(seen(alice)).as("access granted later: it appears").containsExactly(contract.toString());
    }

    @Test
    void aDeletedDocumentTakesTheCallersOwnPinWithIt_butOnlyHidesSomeoneElses() throws Exception {
        pin(pinner, contract, audience("ALL"), 204);
        fx.jdbc().update("update documents set is_deleted = true where document_id = ?", contract);

        assertThat(seen(alice)).isEmpty();
        assertThat(fx.count("select count(*) from document_pin_assignments where tenant_id = ? and document_id = ?",
                tenantId, contract)).as("alice's read must not delete the pinner's pin").isEqualTo(1);

        assertThat(seen(pinner)).isEmpty();
        assertThat(fx.count("select count(*) from document_pin_assignments where tenant_id = ? and document_id = ?",
                tenantId, contract)).as("PID-FR-009: the pinner's own read drops it").isZero();
    }

    @Test
    void theLimitCountsOnlyThePinsAPersonPlaced() throws Exception {
        fx.jdbc().update("insert into pin_settings (tenant_id, max_pins) values (?, 1)", tenantId);
        fx.role(tenantId, "Pinner " + UUID.randomUUID(), PINNER, alice);
        UUID second = fx.document(tenantId, typeId, pinner, "Second");

        pin(pinner, contract, audience("ALL"), 204);
        assertThat(call(put("/api/v1/pins/" + second), fx.jwtFor(pinner), audience("ALL"), 409).get("code").asString())
                .isEqualTo("PIN_LIMIT_REACHED");
        pin(pinner, contract, audience("ALL", bob.getUserId()), 204); // changing the audience is not a new pin

        pin(alice, second, audience("USERS", alice.getUserId()), 204); // hers is her own first pin
        assertThat(seen(alice)).as("one she placed, one placed for her").hasSize(2);
    }

    @Test
    void anAudienceThatCannotWorkIsRefused() throws Exception {
        pin(pinner, contract, Map.of(), 400);
        pin(pinner, contract, audience("USERS"), 400);
        pin(pinner, contract, audience("BOGUS"), 400);

        UUID stranger = fx.user(fx.tenant(), "Stranger").getUserId();
        pin(pinner, contract, audience("USERS", stranger), 400);
        assertThat(fx.count("select count(*) from document_pin_assignments where tenant_id = ?", tenantId)).isZero();

        // ALL that leaves every other person out would show it to nobody but its pinner.
        pin(pinner, contract, audience("ALL", pinner.getUserId(), alice.getUserId(), bob.getUserId()), 400);
        pin(pinner, contract, audience("ALL", alice.getUserId(), bob.getUserId()), 400);
    }

    @Test
    void leavingYourselfOutChangesNothing_andIsNeitherStoredNorRefused() throws Exception {
        pin(pinner, contract, audience("ALL", pinner.getUserId()), 204);

        assertThat(seen(pinner)).as("the pinner always sees their own pin").containsExactly(contract.toString());
        assertThat(seen(alice)).containsExactly(contract.toString());
        assertThat(seen(bob)).containsExactly(contract.toString());
        JsonNode audience = call(get("/api/v1/pins/" + contract + "/audience"), fx.jwtFor(pinner), null, 200);
        assertThat(audience.get("members")).as("not stored").isEmpty();
    }

    @Test
    void inAnOrganisationOfOne_pinningForAllUsersWorks_evenIfTheyTickThemselvesOut() throws Exception {
        UUID soloTenant = fx.tenant();
        User solo = fx.user(soloTenant, "Solo");
        fx.role(soloTenant, "Pinner " + UUID.randomUUID(), PINNER, solo);
        UUID doc = fx.document(soloTenant, fx.documentType(soloTenant, "Memo"), solo, "Memo");

        pin(solo, doc, audience("ALL", solo.getUserId()), 204);

        assertThat(seen(solo)).containsExactly(doc.toString());
    }

    @Test
    void needsThePinPermission() throws Exception {
        pin(alice, contract, audience("ALL"), 403);
        call(get("/api/v1/pins/audience-candidates"), fx.jwtFor(alice), null, 403);
    }

    @Test
    void theAudienceCanBeReadBackForEditing() throws Exception {
        pin(pinner, contract, audience("ALL", bob.getUserId()), 204);

        JsonNode audience = call(get("/api/v1/pins/" + contract + "/audience"), fx.jwtFor(pinner), null, 200);
        assertThat(audience.get("mode").asString()).isEqualTo("ALL");
        assertThat(audience.get("members")).hasSize(1);
        assertThat(audience.get("members").get(0).get("userId").asString()).isEqualTo(bob.getUserId().toString());

        call(get("/api/v1/pins/" + contract + "/audience"), fx.jwtFor(alice), null, 403);
        fx.role(tenantId, "Pinner " + UUID.randomUUID(), PINNER, alice);
        call(get("/api/v1/pins/" + contract + "/audience"), fx.jwtFor(alice), null, 404);
    }

    @Test
    void candidatesAreThePeopleOfThePinnersOwnOrganisationOnly() throws Exception {
        User other = fx.user(fx.tenant(), "Alice"); // same name, different organisation

        JsonNode found = call(get("/api/v1/pins/audience-candidates").param("q", "alice"), fx.jwtFor(pinner), null, 200);

        List<String> ids = new ArrayList<>();
        found.forEach(person -> ids.add(person.get("userId").asString()));
        assertThat(ids).containsExactly(alice.getUserId().toString());
        assertThat(ids).doesNotContain(other.getUserId().toString());
    }

    @Test
    void anotherOrganisationNeverSeesIt() throws Exception {
        pin(pinner, contract, audience("ALL"), 204);

        UUID otherTenant = fx.tenant();
        User outsider = fx.user(otherTenant, "Outsider");
        fx.role(otherTenant, "Reader " + UUID.randomUUID(), READER, outsider);

        assertThat(seen(outsider)).isEmpty();
    }
}
