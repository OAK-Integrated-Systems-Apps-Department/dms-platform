package com.oak.dms.reporting.format;

import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a typed cell into the text a person reads, using the organisation's date and number patterns, the report's time zone and
 * locale. One instance per report run: {@link DecimalFormat} is not thread-safe, so an instance is not shared.
 * <p>
 * Exporters that write native cells (Excel dates and numbers) use the typed value and this only for the text forms (CSV, PDF,
 * the preview).
 */
public final class ReportValueFormatter {

    private static final String DEFAULT_DATE = "yyyy-MM-dd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String datePattern;
    private final DateTimeFormatter date;
    private final DateTimeFormatter dateTime;
    private final DecimalFormat decimal;
    private final DecimalFormat integer;
    private final ZoneId zone;

    public ReportValueFormatter(EffectiveReportSettings settings, ZoneId zone) {
        this(settings.dateFormat(), settings.numberFormat(), settings.defaultLocale(), zone);
    }

    public ReportValueFormatter(String datePattern, String numberPattern, String localeTag, ZoneId zone) {
        Locale locale = localeTag == null || localeTag.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(localeTag);
        String dp = datePattern == null || datePattern.isBlank() ? DEFAULT_DATE : datePattern;
        this.datePattern = dp;
        this.date = DateTimeFormatter.ofPattern(dp, locale);
        this.dateTime = DateTimeFormatter.ofPattern(dp + " HH:mm", locale);
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        this.decimal = new DecimalFormat(numberPattern == null || numberPattern.isBlank() ? "#,##0.##" : numberPattern, symbols);
        this.integer = new DecimalFormat("#,##0", symbols);
        this.zone = zone;
    }

    /** An instant in the report's zone with the zone's name, as the header prints "Generated at". */
    public String timestampWithZone(OffsetDateTime when) {
        return dateTime.format(when.atZoneSameInstant(zone)) + " (" + zone.getId() + ")";
    }

    /** The patterns, for writing native Excel dates and numbers that look the way the organisation chose. */
    public String datePattern() {
        return datePattern;
    }

    public String dateTimePattern() {
        return datePattern + " HH:mm";
    }

    public String decimalPattern() {
        return decimal.toPattern();
    }

    public ZoneId zone() {
        return zone;
    }

    /** @return "" for no value, never null */
    public String display(Column column, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof OffsetDateTime t) {
            return dateTime.format(t.atZoneSameInstant(zone));
        }
        if (value instanceof LocalDate d) {
            return date.format(d);
        }
        if (value instanceof BigDecimal n) {
            // Built-in counts (pages, bytes, version) are whole numbers; a custom number field follows the tenant's pattern.
            return column.key().startsWith("meta.") ? decimal.format(n) : integer.format(n);
        }
        if (value instanceof Boolean b) {
            return b ? "Yes" : "No";
        }
        String text = value.toString();
        return column.multi() ? list(text) : text;
    }

    /** A multi-select value is stored as a JSON list; show its entries separated by commas. */
    private static String list(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isArray()) {
                return json;
            }
            List<String> items = new ArrayList<>();
            node.forEach(n -> items.add(n.asText()));
            return String.join(", ", items);
        } catch (RuntimeException notJson) {
            return json;
        }
    }
}
