package com.oak.dms.folder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How a caller's access to each folder is worked out: inheritance, restriction, admins, the top level. */
class FolderAccessViewTest {

    private final Folder policies = folder("Policies", null, FolderAccessMode.inherit);
    private final Folder hr = folder("HR", null, FolderAccessMode.restricted);
    private final Folder payroll = folder("Payroll", hr.getFolderId(), FolderAccessMode.inherit);
    private final Folder handbook = folder("Handbook", hr.getFolderId(), FolderAccessMode.everyone);
    private final Folder board = folder("Board", payroll.getFolderId(), FolderAccessMode.restricted);
    private final List<Folder> all = List.of(policies, hr, payroll, handbook, board);

    @Test
    void openFolders_andTheTopLevel_areOpenToEveryone() {
        FolderAccessView view = new FolderAccessView(all, Map.of(), false);

        assertThat(view.level(policies.getFolderId())).isEqualTo(FolderAccessLevel.manage);
        assertThat(view.level(null)).isEqualTo(FolderAccessLevel.manage);
        assertThat(view.restricted(policies.getFolderId())).isFalse();
    }

    @Test
    void aRestrictedFolder_isHiddenWithItsInheritingSubfolders_unlessTheCallerIsListed() {
        FolderAccessView stranger = new FolderAccessView(all, Map.of(), false);

        assertThat(stranger.canSee(hr.getFolderId())).isFalse();
        assertThat(stranger.canSee(payroll.getFolderId())).isFalse();
        assertThat(stranger.restricted(payroll.getFolderId())).isTrue();
        assertThat(stranger.hiddenFolderIds())
                .containsExactlyInAnyOrder(hr.getFolderId(), payroll.getFolderId(), board.getFolderId());
        // A subfolder that sets "everyone" itself is open again.
        assertThat(stranger.level(handbook.getFolderId())).isEqualTo(FolderAccessLevel.manage);

        FolderAccessView viewer = new FolderAccessView(all, Map.of(hr.getFolderId(), FolderAccessLevel.view), false);
        assertThat(viewer.level(payroll.getFolderId())).isEqualTo(FolderAccessLevel.view);
        assertThat(viewer.governingFolder(payroll.getFolderId())).contains(hr);
        // Board has its own list, which the HR viewer isn't on.
        assertThat(viewer.canSee(board.getFolderId())).isFalse();
    }

    @Test
    void tenantAdmins_seeEverything_andUnknownFoldersAreHidden() {
        FolderAccessView admin = new FolderAccessView(all, Map.of(), true);

        assertThat(all).allSatisfy(f -> assertThat(admin.level(f.getFolderId())).isEqualTo(FolderAccessLevel.manage));
        assertThat(admin.hiddenFolderIds()).isEmpty();
        assertThat(admin.canSee(UUID.randomUUID())).isFalse();
    }

    private static Folder folder(String name, UUID parent, FolderAccessMode mode) {
        return Folder.builder().folderId(UUID.randomUUID()).tenantId(UUID.randomUUID()).folderName(name)
                .parentFolderId(parent).accessMode(mode).build();
    }
}
