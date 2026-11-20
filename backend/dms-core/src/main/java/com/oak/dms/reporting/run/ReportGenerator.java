package com.oak.dms.reporting.run;

import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.datasource.ReadOnlySnapshot;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.datasource.ReportQuery;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.export.ReportExporter;
import com.oak.dms.reporting.export.ReportFileSpec;
import com.oak.dms.reporting.format.ReportValueFormatter;
import com.oak.dms.reporting.format.RowResolver;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Produces one report file from a planned query, in a single read-only snapshot: the visible-row count, the row-limit decision,
 * the group counts and the rows all see the same state. Rows are read in chunks and written as they arrive, so memory does not
 * grow with the size of the report.
 */
@Component
public class ReportGenerator {

    static final int FETCH_SIZE = 1000;
    private static final int RESOLVE_BATCH = 500;
    private static final int CANCEL_CHECK_EVERY = 2000;
    private static final int PROGRESS_EVERY = 5000;

    /** @param written rows in the file; 0 with {@code total == 0} means there was nothing to write (no file) */
    public record Outcome(long total, long written, boolean truncated) {
        public boolean empty() {
            return total == 0;
        }
    }

    /** Builds the file's header once the snapshot has said how many rows there are and whether the file is cut short. */
    @FunctionalInterface
    public interface SpecFactory {
        ReportFileSpec create(long rowCount, boolean truncated, long limit, Map<String, Long> groupCounts);
    }

    /** Thrown from inside the stream when the run was cancelled meanwhile. */
    public static final class RunCancelledException extends RuntimeException {
        public RunCancelledException() {
            super("The run was cancelled", null, false, false);
        }
    }

    /** A read failure carried through code that cannot throw {@link SQLException}; the cause stays on the chain. */
    private static final class RowReadException extends RuntimeException {
        RowReadException(SQLException cause) {
            super(cause);
        }
    }

    private final ReadOnlySnapshot snapshot;
    private final RowResolver rowResolver;

    public ReportGenerator(ReadOnlySnapshot snapshot, RowResolver rowResolver) {
        this.snapshot = snapshot;
        this.rowResolver = rowResolver;
    }

    /**
     * @param rowLimit        the most rows this format may hold (the organisation's limit, capped at the platform's)
     * @param truncateOnLimit write the first {@code rowLimit} rows and say so, instead of refusing
     * @param progress        told how many rows have been written, every few thousand
     * @param cancelled       asked every couple of thousand rows; true stops the run
     * @throws ReportException 422 {@code REPORT_ROW_LIMIT_EXCEEDED} (with the visible row count) when the report is over the limit
     * @throws RunCancelledException when {@code cancelled} turned true
     */
    public Outcome generate(ReportQuery query, ReportContext context, ReportExporter exporter, Path target, long rowLimit,
                            boolean truncateOnLimit, Duration timeout, LongConsumer progress, BooleanSupplier cancelled,
                            SpecFactory specs) throws IOException {
        try {
            return snapshot.runStreaming(timeout, FETCH_SIZE, jdbc -> {
                MapSqlParameterSource params = new MapSqlParameterSource(query.params());
                Long counted = jdbc.queryForObject(query.countSql(), params, Long.class);
                long total = counted == null ? 0 : counted;
                if (total == 0) {
                    return new Outcome(0, 0, false);
                }
                boolean truncated = total > rowLimit;
                if (truncated && !truncateOnLimit) {
                    throw ReportException.unprocessable("REPORT_ROW_LIMIT_EXCEEDED",
                            "This report matches " + total + " documents, but a " + exporter.format().toUpperCase()
                                    + " file can hold at most " + rowLimit + ". Narrow the filters and run it again.");
                }
                long written = truncated ? rowLimit : total;

                Map<String, Long> groupCounts = new LinkedHashMap<>();
                if (query.groupCountSql() != null) {
                    ReportQuery.Column group = query.groupColumn().withAlias("g");
                    ReportValueFormatter formatter = specs.create(written, truncated, rowLimit, Map.of()).formatter();
                    jdbc.query(query.groupCountSql(), params, rs -> {
                        Object value = ReportRow.cell(rs, group);
                        groupCounts.merge(formatter.display(group, value), rs.getLong("n"), Long::sum);
                    });
                }
                ReportFileSpec spec = specs.create(written, truncated, rowLimit, groupCounts);
                String sql = query.sql() + (truncated ? " limit " + rowLimit : "");
                jdbc.query(sql, params, (ResultSet rs) -> {
                    try {
                        exporter.write(spec, new BatchCursor(rs, query, context, cancelled, progress), target);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return null;
                });
                return new Outcome(total, written, truncated);
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Reads the result set in batches, finishes each batch (folder paths, names) and hands the rows out one at a time. */
    private final class BatchCursor implements ReportFileSpec.RowCursor {
        private final ResultSet rs;
        private final ReportQuery query;
        private final ReportContext context;
        private final BooleanSupplier cancelled;
        private final LongConsumer progress;
        private final Deque<ReportRow> ready = new ArrayDeque<>();
        private boolean exhausted;
        private long delivered;

        BatchCursor(ResultSet rs, ReportQuery query, ReportContext context, BooleanSupplier cancelled, LongConsumer progress) {
            this.rs = rs;
            this.query = query;
            this.context = context;
            this.cancelled = cancelled;
            this.progress = progress;
        }

        @Override
        public ReportRow next() {
            if (ready.isEmpty() && !exhausted) {
                fill();
            }
            ReportRow row = ready.poll();
            if (row == null) {
                return null;
            }
            delivered++;
            if (delivered % CANCEL_CHECK_EVERY == 0 && cancelled.getAsBoolean()) {
                throw new RunCancelledException();
            }
            if (delivered % PROGRESS_EVERY == 0) {
                progress.accept(delivered);
            }
            return row;
        }

        private void fill() {
            List<ReportRow> batch = new ArrayList<>(RESOLVE_BATCH);
            try {
                while (batch.size() < RESOLVE_BATCH) {
                    if (!rs.next()) {
                        exhausted = true;
                        break;
                    }
                    batch.add(ReportRow.read(rs, query.columns(), query.groupColumn()));
                }
            } catch (SQLException e) {
                throw new RowReadException(e);
            }
            rowResolver.resolve(batch, query.columns(), context.access(), context.tenantId());
            ready.addAll(batch);
        }
    }
}
