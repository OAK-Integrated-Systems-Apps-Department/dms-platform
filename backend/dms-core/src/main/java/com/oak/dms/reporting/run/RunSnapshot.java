package com.oak.dms.reporting.run;

import com.oak.dms.reporting.dto.ReportDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exactly what a run executes: the definition, the time zone it was resolved in and the title on the file. Stored as the run's
 * {@code definition_snapshot}, so a retry runs the very same thing and an old run stays explainable after its template changed.
 */
public record RunSnapshot(ReportDefinition definition, String zone, String title) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("definition", JSON.convertValue(definition, Map.class));
        map.put("zone", zone);
        map.put("title", title);
        return map;
    }

    public static RunSnapshot from(Map<String, Object> map) {
        ReportDefinition definition = JSON.convertValue(map.get("definition"), ReportDefinition.class);
        return new RunSnapshot(definition, (String) map.get("zone"), (String) map.get("title"));
    }
}
