package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.Anvil;
import com.porter.qwerte.core.enchant.McData;
import com.porter.qwerte.core.enchant.Stack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The steps of one enchant item, from the first anvil step to the finished item (ENCHANT-CONTRACT v1.0, "Bot rules" 3 to 6): the gear that
 * would take the XP is put away, then for every step the bot levels up with single bottles ({@link XpLeveler}), walks to the anvil, opens it,
 * puts the left operand in slot 0 (exactly one item) and the right in slot 1, reads the cost and compares it with the plan, takes the result
 * and checks its enchantments. Every step is journalled through {@link Port#stepDone}, so a disconnect carries on at the right step: items are
 * found again by what they are (book or item, enchantments, stored repair cost), and a step whose result is already carried is done.
 *
 * <p>The trip never walks away from a step it began: a player near, or the bot being controlled, only pauses it between steps (never between
 * placing and taking), and a pause past step 1 has no time limit. It ends with an {@link Outcome}: the item is finished, short with a reason,
 * or it needs something (an anvil, bottles) that the host fetches before the trip is made again from the journal. Plain Java behind
 * {@link Port}; one {@link #tick} per game tick, on the bot tick thread.
 */
public final class EnchantTrip {
    /** What the trip needs from the world. Calls that ask for something return false when it can't be asked now. */
    public interface Port extends XpLeveler.Port {
        /** A force-cancel was heard: stop at the next step boundary. */
        boolean forceCancelled();

        /** A player took the bot's controls, or the bot may not work now: the trip waits (between steps only). */
        boolean controlled();

        /** Puts the gear that would take the XP away. 0 working, 1 done (or nothing to do), -1 no room for it. */
        int stowGear();

        /** Puts the gear back. True once everything is back (or could not be and was left in the saved ledger). */
        boolean restoreGear();

        /** The stacks this order carries (its materials, results of earlier steps, spare anvils), by what they are. */
        List<EnchantItems.Held> carried();

        /** The bot's XP level. */
        int level();

        /** Within reach of the anvil. */
        boolean arrived();

        /** Asks to walk to within reach of the anvil. */
        boolean walk();

        /** A walk or open request of ours is still running. */
        boolean pathing();

        /** The anvil block is still standing where it was used (false once it broke, or when it is not known to be there). */
        boolean anvilBlock();

        /** An anvil window of ours is open. */
        boolean anvilOpen();

        /** Some other window of ours is open: it is closed first. */
        boolean otherWindowOpen();

        /** Right-clicks the anvil. */
        boolean open();

        /** The container delay holds the open click back this tick. */
        default boolean openPaused() {
            return false;
        }

        /** The anvil window has just been seen open. */
        default void opened() {
        }

        /** Puts the stack in carry slot {@code left} into anvil slot 0 and the one in {@code right} into slot 1, as one request. */
        boolean place(int left, int right);

        /** Items in anvil slot 0 (0 when it is empty). */
        int leftCount();

        /** Items in anvil slot 1 (0 when it is empty). */
        int rightCount();

        /** The XP cost the anvil showed since the items were put in, or -1 when none was seen. */
        int cost();

        /** The stack in the anvil's result slot (slot number 2 in {@code slot}), or null when it is empty. */
        EnchantItems.@Nullable Held result();

        /** Takes the result into a free carry slot. False: not now. */
        boolean take();

        /** Puts what is in anvil slots 0 and 1 back into the inventory. True once both are empty. */
        boolean retrieve();

        /** Closes our open window; true once nothing of ours is open. */
        boolean close();

        /** A step is done: journalled by the host (saved). {@code shown} is the cost the anvil showed. */
        void stepDone(int step, int shown);

        /** A line for the activity log. */
        default void say(String text) {
        }
    }

    public enum Kind {
        /** The item is finished: {@link Outcome#spec()} says what it is. */
        FINISHED,
        /** The item cannot be finished: short with {@link Outcome#reason()}; the host returns everything. */
        SHORT,
        /** The anvil is gone or can't be used: the host puts a spare down (or finds one), then the trip is made again from the journal. */
        NEED_ANVIL,
        /** The bottles ran out before the level was reached: the host fetches more (or waits), then the trip is made again. */
        NEED_BOTTLES,
        /** The order ended, was parked or the bot gave way: everything was put back and journalled; the trip is made again from the journal. */
        ABORTED,
        /** A force-cancel was heard and the trip stopped at a step boundary: the host returns the items. */
        FORCE_CANCELLED
    }

    /**
     * How the trip ended.
     *
     * @param levels      for {@link Kind#NEED_BOTTLES}: the level the next step needs (the host works out the bottles from the points missing)
     * @param spec        for {@link Kind#FINISHED}: the finished item
     * @param anvilBroken the anvil broke during this trip
     */
    public record Outcome(Kind kind, @Nullable String reason, int stepsDone, int levels, EnchantItems.@Nullable Spec spec, int anvilsBroken) {
        public boolean finished() {
            return kind == Kind.FINISHED;
        }
    }

    public enum Stage { Start, Stow, Level, Walk, Open, Place, Cost, Take, Settle, Retrieve, Close, Unstow, Done }

    // Ticks (20 a second)
    public static final int WALK_TICKS = 20 * 60, OPEN_TICKS = 20 * 15, OPEN_TRIES = 4, OPEN_RETRY_TICKS = 40;
    public static final int COST_TICKS = 20 * 10, TAKE_TICKS = 20 * 10, SETTLE_TICKS = 10, CLOSE_TICKS = 20 * 10, STUCK_TICKS = 200;
    /** Leveler attempts for one step that threw bottles with no points to show. */
    public static final int MAX_DRY = 2;

    private final Port port;
    private final McData data;
    private final String itemKey;
    private final EnchantOrder.ItemPlan plan;
    private final int mat;

    private int step;
    private Stage stage = Stage.Start;
    private @Nullable Outcome outcome;
    private @Nullable XpLeveler leveler;
    private int stageTicks, tries, sinceOpen, dry, shown = -1, expectedCost, broken, stuck, taken;
    private boolean stowed, placed, resumed;
    private @Nullable String waiting;
    private @Nullable Stage afterRetrieve;
    private Stage afterClose = Stage.Start;
    private @Nullable String pendingShort;
    private int bottlesAtStart = -1;

    /**
     * @param itemKey   the line's item key (what the anvil needs to know a non-book left operand is)
     * @param plan      the plan made with the real items
     * @param stepsDone the journal: steps already done
     */
    public EnchantTrip(Port port, String mc, String itemKey, EnchantOrder.ItemPlan plan, int stepsDone) {
        this.port = port;
        this.data = McData.load(mc);
        this.itemKey = McData.norm(itemKey);
        this.plan = plan;
        this.mat = plan.materials().size();
        this.step = Math.max(0, Math.min(stepsDone, plan.steps().size()));
    }

    public Stage stage() {
        return stage;
    }

    /** The step being worked on (the number of steps done). */
    public int step() {
        return step;
    }

    public int steps() {
        return plan.steps().size();
    }

    public boolean done() {
        return stage == Stage.Done;
    }

    public @Nullable Outcome outcome() {
        return outcome;
    }

    /** Why the trip is only waiting now ("a player is near", "controlled"), or null. */
    public @Nullable String waiting() {
        return waiting;
    }

    /** Bottles this trip used. */
    public int bottlesUsed() {
        return bottlesAtStart < 0 ? 0 : Math.max(0, bottlesAtStart - port.bottles());
    }

    // The specs of the numbered items

    private final Map<Integer, EnchantItems.Spec> specs = new HashMap<>();

    /** What item number {@code n} is: a material, or the result of an earlier step as the anvil rules make it. */
    EnchantItems.Spec specOf(int n) {
        EnchantItems.Spec known = specs.get(n);
        if (known != null) return known;
        EnchantItems.Spec out;
        if (n < 0 || n >= mat + plan.steps().size()) {
            // Not an item of the plan: a spec nothing carried can match (a stored repair cost is never -1)
            return new EnchantItems.Spec(false, Map.of(), -1);
        }
        if (n < mat) {
            EnchantOrder.Mat m = plan.materials().get(n);
            out = new EnchantItems.Spec(m.book(), m.ench(), m.rc());
        }
        else {
            EnchantOrder.Step s = plan.steps().get(n - mat);
            // A step may only use items that exist before it (a journal that says otherwise is not followed: no recursion on itself)
            if (s.left() < 0 || s.right() < 0 || s.left() >= n || s.right() >= n || s.left() == s.right()) return new EnchantItems.Spec(false, Map.of(), -1);
            Anvil.Result r = Anvil.combine(data, itemKey, specOf(s.left()).stack(), specOf(s.right()).stack());
            out = r.result() == null ? new EnchantItems.Spec(false, Map.of(), 0) : EnchantItems.Spec.of(r.result());
        }
        specs.put(n, out);
        return out;
    }

    /** The finished item's spec. */
    public EnchantItems.Spec finalSpec() {
        return specOf(plan.steps().isEmpty() ? 0 : mat + plan.steps().size() - 1);
    }

    private EnchantItems.@Nullable Held find(EnchantItems.Spec spec, int not) {
        for (EnchantItems.Held h : port.carried()) if (h.slot() != not && spec.matches(h)) return h;
        return null;
    }

    private boolean consumed(int n, int upTo) {
        for (int j = 0; j < upTo; j++) {
            EnchantOrder.Step s = plan.steps().get(j);
            if (s.left() == n || s.right() == n) return true;
        }
        return false;
    }

    /**
     * After a restart: a step whose result is carried while its inputs are not is done (the bot was cut off between taking the result and
     * writing the journal). Moves the step counter forward over such steps.
     */
    private void catchUp() {
        while (step < plan.steps().size()) {
            EnchantOrder.Step s = plan.steps().get(step);
            EnchantItems.Held left = find(specOf(s.left()), -1);
            EnchantItems.Held right = left == null ? null : find(specOf(s.right()), left.slot());
            if (left != null && right != null) return;
            if (find(specOf(mat + step), -1) == null) return;
            port.say("enchanting: step " + (step + 1) + " was already done.");
            step++;
        }
    }

    // The tick

    public void tick() {
        if (stage == Stage.Done) return;
        if (port.busy() && stage != Stage.Level) return;
        stageTicks++;
        // Aborting: what is in the anvil comes back and the window closes, then the trip ends (the journal is what carries on)
        if (port.abort() && stage != Stage.Retrieve && stage != Stage.Close && stage != Stage.Unstow && outcome == null) {
            leveler = null;
            pendingShort = null;
            // A force-cancel heard with nothing in the anvil ends as a force-cancel (the items are returned), any other abort is carried on from the journal
            boolean forced = port.forceCancelled() && !port.anvilOpen() && port.leftCount() == 0 && port.rightCount() == 0;
            end(new Outcome(forced ? Kind.FORCE_CANCELLED : Kind.ABORTED, null, step, 0, null, broken));
            goTo(port.anvilOpen() || port.otherWindowOpen() ? Stage.Retrieve : Stage.Unstow);
            return;
        }
        switch (stage) {
            case Start -> start();
            case Stow -> stow();
            case Level -> level();
            case Walk -> walk();
            case Open -> open();
            case Place -> place();
            case Cost -> cost();
            case Take -> take();
            case Settle -> settle();
            case Retrieve -> retrieve();
            case Close -> close();
            case Unstow -> unstow();
            case Done -> {
            }
        }
    }

    private void goTo(Stage s) {
        stage = s;
        stageTicks = 0;
        tries = 0;
        stuck = 0;
    }

    private void end(Outcome o) {
        if (outcome == null) outcome = o;
    }

    private void start() {
        if (!resumed) {
            resumed = true;
            catchUp();
        }
        if (bottlesAtStart < 0) bottlesAtStart = port.bottles();
        if (step >= plan.steps().size()) {
            end(new Outcome(Kind.FINISHED, null, step, 0, finalSpec(), broken));
            goTo(Stage.Unstow);
            return;
        }
        // Between steps: a force-cancel stops here, a player near or the bot being controlled only waits
        if (port.forceCancelled()) {
            end(new Outcome(Kind.FORCE_CANCELLED, null, step, 0, null, broken));
            goTo(Stage.Unstow);
            return;
        }
        if (!port.anvilBlock()) {
            end(new Outcome(Kind.NEED_ANVIL, null, step, 0, null, broken));
            goTo(Stage.Unstow);
            return;
        }
        String hold = hold();
        if (hold != null) {
            waiting = hold;
            return;
        }
        waiting = null;
        // The operands are looked for now: an item that is not where the journal says it is ends the item
        EnchantOrder.Step s = plan.steps().get(step);
        EnchantItems.Held left = find(specOf(s.left()), -1);
        EnchantItems.Held right = left == null ? null : find(specOf(s.right()), left.slot());
        if (left == null || right == null) {
            pendingShort = Codes.WRONG_RESULT;
            port.say("enchanting: the items for step " + (step + 1) + " are not carried.");
            end(new Outcome(Kind.SHORT, Codes.WRONG_RESULT, step, 0, null, broken));
            goTo(Stage.Unstow);
            return;
        }
        expectedCost = s.cost();
        shown = -1;
        goTo(stowed ? Stage.Level : Stage.Stow);
    }

    /** A reason to hold still (a player is near, the bot is controlled), or null. */
    private @Nullable String hold() {
        if (port.controlled()) return "the bot is not free to work";
        if (port.playerNear()) return "a player is near";
        return null;
    }

    private void stow() {
        int r = port.stowGear();
        if (r == 0) return;
        if (r < 0) {
            end(new Outcome(Kind.SHORT, Codes.INVENTORY_FULL, step, 0, null, broken));
            goTo(Stage.Unstow);
            return;
        }
        stowed = true;
        goTo(Stage.Level);
    }

    private void level() {
        // A force-cancel heard in the pause (or while throwing): nothing is in the anvil here, so the trip ends at once and the items are returned
        if (port.forceCancelled()) {
            leveler = null;
            end(new Outcome(Kind.FORCE_CANCELLED, null, step, 0, null, broken));
            goTo(Stage.Unstow);
            return;
        }
        int need = Math.max(expectedCost, shown);
        if (port.points() >= com.porter.qwerte.core.enchant.EnchantRules.points(need) - 1e-9) {
            leveler = null;
            goTo(Stage.Walk);
            return;
        }
        String hold = hold();
        if (hold != null) {
            waiting = hold;
            return;
        }
        waiting = null;
        XpLeveler l = leveler;
        if (l == null || l.target() != need) l = leveler = new XpLeveler(port, need);
        XpLeveler.Result r = l.tick();
        switch (r) {
            case Working -> {
            }
            case Reached -> {
                leveler = null;
                goTo(Stage.Walk);
            }
            case Player -> {
                leveler = null;
                waiting = "a player is near";
            }
            case NoBottles -> {
                leveler = null;
                end(new Outcome(Kind.NEED_BOTTLES, null, step, need, null, broken));
                goTo(Stage.Unstow);
            }
            case Aborted -> leveler = null;
            case Dry, Stuck -> {
                leveler = null;
                if (r == XpLeveler.Result.Dry && ++dry <= MAX_DRY) return;
                end(new Outcome(Kind.SHORT, Codes.XP_SHORT, step, need, null, broken));
                goTo(Stage.Unstow);
            }
        }
    }

    private void walk() {
        if (port.anvilOpen()) {
            goTo(Stage.Place);
            return;
        }
        if (port.otherWindowOpen()) {
            port.close();
            return;
        }
        if (port.arrived()) {
            goTo(Stage.Open);
            return;
        }
        if (stageTicks > WALK_TICKS) {
            giveUpAnvil();
            return;
        }
        if (!port.pathing()) port.walk();
    }

    private void open() {
        if (port.anvilOpen()) {
            port.opened();
            goTo(Stage.Place);
            return;
        }
        if (port.otherWindowOpen()) {
            port.close();
            return;
        }
        if (!port.arrived()) {
            goTo(Stage.Walk);
            return;
        }
        if (port.openPaused() || port.pathing()) return;
        sinceOpen++;
        if (stageTicks > OPEN_TICKS || tries >= OPEN_TRIES) {
            giveUpAnvil();
            return;
        }
        if (tries == 0 || sinceOpen >= OPEN_RETRY_TICKS) {
            if (port.open()) {
                tries++;
                sinceOpen = 0;
            }
        }
    }

    /** The anvil can't be reached or opened: the host looks for another (or puts the spare down) and the trip is made again. */
    private void giveUpAnvil() {
        end(new Outcome(Kind.NEED_ANVIL, null, step, 0, null, broken));
        goTo(Stage.Unstow);
    }

    private void place() {
        if (!port.anvilOpen()) {
            goTo(Stage.Walk);
            return;
        }
        // Between placing and taking nothing pauses the trip: a player near does not matter once the items are in
        EnchantOrder.Step s = plan.steps().get(step);
        EnchantItems.Held left = find(specOf(s.left()), -1);
        EnchantItems.Held right = left == null ? null : find(specOf(s.right()), left.slot());
        if (left == null || right == null) {
            pendingShort = Codes.WRONG_RESULT;
            goTo(Stage.Retrieve);
            return;
        }
        if (port.leftCount() > 0 || port.rightCount() > 0) {
            // Something is in the anvil already (a late answer, or a restart): put back first
            afterRetrieve = Stage.Place;
            goTo(Stage.Retrieve);
            return;
        }
        if (port.place(left.slot(), right.slot())) {
            placed = true;
            goTo(Stage.Cost);
        }
        else if (++stuck > STUCK_TICKS) {
            giveUpAnvil();
        }
    }

    private void cost() {
        // The window closed on its own (the game or a lag): the inputs are carried again, so the step is made again from the anvil
        if (!port.anvilOpen() && recover()) return;
        // Exactly one item on the left, always: a stack of more forces the cost to 40
        if (port.leftCount() > 1) {
            pendingShort = Codes.WRONG_RESULT;
            port.say("enchanting: more than one item is in the anvil's left slot.");
            goTo(Stage.Retrieve);
            return;
        }
        int c = port.cost();
        EnchantItems.Held result = port.result();
        if (c < 0 && result == null) {
            if (stageTicks > COST_TICKS) {
                pendingShort = Codes.WRONG_RESULT;
                goTo(Stage.Retrieve);
            }
            return;
        }
        if (c < 0 && stageTicks < SETTLE_TICKS) return;
        if (result == null) {
            // A cost with no result: the anvil would not give one (Too Expensive, or nothing changes)
            if (stageTicks < SETTLE_TICKS) return;
            pendingShort = c >= 40 ? Codes.TOO_EXPENSIVE : Codes.WRONG_RESULT;
            goTo(Stage.Retrieve);
            return;
        }
        if (c >= 40) {
            pendingShort = Codes.TOO_EXPENSIVE;
            goTo(Stage.Retrieve);
            return;
        }
        shown = Math.max(c, 0);
        if (shown > expectedCost) {
            // Dearer than planned, under 40: before step 1 it is a mismatch; later the bot levels up to it (one more throw round)
            if (step == 0) {
                pendingShort = Codes.COST_MISMATCH;
                goTo(Stage.Retrieve);
                return;
            }
            port.say("enchanting: step " + (step + 1) + " shows " + shown + " levels, planned " + expectedCost + ".");
            if (!have(shown)) {
                afterRetrieve = Stage.Level;
                goTo(Stage.Retrieve);
                return;
            }
        }
        if (!EnchantItems.Spec.of(specOf(mat + step).stack()).matches(result)) {
            pendingShort = Codes.WRONG_RESULT;
            goTo(Stage.Retrieve);
            return;
        }
        if (shown > 0 && !have(shown)) {
            afterRetrieve = Stage.Level;
            goTo(Stage.Retrieve);
            return;
        }
        goTo(Stage.Take);
    }

    /** Window closes mid-step before the item ends SHORT for it: bounded re-opens of the anvil. */
    public static final int MAX_REOPENS = 3;
    private int reopens;

    /**
     * The anvil window is gone in the middle of a step. If the result is carried the step is done (Settle); else if both inputs are carried the anvil is
     * walked to and opened again (at most {@link #MAX_REOPENS} times, then the anvil is given up on: NEED_ANVIL, never a SHORT for a closed window).
     * @return true when it took over (false: the inputs are not carried either)
     */
    private boolean recover() {
        EnchantOrder.Step s = plan.steps().get(step);
        if (find(specOf(mat + step), -1) != null) {
            goTo(Stage.Settle);
            stageTicks = SETTLE_TICKS;
            return true;
        }
        EnchantItems.Held left = find(specOf(s.left()), -1);
        EnchantItems.Held right = left == null ? null : find(specOf(s.right()), left.slot());
        if (left == null || right == null) return false;
        placed = false;
        shown = -1;
        if (++reopens > MAX_REOPENS) {
            port.say("enchanting: the anvil window keeps closing.");
            end(new Outcome(Kind.NEED_ANVIL, null, step, 0, null, broken));
            goTo(Stage.Unstow);
            return true;
        }
        port.say("enchanting: the anvil window closed in step " + (step + 1) + "; opening it again.");
        goTo(Stage.Walk);
        return true;
    }

    private void take() {
        if (!port.anvilOpen() && recover()) return;
        EnchantItems.Held result = port.result();
        if (result == null) {
            // Gone: taken already (the carried stack tells) or lost
            goTo(Stage.Settle);
            return;
        }
        if (stageTicks > TAKE_TICKS) {
            pendingShort = Codes.WRONG_RESULT;
            goTo(Stage.Retrieve);
            return;
        }
        if (port.take()) goTo(Stage.Settle);
    }

    private void settle() {
        if (stageTicks < SETTLE_TICKS) return;
        EnchantItems.Held got = find(specOf(mat + step), -1);
        if (got == null) {
            // The result is still in the anvil's slot and the window is open: the take is made again. The window gone: the step is made again from the anvil
            if (port.anvilOpen() && port.result() != null) {
                goTo(Stage.Take);
                return;
            }
            if (!port.anvilOpen() && recover()) return;
            if (stageTicks > TAKE_TICKS) {
                pendingShort = Codes.WRONG_RESULT;
                end(new Outcome(Kind.SHORT, Codes.WRONG_RESULT, step, 0, null, broken));
                goTo(Stage.Close);
            }
            return;
        }
        // The anvil may have broken on the pickup (12 percent): the window closed and the block is gone
        boolean gone = !port.anvilBlock();
        if (gone) {
            broken++;
            port.say("enchanting: the anvil broke after step " + (step + 1) + ".");
        }
        port.stepDone(step, shown);
        reopens = 0;
        step++;
        taken++;
        shown = -1;
        placed = false;
        dry = 0;
        if (gone) {
            if (step >= plan.steps().size()) end(new Outcome(Kind.FINISHED, null, step, 0, finalSpec(), broken));
            else end(new Outcome(Kind.NEED_ANVIL, null, step, 0, null, broken));
            goTo(Stage.Unstow);
            return;
        }
        goTo(Stage.Close);
    }

    private void retrieve() {
        if (port.anvilOpen() || port.leftCount() > 0 || port.rightCount() > 0) {
            if (port.retrieve()) {
                finishRetrieve();
            }
            else if (++stuck > STUCK_TICKS) finishRetrieve();
            return;
        }
        finishRetrieve();
    }

    private void finishRetrieve() {
        String why = pendingShort;
        pendingShort = null;
        placed = false;
        if (why != null) {
            end(new Outcome(Kind.SHORT, why, step, 0, null, broken));
            goTo(Stage.Close);
            return;
        }
        // Back to the step in hand (the window is closed first when more throwing is needed)
        Stage next = afterRetrieve == null ? Stage.Level : afterRetrieve;
        afterRetrieve = null;
        if (next == Stage.Place) {
            goTo(Stage.Place);
            return;
        }
        afterClose = Stage.Level;
        goTo(Stage.Close);
    }

    /** The bot holds enough experience for this many levels. */
    private boolean have(int levels) {
        return port.points() >= com.porter.qwerte.core.enchant.EnchantRules.points(levels) - 1e-9;
    }

    private void close() {
        if (port.close() || stageTicks > CLOSE_TICKS) {
            // A trip with an outcome goes on to put the gear back; a step done goes on to the next one
            if (outcome != null) {
                goTo(Stage.Unstow);
                return;
            }
            Stage next = afterClose;
            afterClose = Stage.Start;
            goTo(next);
        }
    }

    private void unstow() {
        // The window closes before the gear moves
        if (port.anvilOpen() || port.otherWindowOpen()) {
            if (port.leftCount() > 0 || port.rightCount() > 0) {
                if (!port.retrieve() && ++stuck <= STUCK_TICKS) return;
            }
            if (!port.close() && stageTicks <= CLOSE_TICKS) return;
        }
        // Between steps the gear stays off: only the end of the trip puts it back
        if (outcome != null) {
            // Always asked, also when the trip was aborted in the middle of stowing (stowed is not set yet): the ledger knows what moved
            if (!port.restoreGear() && stageTicks <= STUCK_TICKS * 5) return;
            stowed = false;
            leveler = null;
            stage = Stage.Done;
            return;
        }
        goTo(Stage.Start);
    }

    /** Bottles are fetched, an anvil was put down: the same trip carries on from where the journal is. */
    public int stepsTaken() {
        return taken;
    }
}
