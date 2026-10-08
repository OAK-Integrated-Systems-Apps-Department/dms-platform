package com.oak.dms.folder.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of /api/v1/folders. */
public final class FolderDtos {

    private FolderDtos() {
    }

    /**
     * One folder; the browser builds the tree from {@code parentFolderId}.
     *
     * @param accessMode the folder's own setting: inherit, everyone or restricted
     * @param restricted whether access is restricted, by this folder or an ancestor
     * @param myAccess   the caller's level here: view, edit or manage
     */
    public record FolderResponse(UUID folderId, UUID parentFolderId, String name, long documentCount,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                 String accessMode, boolean restricted, String myAccess) {
    }

    /**
     * Who can get into a folder. When the folder inherits, {@code inheritedFrom*}
     * names the folder whose setting applies and {@code entries} are that
     * folder's (read-only here).
     *
     * @param effectiveMode everyone or restricted - what actually applies
     * @param canManage     the caller may change it (permission + manage level)
     */
    public record FolderAccessResponse(UUID folderId, String mode, String effectiveMode, UUID inheritedFromFolderId,
                                       String inheritedFromName, List<FolderAccessEntryResponse> entries,
                                       String myAccess, boolean canManage) {
    }

    /**
     * Who can open a document (V54). {@code mode} is inherit (follows its
     * folder - {@code effectiveMode}, {@code entries} and {@code inheritedFrom*}
     * then describe the folder setting that applies) or restricted (its own list).
     */
    public record DocumentAccessResponse(UUID documentId, String mode, String effectiveMode, UUID folderId,
                                         UUID inheritedFromFolderId, String inheritedFromName,
                                         List<FolderAccessEntryResponse> entries, String myAccess,
                                         boolean canManage) {
    }

    /** @param principalType user or role; {@code detail} is a user's email, or "Role" */
    public record FolderAccessEntryResponse(String principalType, UUID principalId, String name, String detail,
                                            String level) {
    }

    /** @param mode inherit, everyone or restricted; entries are kept only for restricted */
    public record UpdateFolderAccessRequest(@NotBlank String mode,
                                            @Size(max = 200) List<@Valid FolderAccessEntryRequest> entries) {
    }

    /** @param level view, edit or manage */
    public record FolderAccessEntryRequest(@NotBlank String principalType, @NotNull UUID principalId,
                                           @NotBlank String level) {
    }

    /** A user or role that can be given access - the picker's search results. */
    public record AccessPrincipal(String principalType, UUID principalId, String name, String detail) {
    }

    /** One step of a folder's path, top-level first (DSC-FR-010). */
    public record FolderPathItem(UUID folderId, String name) {
    }

    /** @param parentFolderId null for a top-level folder */
    public record CreateFolderRequest(@NotBlank @Size(max = 255) String name, UUID parentFolderId) {
    }

    public record RenameFolderRequest(@NotBlank @Size(max = 255) String name) {
    }

    /** @param parentFolderId the new parent; null to make it a top-level folder */
    public record MoveFolderRequest(UUID parentFolderId) {
    }
}
