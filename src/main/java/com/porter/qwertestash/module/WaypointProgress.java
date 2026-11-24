package com.porter.qwertestash.module;

/** Tick-thread progress for one exploration waypoint. A newly selected column starts with fresh counters. */
final class WaypointProgress {
    interface Pathing {
        boolean ownFailureSettled();
        boolean mayRequest();
    }

    enum Decision { WAIT, REQUEST_PATH, TIMED_OUT, NO_PATH, STALLED, UNLOADED }

    private static final int TIMEOUT_TICKS = 20 * 40;
    private static final int STALL_TICKS = 20 * 15;
    private static final int LOAD_GRACE_TICKS = 40;
    private static final int REQUEST_GAP_TICKS = 60;

    private double bestDistance = Double.MAX_VALUE;
    private int activeTicks;
    private int lastProgressTick;
    private int ticksAtWaypoint;
    private long lastRequest = -1;

    /** Called only while the tick driver is actively walking this waypoint. Request spacing uses the bot's clock. */
    Decision advance(long botTick, double distance, Pathing pathing) {
        if (++activeTicks > TIMEOUT_TICKS) return Decision.TIMED_OUT;
        if (lastRequest >= 0 && pathing.ownFailureSettled()) return Decision.NO_PATH;

        if (distance < bestDistance - 1) {
            bestDistance = distance;
            lastProgressTick = activeTicks;
        }
        if (activeTicks - lastProgressTick > STALL_TICKS) return Decision.STALLED;
        if (distance <= 2) {
            return ++ticksAtWaypoint > LOAD_GRACE_TICKS ? Decision.UNLOADED : Decision.WAIT;
        }
        ticksAtWaypoint = 0;
        if (pathing.mayRequest() && (lastRequest < 0 || botTick - lastRequest > REQUEST_GAP_TICKS)) {
            lastRequest = botTick;
            return Decision.REQUEST_PATH;
        }
        return Decision.WAIT;
    }
}
