package com.oak.dms.reporting.format;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.datasource.ReportQuery;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finishes the cells SQL cannot: the folder path (from the caller's visible folders) and the name behind a custom user-reference
 * field. Works on a batch of rows so a 200,000-row export asks for names a few hundred at a time, never once per row.
 */
@Component
public class RowResolver {

    private final UserRepository users;

    public RowResolver(UserRepository users) {
        this.users = users;
    }

    /** Replaces folder ids and referenced user ids in {@code batch} with text, in place. */
    public void resolve(List<ReportRow> batch, List<ReportQuery.Column> columns, DocumentAccessView access, UUID tenantId) {
        for (int c = 0; c < columns.size(); c++) {
            ReportQuery.Column column = columns.get(c);
            if (column.special() == Special.FOLDER_PATH) {
                for (ReportRow row : batch) {
                    row.cells().set(c, FolderPaths.pathOf((UUID) row.cells().get(c), access.folders()));
                }
            } else if (column.isMetadataReference()) {
                resolveUsers(batch, c, tenantId);
            }
        }
    }

    private void resolveUsers(List<ReportRow> batch, int column, UUID tenantId) {
        Set<UUID> wanted = new HashSet<>();
        for (ReportRow row : batch) {
            UUID id = asUuid(row.cells().get(column));
            if (id != null) {
                wanted.add(id);
            }
        }
        Map<UUID, String> names = new HashMap<>();
        if (!wanted.isEmpty()) {
            for (User user : users.findAllByUserIdInAndTenantIdAndDeletedFalse(wanted, tenantId)) {
                names.put(user.getUserId(), user.getDisplayName());
            }
        }
        for (ReportRow row : batch) {
            Object value = row.cells().get(column);
            UUID id = asUuid(value);
            // A reference to someone who has left, or to another tenant's user, shows as empty - never as a raw id.
            row.cells().set(column, id == null ? value : names.getOrDefault(id, ""));
        }
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
