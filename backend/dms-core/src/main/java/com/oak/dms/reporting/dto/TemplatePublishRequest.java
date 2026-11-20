package com.oak.dms.reporting.dto;

import java.util.List;

/**
 * Publishes a template as the organisation's own.
 *
 * @param locked              people may run it but not change its columns or grouping
 * @param userEditableFilters for a locked template: the filter fields (catalogue keys) a person running it may still change
 * @param audience            who sees it; empty = everyone who may run reports. Run access only: nobody edits an official template
 *                            but its administrators.
 */
public record TemplatePublishRequest(boolean locked, List<String> userEditableFilters, List<TemplateShareRequest.Grant> audience) {
}
