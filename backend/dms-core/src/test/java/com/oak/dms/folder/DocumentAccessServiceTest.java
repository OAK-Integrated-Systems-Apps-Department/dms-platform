package com.oak.dms.folder;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessEntry.PrincipalType;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessEntryRequest;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Per-document access: its own list overrides the folder, admins see everything, restricting keeps you a manager. */
class DocumentAccessServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID COLLEAGUE = UUID.randomUUID();

    private final FolderAccessService folderAccess = mock(FolderAccessService.class);
    private final DocumentAccessRepository entries = mock(DocumentAccessRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final DocumentAccessService service = new DocumentAccessService(folderAccess, entries, documents,
            new AccessPrincipals(userRoles, users, mock(RoleRepository.class)), audit);

    private final Folder open = Folder.builder().folderId(UUID.randomUUID()).tenantId(TENANT).folderName("Open")
            .accessMode(FolderAccessMode.inherit).build();
    private final FolderAccessView folders = new FolderAccessView(List.of(open), Map.of(), false);

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, ME);
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(folderAccess.forCaller()).thenReturn(folders);
        when(documents.existsByTenantIdAndAccessModeAndDeletedFalse(TENANT, FolderAccessMode.restricted)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aRestrictedDocument_usesOnlyItsOwnList_whateverItsFolderSays() {
        Document mine = document(FolderAccessMode.restricted);
        Document theirs = document(FolderAccessMode.restricted);
        Document follows = document(FolderAccessMode.inherit);
        DocumentAccessView view = new DocumentAccessView(folders,
                Map.of(mine.getDocumentId(), FolderAccessLevel.edit), false, true);

        assertThat(view.level(mine)).isEqualTo(FolderAccessLevel.edit);
        assertThat(view.canSee(theirs)).isFalse();
        assertThat(view.level(follows)).isEqualTo(FolderAccessLevel.manage); // its folder is open
        assertThat(view.seesEverything()).isFalse();
        assertThat(new DocumentAccessView(folders, Map.of(), true, true).level(theirs))
                .isEqualTo(FolderAccessLevel.manage);

        assertThatThrownBy(() -> service.require(view, theirs, FolderAccessLevel.view))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.require(view, mine, FolderAccessLevel.manage))
                .isInstanceOf(FolderException.class).hasMessageContaining("Only people who manage");
    }

    @Test
    void restrictingADocument_keepsThePersonDoingItAsAManager_andOpenToEveryoneIsRefused() {
        Document doc = document(FolderAccessMode.inherit);
        when(documents.findByDocumentIdAndTenantIdAndDeletedFalse(doc.getDocumentId(), TENANT)).thenReturn(Optional.of(doc));
        when(users.findAllByUserIdInAndTenantIdAndDeletedFalse(anyCollection(), eq(TENANT)))
                .thenReturn(List.of(User.builder().userId(COLLEAGUE).tenantId(TENANT).build()));
        // After saving, the caller's manager entry lets them read the result back.
        when(entries.findForPrincipals(eq(TENANT), eq(ME), anyCollection())).thenReturn(List.of(
                DocumentAccessEntry.builder().tenantId(TENANT).documentId(doc.getDocumentId())
                        .principalType(PrincipalType.user).principalId(ME).accessLevel(FolderAccessLevel.manage).build()));

        assertThatThrownBy(() -> service.updateAccess(doc.getDocumentId(),
                new UpdateFolderAccessRequest("everyone", List.of())))
                .isInstanceOf(FolderException.class).hasMessageContaining("follows its folder");
        verify(documents, never()).save(any());

        service.updateAccess(doc.getDocumentId(), new UpdateFolderAccessRequest("restricted",
                List.of(new FolderAccessEntryRequest("user", COLLEAGUE, "view"))));

        assertThat(doc.getAccessMode()).isEqualTo(FolderAccessMode.restricted);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<DocumentAccessEntry>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(entries).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(DocumentAccessEntry::getPrincipalId, DocumentAccessEntry::getAccessLevel)
                .containsExactlyInAnyOrder(Tuple.tuple(COLLEAGUE, FolderAccessLevel.view),
                        Tuple.tuple(ME, FolderAccessLevel.manage));
    }

    private Document document(FolderAccessMode mode) {
        return Document.builder().documentId(UUID.randomUUID()).tenantId(TENANT).title("Payroll")
                .folderId(open.getFolderId()).accessMode(mode).build();
    }
}
