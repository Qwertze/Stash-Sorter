package com.porter.qwertestash.farm;

import com.aquarius.command.api.CommandSources;
import com.porter.qwertestash.manager.DashboardCommandSource;

/**
 * Which kind of source a command came from, for the commands that only the proxy terminal may run (C4: {@code stash farm accept} and
 * the rest of {@code stash farm}). The dashboard, Discord, the console route, {@code cmd/invoke} and Bridge are all "not the terminal".
 * Pure.
 */
public final class FarmCommandSupport {
    private FarmCommandSupport() {
    }

    public static AcceptSwitch.Source sourceOf(Object source) {
        if (source == CommandSources.TERMINAL) return AcceptSwitch.Source.TERMINAL;
        if (source == CommandSources.DISCORD) return AcceptSwitch.Source.DISCORD;
        if (source instanceof DashboardCommandSource) return AcceptSwitch.Source.DASHBOARD;
        return AcceptSwitch.Source.OTHER;
    }

    /** Where the viewer-control switch is read from (tests replace it). While it is on, anyone on loopback runs commands as the terminal. */
    public static volatile java.util.function.BooleanSupplier viewerControl = ProxyFarmView::viewerControlNow;

    /** {@code stash farm ...}: terminal only, and not while the viewer API can run commands as the terminal (defence in depth). */
    public static boolean farmAllowed(Object source) {
        return sourceOf(source) == AcceptSwitch.Source.TERMINAL && !viewerControl.getAsBoolean();
    }

    /** {@code stash tasks stop}: not from the dashboard (the terminal, Discord and in game may stop). */
    public static boolean stopAllowed(Object source) {
        return sourceOf(source) != AcceptSwitch.Source.DASHBOARD;
    }

    /** {@code stash tasks resume}: the terminal or Discord, never the dashboard (TASK). */
    public static boolean resumeAllowed(Object source) {
        AcceptSwitch.Source s = sourceOf(source);
        return (s == AcceptSwitch.Source.TERMINAL || s == AcceptSwitch.Source.DISCORD) && !viewerControl.getAsBoolean();
    }
}
