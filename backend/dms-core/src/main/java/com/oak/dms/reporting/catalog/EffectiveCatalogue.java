package com.oak.dms.reporting.catalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fields one organisation can use right now: built-ins plus its active metadata fields, with the administrator's overrides
 * applied. A field the administrator disabled is simply absent, so it cannot be shown, filtered or sorted by anyone.
 */
public final class EffectiveCatalogue {

    private final Map<String, FieldDescriptor> fields;

    public EffectiveCatalogue(Collection<FieldDescriptor> ordered) {
        Map<String, FieldDescriptor> map = new LinkedHashMap<>();
        ordered.forEach(f -> map.put(f.key(), f));
        this.fields = Map.copyOf(map);
        this.order = List.copyOf(ordered);
    }

    private final List<FieldDescriptor> order;

    public Optional<FieldDescriptor> find(String key) {
        return Optional.ofNullable(key == null ? null : fields.get(key));
    }

    /** In display order. */
    public List<FieldDescriptor> all() {
        return order;
    }

    public boolean contains(String key) {
        return key != null && fields.containsKey(key);
    }
}
