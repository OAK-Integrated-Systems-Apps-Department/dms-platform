package com.oak.dms.ownership.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.dto.DepartmentRef;
import com.oak.dms.ownership.dto.OwnershipHistoryItem;
import com.oak.dms.ownership.dto.OwnershipHistoryPage;
import com.oak.dms.ownership.dto.PersonRef;
import com.oak.dms.ownership.entity.OwnershipTransfer;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.ownership.helper.OwnershipCsv;
import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.ownership.helper.OwnershipLocks;
import com.oak.dms.ownership.helper.OwnershipNames;
import com.oak.dms.ownership.repository.OwnershipTransferRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ownership history (DOT-FR-004, DOT-NFR-001): who owned a document, who moved it, when and why - newest first.
 * Anyone with the history permission also needs to be able to open the document (404 otherwise); a tenant admin
 * sees everything. The export is the same data, filtered, as CSV.
 */
@Service
@RequiredArgsConstructor
public class OwnershipHistoryService {

    static final int EXPORT_CAP = 100_000;

    private final OwnershipTransferRepository transfers;
    private final OwnershipNames names;
    private final OwnershipLocks locks;
    private final DocumentAccessService documentAccess;
    private final AuthorizationService authorization;
    private final NamedParameterJdbcTemplate jdbc;

    /** Optional export filters; ids and times are all ANDed. */
    public record ExportFilter(OffsetDateTime from, OffsetDateTime to, UUID documentId, UUID userId, UUID batchId,
                               String reason) {
    }

    @Transactional(readOnly = true)
    public OwnershipHistoryPage forDocument(UUID documentId, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        List<OwnedDocument> found = locks.readDocuments(tenantId, Set.of(documentId));
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        if (!authorization.isTenantAdmin() && !documentAccess.forCaller().canSee(found.get(0).accessStub())) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        Page<OwnershipTransfer> rows = transfers.findByTenantIdAndDocumentIdOrderByTransferredAtDescTransferIdDesc(
                tenantId, documentId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));
        Set<UUID> peopleIds = new HashSet<>();
        Set<UUID> departmentIds = new HashSet<>();
        for (OwnershipTransfer t : rows) {
            peopleIds.add(t.getPreviousOwnerUserId());
            peopleIds.add(t.getNewOwnerUserId());
            peopleIds.add(t.getActorUserId());
            departmentIds.add(t.getPreviousOwnerDepartmentId());
            departmentIds.add(t.getNewOwnerDepartmentId());
        }
        Map<UUID, PersonRef> people = names.persons(tenantId, peopleIds);
        Map<UUID, DepartmentRef> departments = names.departments(tenantId, departmentIds);
        List<OwnershipHistoryItem> items = rows.stream().map(t -> new OwnershipHistoryItem(t.getTransferId(),
                t.getTransferredAt(), t.getReason(), t.getBatchId(), t.getNote(), t.isAccessGranted(),
                names.person(people, t.getPreviousOwnerUserId(), t.getPreviousOwnerName()),
                departments.get(t.getPreviousOwnerDepartmentId()),
                names.person(people, t.getNewOwnerUserId(), t.getNewOwnerName()),
                departments.get(t.getNewOwnerDepartmentId()),
                names.person(people, t.getActorUserId(), t.getActorName()))).toList();
        return new OwnershipHistoryPage(items, rows.getNumber(), rows.getSize(), rows.getTotalElements());
    }

    /** Streams the filtered history as CSV. Refuses (422) more than {@value #EXPORT_CAP} rows - narrow the filter. */
    @Transactional(readOnly = true)
    public void export(ExportFilter filter, HttpServletResponse response) throws IOException {
        UUID tenantId = TenantContext.getTenantId();
        OwnedDocumentsSql sql = OwnedDocumentsSql.includingPurged(tenantId);
        if (!authorization.isTenantAdmin()) {
            sql.visibleTo(documentAccess.forCaller());
        }
        if (filter.from() != null) {
            sql.and("h.transferred_at >= :from").param("from", filter.from());
        }
        if (filter.to() != null) {
            sql.and("h.transferred_at < :to").param("to", filter.to());
        }
        if (filter.documentId() != null) {
            sql.and("h.document_id = :doc").param("doc", filter.documentId());
        }
        if (filter.userId() != null) {
            sql.and("(h.previous_owner_user_id = :person or h.new_owner_user_id = :person)").param("person", filter.userId());
        }
        if (filter.batchId() != null) {
            sql.and("h.batch_id = :batch").param("batch", filter.batchId());
        }
        if (filter.reason() != null && !filter.reason().isBlank()) {
            sql.and("h.reason = :reason").param("reason", filter.reason());
        }
        String from = " from ownership_transfers h join documents d on d.tenant_id = h.tenant_id and d.document_id = h.document_id"
                + " where h.tenant_id = :t and " + sql.where();
        Long total = jdbc.queryForObject("select count(*)" + from, sql.params(), Long.class);
        if (total != null && total > EXPORT_CAP) {
            throw OwnershipException.invalid("OWNERSHIP_EXPORT_TOO_LARGE", "The export would have " + total
                    + " rows; the limit is " + EXPORT_CAP + ". Narrow it by date, document, person or batch.");
        }
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"ownership-history.csv\"");
        Writer out = new java.io.OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        out.write(OwnershipCsv.row("Transferred at", "Document id", "Document title", "Previous owner",
                "Previous department", "New owner", "New department", "Reason", "Batch id", "Done by", "Access granted", "Note"));
        jdbc.query("""
                select h.transferred_at, h.document_id, d.title, h.reason, h.batch_id, h.access_granted, h.note,
                       coalesce(po.display_name, h.previous_owner_name, h.previous_owner_user_id::text) as previous_owner,
                       coalesce(no.display_name, h.new_owner_name, h.new_owner_user_id::text) as new_owner,
                       coalesce(ac.display_name, h.actor_name, h.actor_user_id::text) as actor,
                       pd.name as previous_department, nd.name as new_department
                """ + from.replace(" where h.tenant_id", """
                  left join users po on po.tenant_id = h.tenant_id and po.user_id = h.previous_owner_user_id
                  left join users no on no.tenant_id = h.tenant_id and no.user_id = h.new_owner_user_id
                  left join users ac on ac.tenant_id = h.tenant_id and ac.user_id = h.actor_user_id
                  left join departments pd on pd.tenant_id = h.tenant_id and pd.department_id = h.previous_owner_department_id
                  left join departments nd on nd.tenant_id = h.tenant_id and nd.department_id = h.new_owner_department_id
                 where h.tenant_id""") + " order by h.transferred_at, h.transfer_id", sql.params(), rs -> {
            try {
                out.write(OwnershipCsv.row(rs.getObject("transferred_at", OffsetDateTime.class), rs.getString("document_id"),
                        rs.getString("title"), rs.getString("previous_owner"), rs.getString("previous_department"),
                        rs.getString("new_owner"), rs.getString("new_department"), rs.getString("reason"),
                        rs.getString("batch_id"), rs.getString("actor"), rs.getBoolean("access_granted"), rs.getString("note")));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        out.flush();
    }
}
