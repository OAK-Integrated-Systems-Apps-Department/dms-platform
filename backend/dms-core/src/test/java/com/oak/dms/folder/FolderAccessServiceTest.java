package com.oak.dms.folder;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.FolderAccessEntry.PrincipalType;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessEntryRequest;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
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

/** The checks folder operations run through, and changing who can get into a folder. */
class FolderAccessServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID COLLEAGUE = UUID.randomUUID();

    private final FolderRepository folders = mock(FolderRepository.class);
    private final FolderAccessRepository entries = mock(FolderAccessRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final AuthorizationService authz = mock(AuthorizationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final FolderAccessService service =
            new FolderAccessService(folders, entries, new AccessPrincipals(userRoles, users, roles), authz, audit,
                    new AccessCache(mock(org.springframework.jdbc.core.JdbcTemplate.class), java.time.Duration.ZERO, 10),
                    mock(org.springframework.context.ApplicationEventPublisher.class));

    private final Folder finance = Folder.builder().folderId(UUID.randomUUID()).tenantId(TENANT)
            .folderName("Finance").accessMode(FolderAccessMode.restricted).build();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, ME);
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(folders.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(TENANT)).thenReturn(List.of(finance));
        when(userRoles.findActiveRoleIds(ME, TENANT)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aHiddenFolder_is404_andViewOnlyAccess_is403ForChanges() {
        when(entries.findForPrincipals(eq(TENANT), eq(ME), anyCollection())).thenReturn(List.of());
        assertThatThrownBy(() -> service.require(finance.getFolderId(), FolderAccessLevel.view))
                .isInstanceOf(ResourceNotFoundException.class);

        when(entries.findForPrincipals(eq(TENANT), eq(ME), anyCollection()))
                .thenReturn(List.of(entry(PrincipalType.user, ME, FolderAccessLevel.view)));
        // A copy (AccessCache never hands out what it holds), of the same folder.
        assertThat(service.require(finance.getFolderId(), FolderAccessLevel.view).getFolderId())
                .isEqualTo(finance.getFolderId());
        assertThatThrownBy(() -> service.require(finance.getFolderId(), FolderAccessLevel.edit))
                .isInstanceOf(FolderException.class).hasMessageContaining("can view \"Finance\" but not change");
    }

    @Test
    void restrictingAFolder_keepsThePersonDoingItAsAManager() {
        Folder open = Folder.builder().folderId(UUID.randomUUID()).tenantId(TENANT).folderName("HR")
                .accessMode(FolderAccessMode.inherit).build();
        when(folders.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(TENANT)).thenReturn(List.of(open));
        when(users.findAllByUserIdInAndTenantIdAndDeletedFalse(anyCollection(), eq(TENANT)))
                .thenReturn(List.of(User.builder().userId(COLLEAGUE).tenantId(TENANT).build()));
        // Once saved, the caller's own manager entry is what lets them read the result back.
        when(entries.findForPrincipals(eq(TENANT), eq(ME), anyCollection())).thenReturn(List.of(
                FolderAccessEntry.builder().tenantId(TENANT).folderId(open.getFolderId()).principalType(PrincipalType.user)
                        .principalId(ME).accessLevel(FolderAccessLevel.manage).build()));

        service.updateAccess(open.getFolderId(), new UpdateFolderAccessRequest("restricted",
                List.of(new FolderAccessEntryRequest("user", COLLEAGUE, "view"))));

        assertThat(savedFolder().getAccessMode()).isEqualTo(FolderAccessMode.restricted);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<FolderAccessEntry>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(entries).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(FolderAccessEntry::getPrincipalId, FolderAccessEntry::getAccessLevel)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(COLLEAGUE, FolderAccessLevel.view),
                        org.assertj.core.groups.Tuple.tuple(ME, FolderAccessLevel.manage));
    }

    @Test
    void openingAFolderToEveryone_dropsItsList_andUnknownModesAreRefused() {
        when(entries.findForPrincipals(eq(TENANT), eq(ME), anyCollection()))
                .thenReturn(List.of(entry(PrincipalType.user, ME, FolderAccessLevel.manage)));

        service.updateAccess(finance.getFolderId(), new UpdateFolderAccessRequest("everyone",
                List.of(new FolderAccessEntryRequest("user", COLLEAGUE, "edit"))));

        assertThat(savedFolder().getAccessMode()).isEqualTo(FolderAccessMode.everyone);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<FolderAccessEntry>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(entries).saveAll(saved.capture());
        assertThat(saved.getValue()).isEmpty();

        assertThatThrownBy(() -> service.updateAccess(finance.getFolderId(),
                new UpdateFolderAccessRequest("secret", List.of())))
                .isInstanceOf(FolderException.class).hasMessageContaining("Unknown access mode");
    }

    /** The folder as saved - the service changes a copy of it, not the repository's own object. */
    private Folder savedFolder() {
        ArgumentCaptor<Folder> saved = ArgumentCaptor.forClass(Folder.class);
        verify(folders).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void onlyManagers_canChangeAccess() {
        when(entries.findForPrincipals(eq(TENANT), eq(ME), anyCollection()))
                .thenReturn(List.of(entry(PrincipalType.user, ME, FolderAccessLevel.edit)));

        assertThatThrownBy(() -> service.updateAccess(finance.getFolderId(),
                new UpdateFolderAccessRequest("everyone", List.of())))
                .isInstanceOf(FolderException.class).hasMessageContaining("Only people who manage");
        verify(folders, never()).save(any());
    }

    private FolderAccessEntry entry(PrincipalType type, UUID principal, FolderAccessLevel level) {
        return FolderAccessEntry.builder().tenantId(TENANT).folderId(finance.getFolderId())
                .principalType(type).principalId(principal).accessLevel(level).build();
    }
}
