package com.oak.dms.reporting.dto;

import java.util.List;

/**
 * One page of the values a choice or reference field can take, for a filter's picker.
 *
 * @param value what a filter sends back: an id for a document type, department or person, the stored text for a status
 * @param label what the person sees
 * @param hasMore whether a further page exists - the picker asks for it as the person scrolls or types, so a tenant with ten thousand
 *                users is never sent them in one answer
 */
public record FieldValuesResponse(List<Value> content, int page, int size, boolean hasMore) {

    public record Value(String value, String label) {
    }
}
