package com.oak.dms.folder;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.folder.dto.FolderDtos.FolderPathItem;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The folder tree's rules: unique sibling names, no cycles, only empty folders deleted, paths top-down. */
class FolderServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final FolderRepository repository = mock(FolderRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final FolderAccessService access = mock(FolderAccessService.class);
    private final DocumentAccessService documentAccess = mock(DocumentAccessService.class);
    private final FolderService service = new FolderService(repository, audit, access, documentAccess,
            mock(AccessPrincipals.class));

    private final Folder finance = folder("Finance", null);
    private final Folder invoices = folder("Invoices", finance.getFolderId());
    private final Folder y2026 = folder("2026", invoices.getFolderId());

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, UUID.randomUUID());
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        // Every folder open to the caller - access itself is covered by FolderAccessViewTest/FolderAccessServiceTest.
        when(access.forCaller()).thenAnswer(call -> new FolderAccessView(List.of(finance, invoices, y2026), java.util.Map.of(), false));
        when(documentAccess.forCaller()).thenAnswer(call -> new DocumentAccessService.DocumentAccessView(
                access.forCaller(), java.util.Map.of(), false, false));
        for (Folder f : List.of(finance, invoices, y2026)) {
            when(repository.findByFolderIdAndTenantIdAndDeletedFalse(f.getFolderId(), TENANT)).thenReturn(Optional.of(f));
        }
        when(repository.findByTenantIdAndParentFolderIdIsNullAndDeletedFalse(TENANT)).thenReturn(List.of(finance));
        when(repository.findByTenantIdAndParentFolderIdAndDeletedFalse(TENANT, finance.getFolderId())).thenReturn(List.of(invoices));
        when(repository.findByTenantIdAndParentFolderIdAndDeletedFalse(TENANT, invoices.getFolderId())).thenReturn(List.of(y2026));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            Folder f = call.getArgument(0);
            if (f.getFolderId() == null) {
                f.setFolderId(UUID.randomUUID());
            }
            return f;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void create_trimsTheName_andRefusesASiblingOfTheSameNameIgnoringCase() {
        assertThat(service.create("  HR  ", null).name()).isEqualTo("HR");

        assertThatThrownBy(() -> service.create("finance", null))
                .isInstanceOf(FolderException.class).hasMessageContaining("already a folder called \"Finance\"");
        // The same name is fine under a different parent.
        assertThat(service.create("Finance", invoices.getFolderId()).parentFolderId()).isEqualTo(invoices.getFolderId());
    }

    @Test
    void names_withPathCharactersOrBlank_areRefused() {
        for (String bad : new String[]{"a/b", "a\\b", "..", "  ", "x:y", "what?"}) {
            assertThatThrownBy(() -> FolderService.validName(bad)).as(bad).isInstanceOf(FolderException.class);
        }
        assertThat(FolderService.validName("Q3 - Board (final)")).isEqualTo("Q3 - Board (final)");
    }

    @Test
    void move_intoItselfOrBelowItself_isRefused() {
        when(repository.findSubtreeIds(TENANT, finance.getFolderId()))
                .thenReturn(List.of(finance.getFolderId(), invoices.getFolderId(), y2026.getFolderId()));

        assertThatThrownBy(() -> service.move(finance.getFolderId(), y2026.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("into itself");
    }

    @Test
    void move_toTheTopLevel_checksNamesThere() {
        Folder other = folder("Invoices", null);
        when(repository.findByTenantIdAndParentFolderIdIsNullAndDeletedFalse(TENANT)).thenReturn(List.of(finance, other));

        assertThatThrownBy(() -> service.move(invoices.getFolderId(), null))
                .isInstanceOf(FolderException.class).hasMessageContaining("already a folder called \"Invoices\"");
    }

    @Test
    void delete_onlyAnEmptyFolder() {
        when(repository.existsByTenantIdAndParentFolderIdAndDeletedFalse(TENANT, invoices.getFolderId())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(invoices.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("has subfolders");

        when(repository.countDocuments(TENANT, y2026.getFolderId())).thenReturn(3L);
        when(repository.countActiveDocuments(TENANT, y2026.getFolderId())).thenReturn(3L);
        assertThatThrownBy(() -> service.delete(y2026.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("holds 3 documents");

        // Only inactive or archived documents left: hidden from the folder's list, but they would be orphaned - and it says so.
        when(repository.countActiveDocuments(TENANT, y2026.getFolderId())).thenReturn(0L);
        assertThatThrownBy(() -> service.delete(y2026.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("holds 3 inactive or archived documents")
                .hasMessageContaining("Inactive & Archive");

        when(repository.countDocuments(TENANT, y2026.getFolderId())).thenReturn(0L);
        when(repository.countPendingUploads(TENANT, y2026.getFolderId())).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(y2026.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("still being uploaded");
        verify(repository, never()).save(any());

        when(repository.countPendingUploads(TENANT, y2026.getFolderId())).thenReturn(0L);
        service.delete(y2026.getFolderId());
        assertThat(y2026.isDeleted()).isTrue();
        verify(audit).event("FOLDER_DELETED");
    }

    @Test
    void path_runsFromTheTopLevelDown() {
        assertThat(service.path(y2026.getFolderId())).extracting(FolderPathItem::name)
                .containsExactly("Finance", "Invoices", "2026");
    }

    private static Folder folder(String name, UUID parent) {
        return Folder.builder().folderId(UUID.randomUUID()).tenantId(TENANT).folderName(name).parentFolderId(parent).build();
    }
}
