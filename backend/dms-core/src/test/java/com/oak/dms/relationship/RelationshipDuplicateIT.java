package com.oak.dms.relationship;

import com.oak.dms.relationship.event.DuplicateKeptEvent;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The automatic "Duplicate of" after "Keep both" (R7): the promoter's event, the checks, and that nothing can fail an upload. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipDuplicateIT extends RelationshipApiSupport {

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenantId;
    private User uploader;
    private UUID contractType;
    private UUID original;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        uploader = fx.user(tenantId, "Uploader");
        fx.role(tenantId, "Uploader", new String[]{CREATE, VIEW}, uploader);
        contractType = fx.documentType(tenantId, "Contract");
        original = fx.document(tenantId, contractType, uploader, "Supplier contract");
    }

    private JsonNode firstRow(UUID document) throws Exception {
        return panel(uploader, document).get("groups").get(0).get("items").get(0);
    }

    @Test
    void keepBoth_linksTheNewCopyToTheOriginal_asDuplicateOf() throws Exception {
        UUID copy = fx.documentKeptNextTo(tenantId, contractType, uploader, "Supplier contract (copy)", original);

        JsonNode fromCopy = firstRow(copy);
        assertThat(fromCopy.get("label").asString()).isEqualTo("Duplicate of");
        assertThat(fromCopy.get("direction").asString()).isEqualTo("outgoing");
        assertThat(fromCopy.get("origin").asString()).isEqualTo("duplicate_detection");
        assertThat(fromCopy.get("other").get("title").asString()).isEqualTo("Supplier contract");
        JsonNode fromOriginal = firstRow(original);
        assertThat(fromOriginal.get("label").asString()).isEqualTo("Has duplicate");
        assertThat(fromOriginal.get("other").get("documentId").asString()).isEqualTo(copy.toString());
        assertThat(auditCount(tenantId, "RELATIONSHIP_CREATED")).isEqualTo(1);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ? and created_by = ?"
                + " and source_document_id = ? and target_document_id = ?", tenantId, uploader.getUserId(), copy, original))
                .isEqualTo(1);
    }

    @Test
    void anUploadWithoutKeepBoth_makesNoLink() throws Exception {
        UUID plain = fx.document(tenantId, contractType, uploader, "Unrelated");
        assertThat(panel(uploader, plain).get("total").asInt()).isZero();
        assertThat(activeLinks(tenantId)).isZero();
    }

    @Test
    void anOriginalTheUploaderCantOpen_isNotLinked_andTheUploadStillSucceeds() throws Exception {
        User owner = fx.user(tenantId, "Owner");
        fx.role(tenantId, "Owner", new String[]{CREATE, VIEW}, owner);
        UUID secret = fx.document(tenantId, contractType, owner, "Secret contract");
        restrictTo(tenantId, secret, owner, "manage");

        UUID copy = fx.documentKeptNextTo(tenantId, contractType, uploader, "Copy of something", secret);

        assertThat(fx.count("select count(*) from documents where document_id = ?", copy)).isEqualTo(1);
        assertThat(activeLinks(tenantId)).isZero();
    }

    @Test
    void aDisabledType_orADeletedOriginal_meansNoLink_butNeverAnError() throws Exception {
        fx.jdbc().update("update relationship_types set is_enabled = false where tenant_id = ? and code = 'DUPLICATE_OF'", tenantId);
        UUID copy = fx.documentKeptNextTo(tenantId, contractType, uploader, "Copy 1", original);
        assertThat(fx.count("select count(*) from documents where document_id = ?", copy)).isEqualTo(1);
        assertThat(activeLinks(tenantId)).isZero();

        fx.jdbc().update("update relationship_types set is_enabled = true where tenant_id = ? and code = 'DUPLICATE_OF'", tenantId);
        UUID gone = fx.document(tenantId, contractType, uploader, "Deleted original");
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", gone);
        fx.documentKeptNextTo(tenantId, contractType, uploader, "Copy 2", gone);
        assertThat(activeLinks(tenantId)).isZero();
    }

    @Test
    void aBrokenEvent_isLoggedNotThrown() {
        // An original that never existed, a new document that doesn't either: the listener must swallow whatever happens.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publishEvent(new DuplicateKeptEvent(tenantId, UUID.randomUUID(), UUID.randomUUID(),
                        uploader.getUserId(), UUID.randomUUID())));
        assertThat(activeLinks(tenantId)).isZero();
    }

    @Test
    void theOptionalFieldChangesNothingForExistingCallers() {
        IngestionRequest plain = IngestionRequest.newDocument(contractType, "T", "a.pdf", 1, new ByteArrayInputStream(new byte[0]));
        assertThat(plain.duplicateOfDocumentId()).isNull();
        // The 13-argument constructor every earlier caller used still exists and defaults it to null.
        IngestionRequest old = new IngestionRequest(plain.kind(), plain.createdReason(), contractType, null, "T", "a.pdf", 1,
                plain.content(), null, null, false, null, false);
        assertThat(old.duplicateOfDocumentId()).isNull();

        IngestionRequest linked = plain.duplicateOf(original);
        assertThat(linked.duplicateOfDocumentId()).isEqualTo(original);
        // Refusing duplicates and asking for OCR keep it - and refuseDuplicates itself is untouched.
        assertThat(linked.refusingDuplicates().duplicateOfDocumentId()).isEqualTo(original);
        assertThat(linked.refusingDuplicates().refuseDuplicates()).isTrue();
        assertThat(linked.withOcr(java.util.List.of("a"), true).duplicateOfDocumentId()).isEqualTo(original);
        assertThat(linked.refuseDuplicates()).isFalse();
    }
}
