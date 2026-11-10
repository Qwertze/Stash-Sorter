package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Whether the crafting table is worth another trip for this task (an order, or compaction), and how many shulker boxes
 * were crafted in the last hour. A table that could not be reached or opened, gave no result, or never gave the result
 * up is remembered as trouble for the task and that table: later crafts of the task are refused at once with the same
 * reason. A table that closed on the bot twice in a row counts too. A new task or another table starts afresh. Bot tick
 * thread for the notes; the hourly count is also read by the command thread, so every method is synchronized (1.8.1). Modelled
 * on {@link AnvilHealth}.
 */
public final class CraftHealth {
    public static final int CLOSED_IN_A_ROW = 2;
    public static final long HOUR_MS = 60 * 60 * 1000L;

    private @Nullable String failure;
    private @Nullable String task;
    private @Nullable Object table;
    private int closed;
    private @Nullable String closedTask;
    private @Nullable Object closedTable;
    private final Deque<long[]> crafted = new ArrayDeque<>();

    public synchronized void note(@Nullable String taskId, @Nullable Object tableSpot, CraftTrip.Outcome o) {
        if (!Objects.equals(taskId, closedTask) || !Objects.equals(tableSpot, closedTable)) {
            closed = 0;
            closedTask = taskId;
            closedTable = tableSpot;
        }
        if (o.done()) {
            closed = 0;
            return;
        }
        String why = o.failure();
        boolean trouble = o.tableBad() || CraftTrip.NO_RESULT.equals(why) || CraftTrip.NOT_TAKEN.equals(why);
        if (CraftTrip.CLOSED.equals(why)) trouble = ++closed >= CLOSED_IN_A_ROW;
        if (!trouble) return;
        failure = why;
        task = taskId;
        table = tableSpot;
    }

    /** Forgets the table trouble remembered (the website's table is tried once more before it is given up on). */
    public synchronized void forget() {
        failure = null;
        task = null;
        table = null;
    }

    /** The table trouble remembered for this task and table, or null. */
    public synchronized @Nullable String problem(@Nullable String taskId, @Nullable Object tableSpot) {
        return failure != null && Objects.equals(taskId, task) && Objects.equals(tableSpot, table) ? failure : null;
    }

    /** {@code n} were crafted at {@code now} (milliseconds). */
    public synchronized void crafted(long now, int n) {
        if (n > 0) crafted.addLast(new long[] {now, n});
    }

    /** How many were crafted in the hour before {@code now}. */
    public synchronized int lastHour(long now) {
        while (!crafted.isEmpty() && now - crafted.peekFirst()[0] >= HOUR_MS) crafted.pollFirst();
        int n = 0;
        for (long[] e : crafted) n += (int) e[1];
        return n;
    }
}
