package com.oak.dms.document.dto;

import java.util.Map;
import java.util.Set;

/**
 * {@code GET/PUT /documents/{id}/metadata} response. {@code values} is the
 * same field_key -> value shape the endpoint always returned; {@code
 * needsReview} is the set of field keys whose current value is an
 * OCR-sourced, low-confidence extraction (see
 * {@code DocumentMetadataValue#needsReview}) -- the frontend uses this to
 * show a review badge next to those fields rather than presenting them as
 * indistinguishable from a confident value. A field key absent from both
 * has no saved value at all.
 */
public record DocumentMetadataResponse(Map<String, Object> values, Set<String> needsReview) {
}
