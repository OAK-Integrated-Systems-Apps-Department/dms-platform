package com.oak.dms.folder;

/** How a folder decides who can get into it (folders.access_mode, V53). */
public enum FolderAccessMode {
    /** Same as the parent folder; a top-level folder that inherits is open to everyone. */
    inherit,
    /** Everyone in the tenant (their permission codes still apply). */
    everyone,
    /** Only the users and roles listed in folder_access. */
    restricted
}
