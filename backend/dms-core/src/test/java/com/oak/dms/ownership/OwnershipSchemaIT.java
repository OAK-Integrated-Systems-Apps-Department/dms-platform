package com.oak.dms.ownership;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditActionSpec;
import com.oak.dms.department.entity.Department;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O2: the promoter sets the owner department, new versions leave ownership alone, constants and audit actions exist. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipSchemaIT extends OwnershipApiSupport {

    @Test
    void newDocumentTakesTheUploadersDepartment() {
        UUID tenantId = fx.tenant();
        User uploader = fx.user(tenantId, "Uploader");
        Department finance = fx.department(tenantId, "Finance", uploader);
        UUID type = fx.documentType(tenantId, "Contract");

        UUID documentId = fx.document(tenantId, type, uploader, "With department");

        assertThat(fx.jdbc().queryForObject("select owner_department_id from documents where document_id = ?",
                UUID.class, documentId)).isEqualTo(finance.getDepartmentId());
        assertThat(fx.jdbc().queryForObject("select ownership_state from documents where document_id = ?",
                String.class, documentId)).isEqualTo("assigned");
    }

    @Test
    void uploaderWithoutDepartmentLeavesItNull() {
        UUID tenantId = fx.tenant();
        User uploader = fx.user(tenantId, "Loner");
        UUID documentId = fx.document(tenantId, fx.documentType(tenantId, "Memo"), uploader, "No department");

        assertThat(fx.jdbc().queryForObject("select owner_department_id from documents where document_id = ?",
                UUID.class, documentId)).isNull();
    }

    @Test
    void newVersionByAnotherUserLeavesOwnerFieldsAlone() {
        UUID tenantId = fx.tenant();
        User owner = fx.user(tenantId, "Owner");
        User editor = fx.user(tenantId, "Editor");
        Department finance = fx.department(tenantId, "Finance", owner);
        Department legal = fx.department(tenantId, "Legal", editor);
        UUID documentId = fx.document(tenantId, fx.documentType(tenantId, "Policy"), owner, "Policy");

        fx.newVersion(tenantId, documentId, editor);

        assertThat(ownerOf(documentId)).isEqualTo(owner.getUserId().toString());
        assertThat(fx.jdbc().queryForObject("select owner_department_id from documents where document_id = ?",
                UUID.class, documentId)).isEqualTo(finance.getDepartmentId()).isNotEqualTo(legal.getDepartmentId());
    }

    @Test
    void auditActionsAreRegisteredUnderDot() {
        for (String action : new String[]{AuditActionCatalog.DOCUMENT_OWNERSHIP_TRANSFERRED,
                AuditActionCatalog.OWNERSHIP_BATCH_TRANSFERRED, AuditActionCatalog.USER_OFFBOARDED,
                AuditActionCatalog.DEPARTMENT_HOLDING_OWNER_CHANGED}) {
            AuditActionSpec spec = AuditActionCatalog.require(action);
            assertThat(spec.module()).isEqualTo("DOT");
        }
        assertThat(AuditActionCatalog.require(AuditActionCatalog.USER_OFFBOARDED).reasonRequired()).isTrue();
    }

    @Test
    void permissionConstantsMatchTheSeededCodes() {
        for (String code : new String[]{PermissionCodes.DOT_TRANSFER_INITIATE, PermissionCodes.DOT_TRANSFER_APPROVE,
                PermissionCodes.DOT_BULK_SELECT, PermissionCodes.DOT_BULK_EXECUTE, PermissionCodes.DOT_HISTORY_VIEW,
                PermissionCodes.DOT_HISTORY_EXPORT}) {
            assertThat(fx.count("select count(*) from permissions where permission_code = ? and tenant_id is null", code))
                    .as(code).isEqualTo(1);
        }
    }
}
