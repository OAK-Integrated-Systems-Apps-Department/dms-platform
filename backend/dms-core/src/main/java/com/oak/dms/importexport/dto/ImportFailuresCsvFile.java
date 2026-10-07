package com.oak.dms.importexport.dto;

/** A bulk import's failure report as a CSV file: its download name and its bytes (UTF-8, with a BOM). */
public record ImportFailuresCsvFile(String filename, byte[] content) {
}
