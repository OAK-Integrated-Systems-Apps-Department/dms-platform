package com.oak.dms.metadata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayOrderNormalizerTest {

    private static final class MutableRow {
        final String name;
        int order;

        MutableRow(String name, int order) {
            this.name = name;
            this.order = order;
        }
    }

    @Test
    void insertsNewRowAtRequestedPositionAndRestampsGapFree() {
        List<MutableRow> siblings = List.of(new MutableRow("a", 10), new MutableRow("b", 30));
        MutableRow inserted = new MutableRow("new", 20);

        List<MutableRow> touched = DisplayOrderNormalizer.renumber(
                siblings, inserted, 20, r -> r.order, (r, v) -> r.order = v);

        // "new" requested position 20, between a (10) and b (30) -> final
        // sequence should be a=10, new=20, b=30 (no siblings actually move).
        assertThat(inserted.order).isEqualTo(20);
        assertThat(touched).isEmpty();
        assertThat(siblings.get(0).order).isEqualTo(10);
        assertThat(siblings.get(1).order).isEqualTo(30);
    }

    @Test
    void insertingBeforeAllSiblingsShiftsThemAllDown() {
        List<MutableRow> siblings = new ArrayList<>(List.of(
                new MutableRow("a", 10), new MutableRow("b", 20), new MutableRow("c", 30)));
        MutableRow inserted = new MutableRow("new", 0);

        List<MutableRow> touched = DisplayOrderNormalizer.renumber(
                siblings, inserted, 0, r -> r.order, (r, v) -> r.order = v);

        assertThat(inserted.order).isEqualTo(10);
        assertThat(touched).hasSize(3);
        assertThat(siblings.get(0).order).isEqualTo(20);
        assertThat(siblings.get(1).order).isEqualTo(30);
        assertThat(siblings.get(2).order).isEqualTo(40);
    }

    @Test
    void repositioningAnExistingRowNeverProducesADuplicate() {
        // Simulate updateOption: "b" is the row being repositioned, so it's
        // excluded from `siblings` and passed as `changed` with its new
        // requested displayOrder.
        MutableRow a = new MutableRow("a", 10);
        MutableRow b = new MutableRow("b", 20);
        MutableRow c = new MutableRow("c", 30);
        List<MutableRow> siblings = new ArrayList<>(List.of(a, c));

        DisplayOrderNormalizer.renumber(siblings, b, 5, r -> r.order, (r, v) -> r.order = v);

        List<Integer> finalOrders = List.of(b.order, a.order, c.order);
        assertThat(finalOrders).doesNotHaveDuplicates();
        assertThat(finalOrders).containsExactlyInAnyOrder(10, 20, 30);
        // b was requested at the front (5 < a's 10), so it should now sort first.
        assertThat(b.order).isLessThan(a.order);
        assertThat(a.order).isLessThan(c.order);
    }

    @Test
    void onlyTouchedSiblingsAreReturned() {
        List<MutableRow> siblings = List.of(new MutableRow("a", 10), new MutableRow("b", 20));
        MutableRow inserted = new MutableRow("new", 30);

        List<MutableRow> touched = DisplayOrderNormalizer.renumber(
                siblings, inserted, 30, r -> r.order, (r, v) -> r.order = v);

        // Appending at the end doesn't move any existing sibling.
        assertThat(touched).isEmpty();
        assertThat(inserted.order).isEqualTo(30);
    }
}
