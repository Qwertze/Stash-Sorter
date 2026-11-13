package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which carried stacks are the bases the order's craft made for its enchant plans (ENCHANT-CONTRACT draft 3.4). The flow knows how many made bases wait
 * (per plain item id, {@link OrderFlow#madeBases}); the host knows the crafted stacks it set aside, by slot. A crafted stack counts only while it is still a
 * fresh item of the plain id (no components): a slot whose base was used up and now holds the enchanted result is stale and never claimed. Those stacks are
 * never packed into a shulker, never used as a gathered base and never put back by an item; the claim is what the host keeps out of its other matching. Pure.
 */
public final class BaseSlots {
    private BaseSlots() {
    }

    /** A crafted stack the host set aside: its slot, the namespaced item id and whether it is still a plain fresh stack of it. */
    public record Stack(int slot, String id, boolean fresh) {
    }

    /** The slots of the stacks that are made bases: at most as many per id as {@code made} says, fresh stacks only, lowest slots first. */
    public static List<Integer> claim(Map<String, Integer> made, List<Stack> crafted) {
        Map<String, Integer> left = new HashMap<>(made);
        List<Stack> sorted = new ArrayList<>(crafted);
        sorted.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        List<Integer> out = new ArrayList<>();
        for (Stack s : sorted) {
            if (!s.fresh()) continue;
            int n = left.getOrDefault(s.id(), 0);
            if (n <= 0) continue;
            left.put(s.id(), n - 1);
            out.add(s.slot());
        }
        return out;
    }
}
