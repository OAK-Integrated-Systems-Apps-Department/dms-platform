package com.oak.dms.reporting.export;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes one report format. Rows are pulled from a cursor and written as they arrive - an exporter never holds the result set -
 * to a file the caller created and deletes (see {@code TempFiles}).
 */
public interface ReportExporter {

    /** csv, xlsx or pdf - the value of {@code report_runs.format}. */
    String format();

    String contentType();

    String extension();

    void write(ReportFileSpec spec, ReportFileSpec.RowCursor rows, Path target) throws IOException;
}
