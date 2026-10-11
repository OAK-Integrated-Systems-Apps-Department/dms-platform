package com.oak.dms.importexport.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One typed cell of a bulk export's metadata table. Each writer renders it
 * for its reader: the xlsx sheet (for people) shows real numbers and dates,
 * option labels and user names; the CSV (for systems) shows plain ISO
 * values, option codes and user IDs.
 */
public sealed interface ExportCell {

    ExportCell BLANK = new Blank();

    /** The CSV form: what a system re-reading the file needs. */
    String csvText();

    record Blank() implements ExportCell {
        @Override
        public String csvText() {
            return "";
        }
    }

    record Text(String value) implements ExportCell {
        @Override
        public String csvText() {
            return value;
        }
    }

    record Number(BigDecimal value) implements ExportCell {
        @Override
        public String csvText() {
            return value.toPlainString();
        }
    }

    record Date(LocalDate value) implements ExportCell {
        @Override
        public String csvText() {
            return value.toString();
        }
    }

    /**
     * A date and time. {@code offset} is null for a metadata datetime,
     * which is stored without one; document timestamps are given in UTC.
     */
    record DateTime(LocalDateTime value, ZoneOffset offset) implements ExportCell {

        public static DateTime utc(OffsetDateTime instant) {
            return new DateTime(instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), ZoneOffset.UTC);
        }

        @Override
        public String csvText() {
            return offset != null
                    ? DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atOffset(offset))
                    : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
        }
    }

    record Bool(boolean value) implements ExportCell {
        @Override
        public String csvText() {
            return String.valueOf(value);
        }
    }

    /**
     * A dropdown or multi_select value. CSV joins the codes with {@code ;},
     * the separator bulk import splits multi_select values on.
     */
    record Options(List<String> codes, List<String> labels) implements ExportCell {
        @Override
        public String csvText() {
            return String.join(";", codes);
        }

        public String labelText() {
            return String.join("; ", labels);
        }
    }

    /** A user_ref value: the user's ID, and their name when it could be looked up. */
    record UserRef(String id, String name) implements ExportCell {
        @Override
        public String csvText() {
            return id;
        }
    }

    static ExportCell text(String value) {
        return value == null || value.isEmpty() ? BLANK : new Text(value);
    }
}
