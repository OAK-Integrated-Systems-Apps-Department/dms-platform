package com.oak.dms.reporting.dto;

/** Creates or replaces a private template: its name, a short description and the definition itself. */
public record TemplateRequest(String name, String description, ReportDefinition definition) {
}
