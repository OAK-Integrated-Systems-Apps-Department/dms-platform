package com.oak.dms.reporting.template;

import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.dto.ReportDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading and writing a template's JSON. {@link #CURRENT_VERSION} is stored beside it, so a definition saved by an older release is
 * upgraded when it is read - the stored row is never rewritten behind anyone's back. Version 1 is today's shape; when the shape
 * changes, add a step to {@link #upgrade} and raise the number.
 * <p>
 * Also decides which fields of a stored definition are no longer usable (an administrator switched them off), so a template can warn
 * about them and still run without them.
 */
public final class TemplateDefinitions {

    public static final int CURRENT_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TemplateDefinitions() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toStored(ReportDefinition definition) {
        return JSON.convertValue(definition, Map.class);
    }

    /** The definition at the current shape; a row saved by a NEWER release than this one is refused rather than guessed at. */
    public static ReportDefinition load(Map<String, Object> stored, int version) {
        if (version > CURRENT_VERSION) {
            throw new IllegalStateException("Template definition version " + version + " is newer than this release supports ("
                    + CURRENT_VERSION + ")");
        }
        Map<String, Object> current = upgrade(stored, version);
        return JSON.convertValue(current, ReportDefinition.class);
    }

    /** One step per version bump; version 1 needs none. */
    private static Map<String, Object> upgrade(Map<String, Object> stored, int version) {
        Map<String, Object> current = stored;
        // if (version < 2) { current = v1ToV2(current); }   <- the next shape change goes here
        return current;
    }

    /** The keys the definition uses that the catalogue can no longer serve for that use, in the order they appear. */
    public static List<String> unavailable(ReportDefinition def, EffectiveCatalogue catalogue) {
        Set<String> gone = new LinkedHashSet<>();
        for (ReportDefinition.Filter f : def.filtersOrEmpty()) {
            if (catalogue.find(f.field()).filter(FieldDescriptor::filterable).isEmpty()) {
                gone.add(f.field());
            }
        }
        for (String column : def.columnsOrEmpty()) {
            if (catalogue.find(column).filter(FieldDescriptor::selectable).isEmpty()) {
                gone.add(column);
            }
        }
        for (ReportDefinition.Sort s : def.sortOrEmpty()) {
            if (catalogue.find(s.field()).filter(FieldDescriptor::sortable).isEmpty()) {
                gone.add(s.field());
            }
        }
        if (def.groupBy() != null && !def.groupBy().isBlank()
                && catalogue.find(def.groupBy()).filter(FieldDescriptor::groupable).isEmpty()) {
            gone.add(def.groupBy());
        }
        return new ArrayList<>(gone);
    }

    /** The definition without what {@link #unavailable} lists: what a run actually executes. */
    public static ReportDefinition withoutUnavailable(ReportDefinition def, EffectiveCatalogue catalogue) {
        Set<String> gone = Set.copyOf(unavailable(def, catalogue));
        if (gone.isEmpty()) {
            return def;
        }
        return new ReportDefinition(def.source(),
                def.filtersOrEmpty().stream().filter(f -> !gone.contains(f.field())).toList(),
                def.columnsOrEmpty().stream().filter(c -> !gone.contains(c)).toList(),
                def.sortOrEmpty().stream().filter(s -> !gone.contains(s.field())).toList(),
                def.groupBy() != null && gone.contains(def.groupBy()) ? null : def.groupBy(),
                def.includeInactive(), def.timeZone());
    }
}
