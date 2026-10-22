package com.oak.dms.relationship.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** One document in a loop chain. A document the caller can't open has no id and a placeholder title. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainItem(UUID documentId, String title) {
    public static final String HIDDEN_TITLE = "A document you can't open";
}
