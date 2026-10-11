package com.oak.dms.importexport.export;

/** A requested document left out of the ZIP, and why. */
public record SkippedDocument(String title, String reason) {
}
