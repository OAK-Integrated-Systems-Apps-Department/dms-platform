package com.oak.dms.workflow;

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
 * V120: a document type may have more than one active template. With
 * exactly one, submission resolves it automatically (unchanged); with more
 * than one, the submitter must say which (TemplateSelector).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowTemplateSelectionIT extends WorkflowApiSupport {

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
    }

    @Test
    void oneActiveTemplate_isSelectedAutomatically() throws Exception {
        activeTemplate(w.admin(), "ONLY", w.contractTypeId(), "flag_only",
                List.of(stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId()))));

        assertThat(get200("/api/v1/workflow/templates/active?documentTypeId=" + w.contractTypeId(), w.chamath()))
                .hasSize(1);

        JsonNode instance = submit(w.chamath(), w.contractDocumentId(), 201);
        assertThat(instance.get("templateName").asString()).isEqualTo("ONLY approval");
    }

    @Test
    void multipleActiveTemplates_requireAChoice() throws Exception {
        UUID first = activeTemplate(w.admin(), "FIRST", w.contractTypeId(), "flag_only",
                List.of(stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId()))));
        UUID second = activeTemplate(w.admin(), "SECOND", w.contractTypeId(), "flag_only",
                List.of(stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId()))));

        JsonNode options = get200("/api/v1/workflow/templates/active?documentTypeId=" + w.contractTypeId(), w.chamath());
        assertThat(options).hasSize(2);
        List<String> names = new java.util.ArrayList<>();
        options.forEach(o -> names.add(o.get("name").asString()));
        assertThat(names).containsExactlyInAnyOrder("FIRST approval", "SECOND approval");

        // No templateId given - refused, not guessed.
        assertThat(submit(w.chamath(), w.contractDocumentId(), 422).get("code").asString())
                .isEqualTo("WORKFLOW_TEMPLATE_SELECTION_REQUIRED");

        // An explicit, valid choice works.
        JsonNode instance = submit(w.chamath(), w.contractDocumentId(), first, 201);
        assertThat(id(instance, "templateId")).isEqualTo(first);

        // The other active template is untouched by this submission.
        assertThat(adminGet("/api/v1/workflow/templates/" + second).get("status").asString()).isEqualTo("active");
    }

    @Test
    void aTemplateOfAnotherDocumentType_isNotAValidChoice() throws Exception {
        activeTemplate(w.admin(), "CONTRACT_A", w.contractTypeId(), "flag_only",
                List.of(stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId()))));
        activeTemplate(w.admin(), "CONTRACT_B", w.contractTypeId(), "flag_only",
                List.of(stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId()))));
        UUID invoiceTemplate = activeTemplate(w.admin(), "INVOICE_ONLY", w.invoiceTypeId(), "flag_only",
                List.of(stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId()))));

        assertThat(submit(w.chamath(), w.contractDocumentId(), invoiceTemplate, 422).get("code").asString())
                .isEqualTo("WORKFLOW_TEMPLATE_INVALID_SELECTION");
    }

    private JsonNode adminGet(String path) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path),
                fx.tenantAdminJwtFor(w.admin()), null, 200);
    }
}
