package com.oak.dms.document;

import java.util.List;

/**
 * Which part of the document lifecycle a listing shows (6.1): the normal listings show only ACTIVE documents
 * (CRM-FR-004); the Archive view shows the inactive and archived ones (CRM-FR-005/009).
 */
public enum DocumentView {
    ACTIVE(List.of(Document.ACTIVE)),
    ARCHIVE(List.of(Document.INACTIVE, Document.ARCHIVED));

    private final List<String> states;

    DocumentView(List<String> states) {
        this.states = states;
    }

    public List<String> states() {
        return states;
    }
}
