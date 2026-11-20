package com.oak.dms.reporting.format;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RowResolverTest {

    private final UUID tenant = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final RowResolver resolver = new RowResolver(users);

    private final Folder open = Folder.builder().folderId(UUID.randomUUID()).tenantId(tenant).folderName("Contracts")
            .accessMode(FolderAccessMode.inherit).build();
    private final Folder hidden = Folder.builder().folderId(UUID.randomUUID()).tenantId(tenant).folderName("HR")
            .accessMode(FolderAccessMode.restricted).build();
    private final DocumentAccessView access = new DocumentAccessView(
            new FolderAccessView(List.of(open, hidden), Map.of(), false), Map.of(), false, true);

    private final Column title = new Column("title", "Title", FieldType.TEXT, ValueKind.TEXT, false, Special.NONE, "c0");
    private final Column folder = new Column("folderPath", "Folder path", FieldType.TEXT, ValueKind.TEXT, false, Special.FOLDER_PATH, "c1");
    private final Column manager = new Column("meta.manager", "Manager", FieldType.REFERENCE, ValueKind.TEXT, false, Special.NONE, "c2");

    private ReportRow row(Object... cells) {
        return new ReportRow(UUID.randomUUID(), new ArrayList<>(Arrays.asList(cells)));
    }

    private User user(UUID id, String name) {
        User u = mock(User.class);
        when(u.getUserId()).thenReturn(id);
        when(u.getDisplayName()).thenReturn(name);
        return u;
    }

    @Test
    void folderIdsBecomeVisiblePaths_andAHiddenFolderBecomesEmpty() {
        ReportRow inOpen = row("A", open.getFolderId());
        ReportRow inHidden = row("B", hidden.getFolderId());
        ReportRow nowhere = row("C", (Object) null);

        resolver.resolve(List.of(inOpen, inHidden, nowhere), List.of(title, folder), access, tenant);

        assertThat(inOpen.cells()).containsExactly("A", "Contracts");
        assertThat(inHidden.cells()).containsExactly("B", "");
        assertThat(nowhere.cells()).containsExactly("C", "");
    }

    @Test
    void aCustomUserReferenceShowsTheNameFoundInOneBatchLookup() {
        UUID ann = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        User annUser = user(ann, "Ann"); // built before stubbing: a mock cannot be created inside another when(...)
        User benUser = user(ben, "Ben");
        when(users.findAllByUserIdInAndTenantIdAndDeletedFalse(any(), any())).thenReturn(List.of(annUser, benUser));
        ReportRow one = row("A", ann.toString());
        ReportRow two = row("B", ben.toString());
        ReportRow three = row("C", ann.toString());

        resolver.resolve(List.of(one, two, three), List.of(title, manager), access, tenant);

        assertThat(one.cells().get(1)).isEqualTo("Ann");
        assertThat(two.cells().get(1)).isEqualTo("Ben");
        assertThat(three.cells().get(1)).isEqualTo("Ann");
        verify(users).findAllByUserIdInAndTenantIdAndDeletedFalse(org.mockito.ArgumentMatchers.<Collection<UUID>>argThat(c -> c.size() == 2), any());
    }

    @Test
    void aReferenceToSomeoneWhoIsGoneOrInAnotherTenantIsEmpty_neverARawId() {
        UUID stranger = UUID.randomUUID();
        when(users.findAllByUserIdInAndTenantIdAndDeletedFalse(any(), any())).thenReturn(List.of());
        ReportRow r = row("A", stranger.toString());

        resolver.resolve(List.of(r), List.of(title, manager), access, tenant);

        assertThat(r.cells().get(1)).isEqualTo("");
    }

    @Test
    void textThatIsNotAnIdIsLeftAlone_andNothingIsLookedUpWhenThereIsNoReferenceColumnOrNoValue() {
        ReportRow notAnId = row("A", "Alice (contractor)");
        ReportRow empty = row("B", (Object) null);

        resolver.resolve(List.of(notAnId, empty), List.of(title, manager), access, tenant);
        resolver.resolve(List.of(row("C")), List.of(title), access, tenant);

        assertThat(notAnId.cells().get(1)).isEqualTo("Alice (contractor)");
        assertThat(empty.cells().get(1)).isNull();
        verify(users, never()).findAllByUserIdInAndTenantIdAndDeletedFalse(any(), any());
    }
}
