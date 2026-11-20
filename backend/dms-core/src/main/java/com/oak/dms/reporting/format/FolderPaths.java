package com.oak.dms.reporting.format;

import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Folder path" for a report cell, built from the caller's own {@link FolderAccessView}. A folder the caller cannot see is never
 * named: a document they can open inside a hidden folder (it was shared with them) shows an empty path, and a hidden folder in the
 * middle of a visible path shows as an ellipsis - the report must not reveal the name of a folder they cannot open.
 */
public final class FolderPaths {

    static final String SEPARATOR = " / ";
    static final String HIDDEN = "…";
    private static final int MAX_DEPTH = 64;

    private FolderPaths() {
    }

    public static String pathOf(UUID folderId, FolderAccessView view) {
        if (folderId == null || !view.canSee(folderId)) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        UUID current = folderId;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            Optional<Folder> folder = view.folder(current);
            if (folder.isEmpty()) {
                break;
            }
            String name = view.canSee(current) ? folder.get().getFolderName() : HIDDEN;
            if (!(HIDDEN.equals(name) && !segments.isEmpty() && HIDDEN.equals(segments.get(segments.size() - 1)))) {
                segments.add(name);
            }
            current = folder.get().getParentFolderId();
        }
        Collections.reverse(segments);
        return String.join(SEPARATOR, segments);
    }
}
