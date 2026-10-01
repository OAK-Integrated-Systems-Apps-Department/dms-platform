package com.oak.dms.importexport.dto;

public record ImportTemplateFile(
        String filename,
        byte[] content
) {
}
