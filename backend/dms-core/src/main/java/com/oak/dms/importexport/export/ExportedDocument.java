package com.oak.dms.importexport.export;

/** A document that made it into the ZIP, and the entry name it got there. */
public record ExportedDocument(ExportableDocumentRow row, String zipEntryName) {
}
