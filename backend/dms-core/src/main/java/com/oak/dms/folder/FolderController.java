package com.oak.dms.folder;

import com.oak.dms.folder.dto.FolderDtos.AccessPrincipal;
import com.oak.dms.folder.dto.FolderDtos.CreateFolderRequest;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessResponse;
import com.oak.dms.folder.dto.FolderDtos.FolderPathItem;
import com.oak.dms.folder.dto.FolderDtos.FolderResponse;
import com.oak.dms.folder.dto.FolderDtos.MoveFolderRequest;
import com.oak.dms.folder.dto.FolderDtos.RenameFolderRequest;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The tenant's shared folder tree (Module DSC) - see {@link FolderService}.
 * Browsing needs docm.documents.view; changing the tree needs the matching
 * dsc.manage-folder-structure.* code (seeded by V10). Who can get into a
 * folder (V53, {@link FolderAccessService}) needs the
 * dsc.configure-folder-access-control.* codes - and, on top of every code,
 * the caller's own access level in that folder.
 */
@RestController
@RequestMapping("/api/v1/folders")
@RequiredArgsConstructor
@Tag(name = "Folders", description = "Shared folder tree for organising documents")
public class FolderController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_VIEW = "@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)";
    private static final String CAN_CREATE = "@authz.hasPermission(" + P + "DSC_FOLDERS_CREATE)";
    private static final String CAN_RENAME_MOVE = "@authz.hasPermission(" + P + "DSC_FOLDERS_RENAME_MOVE)";
    private static final String CAN_DELETE = "@authz.hasPermission(" + P + "DSC_FOLDERS_DELETE)";
    private static final String CAN_ASSIGN_ACCESS = "@authz.hasPermission(" + P + "DSC_FOLDER_ACCESS_ASSIGN)";
    private static final String CAN_VIEW_ACCESS = "@authz.hasPermission(" + P + "DSC_FOLDER_ACCESS_VIEW)";

    private final FolderService folderService;
    private final FolderAccessService folderAccessService;

    @GetMapping
    @PreAuthorize(CAN_VIEW + " or " + CAN_CREATE)
    @Operation(summary = "Every folder of the tenant, flat, with its parent and document count")
    public List<FolderResponse> list() {
        return folderService.listAll();
    }

    @GetMapping("/shared-with-me")
    @PreAuthorize(CAN_VIEW)
    @Operation(summary = "Restricted folders shared with the caller by name or role (not ones they created)")
    public List<FolderResponse> sharedWithMe() {
        return folderService.sharedWithMe();
    }

    @GetMapping("/{folderId}/path")
    @PreAuthorize(CAN_VIEW + " or " + CAN_CREATE)
    @Operation(summary = "The folder's path from the top level down (breadcrumb)")
    public List<FolderPathItem> path(@PathVariable UUID folderId) {
        return folderService.path(folderId);
    }

    @PostMapping
    @PreAuthorize(CAN_CREATE)
    @Operation(summary = "Create a folder (top-level, or inside parentFolderId)")
    public ResponseEntity<FolderResponse> create(@Valid @RequestBody CreateFolderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(folderService.create(request.name(), request.parentFolderId()));
    }

    @PatchMapping("/{folderId}")
    @PreAuthorize(CAN_RENAME_MOVE)
    @Operation(summary = "Rename a folder")
    public FolderResponse rename(@PathVariable UUID folderId, @Valid @RequestBody RenameFolderRequest request) {
        return folderService.rename(folderId, request.name());
    }

    @PostMapping("/{folderId}/move")
    @PreAuthorize(CAN_RENAME_MOVE)
    @Operation(summary = "Move a folder under another one (parentFolderId null = top level)")
    public FolderResponse move(@PathVariable UUID folderId, @RequestBody MoveFolderRequest request) {
        return folderService.move(folderId, request.parentFolderId());
    }

    @DeleteMapping("/{folderId}")
    @PreAuthorize(CAN_DELETE)
    @Operation(summary = "Delete an empty folder")
    public ResponseEntity<Void> delete(@PathVariable UUID folderId) {
        folderService.delete(folderId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{folderId}/access")
    @PreAuthorize(CAN_VIEW_ACCESS + " or " + CAN_ASSIGN_ACCESS)
    @Operation(summary = "Who can get into the folder - its own setting, or the one it inherits")
    public FolderAccessResponse access(@PathVariable UUID folderId) {
        return folderAccessService.getAccess(folderId);
    }

    @PutMapping("/{folderId}/access")
    @PreAuthorize(CAN_ASSIGN_ACCESS)
    @Operation(summary = "Set who can get into the folder: inherit, everyone, or restricted to users and roles")
    public FolderAccessResponse updateAccess(@PathVariable UUID folderId,
                                             @Valid @RequestBody UpdateFolderAccessRequest request) {
        return folderAccessService.updateAccess(folderId, request);
    }

    @GetMapping("/access/principals")
    @PreAuthorize(CAN_ASSIGN_ACCESS)
    @Operation(summary = "Users and roles matching q, to give folder access to")
    public List<AccessPrincipal> principals(@RequestParam(name = "q", required = false) String q) {
        return folderAccessService.searchPrincipals(q);
    }
}
