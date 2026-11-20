package com.oak.dms.folder;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * DOC-14, the service side: the tenant's folder-tree lock is taken first, once, by every structure change and by nothing
 * else, and the tree rules still refuse what they refused before. That the lock really serialises two concurrent changes
 * is proved against PostgreSQL in {@code FolderTreeConcurrencyIT}; mocks cannot show that.
 */
class FolderServiceLockTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final FolderRepository repository = mock(FolderRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final FolderAccessService access = mock(FolderAccessService.class);
    private final DocumentAccessService documentAccess = mock(DocumentAccessService.class);
    private final FolderService service = new FolderService(repository, audit, access, documentAccess,
            mock(AccessPrincipals.class));

    private final Folder finance = folder("Finance", null);
    private final Folder invoices = folder("Invoices", finance.getFolderId());

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, UUID.randomUUID());
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(access.forCaller()).thenAnswer(call -> new FolderAccessView(List.of(finance, invoices), Map.of(), false));
        when(documentAccess.forCaller()).thenAnswer(call -> new DocumentAccessService.DocumentAccessView(
                access.forCaller(), Map.of(), false, false));
        for (Folder f : List.of(finance, invoices)) {
            when(repository.findByFolderIdAndTenantIdAndDeletedFalse(f.getFolderId(), TENANT)).thenReturn(Optional.of(f));
        }
        when(repository.findByTenantIdAndParentFolderIdIsNullAndDeletedFalse(TENANT)).thenReturn(List.of(finance));
        when(repository.findByTenantIdAndParentFolderIdAndDeletedFalse(TENANT, finance.getFolderId())).thenReturn(List.of(invoices));
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

    private static String key(UUID tenant) {
        return FolderService.lockKey(tenant);
    }

    // ---- the lock identity ----

    @Test
    void theLockKeyIsTheNamespaceAndTheTenantAndDiffersPerTenant() {
        UUID other = UUID.randomUUID();

        assertThat(key(TENANT)).isEqualTo("folder-tree:" + TENANT).isEqualTo(key(TENANT));
        assertThat(key(other)).isNotEqualTo(key(TENANT));
        assertThat(FolderService.LOCK_NAMESPACE).isEqualTo("folder-tree:");
    }

    // ---- the lock is first, once, for exactly the three structure changes ----

    @Test
    void createTakesTheLockBeforeItReadsAnyFolder() {
        service.create("HR", finance.getFolderId());

        InOrder order = inOrder(repository, access);
        order.verify(repository).lockHierarchy(key(TENANT));
        order.verify(access).require(finance.getFolderId(), FolderAccessLevel.edit);
        order.verify(repository).findByFolderIdAndTenantIdAndDeletedFalse(finance.getFolderId(), TENANT);
        order.verify(repository).saveAndFlush(any());
        verify(repository, times(1)).lockHierarchy(anyString());
    }

    @Test
    void moveTakesTheLockBeforeItReadsAnyFolderAndBeforeTheCycleCheck() {
        Folder sales = folder("Sales", null);
        when(repository.findByFolderIdAndTenantIdAndDeletedFalse(sales.getFolderId(), TENANT)).thenReturn(Optional.of(sales));
        when(repository.findSubtreeIds(TENANT, invoices.getFolderId())).thenReturn(List.of(invoices.getFolderId()));
        when(access.forCaller()).thenAnswer(call -> new FolderAccessView(List.of(finance, invoices, sales), Map.of(), false));

        service.move(invoices.getFolderId(), sales.getFolderId());

        InOrder order = inOrder(repository, access);
        order.verify(repository).lockHierarchy(key(TENANT));
        order.verify(repository).findSubtreeIds(TENANT, invoices.getFolderId());
        order.verify(repository).saveAndFlush(any());
        verify(repository, times(1)).lockHierarchy(anyString());
        assertThat(invoices.getParentFolderId()).isEqualTo(sales.getFolderId());
    }

    @Test
    void deleteTakesTheLockBeforeTheNoSubfoldersCheck() {
        service.delete(invoices.getFolderId());

        InOrder order = inOrder(repository, access);
        order.verify(repository).lockHierarchy(key(TENANT));
        order.verify(access).require(invoices.getFolderId(), FolderAccessLevel.manage);
        order.verify(repository).existsByTenantIdAndParentFolderIdAndDeletedFalse(TENANT, invoices.getFolderId());
        verify(repository, times(1)).lockHierarchy(anyString());
        assertThat(invoices.isDeleted()).isTrue();
    }

    @Test
    void renameAndTheReadOnlyOperationsDoNotTakeTheLock() {
        service.rename(invoices.getFolderId(), "Bills");
        service.path(invoices.getFolderId());
        service.listAll();
        service.require(invoices.getFolderId());
        service.requireLive(invoices.getFolderId());

        verify(repository, never()).lockHierarchy(anyString());
    }

    // ---- failures: the lock never swallows or replaces the rule that refused ----

    @Test
    void aNameThatCannotBeValidIsRefusedBeforeAnyLockIsTaken() {
        assertThatThrownBy(() -> service.create("a/b", null)).isInstanceOf(FolderException.class);

        verify(repository, never()).lockHierarchy(anyString());
    }

    @Test
    void ifTheLockCannotBeTakenNothingElseIsReadOrWritten() {
        when(repository.lockHierarchy(anyString())).thenThrow(new IllegalStateException("connection lost"));

        assertThatThrownBy(() -> service.move(invoices.getFolderId(), null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.create("HR", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.delete(invoices.getFolderId())).isInstanceOf(IllegalStateException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
        verify(repository, never()).findByFolderIdAndTenantIdAndDeletedFalse(any(), any());
        verifyNoMoreInteractions(audit);
    }

    @Test
    void aRuleThatRefusesAfterTheLockLeavesNothingWritten() {
        assertThatThrownBy(() -> service.create("finance", null))                        // name taken
                .isInstanceOf(FolderException.class).hasMessageContaining("already a folder called");
        when(repository.findSubtreeIds(TENANT, finance.getFolderId()))
                .thenReturn(List.of(finance.getFolderId(), invoices.getFolderId()));
        assertThatThrownBy(() -> service.move(finance.getFolderId(), invoices.getFolderId()))   // into its own subtree
                .isInstanceOf(FolderException.class).hasMessageContaining("into itself");
        when(repository.existsByTenantIdAndParentFolderIdAndDeletedFalse(TENANT, finance.getFolderId())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(finance.getFolderId()))                  // has subfolders
                .isInstanceOf(FolderException.class).hasMessageContaining("has subfolders");

        verify(repository, times(3)).lockHierarchy(key(TENANT));
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
    }

    @Test
    void aDeniedCallerIsRefusedAfterTheLockAndWritesNothing() {
        when(access.require(any(), any(FolderAccessLevel.class))).thenThrow(FolderException.conflict("DENIED", "no"));

        assertThatThrownBy(() -> service.delete(invoices.getFolderId())).isInstanceOf(FolderException.class);

        verify(repository, never()).save(any());
    }

    // ---- the tree rules, as before ----

    @Test
    void creatingUnderADeletedOrMissingParentIsRefusedAs404() {
        UUID gone = UUID.randomUUID();      // the lookup filters deleted folders, so a deleted parent is simply not found
        when(repository.findByFolderIdAndTenantIdAndDeletedFalse(gone, TENANT)).thenReturn(Optional.empty());
        when(access.require(gone, FolderAccessLevel.edit)).thenThrow(new ResourceNotFoundException("Folder", gone));

        assertThatThrownBy(() -> service.create("Child", gone)).isInstanceOf(ResourceNotFoundException.class);

        verify(repository).lockHierarchy(key(TENANT));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theDepthLimitStillRefusesACreateAndAMove() {
        List<Folder> chain = new ArrayList<>();
        Folder parent = null;
        for (int i = 1; i <= FolderService.MAX_DEPTH; i++) {
            Folder f = folder("level" + i, parent == null ? null : parent.getFolderId());
            when(repository.findByFolderIdAndTenantIdAndDeletedFalse(f.getFolderId(), TENANT)).thenReturn(Optional.of(f));
            chain.add(f);
            parent = f;
        }
        Folder deepest = chain.get(chain.size() - 1);
        Folder leaf = folder("leaf", null);
        when(repository.findByFolderIdAndTenantIdAndDeletedFalse(leaf.getFolderId(), TENANT)).thenReturn(Optional.of(leaf));
        when(repository.findSubtreeIds(TENANT, leaf.getFolderId())).thenReturn(List.of(leaf.getFolderId()));

        assertThatThrownBy(() -> service.create("one too many", deepest.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("at most 20 deep");
        assertThatThrownBy(() -> service.move(leaf.getFolderId(), deepest.getFolderId()))
                .isInstanceOf(FolderException.class).hasMessageContaining("at most 20 deep");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aNormalCreateMoveAndDeleteStillSucceed() {
        assertThat(service.create("HR", null).name()).isEqualTo("HR");

        Folder sales = folder("Sales", null);
        when(repository.findByFolderIdAndTenantIdAndDeletedFalse(sales.getFolderId(), TENANT)).thenReturn(Optional.of(sales));
        when(repository.findSubtreeIds(TENANT, invoices.getFolderId())).thenReturn(List.of(invoices.getFolderId()));
        when(access.forCaller()).thenAnswer(call -> new FolderAccessView(List.of(finance, invoices, sales), Map.of(), false));
        service.move(invoices.getFolderId(), sales.getFolderId());
        service.delete(invoices.getFolderId());

        assertThat(invoices.isDeleted()).isTrue();
        verify(repository, times(3)).lockHierarchy(key(TENANT));
    }

    // ---- tenants ----

    @Test
    void anotherTenantLocksItsOwnKey() {
        UUID other = UUID.randomUUID();
        TenantContext.set(other, UUID.randomUUID());
        when(repository.findByTenantIdAndParentFolderIdIsNullAndDeletedFalse(other)).thenReturn(List.of());

        service.create("HR", null);

        verify(repository).lockHierarchy(key(other));
        verify(repository, never()).lockHierarchy(key(TENANT));
    }

    private static Folder folder(String name, UUID parent) {
        return Folder.builder().folderId(UUID.randomUUID()).tenantId(TENANT).folderName(name).parentFolderId(parent).build();
    }
}
