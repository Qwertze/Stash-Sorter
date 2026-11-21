package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A plan cut into runs of consecutive steps made at the same station (B2): a run at the crafting table is one {@link CraftTrip}, a run at the furnace
 * one {@link FurnaceTrip}. The runs are made in order, and what one makes is carried to the next (a glass that is smelted then crafted into panes: a
 * furnace run, then a table run). Only the last run's plan names the item asked for, so only it counts towards what was made. Pure.
 */
public final class CraftSegments {
    private CraftSegments() {
    }

    /** @param furnace the steps are smelted; {@code plan} holds only these steps (its item is "" unless it is the last run's) */
    public record Segment(boolean furnace, CraftPlanner.Plan plan) {
    }

    public static List<Segment> split(CraftPlanner.Plan plan) {
        List<List<CraftPlanner.Step>> runs = new ArrayList<>();
        Boolean station = null;
        for (CraftPlanner.Step s : plan.steps()) {
            if (station == null || station != s.furnace()) {
                runs.add(new ArrayList<>());
                station = s.furnace();
            }
            runs.get(runs.size() - 1).add(s);
        }
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            List<CraftPlanner.Step> steps = runs.get(i);
            boolean last = i == runs.size() - 1;
            boolean furnace = steps.get(0).furnace();
            CraftPlanner.Plan sub = new CraftPlanner.Plan(last ? plan.item() : "", plan.count(), List.copyOf(steps), last ? plan.fromStock() : Map.of(), CraftPlanner.gridFills(steps), null);
            out.add(new Segment(furnace, sub));
        }
        if (out.isEmpty()) out.add(new Segment(false, plan));
        return out;
    }

    /** The plan has a step made in a furnace. */
    public static boolean smelts(CraftPlanner.Plan plan) {
        for (CraftPlanner.Step s : plan.steps()) if (s.furnace()) return true;
        return false;
    }
}
