package com.oak.dms.folder;

/**
 * What someone may do inside a folder, lowest first. Permission codes still
 * apply on top - a level only decides which folders the codes work in.
 */
public enum FolderAccessLevel {
    /** The folder is hidden from them. */
    none,
    /** Browse, download and export. */
    view,
    /** Also upload, move documents in and out, create and rename subfolders. */
    edit,
    /** Also move or delete the folder and change who can get in. */
    manage;

    public boolean atLeast(FolderAccessLevel other) {
        return compareTo(other) >= 0;
    }
}
