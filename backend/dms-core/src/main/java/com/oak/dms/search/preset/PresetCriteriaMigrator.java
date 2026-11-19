package com.oak.dms.search.preset;

import com.oak.dms.search.api.SearchApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Brings a stored preset's criteria up to the shape this version of the code understands, in memory (a read never
 * rewrites the row). Adding an optional filter later needs no step (still version 1). Renaming or retyping a key adds a
 * step here, bumps {@link PresetCriteria#CURRENT_VERSION}, and adds a stored-v1 fixture to the tests that is kept for
 * good. A stored version higher than this code knows is refused at run time (409), never guessed at, so rolling the
 * application back cannot misread a newer preset.
 */
public final class PresetCriteriaMigrator {

    /** Steps from version n to n+1, index 0 = 1 to 2. Empty today. */
    private static final List<UnaryOperator<Map<String, Object>>> STEPS = new ArrayList<>();

    private PresetCriteriaMigrator() {
    }

    public static boolean isSupported(int storedVersion) {
        return storedVersion >= 1 && storedVersion <= PresetCriteria.CURRENT_VERSION;
    }

    /** @throws SearchApiException 409 PRESET_VERSION_UNSUPPORTED when saved by a newer version */
    public static Map<String, Object> migrate(Map<String, Object> stored, int storedVersion) {
        if (!isSupported(storedVersion)) {
            throw new SearchApiException(HttpStatus.CONFLICT, "PRESET_VERSION_UNSUPPORTED",
                    "This preset was saved by a newer version and cannot be run.");
        }
        Map<String, Object> current = new LinkedHashMap<>(stored);
        for (int v = storedVersion; v < PresetCriteria.CURRENT_VERSION; v++) {
            current = STEPS.get(v - 1).apply(current);
        }
        current.put("schemaVersion", PresetCriteria.CURRENT_VERSION);
        return current;
    }
}
