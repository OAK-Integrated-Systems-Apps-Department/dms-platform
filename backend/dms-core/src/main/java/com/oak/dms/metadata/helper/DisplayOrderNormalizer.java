package com.oak.dms.metadata.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;

/**
 * Re-stamps a sibling set's displayOrder as a canonical, gap-free
 * {@code (index+1)*10} sequence whenever a single-item create/update inserts
 * or repositions one row among them. The client's requested displayOrder is
 * treated as a positional hint (insert before the first sibling whose
 * current order is >= it), not a literal stored value - this makes a stored
 * duplicate structurally impossible without rejecting the request, which is
 * the right tradeoff for single-item CRUD screens that have no way to show
 * the caller a numeric collision the way a bulk reorder screen can.
 */
// public for internal cross-package wiring within metadata (called from
// service/impl/*ServiceImpl), not external API.
public final class DisplayOrderNormalizer {

    private DisplayOrderNormalizer() {
    }

    /**
     * @param siblings           the other rows in the same scope (e.g. same
     *                           field, same tenant), already excluding
     *                           {@code changed}, in any order
     * @param changed            the row being created or repositioned - not
     *                           yet present in {@code siblings}
     * @param requestedOrder     the displayOrder value the caller submitted
     *                           for {@code changed}, used only as a sort cursor
     * @return the subset of {@code siblings} whose displayOrder actually
     *         changed as a result of re-stamping (excludes {@code changed}
     *         itself, which the caller always persists separately)
     */
    public static <T> List<T> renumber(List<T> siblings, T changed, int requestedOrder,
                                 Function<T, Integer> getOrder, ObjIntConsumer<T> setOrder) {
        List<T> ordered = new ArrayList<>(siblings);
        ordered.sort((a, b) -> Integer.compare(getOrder.apply(a), getOrder.apply(b)));

        int insertAt = 0;
        while (insertAt < ordered.size() && getOrder.apply(ordered.get(insertAt)) < requestedOrder) {
            insertAt++;
        }
        ordered.add(insertAt, changed);

        List<T> touchedSiblings = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            T item = ordered.get(i);
            int newOrder = (i + 1) * 10;
            if (item == changed) {
                setOrder.accept(item, newOrder);
                continue;
            }
            if (getOrder.apply(item) != newOrder) {
                setOrder.accept(item, newOrder);
                touchedSiblings.add(item);
            }
        }
        return touchedSiblings;
    }
}
