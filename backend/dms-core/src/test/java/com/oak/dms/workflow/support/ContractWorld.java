package com.oak.dms.workflow.support;

import com.oak.dms.department.entity.Department;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.user.User;

import java.util.UUID;

/**
 * The Phase 1 scenario cast (docs/workflow-phase1-plan.md W20a), in its own
 * tenant. Chamath submits; Saman/Dilani/Ruwan are Legal; Kumari/Asanka hold
 * Dept Head; Nimal is the CFO; finance1 is Finance (scenario H only).
 * {@code admin} configures templates as a tenant admin.
 */
public record ContractWorld(
        UUID tenantId,
        User admin,
        User chamath,
        User saman,
        User dilani,
        User ruwan,
        User kumari,
        User asanka,
        User nimal,
        User finance1,
        Department legal,
        Department finance,
        Role deptHead,
        UUID contractTypeId,
        UUID invoiceTypeId,
        UUID contractDocumentId
) {

    public static ContractWorld create(WorkflowFixtures fx) {
        UUID tenantId = fx.tenant();
        User admin = fx.user(tenantId, "Admin");
        User chamath = fx.user(tenantId, "Chamath");
        User saman = fx.user(tenantId, "Saman");
        User dilani = fx.user(tenantId, "Dilani");
        User ruwan = fx.user(tenantId, "Ruwan");
        User kumari = fx.user(tenantId, "Kumari");
        User asanka = fx.user(tenantId, "Asanka");
        User nimal = fx.user(tenantId, "Nimal");
        User finance1 = fx.user(tenantId, "Finance1");

        fx.role(tenantId, "Submitter", new String[]{WorkflowFixtures.SUBMIT_PERMISSION, "docm.documents.view"}, chamath);
        fx.role(tenantId, "Approver", WorkflowFixtures.APPROVER_PERMISSIONS,
                saman, dilani, ruwan, kumari, asanka, nimal, finance1);
        Role deptHead = fx.role(tenantId, "Dept Head", new String[0], kumari, asanka);
        Department legal = fx.department(tenantId, "Legal", saman, dilani, ruwan);
        Department finance = fx.department(tenantId, "Finance", finance1);

        UUID contractType = fx.documentType(tenantId, "Contract");
        UUID invoiceType = fx.documentType(tenantId, "Invoice");
        UUID document = fx.document(tenantId, contractType, chamath, "Supplier contract 2026-114");
        return new ContractWorld(tenantId, admin, chamath, saman, dilani, ruwan, kumari, asanka, nimal, finance1,
                legal, finance, deptHead, contractType, invoiceType, document);
    }
}
