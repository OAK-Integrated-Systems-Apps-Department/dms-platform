package com.oak.dms.ownership;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Shared set-up and helpers for the ownership ITs. Test classes add {@code @Import(WorkflowFixtures.class)}. */
abstract class OwnershipApiSupport extends WorkflowApiSupport {

    static final String INITIATE = "dot.transfer-document-ownership.initiate-transfer";
    static final String APPROVE = "dot.transfer-document-ownership.approve-transfer";
    static final String SELECT = "dot.bulk-transfer-ownership.select-documents";
    static final String EXECUTE = "dot.bulk-transfer-ownership.execute-transfer";
    static final String HISTORY = "dot.view-ownership-history.view";
    static final String EXPORT = "dot.view-ownership-history.export";
    static final String DEACTIVATE = "otua.manage-users.deactivate";
    static final String VIEW = "docm.documents.view";

    protected static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    protected String ownerOf(UUID documentId) {
        return fx.jdbc().queryForObject("select owner_user_id::text from documents where document_id = ?", String.class, documentId);
    }

    protected long historyRows(UUID tenantId, UUID documentId) {
        return fx.count("select count(*) from ownership_transfers where tenant_id = ? and document_id = ?", tenantId, documentId);
    }

    protected long auditCount(UUID tenantId, String action) {
        return fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId, action);
    }

    protected org.springframework.test.web.servlet.ResultActions raw(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, User as) throws Exception {
        return mvc.perform(request.with(fx.jwtFor(as)));
    }

    protected static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getReq(String path) {
        return get(path);
    }

    protected static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postReq(String path) {
        return post(path);
    }

    protected static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putReq(String path) {
        return put(path);
    }

    // ---------------------------------------------------------------- world builders

    /** A folder with the given access mode; {@code members} are put on its list at the given level when restricted. */
    protected UUID folder(UUID tenantId, String name, boolean restricted, String level, User... members) {
        UUID folderId = UUID.randomUUID();
        fx.jdbc().update("insert into folders (folder_id, tenant_id, folder_name, access_mode) values (?, ?, ?, ?)",
                folderId, tenantId, name + " " + folderId.toString().substring(0, 6), restricted ? "restricted" : "inherit");
        for (User member : members) {
            fx.jdbc().update("insert into folder_access (tenant_id, folder_id, principal_type, principal_id, access_level)"
                    + " values (?, ?, 'user', ?, ?)", tenantId, folderId, member.getUserId(), level);
        }
        return folderId;
    }

    protected void file(UUID documentId, UUID folderId) {
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", folderId, documentId);
    }

    /** Gives the document its own list: only these users at the level. */
    protected void restrictTo(UUID tenantId, UUID documentId, String level, User... members) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", documentId);
        for (User member : members) {
            fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id,"
                    + " access_level) values (?, ?, 'user', ?, ?)", tenantId, documentId, member.getUserId(), level);
        }
    }

    protected void makeHead(UUID departmentId, User head) {
        fx.jdbc().update("update departments set head_user_id = ? where department_id = ?", head.getUserId(), departmentId);
    }

    /** Whether the user can open the document: 200 vs 404 on GET /documents/{id}. */
    protected boolean canOpen(User user, UUID documentId) throws Exception {
        int status = mvc.perform(getReq("/api/v1/documents/" + documentId).with(fx.jwtFor(user))).andReturn()
                .getResponse().getStatus();
        if (status != 200 && status != 404) {
            throw new AssertionError("Unexpected status " + status + " opening " + documentId);
        }
        return status == 200;
    }

    protected String accessMode(UUID documentId) {
        return fx.jdbc().queryForObject("select access_mode from documents where document_id = ?", String.class, documentId);
    }

    /** {@code count} extra documents for the owner, inserted with SQL (a promoter run per document would be slow). */
    protected void bulkDocuments(UUID tenantId, UUID typeId, User owner, UUID versionRef, int count, String prefix) {
        fx.jdbc().update("insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id,"
                + " owner_department_id) select gen_random_uuid(), ?, ?, ? || ' ' || g, ?, ?, ? from generate_series(1, ?) g",
                tenantId, typeId, prefix, versionRef, owner.getUserId(), owner.getDepartmentId(), count);
    }

    protected UUID versionOf(UUID documentId) {
        return fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?", UUID.class, documentId);
    }

    protected long ownedBy(User user) {
        return fx.count("select count(*) from documents where owner_user_id = ? and purged_at is null", user.getUserId());
    }

    protected static Map<String, Object> allOf(User source, User target, Object... extra) {
        Map<String, Object> map = body("sourceUserId", source.getUserId(), "mode", "all",
                "target", body("type", "user", "newOwnerUserId", target.getUserId()));
        for (int i = 0; i < extra.length; i += 2) {
            map.put((String) extra[i], extra[i + 1]);
        }
        return map;
    }

    protected static Map<String, Object> selected(java.util.Collection<UUID> ids, User target, Object... extra) {
        Map<String, Object> map = body("mode", "selected", "documentIds", ids,
                "target", body("type", "user", "newOwnerUserId", target.getUserId()));
        for (int i = 0; i < extra.length; i += 2) {
            map.put((String) extra[i], extra[i + 1]);
        }
        return map;
    }
}
