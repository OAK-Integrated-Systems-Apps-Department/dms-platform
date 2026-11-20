package com.oak.dms.reporting.format;

import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A report must never reveal the name of a folder its reader cannot open. */
class FolderPathsTest {

    private final UUID tenant = UUID.randomUUID();
    private final Folder company = folder("Company", null, FolderAccessMode.inherit);
    private final Folder hr = folder("HR", company.getFolderId(), FolderAccessMode.restricted);
    private final Folder payroll = folder("Payroll", hr.getFolderId(), FolderAccessMode.everyone);
    private final Folder secret = folder("Secret plans", hr.getFolderId(), FolderAccessMode.inherit);

    private Folder folder(String name, UUID parent, FolderAccessMode mode) {
        return Folder.builder().folderId(UUID.randomUUID()).tenantId(tenant).parentFolderId(parent).folderName(name)
                .accessMode(mode).build();
    }

    private FolderAccessView limited() {
        return new FolderAccessView(List.of(company, hr, payroll, secret), Map.of(), false);
    }

    @Test
    void aVisibleFolderShowsItsFullPath() {
        assertThat(FolderPaths.pathOf(company.getFolderId(), limited())).isEqualTo("Company");
    }

    @Test
    void aHiddenFolderInTheMiddleOfAVisiblePathIsAnEllipsis_notItsName() {
        // Payroll is open to everyone even though its parent HR is not
        assertThat(FolderPaths.pathOf(payroll.getFolderId(), limited())).isEqualTo("Company / … / Payroll")
                .doesNotContain("HR");
    }

    @Test
    void aDocumentInAFolderTheReaderCannotSeeHasNoPath() {
        assertThat(FolderPaths.pathOf(secret.getFolderId(), limited())).isEmpty();
        assertThat(FolderPaths.pathOf(hr.getFolderId(), limited())).isEmpty();
    }

    @Test
    void noFolderIsAnEmptyPath() {
        assertThat(FolderPaths.pathOf(null, limited())).isEmpty();
    }

    @Test
    void anAdministratorSeesEveryName() {
        FolderAccessView admin = new FolderAccessView(List.of(company, hr, payroll, secret), Map.of(), true);

        assertThat(FolderPaths.pathOf(payroll.getFolderId(), admin)).isEqualTo("Company / HR / Payroll");
    }

    @Test
    void aBrokenFolderLoopEndsInsteadOfHangingTheReport() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Folder fa = Folder.builder().folderId(a).tenantId(tenant).parentFolderId(b).folderName("A").accessMode(FolderAccessMode.inherit).build();
        Folder fb = Folder.builder().folderId(b).tenantId(tenant).parentFolderId(a).folderName("B").accessMode(FolderAccessMode.inherit).build();

        String path = FolderPaths.pathOf(a, new FolderAccessView(List.of(fa, fb), Map.of(), true));

        assertThat(path).isNotEmpty().hasSizeLessThan(1000);
    }
}
