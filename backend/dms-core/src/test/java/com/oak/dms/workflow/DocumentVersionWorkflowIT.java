package com.oak.dms.workflow;

import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A12/C11: both {@code DocumentVersionService} paths that create a version
 * reach {@code DocumentVersionChangeListener} - an upload over the document
 * ({@code replacement_upload}) and putting an older version back
 * ({@code rollback}) - under each {@code onDocumentChange} setting.
 * <p>
 * The version is made through the real {@code DocumentVersionPromoter} with
 * the created_reason the service sends through the gate
 * ({@link DocumentVersionService#REPLACEMENT_UPLOAD},
 * {@link DocumentVersionService#ROLLBACK}), which is where the gate hands
 * over once a file has scanned clean. Driving the gate itself would need a
 * live ClamAV, which the suite deliberately doesn't have (DocumentUploadIT
 * is excluded for the same reason).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentVersionWorkflowIT extends WorkflowApiSupport {

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
    }

    /** One stage, Nimal alone, so a run is easy to follow. */
    private void template(String onDocumentChange) throws Exception {
        activeTemplate(w.admin(), "CONTRACT", w.contractTypeId(), onDocumentChange, List.of(
                stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
    }

    private UUID inProgressInstance() {
        return fx.jdbc().queryForObject("select instance_id from workflow_instances where document_id = ?"
                + " and status = 'in_progress'", UUID.class, w.contractDocumentId());
    }

    private void flagOnly(String createdReason) throws Exception {
        template("flag_only");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID originalVersion = id(submitted, "documentVersionId");

        UUID newVersion = fx.newVersion(w.tenantId(), w.contractDocumentId(), w.chamath(), createdReason);

        JsonNode flagged = instance(instanceId, w.chamath());
        assertThat(flagged.get("versionChangedFlag").asBoolean()).as(createdReason + " flags the run").isTrue();
        assertThat(flagged.get("status").asString()).isEqualTo("in_progress");
        assertThat(id(flagged, "documentVersionId")).isEqualTo(originalVersion);
        assertThat(id(flagged, "latestDocumentVersionId")).isEqualTo(newVersion);
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ?"
                        + " and action_type = 'version_changed' and detail ->> 'toVersionId' = ?",
                instanceId, newVersion.toString())).isEqualTo(1);
        assertThat(fx.count("select count(*) from document_versions where version_id = ? and created_reason = ?",
                newVersion, createdReason)).isEqualTo(1);
    }

    private void restart(String createdReason) throws Exception {
        template("restart");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID oldInstance = id(submitted, "instanceId");

        UUID newVersion = fx.newVersion(w.tenantId(), w.contractDocumentId(), w.chamath(), createdReason);

        JsonNode old = instance(oldInstance, w.chamath());
        assertThat(old.get("status").asString()).as(createdReason + " restarts the run").isEqualTo("cancelled");
        assertThat(old.get("outcomeReason").asString()).contains("new version");
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'restarted'",
                oldInstance)).isEqualTo(1);

        UUID freshId = inProgressInstance();
        assertThat(freshId).isNotEqualTo(oldInstance);
        JsonNode fresh = instance(freshId, w.chamath());
        assertThat(id(fresh, "documentVersionId")).isEqualTo(newVersion);
        assertThat(fresh.get("initiatedBy").get("displayName").asString()).isEqualTo("Chamath");
        assertThat(stage(fresh, 1).get("status").asString()).isEqualTo("active");
    }

    @Test
    void replacementUpload_flagOnly_flagsTheRunAndKeepsItOnTheSubmittedVersion() throws Exception {
        flagOnly(DocumentVersionService.REPLACEMENT_UPLOAD);
    }

    @Test
    void replacementUpload_restart_cancelsAndResubmitsOnTheNewVersion() throws Exception {
        restart(DocumentVersionService.REPLACEMENT_UPLOAD);
    }

    @Test
    void rollback_flagOnly_flagsTheRunAndKeepsItOnTheSubmittedVersion() throws Exception {
        flagOnly(DocumentVersionService.ROLLBACK);
    }

    @Test
    void rollback_restart_cancelsAndResubmitsOnTheNewVersion() throws Exception {
        restart(DocumentVersionService.ROLLBACK);
    }
}
