package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a load comes from: loose before shulker, the wanted variant, enough but not much more, then the nearest. */
class SourcePickerTest {
    private static SourcePicker.Cand c(int id, boolean inShulker, boolean same, int count, double d) {
        return new SourcePicker.Cand(id, inShulker, same, count, d);
    }

    @Test
    void aLooseStackBeatsAShulkerEvenWhenFurtherAndWorseFitting() {
        assertEquals(1, SourcePicker.pick(List.of(c(0, true, true, 64, 1), c(1, false, true, 10, 500)), 64));
    }

    @Test
    void theWantedVariantBeatsAnotherOne() {
        assertEquals(0, SourcePicker.pick(List.of(c(0, false, true, 10, 50), c(1, false, false, 64, 1)), 64));
    }

    @Test
    void exactlyEnoughBeatsMuchMoreAndAShortOneIsLast() {
        assertEquals(2, SourcePicker.pick(List.of(c(0, false, true, 640, 1), c(1, false, true, 20, 1), c(2, false, true, 64, 90)), 64));
        assertEquals(0, SourcePicker.pick(List.of(c(0, false, true, 70, 1), c(1, false, true, 20, 1)), 64));
        assertEquals(1, SourcePicker.pick(List.of(c(0, false, true, 5, 1), c(1, false, true, 40, 1)), 64));
    }

    @Test
    void theNearestWinsATie() {
        assertEquals(1, SourcePicker.pick(List.of(c(0, true, true, 64, 30), c(1, true, true, 64, 3)), 64));
    }

    @Test
    void nothingIsMinusOne() {
        assertEquals(-1, SourcePicker.pick(List.of(), 1));
    }
}
