package com.porter.qwertestash.farm;

import java.util.List;
import java.util.Set;

/** The closed vocabularies of FARM-CONTRACT E3 and TASK "Closed vocabularies for the farm kind". Pure. */
public final class FarmCodes {
    private FarmCodes() {
    }

    /** Codes that end a session as completed. */
    public static final Set<String> COMPLETED = Set.of("time_up");
    /** Codes that end a session as cancelled. */
    public static final Set<String> CANCELLED = Set.of("stopped", "ops_stop", "pause_timeout");
    /** Codes that park a session (task state paused). */
    public static final Set<String> PARKS = Set.of("controller_connected", "disconnected", "pathfinder_busy", "server_unreachable", "lease_lost");
    /** Codes that end a session as failed. */
    public static final Set<String> FAILED = Set.of("player_near", "low_health", "no_food", "no_totem", "drifted", "farm_changed",
        "inventory_full", "item_missing", "not_at_farm", "wrong_dim", "chunk_not_loaded", "module_conflict", "guard_off", "unsafe_logging",
        "unsafe_discord", "unsafe_bridge", "unsafe_rotation", "key_missing", "key_mismatch", "key_invalid", "key_perms", "mode_not_ready",
        "program_missing", "program_mismatch", "unsafe_viewer", "error");

    public static final Set<String> PHASES = Set.of("setup", "loop", "recover", "stop_steps", "parked", "waiting");
    public static final Set<String> CHECKPOINT_STEPS = Set.of("setup", "loop", "recover", "stop_steps", "parked");
    public static final Set<String> CHECKPOINT_KEYS = Set.of("loop", "anchorId", "tick", "recoveries");
    public static final Set<String> RESULT_KEYS = Set.of("loops", "recoveries", "runtimeMs", "endedBy", "gained");
    /** The modes this build runs (FARM A6, Q3). */
    public static final List<String> MODES = List.of("custom", "afk_still");

    public static boolean known(String code) {
        return COMPLETED.contains(code) || CANCELLED.contains(code) || PARKS.contains(code) || FAILED.contains(code);
    }

    /** The task state a code ends or parks a session in: completed, cancelled, paused or failed. */
    public static String stateOf(String code) {
        if (COMPLETED.contains(code)) return "completed";
        if (CANCELLED.contains(code)) return "cancelled";
        if (PARKS.contains(code)) return "paused";
        return "failed";
    }
}
