package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which stack of a written book to take for a copy (B4): the original (generation 0), never a copy of it while an original is in the stash. A copy of a book is
 * made only from an original, and the seal check puts everything back when the stack in the grid is itself a copy, so a gather that took the copy would end the
 * line short with the original still in the stash. A stack whose generation is not known (a stash saved before B4, -1) is neither preferred nor avoided. Pure.
 */
public final class GenerationPref {
    private GenerationPref() {
    }

    /** Added to the score of a stack that should not be taken while another can be (a copy of a book that is copied). Larger than any other term. */
    public static final long COPY_PENALTY = 100_000_000L;

    /** Whether a generation (as {@link Variants#generationOf}) is that of a copy: 1 or more. */
    public static boolean isCopy(int generation) {
        return generation > 0;
    }

    /** The score penalty of a stack: {@link #COPY_PENALTY} for a copy when {@code bookSource} (the key is a book that is copied), else 0. */
    public static long penalty(boolean bookSource, int generation) {
        return bookSource && isCopy(generation) ? COPY_PENALTY : 0;
    }

    /**
     * The score of a stack for the next load (lower is taken first): a copy of a copied book last, then a stack of another variant than the load names, then a
     * stack with exactly enough or the smallest with more, else the largest short one. The pick is always the lowest score, so a key with copies only still
     * gives one.
     */
    public static long score(boolean bookSource, int generation, boolean sameVariant, int count, int want, int maxStack) {
        return penalty(bookSource, generation) + (sameVariant ? 0 : 1_000_000L) + (count >= want ? count - want : 10_000L + (maxStack - count));
    }

    /** The candidates that hold an original when any does, else all of them (the order is kept). */
    public static <T> List<T> prefer(List<T> cands, Predicate<T> holdsOriginal) {
        List<T> originals = new ArrayList<>();
        for (T c : cands) if (holdsOriginal.test(c)) originals.add(c);
        return originals.isEmpty() ? cands : originals;
    }
}
