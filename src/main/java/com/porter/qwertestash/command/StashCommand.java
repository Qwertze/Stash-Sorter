package com.porter.qwertestash.command;

import com.aquarius.command.api.Command;
import com.aquarius.command.api.CommandCategory;
import com.aquarius.command.api.CommandContext;
import com.aquarius.command.api.CommandUsage;
import com.aquarius.discord.Embed;
import com.aquarius.mc.block.BlockPos;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.qwertestash.StashConfig.Mode;
import com.porter.qwertestash.module.AutoDrop;
import com.porter.qwertestash.module.StashSorter;
import com.porter.qwertestash.module.StashTracker;
import com.porter.qwertestash.stash.Containers;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.StashIndex;
import com.porter.qwertestash.stash.Stashes;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.aquarius.Globals.MODULE;
import static com.aquarius.command.brigadier.ToggleArgumentType.getToggle;
import static com.aquarius.command.brigadier.ToggleArgumentType.toggle;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

public class StashCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stash")
            .category(CommandCategory.MODULE)
            .description("""
                Qwerte stash sorter: sorts a saved stash of chests and barrels by tier or by sign, keeps it sorted,
                and lets you search it here or by whisper.
                Stand the bot among the containers and turn it on. The first run reads every container and saves the
                stash, later runs go straight to sorting. Sorting pauses while you're connected to the proxy, and gives
                way to other modules (like PearlPlus): it lets go of its container, waits until they have been quiet for
                `quiet` seconds, then carries on.
                Slots: 1-9 is the hotbar, 10-36 the inventory from the top left.
                """)
            .usageLines(
                "on/off",
                "sort on/off (off = scan only)",
                "search <item>",
                "name <stash name>",
                "mode <tiers/signs>",
                "exclude [x y z]",
                "dropoff [x y z]",
                "radius <blocks>",
                "height <blocks>",
                "barrels on/off",
                "delay <ticks>",
                "quiet <0-60>",
                "pearlSlot on/off",
                "watch on/off (needs tracking on)",
                "watchDelay <0-60>",
                "watchSigns on/off",
                "signDelay <0-60>",
                "autoLabel on/off",
                "unsignedOverflow on/off",
                "labels",
                "stayOn on/off",
                "recheck <seconds>",
                "repack on/off",
                "newBulkAt <stacks>",
                "compact on/off",
                "compactTopUpAt <1-26>",
                "compactExclude add/remove <item id>/list",
                "compactSkip [x y z]",
                "carry <slots>/clear",
                "keep <slots>/clear",
                "rescan",
                "index",
                "forget",
                "tracking on/off",
                "collect on/off",
                "collectRange <blocks>",
                "autoDrop on/off",
                "autoDrop range <blocks>",
                "whisper on/off",
                "whitelist add/del <player>",
                "prefix <word>/clear"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stash")
            .then(argument("toggle", toggle()).executes(c -> {
                boolean on = getToggle(c, "toggle");
                StashSorter sorter = MODULE.get(StashSorter.class);
                sorter.clearProblem();
                CONFIG.sorting = on;
                sorter.syncEnabledFromConfig();

                if (on && !CONFIG.sorting) {
                    c.getSource().getEmbed().title("Stash Sorter Couldn't Start").description(sorter.problem() != null ? sorter.problem() : "See the terminal for why.").errorColor();
                    return;
                }
                c.getSource().getEmbed().title("Stash Sorter " + toggleStrCaps(CONFIG.sorting));
            }))
            .then(literal("sort").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.sortItems = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Sorting Items " + toggleStrCaps(CONFIG.sortItems))
                    .description(CONFIG.sortItems ? "Items are moved again." : "Scan only: containers are read and kept up to date, nothing is moved.");
            })))
            .then(literal("collect").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.collectGround = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Collecting Ground Items " + toggleStrCaps(CONFIG.collectGround));
            })))
            .then(literal("collectRange").then(argument("blocks", integer(0, 64)).executes(c -> {
                CONFIG.collectRange = getInteger(c, "blocks");
                c.getSource().getEmbed().title("Collect Range Set");
            })))
            .then(literal("search").then(argument("query", greedyString()).executes(c -> {
                search(c.getSource().getEmbed(), getString(c, "query"));
            })))
            .then(literal("name").then(argument("name", greedyString()).executes(c -> {
                CONFIG.stashName = getString(c, "name").trim();
                c.getSource().getEmbed().title("Stash Name Set");
            })))
            .then(literal("mode").then(argument("mode", enumStrings(Mode.values())).executes(c -> {
                CONFIG.mode = getString(c, "mode").equalsIgnoreCase("signs") ? Mode.Signs : Mode.Tiers;
                c.getSource().getEmbed().title("Mode Set");
            })))
            .then(literal("exclude")
                .executes(c -> {
                    mark(c.getSource().getEmbed(), null, MARK_EXCLUDE);
                })
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> {
                    mark(c.getSource().getEmbed(), new BlockPos(getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z")), MARK_EXCLUDE);
                })))))
            .then(literal("dropoff")
                .executes(c -> {
                    mark(c.getSource().getEmbed(), null, MARK_DROPOFF);
                })
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> {
                    mark(c.getSource().getEmbed(), new BlockPos(getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z")), MARK_DROPOFF);
                })))))
            .then(literal("compactSkip")
                .executes(c -> {
                    mark(c.getSource().getEmbed(), null, MARK_COMPACT_SKIP);
                })
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> {
                    mark(c.getSource().getEmbed(), new BlockPos(getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z")), MARK_COMPACT_SKIP);
                })))))
            .then(literal("compact").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.compact = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Compact Shulkers " + toggleStrCaps(CONFIG.compact));
            })))
            .then(literal("compactTopUpAt").then(argument("stacks", integer(1, 26)).executes(c -> {
                CONFIG.compactTopUpAt = getInteger(c, "stacks");
                c.getSource().getEmbed().title("Compact Top-up Set");
            })))
            .then(literal("compactExclude")
                .then(literal("add").then(argument("item", word()).executes(c -> {
                    String id = itemId(getString(c, "item"));
                    if (!CONFIG.compactExclude.contains(id)) CONFIG.compactExclude.add(id);
                    c.getSource().getEmbed().title("Never Compacting " + id);
                })))
                .then(literal("remove").then(argument("item", word()).executes(c -> {
                    String id = itemId(getString(c, "item"));
                    CONFIG.compactExclude.remove(id);
                    c.getSource().getEmbed().title("Compacting " + id + " Again");
                })))
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed().title("Never Compacted").description(CONFIG.compactExclude.isEmpty() ? "Nothing is excluded." : String.join(", ", CONFIG.compactExclude)).primaryColor();
                })))
            .then(literal("radius").then(argument("blocks", integer(1, 64)).executes(c -> {
                CONFIG.radius = getInteger(c, "blocks");
                c.getSource().getEmbed().title("Radius Set");
            })))
            .then(literal("height").then(argument("blocks", integer(0, 32)).executes(c -> {
                CONFIG.height = getInteger(c, "blocks");
                c.getSource().getEmbed().title("Height Set");
            })))
            .then(literal("barrels").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.barrels = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Barrels " + toggleStrCaps(CONFIG.barrels));
            })))
            .then(literal("delay").then(argument("ticks", integer(0, 40)).executes(c -> {
                CONFIG.delayTicks = getInteger(c, "ticks");
                c.getSource().getEmbed().title("Delay Set");
            })))
            .then(literal("quiet").then(argument("seconds", integer(0, 60)).executes(c -> {
                CONFIG.quietSeconds = getInteger(c, "seconds");
                c.getSource().getEmbed().title("Quiet Time Set");
            })))
            .then(literal("pearlSlot").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.reservePearlSlot = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Pearl Slot Reserved " + toggleStrCaps(CONFIG.reservePearlSlot));
            })))
            .then(literal("watch").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.watchAccess = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Watching Chests " + toggleStrCaps(CONFIG.watchAccess));
            })))
            .then(literal("watchDelay").then(argument("seconds", integer(0, 60)).executes(c -> {
                CONFIG.accessRescanSeconds = getInteger(c, "seconds");
                c.getSource().getEmbed().title("Watch Delay Set");
            })))
            .then(literal("watchSigns").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.watchSigns = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Watching Signs " + toggleStrCaps(CONFIG.watchSigns));
            })))
            .then(literal("signDelay").then(argument("seconds", integer(0, 60)).executes(c -> {
                CONFIG.signSettleSeconds = getInteger(c, "seconds");
                c.getSource().getEmbed().title("Sign Delay Set");
            })))
            .then(literal("autoLabel").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.autoLabel = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Auto Labelling " + toggleStrCaps(CONFIG.autoLabel));
            })))
            .then(literal("unsignedOverflow").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.unsignedOverflow = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Unsigned Overflow " + toggleStrCaps(CONFIG.unsignedOverflow));
            })))
            .then(literal("labels").executes(c -> {
                c.getSource().getEmbed().title("Labels").description(MODULE.get(StashSorter.class).labelsReport()).primaryColor();
            }))
            .then(literal("stayOn").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.stayOn = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Stay On " + toggleStrCaps(CONFIG.stayOn));
            })))
            .then(literal("recheck").then(argument("seconds", integer(5, 3600)).executes(c -> {
                CONFIG.recheckSeconds = getInteger(c, "seconds");
                c.getSource().getEmbed().title("Recheck Set");
            })))
            .then(literal("repack").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.repack = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Repack " + toggleStrCaps(CONFIG.repack));
            })))
            .then(literal("newBulkAt").then(argument("stacks", integer(0, 27)).executes(c -> {
                CONFIG.newBulkAt = getInteger(c, "stacks");
                c.getSource().getEmbed().title("New Bulk Shulker Threshold Set");
            })))
            .then(literal("carry")
                .then(literal("clear").executes(c -> {
                    CONFIG.carrySlots = "";
                    c.getSource().getEmbed().title("Carry Slots Cleared");
                }))
                .then(argument("slots", greedyString()).executes(c -> {
                    CONFIG.carrySlots = getString(c, "slots").trim();
                    c.getSource().getEmbed().title("Carry Slots Set");
                })))
            .then(literal("keep")
                .then(literal("clear").executes(c -> {
                    CONFIG.keepSlots = "";
                    c.getSource().getEmbed().title("Keep Slots Cleared");
                }))
                .then(argument("slots", greedyString()).executes(c -> {
                    CONFIG.keepSlots = getString(c, "slots").trim();
                    c.getSource().getEmbed().title("Keep Slots Set");
                })))
            .then(literal("index").executes(c -> {
                Stash stash = Stashes.current();
                if (stash == null) {
                    c.getSource().getEmbed().title("No Stash Here Yet");
                    return;
                }
                try {
                    StashIndex.writeNow(stash);
                } catch (RuntimeException ignored) {
                    // The report below says so if the stash can't be read
                }
                c.getSource().getEmbed().title("Stash Index").description(StashIndex.report(stash, 12)).primaryColor();
            }))
            .then(literal("rescan").executes(c -> {
                Stash stash = Stashes.current();
                if (stash == null) {
                    c.getSource().getEmbed().title("No Stash Here Yet");
                    return;
                }
                for (Box box : stash.boxes) box.contents = null;
                stash.save();
                Stashes.version++;
                c.getSource().getEmbed().title("Stash Will Be Read Again").description("Every container is read again the next time sorting runs.");
            }))
            .then(literal("forget").executes(c -> {
                Stashes.forget();
                c.getSource().getEmbed().title("Stash Forgotten").description("Deleted \"" + CONFIG.stashName + "\", including excluded and drop-off marks.");
            }))
            .then(literal("autoDrop")
                .then(literal("range").then(argument("blocks", integer(1, 64)).executes(c -> {
                    CONFIG.autoDropRange = getInteger(c, "blocks");
                    c.getSource().getEmbed().title("Auto Drop Range Set");
                })))
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.autoDrop = getToggle(c, "toggle");
                    MODULE.get(AutoDrop.class).syncEnabledFromConfig();
                    c.getSource().getEmbed().title("Auto Drop " + toggleStrCaps(CONFIG.autoDrop));
                })))
            .then(literal("tracking").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.tracking = getToggle(c, "toggle");
                MODULE.get(StashTracker.class).syncEnabledFromConfig();
                c.getSource().getEmbed().title("Tracking " + toggleStrCaps(CONFIG.tracking));
            })))
            .then(literal("whisper").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.whisperSearch = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Whisper Search " + toggleStrCaps(CONFIG.whisperSearch));
            })))
            .then(literal("whitelist")
                .then(literal("add").then(argument("player", word()).executes(c -> {
                    String player = getString(c, "player");
                    if (CONFIG.whitelist.stream().noneMatch(p -> p.equalsIgnoreCase(player))) CONFIG.whitelist.add(player);
                    c.getSource().getEmbed().title("Added " + player);
                })))
                .then(literal("del").then(argument("player", word()).executes(c -> {
                    String player = getString(c, "player");
                    CONFIG.whitelist.removeIf(p -> p.equalsIgnoreCase(player));
                    c.getSource().getEmbed().title("Removed " + player);
                }))))
            .then(literal("prefix")
                .then(literal("clear").executes(c -> {
                    CONFIG.prefix = "";
                    c.getSource().getEmbed().title("Prefix Cleared").description("Every whisper from a whitelisted player is a search.");
                }))
                .then(argument("word", word()).executes(c -> {
                    CONFIG.prefix = getString(c, "word");
                    c.getSource().getEmbed().title("Prefix Set");
                })));
    }

    /**
     * Toggles a container as excluded, or as a drop-off. With no position, the container you last right-clicked
     * while connected.
     */
    private static final int MARK_EXCLUDE = 0, MARK_DROPOFF = 1, MARK_COMPACT_SKIP = 2;

    /** "minecraft:Ender_Pearl" as "ender_pearl". */
    private static String itemId(String text) {
        String id = text.trim().toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private void mark(Embed embed, @Nullable BlockPos pos, int kind) {
        if (pos == null) pos = MODULE.get(StashTracker.class).lastClicked();
        if (pos == null) {
            embed.title("Which Container?").description("Give its position, or right-click it while connected to the proxy first.").errorColor();
            return;
        }
        String note = "";
        if (Containers.isLoaded(pos) && !Containers.isContainer(pos, true)) {
            // Easy to be a block off, especially with negative coordinates: use the one container right next to it
            BlockPos near = onlyContainerNextTo(pos);
            if (near == null) {
                embed.title("Not a Container").description(pos.x() + ", " + pos.y() + ", " + pos.z()
                    + " isn't a chest or barrel, and there isn't exactly one right next to it. With negative coordinates, round down: -5.3 is block -6.").errorColor();
                return;
            }
            note = String.format(" (%d, %d, %d isn't a container, so I used the one next to it.)", pos.x(), pos.y(), pos.z());
            pos = near;
        }

        Stash stash = Stashes.currentOrNew();
        Box box = stash.boxAt(pos);
        if (box == null) {
            box = Containers.isLoaded(pos) ? Containers.makeBox(pos) : new Box(pos, new java.util.ArrayList<>(List.of(pos)));
            stash.boxes.add(box);
        }

        String at = box.pos.x() + ", " + box.pos.y() + ", " + box.pos.z();
        if (kind == MARK_COMPACT_SKIP) {
            box.noCompact = !box.noCompact;
            embed.title(box.noCompact ? "Compact Skip Set" : "Compact Skip Removed").description(at + (box.noCompact
                ? " is never used to gather stacks or shulkers for compacting." : " can be used for compacting again.") + note);
        }
        else if (kind == MARK_EXCLUDE) {
            box.excluded = !box.excluded;
            box.dropOff = false;
            embed.title(box.excluded ? "Excluded" : "No Longer Excluded").description(at + (box.excluded ? " won't be sorted." : " will be sorted again.") + note);
        }
        else {
            box.dropOff = !box.dropOff;
            box.excluded = false;
            embed.title(box.dropOff ? "Drop-off Set" : "Drop-off Removed").description(at + (box.dropOff ? " will be emptied into the stash." : " is a normal container again.") + note);
        }

        stash.save();
        Stashes.version++;
    }

    /**
     * The one chest or barrel right next to {@code pos}: sharing a face first, then diagonally. Null if there's none,
     * or more than one at the same distance.
     */
    private static @Nullable BlockPos onlyContainerNextTo(BlockPos pos) {
        for (int pass = 1; pass <= 3; pass++) {
            BlockPos found = null;
            boolean ambiguous = false;
            Stash stash = Stashes.current();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // Pass 1: sharing a face, pass 2: sharing an edge, pass 3: corners
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != pass) continue;
                        BlockPos p = pos.add(dx, dy, dz);
                        if (!Containers.isContainer(p, true)) continue;
                        // Both halves of one double chest count once
                        if (found != null && stash != null && stash.boxAt(found) != null && stash.boxAt(found) == stash.boxAt(p)) continue;
                        if (found != null) ambiguous = true;
                        found = p;
                    }
                }
            }
            if (ambiguous) return null;
            if (found != null) return found;
        }
        return null;
    }

    private void search(Embed embed, String query) {
        Stash stash = Stashes.current();
        if (stash == null) {
            embed.title("No Stash Here Yet").description("Scan one first with `stash on`.").errorColor();
            return;
        }

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            embed.title("Nothing Found").description("No " + query + " in \"" + stash.name + "\".");
            return;
        }

        StringBuilder out = new StringBuilder();
        for (Stash.Result result : results.subList(0, Math.min(15, results.size()))) {
            Stash.Spot best = result.best();
            BlockPos pos = best.box().pos;
            int more = result.spots.size() - 1;

            out.append(String.format("%s x%,d @ %d, %d, %d%s%s", result.sample.label(), result.total, pos.x(), pos.y(), pos.z(),
                best.inShulker() ? " (shulker)" : "", more > 0 ? String.format(" +%d more", more) : ""));
            if (!result.sample.enchants.isEmpty()) out.append(" [").append(String.join(", ", result.sample.enchants)).append(']');
            if (result.sample.potion != null) out.append(" [").append(result.sample.potion).append(']');
            out.append('\n');
        }
        embed.title("Search: " + query).description(out.toString().stripTrailing()).primaryColor();
    }

    private static String orphanLine() {
        Stash stash = Stashes.current();
        if (stash == null || stash.orphans.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (Stash.Orphan orphan : stash.orphans) {
            out.append(orphan.stacks).append(" stacks of ").append(orphan.item).append(" at ")
                .append(orphan.pos.x()).append(", ").append(orphan.pos.y()).append(", ").append(orphan.pos.z())
                .append(" (").append(orphan.when).append("); ");
        }
        return out.toString();
    }

    @Override
    public void defaultEmbed(Embed embed) {
        Stash stash = Stashes.current();
        String stashLine;
        if (stash == null) stashLine = "None saved here yet";
        else {
            long excluded = stash.boxes.stream().filter(b -> b.excluded).count();
            long dropOffs = stash.boxes.stream().filter(b -> b.dropOff).count();
            stashLine = String.format("%d containers (%d read, %d excluded, %d drop-off), %,d items",
                stash.boxes.size(), stash.knownCount(), excluded, dropOffs, stash.itemCount());
        }

        embed
            .primaryColor()
            .addField("Sorting", toggleStr(CONFIG.sorting) + " - " + MODULE.get(StashSorter.class).statusLine())
            .addField("Sort Items", toggleStr(CONFIG.sortItems) + (CONFIG.sortItems ? "" : " (scan only: containers are read and kept up to date, nothing is moved)"))
            .addField("Collect Ground Items", toggleStr(CONFIG.collectGround) + " - " + MODULE.get(StashSorter.class).groundState()
                + " (items the bot didn't drop or break, " + CONFIG.collectRange + " blocks around the stash, only while no other player is in render distance)")
            .addField("Stash", "\"" + CONFIG.stashName + "\": " + stashLine)
            .addField("Mode", CONFIG.mode.name())
            .addField("Area", "radius " + CONFIG.radius + ", height " + CONFIG.height + ", barrels " + toggleStr(CONFIG.barrels))
            .addField("Repack Shulkers", toggleStr(CONFIG.repack) + " (signs mode" + (CONFIG.compact ? "" : ", new bulk at " + CONFIG.newBulkAt + " stacks") + ")")
            .addField("Compact Shulkers", toggleStr(CONFIG.compact) + " - " + MODULE.get(StashSorter.class).compactStatus()
                + " (signs mode, top up at " + CONFIG.compactTopUpAt + " stacks, " + CONFIG.compactExclude.size() + " items excluded)")
            .addField("Stay On", toggleStr(CONFIG.stayOn) + " (recheck " + CONFIG.recheckSeconds + "s)")
            .addField("Carry / Keep Slots", (CONFIG.carrySlots.isBlank() ? "every empty slot" : CONFIG.carrySlots) + " / " + (CONFIG.keepSlots.isBlank() ? "none" : CONFIG.keepSlots))
            .addField("Delay", CONFIG.delayTicks + " ticks")
            .addField("Gives Way To Other Modules", "waits " + CONFIG.quietSeconds + "s after they stop, pearl slot (hotbar 1) kept for PearlPlus " + toggleStr(CONFIG.reservePearlSlot))
            .addField("Watch Chests", toggleStr(CONFIG.watchAccess) + " (re-read " + CONFIG.accessRescanSeconds + "s after another player closes one; needs tracking on)")
            .addField("Auto Label", toggleStr(CONFIG.autoLabel) + " (signs mode, a sign every " + CONFIG.labelGapSeconds + "s at most, see `stash labels`)")
            .addField("Unsigned Overflow", toggleStr(CONFIG.unsignedOverflow) + " (signs mode, stacks with no room go in unsigned containers after misc/other/overflow ones)")
            .addField("Orphaned Shulkers", orphanLine())
            .addField("Watch Signs", toggleStr(CONFIG.watchSigns) + " (changes used after " + CONFIG.signSettleSeconds + "s, a sign gone or blank for 60s; needs tracking on)")
            .addField("Auto Drop", toggleStr(CONFIG.autoDrop) + " (gives back what a player dropped near the bot, within " + (int) CONFIG.autoDropRange + " blocks)")
            .addField("Tracking", toggleStr(CONFIG.tracking))
            .addField("Whisper Search", toggleStr(CONFIG.whisperSearch) + ", prefix \"" + CONFIG.prefix + "\", whitelist " + (CONFIG.whitelist.isEmpty() ? "empty" : String.join(", ", CONFIG.whitelist)));
    }
}
