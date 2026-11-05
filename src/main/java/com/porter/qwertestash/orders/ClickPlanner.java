package com.porter.qwertestash.orders;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fewest clicks that move an exact number of items between two slots of one kind of item (or empty), under the
 * game's click rules: a left click picks a stack up whole, puts the cursor down whole, or tops a matching stack up to
 * its maximum; a right click picks up the larger half, or puts down one item. A port of the addon's kit builder search.
 * Pure.
 */
public final class ClickPlanner {
    private ClickPlanner() {
    }

    /**
     * Each op is 0 left on the first slot, 1 right on the first, 2 left on the second, 3 right on the second.
     * {@code after[i]} is the cursor, first slot and second slot counts after op i.
     */
    public record Clicks(int[] ops, int[][] after) {
    }

    private static final Map<Integer, Clicks> PLANS = new HashMap<>();
    private static final Clicks UNREACHABLE = new Clicks(new int[0], new int[0][]);

    /**
     * @param max  the stack size of the item
     * @param p0   what the first slot holds (the source)
     * @param q0   what the second slot holds (the destination)
     * @param goal what the second slot is to hold when the cursor is empty again
     * @return the clicks (none when q0 == goal), or null when it can't be done
     */
    public static synchronized Clicks plan(int max, int p0, int q0, int goal) {
        if (max < 1 || max > 99 || p0 < 0 || q0 < 0 || goal < 0 || p0 > max || q0 > max || goal > max) return null;
        int key = (max << 24) | (p0 << 16) | (q0 << 8) | goal;
        Clicks cached = PLANS.get(key);
        if (cached != null) return cached == UNREACHABLE ? null : cached;
        Clicks found = search(max, p0, q0, goal);
        PLANS.put(key, found == null ? UNREACHABLE : found);
        return found;
    }

    private static Clicks search(int max, int p0, int q0, int goal) {
        int total = p0 + q0;
        int w = total + 1;
        int[] prev = new int[w * w];
        int[] how = new int[w * w];
        Arrays.fill(prev, -1);

        int start = p0;
        prev[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            int state = queue.poll();
            int cur = state / w, p = state % w, q = total - cur - p;

            if (cur == 0 && q == goal) {
                List<Integer> ops = new ArrayList<>();
                for (int s = state; s != start; s = prev[s]) ops.add(how[s]);

                int n = ops.size();
                int[] result = new int[n];
                int[][] after = new int[n][];
                int c = 0, pp = p0, qq = q0;
                for (int i = 0; i < n; i++) {
                    result[i] = ops.get(n - 1 - i);
                    int[] r = click(c, result[i] < 2 ? pp : qq, max, result[i] % 2 == 1);
                    c = r[0];
                    if (result[i] < 2) pp = r[1];
                    else qq = r[1];
                    after[i] = new int[] {c, pp, qq};
                }
                return new Clicks(result, after);
            }

            for (int op = 0; op < 4; op++) {
                int[] next = click(cur, op < 2 ? p : q, max, op % 2 == 1);
                if (next == null) continue;

                int nc = next[0], np, nq;
                if (op < 2) {
                    np = next[1];
                    nq = total - nc - np;
                }
                else {
                    nq = next[1];
                    np = total - nc - nq;
                }
                if (np < 0 || nq < 0) continue;

                int ns = nc * w + np;
                if (prev[ns] != -1) continue;
                prev[ns] = state;
                how[ns] = op;
                queue.add(ns);
            }
        }
        return null;
    }

    /** One click on a slot holding {@code x} with {@code cur} on the cursor: {cursor, slot} afterwards, or null if nothing changes. */
    static int[] click(int cur, int x, int max, boolean right) {
        if (cur == 0) {
            if (x == 0) return null;
            int take = right ? (x + 1) / 2 : x;
            return new int[] {take, x - take};
        }

        if (!right) {
            if (x == 0) return new int[] {0, cur};
            int move = Math.min(cur, max - x);
            if (move <= 0) return null;
            return new int[] {cur - move, x + move};
        }

        if (x >= max) return null;
        return new int[] {cur - 1, x + 1};
    }
}
