package com.oak.dms.reporting.dto;

import java.util.List;
import java.util.UUID;

/**
 * Who a template is shared with. The list REPLACES the existing one; an empty list makes the template private again.
 *
 * @param principalType user | role | department
 * @param accessLevel   run (default) | edit
 */
public record TemplateShareRequest(List<Grant> grants) {

    public record Grant(String principalType, UUID principalId, String accessLevel) {
    }
}
