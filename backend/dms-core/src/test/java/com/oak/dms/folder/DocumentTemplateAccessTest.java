package com.oak.dms.folder;

import com.oak.dms.document.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A document type's template (V69) is view-only as a document - for everyone, administrators included. */
class DocumentTemplateAccessTest {

    private final FolderAccessView folders = new FolderAccessView(List.of(), Map.of(), false);

    private static Document topLevel(boolean template) {
        return Document.builder().documentId(UUID.randomUUID()).title("Contract").template(template).build();
    }

    @Test
    void aTemplate_canBeOpenedButNotChanged_evenByAnAdmin() {
        DocumentAccessService.DocumentAccessView admin =
                new DocumentAccessService.DocumentAccessView(folders, Map.of(), true, false);
        DocumentAccessService.DocumentAccessView member =
                new DocumentAccessService.DocumentAccessView(folders, Map.of(), false, false);

        assertThat(admin.level(topLevel(true))).isEqualTo(FolderAccessLevel.view);
        assertThat(member.level(topLevel(true))).isEqualTo(FolderAccessLevel.view);
        // An ordinary top-level document is unchanged.
        assertThat(admin.level(topLevel(false))).isEqualTo(FolderAccessLevel.manage);
        assertThat(member.level(topLevel(false)).atLeast(FolderAccessLevel.edit)).isTrue();
    }
}
