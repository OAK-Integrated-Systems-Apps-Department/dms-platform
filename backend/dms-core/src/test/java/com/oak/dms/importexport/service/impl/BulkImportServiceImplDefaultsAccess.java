package com.oak.dms.importexport.service.impl;

import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;

import java.util.Map;

/** Test-only bridge to the package-private {@link BulkImportServiceImpl#withDefaults}. */
public final class BulkImportServiceImplDefaultsAccess {

    private BulkImportServiceImplDefaultsAccess() {
    }

    public static ParsedRow withDefaults(ParsedRow row, Map<String, String> defaults) {
        return BulkImportServiceImpl.withDefaults(row, defaults);
    }
}
