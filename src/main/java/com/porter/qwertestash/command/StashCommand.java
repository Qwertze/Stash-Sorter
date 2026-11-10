package com.porter.qwertestash.command;

import com.aquarius.command.api.Command;
import com.aquarius.command.api.CommandCategory;
import com.aquarius.command.api.CommandContext;
import com.aquarius.command.api.CommandSources;
import com.aquarius.command.api.CommandUsage;
import com.aquarius.discord.Embed;
import com.aquarius.mc.block.BlockPos;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.qwertestash.StashConfig.LogLevel;
import com.porter.qwertestash.StashConfig.Mode;
import com.porter.qwertestash.module.AutoDrop;
import com.porter.qwertestash.module.StashManager;
import com.porter.qwertestash.module.StashSorter;
import com.porter.qwertestash.module.StashTracker;
import com.porter.qwertestash.stash.Containers;
import com.porter.qwertestash.stash.AnvilSpot;
import com.porter.qwertestash.stash.AreaPending;
import com.porter.qwertestash.stash.AreaRules;
import com.porter.qwertestash.stash.CraftingTableSpot;
import com.porter.qwertestash.stash.DeliverySpot;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.StashIndex;
import com.porter.qwertestash.stash.Stashes;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;

import static com.aquarius.Globals.CACHE;
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
                "log <quiet/normal/debug>",
                "exclude [x y z]",
                "dropoff [x y z]",
                "area <x1> <y1> <z1> <x2> <y2> <z2>",
                "area here1/here2/clear",
                "area",
                "radius <blocks>",
                "height <blocks>",
                "barrels on/off",
                "delay <ticks>",
                "containerDelay [ticks]",
                "quiet <0-60>",
                "pearlSlot on/off",
                "watch on/off (needs tracking on)",
                "watchDelay <0-60>",
                "watchSigns on/off",
                "signDelay <0-60>",
                "autoLabel on/off",
                "unsignedOverflow on/off",
                "kitSorting on/off",
                "labels",
                "stayOn on/off",
                "recheck <seconds>",
                "smartRecheck on/off",
                "repack on/off",
                "newBulkAt <stacks>",
                "compact on/off",
                "compactTopUpAt <1-26>",
                "compactExclude add/remove <item id>/list",
                "compactSkip [x y z]",
                "splitDurability on/off",
                "carry <slots>/clear",
                "keep <slots>/clear",
                "rescan",
                "unstuck",
                "index",
                "forget",
                "tracking on/off",
                "safewalk on/off",
                "collect on/off",
                "collectRange <blocks>",
                "autoDrop on/off",
                "autoDrop range <blocks>",
                "whisper on/off",
                "whitelist add/del <player>",
                "whitelist [list]",
                "friends add/del <player or uuid>",
                "friends [list]",
                "prefix <word>/clear",
                "manager on/off",
                "manager url <url>",
                "manager key <key>",
                "manager status",
                "manager includeExcluded on/off",
                "manager scanInterval <seconds>",
                "manager dashboard on/off",
                "orders on/off",
                "orders",
                "delivery here",
                "delivery <x> <y> <z> [dimension]",
                "delivery clear",
                "delivery expand on/off",
                "delivery expand",
                "delivery",
                "anvil here",
                "anvil <x> <y> <z> [dimension]",
                "anvil clear",
                "anvil",
                "crafting on/off",
                "crafting",
                "craftorders on/off",
                "craftorders maxfills <n>",
                "craftorders maxperhour <n>",
                "craftorders",
                "craftingtable here",
                "craftingtable <x> <y> <z> [dimension]",
                "craftingtable clear",
                "craftingtable",
                "craftNeverUse add/remove <item id>/list"
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
                // A read the Stash Manager started is ended first, so turning sorting on starts a full run
                sorter.takeOver(on);
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
            .then(literal("safewalk").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.safeWalk = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Safe Walking " + toggleStrCaps(CONFIG.safeWalk));
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
            .then(literal("log").then(argument("level", enumStrings(LogLevel.values())).executes(c -> {
                String name = getString(c, "level");
                CONFIG.logLevel = java.util.Arrays.stream(LogLevel.values()).filter(l -> l.name().equalsIgnoreCase(name)).findFirst().orElse(LogLevel.Normal);
                c.getSource().getEmbed().title("Log Level Set").description(switch (CONFIG.logLevel) {
                    case Quiet -> "Quiet: step lines, errors and alerts only (alerts are also whispered to the whitelisted players).";
                    case Normal -> "Normal: step lines, the usual messages, errors and alerts.";
                    case Debug -> "Debug: everything, with the reasons behind each decision, marked [debug].";
                });
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
            .then(literal("splitDurability").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.splitDurability = getToggle(c, "toggle");
                // Where used and full items belong changes: sort again
                Stashes.version++;
                c.getSource().getEmbed().title("Split Durability " + toggleStrCaps(CONFIG.splitDurability));
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
            .then(literal("area")
                .executes(c -> {
                    showArea(c.getSource().getEmbed());
                })
                .then(literal("clear").executes(c -> {
                    Stash stash = Stashes.currentOrNew();
                    if (stash.area == null && stash.cornerA == null && stash.cornerB == null) {
                        c.getSource().getEmbed().title("No Stash Area").description("There is no stash area set.").primaryColor();
                        return;
                    }
                    stash.cornerA = stash.cornerB = null;
                    if (stash.area == null) {
                        stash.markDirty();
                        stash.save();
                        c.getSource().getEmbed().title("Corners Cleared").description("The corner that was set is forgotten.").primaryColor();
                        return;
                    }
                    queueArea(c.getSource().getEmbed(), stash, null, "Stash Area Cleared", "Stash area cleared, finding containers around the bot again.");
                }))
                .then(literal("here1").executes(c -> {
                    setCorner(c.getSource().getEmbed(), true);
                }))
                .then(literal("here2").executes(c -> {
                    setCorner(c.getSource().getEmbed(), false);
                }))
                .then(argument("x1", integer()).then(argument("y1", integer()).then(argument("z1", integer())
                    .then(argument("x2", integer()).then(argument("y2", integer()).then(argument("z2", integer()).executes(c -> {
                        Stash stash = Stashes.currentOrNew();
                        applyArea(c.getSource().getEmbed(), stash,
                            new int[] {getInteger(c, "x1"), getInteger(c, "y1"), getInteger(c, "z1")},
                            new int[] {getInteger(c, "x2"), getInteger(c, "y2"), getInteger(c, "z2")});
                    }))))))))
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
            .then(literal("containerDelay")
                .executes(c -> {
                    c.getSource().getEmbed().title("Container Delay").description(CONFIG.containerDelay + " ticks (0 is as fast as possible).").primaryColor();
                })
                .then(argument("ticks", integer(0, 100)).executes(c -> {
                    CONFIG.containerDelay = getInteger(c, "ticks");
                    c.getSource().getEmbed().title("Container Delay Set").description(CONFIG.containerDelay + " ticks: a container is not opened until then after the last one was closed, and the first click in a new one waits as long.");
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
            .then(literal("kitSorting").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.kitSorting = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Kit Sorting " + toggleStrCaps(CONFIG.kitSorting));
                Stashes.version++;
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
            .then(literal("smartRecheck").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.smartRecheck = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Smart Recheck " + toggleStrCaps(CONFIG.smartRecheck))
                    .description(CONFIG.smartRecheck ? "Only drop-offs a hopper or dropper can fill, or that are out of sight, are looked into, less often while they stay unchanged; the others when their lid moves." : "Every drop-off is looked into every recheck interval.");
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
                // A box that is excluded (by the user, or skipped for being outside the perimeter) is not read again
                stash.markAllStale();
                stash.save();
                Stashes.version++;
                Stashes.exploreVersion++;
                c.getSource().getEmbed().title("Stash Will Be Read Again").description("Every container is read again the next time sorting runs.");
            }))
            .then(literal("unstuck").executes(c -> {
                String what = MODULE.get(StashSorter.class).requestUnstuck();
                c.getSource().getEmbed().title("Stash Sorter Unstuck").description(what
                    + " A shulker of ours still standing is left where it is and listed with the orphaned shulkers.").primaryColor();
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
                // The names only when asked for: every other stash command shows just how many there are
                .executes(c -> {
                    whitelistShow(c.getSource().getEmbed());
                })
                .then(literal("list").executes(c -> {
                    whitelistShow(c.getSource().getEmbed());
                }))
                .then(literal("add").then(argument("player", word()).executes(c -> {
                    String player = getString(c, "player");
                    if (CONFIG.whitelist.stream().noneMatch(p -> p.equalsIgnoreCase(player))) {
                        List<String> next = new java.util.ArrayList<>(CONFIG.whitelist);
                        next.add(player);
                        CONFIG.whitelist = next;
                    }
                    c.getSource().getEmbed().title("Added " + player);
                })))
                .then(literal("del").then(argument("player", word()).executes(c -> {
                    String player = getString(c, "player");
                    List<String> next = new java.util.ArrayList<>(CONFIG.whitelist);
                    next.removeIf(p -> p.equalsIgnoreCase(player));
                    CONFIG.whitelist = next;
                    c.getSource().getEmbed().title("Removed " + player);
                }))))
            .then(literal("friends")
                // Players that do not count as someone else near the bot (the owner's alts); not the whisper whitelist
                .executes(c -> {
                    friendsShow(c.getSource().getEmbed());
                })
                .then(literal("list").executes(c -> {
                    friendsShow(c.getSource().getEmbed());
                }))
                .then(literal("add").then(argument("player", word()).executes(c -> {
                    String player = getString(c, "player");
                    if (!player.matches("[A-Za-z0-9_-]{1,36}")) {
                        c.getSource().getEmbed().title("Bad Name").description("A player name or a UUID: letters, digits, _ and -, up to 36.").errorColor();
                        return;
                    }
                    // Replaced, not changed in place: the tick thread reads the list
                    if (CONFIG.friends.stream().noneMatch(p -> p.equalsIgnoreCase(player))) {
                        List<String> next = new java.util.ArrayList<>(CONFIG.friends);
                        next.add(player);
                        CONFIG.friends = next;
                    }
                    c.getSource().getEmbed().title("Added " + player);
                })))
                .then(literal("del").then(argument("player", word()).executes(c -> {
                    String player = getString(c, "player");
                    List<String> next = new java.util.ArrayList<>(CONFIG.friends);
                    next.removeIf(p -> p.equalsIgnoreCase(player));
                    CONFIG.friends = next;
                    c.getSource().getEmbed().title("Removed " + player);
                }))))
            .then(literal("manager")
                .then(literal("status").executes(c -> {
                    managerStatus(c.getSource().getEmbed());
                }))
                .then(literal("url").then(argument("url", greedyString()).executes(c -> {
                    c.getSource().setSensitiveInput(true);
                    String url = getString(c, "url").trim();
                    URI parsed = StashManager.parse(url);
                    if (parsed == null) {
                        c.getSource().getEmbed().title("Bad Server Address").description("Use a http or https address without a query, fragment or login, like http://127.0.0.1:8765.").errorColor();
                        return;
                    }
                    CONFIG.managerUrl = parsed.toString();
                    c.getSource().getEmbed().title("Stash Manager Server Set").description(CONFIG.managerUrl).primaryColor();
                })))
                .then(literal("key").then(argument("key", greedyString()).executes(c -> {
                    c.getSource().setSensitiveInput(true);
                    // Never echoed: the reply only says it was set, and shows the last four characters
                    CONFIG.managerKey = getString(c, "key").trim();
                    c.getSource().getEmbed().title("Stash Manager Key Set").description("Key " + StashManager.maskKey(CONFIG.managerKey) + ".").primaryColor();
                })))
                .then(literal("includeExcluded").then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.managerIncludeExcluded = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Stash Manager: Include Excluded " + toggleStrCaps(CONFIG.managerIncludeExcluded));
                })))
                .then(literal("dashboard")
                    .then(literal("console").requires(c -> Command.validateCommandSource(c, CommandSources.TERMINAL)).then(argument("toggle", toggle()).executes(c -> {
                        CONFIG.dashboardConsole = getToggle(c, "toggle");
                        MODULE.get(StashManager.class).updateDashboard();
                        c.getSource().getEmbed().title("Dashboard Console " + toggleStrCaps(CONFIG.dashboardConsole))
                            .description(CONFIG.dashboardConsole ? "The website can run console commands here, like the terminal (commands only the terminal may run still refuse it)." : "The website can not run console commands here.");
                    })))
                    .then(literal("proxy").requires(c -> Command.validateCommandSource(c, CommandSources.TERMINAL)).then(argument("toggle", toggle()).executes(c -> {
                        CONFIG.dashboardProxy = getToggle(c, "toggle");
                        MODULE.get(StashManager.class).updateDashboard();
                        c.getSource().getEmbed().title("Dashboard Proxy Settings " + toggleStrCaps(CONFIG.dashboardProxy))
                            .description(CONFIG.dashboardProxy ? "The website can change the proxy's and other plugins' settings." : "The website can not change the proxy's or other plugins' settings.");
                    })))
                    .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.dashboard = getToggle(c, "toggle");
                    MODULE.get(StashManager.class).updateDashboard();
                    c.getSource().getEmbed().title("Stash Manager Dashboard " + toggleStrCaps(CONFIG.dashboard))
                        .description(CONFIG.dashboard ? "The website can show and change these settings and run commands here, while the manager is on." : "The website no longer reaches this proxy.");
                    })))
                .then(literal("scanInterval").then(argument("seconds", integer(2, 600)).executes(c -> {
                    CONFIG.managerScanInterval = getInteger(c, "seconds");
                    c.getSource().getEmbed().title("Stash Manager Scan Interval Set").description(CONFIG.managerScanInterval + " seconds.");
                })))
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.manager = getToggle(c, "toggle");
                    MODULE.get(StashManager.class).syncEnabledFromConfig();
                    c.getSource().getEmbed().title("Stash Manager " + toggleStrCaps(CONFIG.manager));
                    if (CONFIG.manager && StashManager.parse(CONFIG.managerUrl) != null && (CONFIG.managerKey == null || CONFIG.managerKey.isBlank())) {
                        c.getSource().getEmbed().description("No API key yet: set it with `stash manager key <key>` (the server prints it on its console).");
                    }
                })))
            .then(literal("orders")
                .executes(c -> {
                    ordersStatus(c.getSource().getEmbed());
                })
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.acceptOrders = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Accepting Orders " + toggleStrCaps(CONFIG.acceptOrders))
                        .description(CONFIG.acceptOrders ? ordersHint() : "The bot takes no new orders; one in hand is parked and resumed when this is turned on again.").primaryColor();
                })))
            .then(literal("delivery")
                .executes(c -> {
                    deliveryShow(c.getSource());
                })
                .then(literal("here").executes(c -> {
                    deliveryHere(c.getSource());
                }))
                .then(literal("clear").executes(c -> {
                    deliveryClear(c.getSource());
                }))
                .then(literal("expand")
                    .executes(c -> {
                        deliveryExpandStatus(c.getSource().getEmbed());
                    })
                    .then(argument("toggle", toggle()).executes(c -> {
                        CONFIG.autoExpandDelivery = getToggle(c, "toggle");
                        c.getSource().getEmbed().title("Auto Expand Delivery Storage " + toggleStrCaps(CONFIG.autoExpandDelivery))
                            .description(CONFIG.autoExpandDelivery
                                ? "When every chest of the delivery storage is full, another double chest is put up one block above the highest one (taken from the stash, or crafted when crafting is on). The lowest chest with room is filled first."
                                : "When the delivery storage is full an order waits for space (a player claiming an order empties a chest).").primaryColor();
                    })))
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer())
                    .executes(c -> {
                        deliverySet(c.getSource(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"), null);
                    })
                    .then(argument("dimension", word()).executes(c -> {
                        deliverySet(c.getSource(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"), getString(c, "dimension"));
                    }))))))
            .then(literal("anvil")
                .executes(c -> {
                    anvilShow(c.getSource());
                })
                .then(literal("here").executes(c -> {
                    anvilHere(c.getSource());
                }))
                .then(literal("clear").executes(c -> {
                    anvilClear(c.getSource());
                }))
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer())
                    .executes(c -> {
                        anvilSet(c.getSource(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"), null);
                    })
                    .then(argument("dimension", word()).executes(c -> {
                        anvilSet(c.getSource(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"), getString(c, "dimension"));
                    }))))))
            .then(literal("crafting")
                .executes(c -> {
                    craftingStatus(c.getSource().getEmbed());
                })
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.enableCrafting = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Crafting " + toggleStrCaps(CONFIG.enableCrafting))
                        .description(CONFIG.enableCrafting
                            ? "A plain shulker box is crafted when an order or compaction needs an empty one and the stash has none." + (CraftingTableSpot.isSet() ? "" : " With no crafting table set one is looked for in the stash area, taken from the stash or crafted when needed (`stash craftingtable here` picks one by hand).")
                            : "Nothing is crafted.").primaryColor();
                })))
            .then(literal("craftorders")
                .executes(c -> {
                    craftOrdersStatus(c.getSource().getEmbed());
                })
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.craftOrders = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Craft Orders " + toggleStrCaps(CONFIG.craftOrders))
                        .description(CONFIG.craftOrders
                            ? "Orders with craft lines are taken, when crafting is on" + (CONFIG.enableCrafting ? "" : " (it is off now: `stash crafting on`)") + " and a recipe table has been fetched from the Stash Manager server."
                            : "Orders with craft lines are not taken: the server will not give this bot one.").primaryColor();
                }))
                .then(literal("maxfills").then(argument("fills", integer(1, 100000)).executes(c -> {
                    CONFIG.craftOrderMaxFills = getInteger(c, "fills");
                    c.getSource().getEmbed().title("Craft Order Fills").description("One craft order may make at most " + CONFIG.craftOrderMaxFills + " grid fills.").primaryColor();
                })))
                .then(literal("maxperhour").then(argument("fills", integer(0, 100000)).executes(c -> {
                    CONFIG.craftOrderMaxPerHour = getInteger(c, "fills");
                    c.getSource().getEmbed().title("Craft Order Fills Per Hour").description("At most " + CONFIG.craftOrderMaxPerHour + " grid fills for craft orders in any one hour.").primaryColor();
                }))))
            .then(literal("craftNeverUse")
                .then(literal("add").then(argument("item", word()).executes(c -> {
                    String id = itemId(getString(c, "item"));
                    if (!CONFIG.craftNeverUse.contains(id)) {
                        // Copied, never changed in place: the bot tick reads the list meanwhile
                        List<String> next = new java.util.ArrayList<>(CONFIG.craftNeverUse);
                        next.add(id);
                        CONFIG.craftNeverUse = next;
                    }
                    c.getSource().getEmbed().title("Never Crafting With " + id);
                })))
                .then(literal("remove").then(argument("item", word()).executes(c -> {
                    String id = itemId(getString(c, "item"));
                    List<String> next = new java.util.ArrayList<>(CONFIG.craftNeverUse);
                    next.remove(id);
                    CONFIG.craftNeverUse = next;
                    c.getSource().getEmbed().title("Crafting With " + id + " Again");
                })))
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed().title("Never Crafted With").description(CONFIG.craftNeverUse.isEmpty() ? "Nothing is on the list." : String.join(", ", CONFIG.craftNeverUse)).primaryColor();
                })))
            .then(literal("craftingtable")
                .executes(c -> {
                    tableShow(c.getSource());
                })
                .then(literal("here").executes(c -> {
                    tableHere(c.getSource());
                }))
                .then(literal("clear").executes(c -> {
                    tableClear(c.getSource());
                }))
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer())
                    .executes(c -> {
                        tableSet(c.getSource(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"), null);
                    })
                    .then(argument("dimension", word()).executes(c -> {
                        tableSet(c.getSource(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"), getString(c, "dimension"));
                    }))))))
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
        boolean typed = pos != null;
        if (pos == null) pos = MODULE.get(StashTracker.class).lastClicked();
        if (pos == null) {
            embed.title("Which Container?").description("Give its position, or right-click it while connected to the proxy first.").errorColor();
            return;
        }
        // A typo (a digit too many) put a drop-off millions of blocks away, and the bot spent minutes trying to path to it
        if (typed && !Containers.isLoaded(pos)) {
            double away = Math.hypot(CACHE.getPlayerCache().getX() - (pos.x() + 0.5), CACHE.getPlayerCache().getZ() - (pos.z() + 0.5));
            if (away > 128) {
                embed.title("Too Far Away").description(String.format("%d, %d, %d is %,d blocks from the bot and not loaded, check the coordinates. Nothing was changed.",
                    pos.x(), pos.y(), pos.z(), Math.round(away))).errorColor();
                return;
            }
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
        int[] bounds = stash.bounds();
        if (stash.hasArea() && bounds != null && !stash.inArea(pos, 0)) {
            note += String.format(" Note: %d, %d, %d is outside the stash area %s, so the bot may not walk to it; widen the area with stash area here1/here2 if so.",
                pos.x(), pos.y(), pos.z(), Stash.describe(bounds));
        }
        Box box = stash.boxAt(pos);
        // Never a new container outside the perimeter: it would only be skipped
        if (box == null && stash.hasArea() && bounds != null && !stash.inArea(pos, 0)) {
            embed.title("Outside the Stash Area").description(String.format("%d, %d, %d is outside the stash area %s, so it was not added. Widen the area first with stash area here1/here2.",
                pos.x(), pos.y(), pos.z(), Stash.describe(bounds))).errorColor();
            return;
        }
        if (box == null) {
            box = Containers.isLoaded(pos) ? Containers.makeBox(pos) : new Box(pos, new java.util.ArrayList<>(List.of(pos)));
            stash.boxes.add(box);
        }

        String at = box.pos.x() + ", " + box.pos.y() + ", " + box.pos.z();
        // Skipped by the sorter for being outside the perimeter: nothing is changed (it would be skipped again at once)
        if (box.marksBlocked() && kind != MARK_COMPACT_SKIP) {
            embed.title("Outside the Stash Area").description(at + " is outside the stash area, so it is skipped (kept in the file, not read, sorted or listed). Widen the area with stash area here1/here2 to use it. Nothing was changed.").errorColor();
            return;
        }
        if (kind == MARK_COMPACT_SKIP) {
            box.noCompact = !box.noCompact;
            embed.title(box.noCompact ? "Compact Skip Set" : "Compact Skip Removed").description(at + (box.noCompact
                ? " is never used to gather stacks or shulkers for compacting." : " can be used for compacting again.") + note);
        }
        else if (kind == MARK_EXCLUDE) {
            box.excluded = !box.excluded;
            box.outsideSkip = false;
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

    /**
     * Shows the perimeter and its size.
     */
    private static void showArea(Embed embed) {
        Stash stash = Stashes.current();
        int[] b = stash == null || stash.area == null ? null : Stash.boundsOf(stash.area);
        if (b != null) {
            embed.title("Stash Area").description("Stash area: " + Stash.describe(b) + (stash.hasArea() ? "" : ", in another dimension (" + stash.area.dimension + "), so not used here") + ".").primaryColor();
        }
        else if (stash != null && (stash.cornerA != null || stash.cornerB != null)) {
            embed.title("Stash Area").description("Only one corner is set ("
                + corner(stash.cornerA != null ? stash.cornerA : stash.cornerB) + "). Set the other with `stash area here" + (stash.cornerA != null ? 2 : 1) + "`.").primaryColor();
        }
        else embed.title("No Stash Area").description("No stash area set: containers within radius " + CONFIG.radius + " and height " + CONFIG.height + " of the bot are used.").primaryColor();
        AreaPending.Request pending = AreaPending.get();
        if (pending != null) {
            String why = switch (AreaPending.why()) {
                case "far" -> "the area is more than 128 blocks from the bot";
                case "dimension" -> "the bot is in another dimension";
                default -> "waiting until the bot is between tasks";
            };
            embed.addField("Waiting To Be Applied", (pending.box() == null ? "clear the area" : Stash.describe(pending.box())) + " (" + (pending.website() ? "from the website" : "from a command") + "): " + why);
        }
    }

    private static String corner(int[] c) {
        return "[" + c[0] + ", " + c[1] + ", " + c[2] + "]";
    }

    /**
     * The bot's own block as one corner of the perimeter.
     */
    private static void setCorner(Embed embed, boolean first) {
        int[] here = {(int) Math.floor(CACHE.getPlayerCache().getX()), (int) Math.floor(CACHE.getPlayerCache().getY()), (int) Math.floor(CACHE.getPlayerCache().getZ())};
        Stash stash = Stashes.currentOrNew();
        int[] other = first ? stash.cornerB : stash.cornerA;
        if (other != null) {
            applyArea(embed, stash, first ? here : other, first ? other : here);
            return;
        }
        if (first) stash.cornerA = here;
        else stash.cornerB = here;
        stash.markDirty();
        stash.save();
        embed.title("Corner " + (first ? 1 : 2) + " Set").description("Corner " + (first ? 1 : 2) + " set at " + corner(here) + ". Set corner " + (first ? 2 : 1) + " to finish the stash area.").primaryColor();
    }

    /**
     * Sets both corners, if the box is not too large.
     */
    private static void applyArea(Embed embed, Stash stash, int[] a, int[] b) {
        int[] bounds = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        int[] size = Stash.sizeOf(bounds);
        if (size[0] > Stash.MAX_WIDTH || size[1] > Stash.MAX_HEIGHT || size[2] > Stash.MAX_DEPTH) {
            embed.title("Stash Area Too Large").description("That area is " + size[0] + "×" + size[1] + "×" + size[2] + ", the largest allowed is "
                + Stash.MAX_WIDTH + "×" + Stash.MAX_HEIGHT + "×" + Stash.MAX_DEPTH + " (width × height × depth).").errorColor();
            return;
        }
        stash.cornerA = stash.cornerB = null;
        queueArea(embed, stash, bounds, "Stash Area Set", "Stash area set: " + Stash.describe(bounds) + ".");
    }

    /**
     * A change of the stash area from the command thread: it is only queued (AreaPending); the sorter's tick applies it between tasks, so a held
     * order or a walk is never cut short. With the sorter neither on nor driven nothing runs to apply it, so it is applied here at once.
     * @param bounds minX, minY, minZ, maxX, maxY, maxZ, or null to clear
     */
    private static void queueArea(Embed embed, Stash stash, int[] bounds, String title, String done) {
        StashSorter sorter = MODULE.get(StashSorter.class);
        if (sorter.isEnabled() || sorter.isDriven()) {
            AreaPending.queue(new AreaPending.Request(bounds, Stash.currentDimension(), false));
            stash.markDirty();
            stash.save();
            embed.title("Stash Area Queued").description((bounds == null ? "Clearing the stash area" : "Setting the stash area to " + Stash.describe(bounds))
                + " waits until the bot is between tasks (no order, job or carried stacks); `stash area` shows it.").primaryColor();
            return;
        }
        stash.area = bounds == null ? null : new Stash.Area(new int[] {bounds[0], bounds[1], bounds[2]}, new int[] {bounds[3], bounds[4], bounds[5]}, Stash.currentDimension());
        AreaPending.discard();
        AreaPending.markEdited(AreaRules.hashOf(bounds, bounds == null ? null : Stash.currentDimension()));
        areaChanged(stash);
        embed.title(title).description(done).primaryColor();
    }

    /**
     * The perimeter changed: the sorter explores it afresh. With the sorter off, the containers outside it go right away.
     */
    private static void areaChanged(Stash stash) {
        if (!MODULE.get(StashSorter.class).isEnabled()) stash.dropOutsideArea();
        Stashes.exploreVersion++;
        Stashes.version++;
        stash.markDirty();
        stash.save();
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


    // Stash orders

    /**
     * Whether this command's output may carry the delivery container's position. It is only shown where the command was
     * typed: the proxy terminal, or the dashboard console. Discord and API output never has it, so nothing that gets
     * mirrored elsewhere (logEmbed to a channel) leaks it.
     */
    private static boolean showCoordinates(CommandContext ctx) {
        com.aquarius.command.api.CommandSource source = ctx.getSource();
        return source == CommandSources.TERMINAL || source instanceof com.porter.qwertestash.manager.DashboardCommandSource;
    }

    private static String ordersHint() {
        StringBuilder out = new StringBuilder("The bot takes orders from the website");
        if (!CONFIG.manager) out.append(" once the Stash Manager is on (`stash manager on`)");
        if (!DeliverySpot.isSet()) out.append("; set the delivery container first (`stash delivery here`)");
        return out.append('.').toString();
    }

    /** "whitelist: 3 players": the status shows how many, never who (`stash whitelist list` does). */
    static String whitelistCount() {
        int n = CONFIG.whitelist.size();
        return "whitelist: " + (n == 0 ? "empty" : n + (n == 1 ? " player" : " players"));
    }

    private static void friendsShow(Embed embed) {
        embed.title("Friends (do not count as someone near the bot)").primaryColor()
            .description(CONFIG.friends.isEmpty() ? "Nobody yet. Add your alts with `stash friends add <player or uuid>`." : String.join(", ", CONFIG.friends));
    }

    private static void whitelistShow(Embed embed) {
        embed.title("Whisper Search Whitelist").primaryColor()
            .description(CONFIG.whitelist.isEmpty() ? "Nobody yet. Add a player with `stash whitelist add <player>`." : String.join(", ", CONFIG.whitelist));
    }

    private static void ordersStatus(Embed embed) {
        StashManager.OrdersStatus status = MODULE.get(StashManager.class).ordersStatus();
        embed.title("Stash Orders").primaryColor()
            .addField("Accepting Orders", toggleStr(CONFIG.acceptOrders))
            .addField("Current Order", status.current() == null ? "none" : status.current())
            .addField("Waiting On The Website", status.pending() < 0 ? "unknown (no heartbeat yet)" : String.valueOf(status.pending()))
            .addField("Blocker", status.blocker() == null ? "none, ready for an order" : status.blocker())
            .addField("Delivery Container", DeliverySpot.isSet() ? "set (`stash delivery` shows it)" : "not set")
            .addField("Delivery Storage", MODULE.get(StashSorter.class).deliveryStorageLine())
            .addField("Anvil (Shulker Names)", AnvilSpot.isSet() ? AnvilSpot.source() + " (`stash anvil` shows it)" : "none yet: one is looked for in the stash area, or put down from the stash, when needed")
            .addField("Crafting", toggleStr(CONFIG.enableCrafting) + ", crafting table " + (CraftingTableSpot.isSet() ? CraftingTableSpot.source() : "none yet: one is looked for in the stash area, taken from the stash or crafted, when needed"));
    }

    private static void craftOrdersStatus(Embed embed) {
        com.porter.qwertestash.orders.Recipes table = com.porter.qwertestash.orders.Recipes.current();
        embed.title("Craft Orders").primaryColor()
            .addField("Enabled", toggleStr(CONFIG.craftOrders) + (CONFIG.enableCrafting ? "" : ", but crafting is off (`stash crafting on`)"))
            .addField("Recipe Table", table == null ? "none fetched yet: no craft orders are taken, and no shulker boxes crafted, until the Stash Manager server has sent one"
                : "Minecraft " + table.version() + ", " + table.size() + " recipes, hash " + table.hash().substring(0, 8))
            .addField("Crafting Table", MODULE.get(StashSorter.class).craftOrderTableLine())
            .addField("Tables Put Down", MODULE.get(StashSorter.class).craftTablePlacementsLine())
            .addField("Bot's Own Table", MODULE.get(StashSorter.class).botTable() == null ? "none in use" : MODULE.get(StashSorter.class).botTable().source() + " (position hidden)")
            .addField("Caps", CONFIG.craftOrderMaxFills + " grid fills per order, " + CONFIG.craftOrderMaxPerHour + " per hour (" + MODULE.get(StashSorter.class).craftOrderFillsLastHour() + " used this hour)")
            .addField("What", "items the website's planner says the stash can make; crafted at the crafting table set on the website (not `stash craftingtable`)");
    }

    private static void craftingStatus(Embed embed) {
        embed.title("Crafting").primaryColor()
            .addField("Enabled", toggleStr(CONFIG.enableCrafting) + (com.porter.qwertestash.orders.Recipes.current() == null ? ", but no recipe table has been fetched from the Stash Manager server yet: nothing is crafted until it has" : ""))
            .addField("Crafting Table", CraftingTableSpot.isSet() ? CraftingTableSpot.source() + " (`stash craftingtable` shows it)" + (CraftingTableSpot.usable() ? "" : ", but the block there is not a crafting table now") : "none yet: one is looked for in the stash area, taken from the stash or crafted, when needed")
            .addField("Crafted This Hour", MODULE.get(StashSorter.class).craftedLastHour() + " of " + CONFIG.craftMaxPerHour)
            .addField("Caps", CONFIG.craftMaxPerOrder + " per order, " + CONFIG.craftMaxOpsPerTask + " grid fills per task, " + CONFIG.craftMaxPerHour + " per hour")
            .addField("Never Used", CONFIG.craftNeverUse.isEmpty() ? "nothing listed" : String.join(", ", CONFIG.craftNeverUse))
            .addField("What", "plain shulker boxes for orders (bins and empty-shulker lines) and compaction, from shells, a chest, planks or logs in the stash; and the craft lines of orders (`stash craftorders`)");
    }

    // The crafting table: set like the anvil, and shown only where the delivery container is

    private static void tableShow(CommandContext ctx) {
        CraftingTableSpot.Spot spot = CraftingTableSpot.get();
        Embed embed = ctx.getEmbed();
        if (spot == null) {
            embed.title("No Crafting Table").description("None set, found, placed or crafted yet. When a craft needs one the bot looks for a crafting table standing in the stash area, and failing that takes one from the stash and puts it down, or crafts one in its 2x2 inventory grid from planks or logs from the stash; with none of that nothing is crafted (\"" + com.porter.qwertestash.stash.CraftingTableFinder.NONE + "\"). To choose one, look at a crafting table and run `stash craftingtable here`, or give its position with `stash craftingtable <x> <y> <z> [dimension]`.").primaryColor();
            return;
        }
        String how = switch (CraftingTableSpot.source()) {
            case CraftingTableSpot.FOUND -> "found by the bot in the stash area";
            case CraftingTableSpot.PLACED -> "put down by the bot from the stash";
            case CraftingTableSpot.CRAFTED -> "crafted by the bot and put down";
            default -> "set with `stash craftingtable`";
        };
        embed.title("Crafting Table").description(CraftingTableSpot.describe(spot, showCoordinates(ctx)) + ", " + how + (CraftingTableSpot.usable() ? "" : ". Warning: the block there is not a crafting table now.")).primaryColor();
    }

    private static void tableClear(CommandContext ctx) {
        boolean was = CraftingTableSpot.isSet();
        CraftingTableSpot.clear();
        ctx.getEmbed().title(was ? "Crafting Table Cleared" : "No Crafting Table")
            .description(was ? "Another is looked for, taken from the stash or crafted when a craft needs one." : "None was set.").primaryColor();
    }

    private static void tableHere(CommandContext ctx) {
        Embed embed = ctx.getEmbed();
        String dimension = Stash.currentDimension();
        if (!CACHE.getPlayerCache().isAlive() || dimension == null) {
            embed.title("Not In Game").description("The bot has to be in the game, looking at the crafting table.").errorColor();
            return;
        }
        var hit = com.aquarius.feature.player.raycast.RaycastHelper.playerBlockRaycast(DeliverySpot.LOOK_REACH, false);
        if (hit == null || !hit.hit()) {
            embed.title("No Crafting Table In Reach").description("The bot is not looking at a block within " + (int) DeliverySpot.LOOK_REACH + " blocks. Aim at a crafting table first.").errorColor();
            return;
        }
        BlockPos pos = new BlockPos(hit.x(), hit.y(), hit.z());
        if (!CraftingTableSpot.isTable(pos)) {
            embed.title("Not A Crafting Table").description("The block the bot is looking at is not a crafting table.").errorColor();
            return;
        }
        CraftingTableSpot.set(dimension, pos.x(), pos.y(), pos.z());
        embed.title("Crafting Table Set").description(CraftingTableSpot.describe(CraftingTableSpot.get(), showCoordinates(ctx)) + "." + (CONFIG.enableCrafting ? "" : " Turn crafting on with `stash crafting on`.")).primaryColor();
    }

    private static void tableSet(CommandContext ctx, int x, int y, int z, @Nullable String dimensionArg) {
        Embed embed = ctx.getEmbed();
        String here = Stash.currentDimension();
        String dimension = dimensionArg == null ? here : dimensionArg.trim().toLowerCase(java.util.Locale.ROOT);
        if (dimension == null || dimension.isBlank()) {
            embed.title("Which Dimension?").description("The bot's dimension is not known right now: add it, like `stash craftingtable <x> <y> <z> overworld`.").errorColor();
            return;
        }
        if (!dimension.matches("[a-z0-9_.:/-]{1,64}")) {
            embed.title("Bad Dimension").description("Use a name like overworld, the_nether or the_end.").errorColor();
            return;
        }
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < -2048 || y > 2048) {
            embed.title("Bad Position").description("That position is outside the world.").errorColor();
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        String note = "";
        boolean inThisDimension = here != null && com.porter.qwertestash.orders.OrderBlocker.sameDimension(here, dimension);
        if (!inThisDimension) note = " The bot is not in that dimension, so I could not check that it is a crafting table.";
        else if (!Containers.isLoaded(pos)) note = " Warning: that position is not loaded (out of range), so I could not check that it is a crafting table.";
        else if (!CraftingTableSpot.isTable(pos)) {
            embed.title("Not A Crafting Table").description("There is no crafting table at that position. Check the coordinates (with negative ones, round down: -5.3 is block -6).").errorColor();
            return;
        }

        CraftingTableSpot.set(dimension, pos.x(), pos.y(), pos.z());
        embed.title("Crafting Table Set").description(CraftingTableSpot.describe(CraftingTableSpot.get(), showCoordinates(ctx)) + "." + note).primaryColor();
    }

    private static void deliveryExpandStatus(Embed embed) {
        embed.title("Auto Expand Delivery Storage").primaryColor()
            .addField("Enabled", toggleStr(CONFIG.autoExpandDelivery))
            .addField("Storage", MODULE.get(StashSorter.class).deliveryStorageLine());
    }

    private static void deliveryShow(CommandContext ctx) {
        DeliverySpot.Spot spot = DeliverySpot.get();
        Embed embed = ctx.getEmbed();
        if (spot == null) {
            embed.title("No Delivery Container").description("Look at a chest or barrel and run `stash delivery here`, or give its position with `stash delivery <x> <y> <z> [dimension]`.").primaryColor();
            return;
        }
        embed.title("Delivery Container").description(DeliverySpot.describe(spot, showCoordinates(ctx))
            + "\n" + MODULE.get(StashSorter.class).deliveryStorageLine()).primaryColor();
    }

    /** How many chests put up above the delivery container there are (they become ordinary containers when it changes). */
    private static int extraDeliveryChests() {
        Stash stash = Stashes.current();
        if (stash == null || stash.deliveryStorage == null) return 0;
        int n = 0;
        for (var e : stash.deliveryStorage) if (e != null && !e.primary) n++;
        return n;
    }

    private static String extrasWarning(int n) {
        return n <= 0 ? "" : " Warning: " + n + " extra delivery chest" + (n == 1 ? " is" : "s are") + " now ordinary stash containers; their contents will be sorted.";
    }

    private static void deliveryClear(CommandContext ctx) {
        boolean was = DeliverySpot.isSet();
        int extras = extraDeliveryChests();
        DeliverySpot.clear();
        ctx.getEmbed().title(was ? "Delivery Container Cleared" : "No Delivery Container")
            .description((was ? "Orders can not be taken until a new one is set." : "None was set.") + extrasWarning(extras)).primaryColor();
    }

    private static void deliveryHere(CommandContext ctx) {
        Embed embed = ctx.getEmbed();
        String dimension = Stash.currentDimension();
        if (!CACHE.getPlayerCache().isAlive() || dimension == null) {
            embed.title("Not In Game").description("The bot has to be in the game, looking at the container.").errorColor();
            return;
        }
        var hit = com.aquarius.feature.player.raycast.RaycastHelper.playerBlockRaycast(DeliverySpot.LOOK_REACH, false);
        if (hit == null || !hit.hit()) {
            embed.title("No Container In Reach").description("The bot is not looking at a block within " + (int) DeliverySpot.LOOK_REACH + " blocks. Aim at a chest or barrel first.").errorColor();
            return;
        }
        BlockPos pos = new BlockPos(hit.x(), hit.y(), hit.z());
        if (!Containers.isContainer(pos, true)) {
            embed.title("Not A Container").description("The block the bot is looking at is not a chest or barrel.").errorColor();
            return;
        }
        BlockPos at = Containers.makeBox(pos).pos;
        int extras = DeliverySpot.isSet() && !Containers.makeBox(pos).has(DeliverySpot.get().pos()) ? extraDeliveryChests() : 0;
        DeliverySpot.set(dimension, at.x(), at.y(), at.z());
        embed.title("Delivery Container Set").description(DeliverySpot.describe(DeliverySpot.get(), showCoordinates(ctx)) + ". Finished order shulkers are put in here." + extrasWarning(extras) + (CONFIG.acceptOrders ? "" : " Turn orders on with `stash orders on`.")).primaryColor();
    }

    private static void deliverySet(CommandContext ctx, int x, int y, int z, @Nullable String dimensionArg) {
        Embed embed = ctx.getEmbed();
        String here = Stash.currentDimension();
        String dimension = dimensionArg == null ? here : dimensionArg.trim().toLowerCase(java.util.Locale.ROOT);
        if (dimension == null || dimension.isBlank()) {
            embed.title("Which Dimension?").description("The bot's dimension is not known right now: add it, like `stash delivery <x> <y> <z> overworld`.").errorColor();
            return;
        }
        if (!dimension.matches("[a-z0-9_.:/-]{1,64}")) {
            embed.title("Bad Dimension").description("Use a name like overworld, the_nether or the_end.").errorColor();
            return;
        }
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < -2048 || y > 2048) {
            embed.title("Bad Position").description("That position is outside the world.").errorColor();
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        String note = "";
        boolean inThisDimension = here != null && com.porter.qwertestash.orders.OrderBlocker.sameDimension(here, dimension);
        if (!inThisDimension) {
            note = " The bot is not in that dimension, so I could not check that it is a container.";
        }
        else if (!Containers.isLoaded(pos) && Math.hypot(CACHE.getPlayerCache().getX() - (pos.x() + 0.5), CACHE.getPlayerCache().getZ() - (pos.z() + 0.5)) > 128) {
            // Typed on the wrong bot, or a position copied from another one: the bot would make a box for it and try to walk there
            embed.title("Too Far Away").description(String.format("That position is %,d blocks from the bot and not loaded, check the coordinates (and that this is the right bot). Walk the bot near the container and use `stash delivery here`. Nothing was changed.",
                Math.round(Math.hypot(CACHE.getPlayerCache().getX() - (pos.x() + 0.5), CACHE.getPlayerCache().getZ() - (pos.z() + 0.5))))).errorColor();
            return;
        }
        else if (!Containers.isLoaded(pos)) {
            note = " Warning: that position is not loaded (out of range), so I could not check that it is a container.";
        }
        else if (!Containers.isContainer(pos, true)) {
            embed.title("Not A Container").description("There is no chest or barrel at that position. Check the coordinates (with negative ones, round down: -5.3 is block -6).").errorColor();
            return;
        }
        else pos = Containers.makeBox(pos).pos;

        DeliverySpot.set(dimension, pos.x(), pos.y(), pos.z());
        embed.title("Delivery Container Set").description(DeliverySpot.describe(DeliverySpot.get(), showCoordinates(ctx)) + "." + note).primaryColor();
    }

    // The anvil order shulkers are named at: set like the delivery container, and shown only where it is

    private static void anvilShow(CommandContext ctx) {
        AnvilSpot.Spot spot = AnvilSpot.get();
        Embed embed = ctx.getEmbed();
        if (spot == null) {
            embed.title("No Anvil").description("None set, found or placed yet. When an order wants its shulkers named the bot looks for an anvil standing in the stash area, and failing that puts one down from the stash; with neither they are delivered unnamed. To choose one, look at an anvil and run `stash anvil here`, or give its position with `stash anvil <x> <y> <z> [dimension]`.").primaryColor();
            return;
        }
        String how = switch (AnvilSpot.source()) {
            case AnvilSpot.FOUND -> "found by the bot in the stash area";
            case AnvilSpot.PLACED -> "put down by the bot from the stash";
            default -> "set with `stash anvil`";
        };
        embed.title("Anvil").description(AnvilSpot.describe(spot, showCoordinates(ctx)) + ", " + how + (AnvilSpot.usable() ? "" : ". Warning: the block there is not an anvil now; another is looked for when one is needed.")).primaryColor();
    }

    private static void anvilClear(CommandContext ctx) {
        boolean was = AnvilSpot.isSet();
        AnvilSpot.clear();
        ctx.getEmbed().title(was ? "Anvil Cleared" : "No Anvil")
            .description(was ? "Shulkers an order wants named are delivered unnamed until a new one is set." : "None was set.").primaryColor();
    }

    private static void anvilHere(CommandContext ctx) {
        Embed embed = ctx.getEmbed();
        String dimension = Stash.currentDimension();
        if (!CACHE.getPlayerCache().isAlive() || dimension == null) {
            embed.title("Not In Game").description("The bot has to be in the game, looking at the anvil.").errorColor();
            return;
        }
        var hit = com.aquarius.feature.player.raycast.RaycastHelper.playerBlockRaycast(DeliverySpot.LOOK_REACH, false);
        if (hit == null || !hit.hit()) {
            embed.title("No Anvil In Reach").description("The bot is not looking at a block within " + (int) DeliverySpot.LOOK_REACH + " blocks. Aim at an anvil first.").errorColor();
            return;
        }
        BlockPos pos = new BlockPos(hit.x(), hit.y(), hit.z());
        if (!AnvilSpot.isAnvil(pos)) {
            embed.title("Not An Anvil").description("The block the bot is looking at is not an anvil (anvil, chipped or damaged).").errorColor();
            return;
        }
        AnvilSpot.set(dimension, pos.x(), pos.y(), pos.z());
        embed.title("Anvil Set").description(AnvilSpot.describe(AnvilSpot.get(), showCoordinates(ctx)) + ". Shulkers an order wants named are named here before they are delivered (about 1 XP level each).").primaryColor();
    }

    private static void anvilSet(CommandContext ctx, int x, int y, int z, @Nullable String dimensionArg) {
        Embed embed = ctx.getEmbed();
        String here = Stash.currentDimension();
        String dimension = dimensionArg == null ? here : dimensionArg.trim().toLowerCase(java.util.Locale.ROOT);
        if (dimension == null || dimension.isBlank()) {
            embed.title("Which Dimension?").description("The bot's dimension is not known right now: add it, like `stash anvil <x> <y> <z> overworld`.").errorColor();
            return;
        }
        if (!dimension.matches("[a-z0-9_.:/-]{1,64}")) {
            embed.title("Bad Dimension").description("Use a name like overworld, the_nether or the_end.").errorColor();
            return;
        }
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < -2048 || y > 2048) {
            embed.title("Bad Position").description("That position is outside the world.").errorColor();
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        String note = "";
        boolean inThisDimension = here != null && com.porter.qwertestash.orders.OrderBlocker.sameDimension(here, dimension);
        if (!inThisDimension) note = " The bot is not in that dimension, so I could not check that it is an anvil.";
        else if (!Containers.isLoaded(pos)) note = " Warning: that position is not loaded (out of range), so I could not check that it is an anvil.";
        else if (!AnvilSpot.isAnvil(pos)) {
            embed.title("Not An Anvil").description("There is no anvil at that position. Check the coordinates (with negative ones, round down: -5.3 is block -6).").errorColor();
            return;
        }

        AnvilSpot.set(dimension, pos.x(), pos.y(), pos.z());
        embed.title("Anvil Set").description(AnvilSpot.describe(AnvilSpot.get(), showCoordinates(ctx)) + "." + note).primaryColor();
    }

    /** The Stash Manager in one line each: never the key itself. */
    private static String managerLine() {
        StashManager manager = MODULE.get(StashManager.class);
        return toggleStr(CONFIG.manager) + " - " + (CONFIG.manager ? manager.status() : "off") + ", " + CONFIG.managerUrl + ", key " + StashManager.maskKey(CONFIG.managerKey)
            + ", stash name \"" + CONFIG.stashName + "\" (each bot needs its own)";
    }

    private static void managerStatus(Embed embed) {
        StashManager manager = MODULE.get(StashManager.class);
        long at = manager.lastPushedAt();
        String last = at == 0 ? "never" : ((System.currentTimeMillis() - at) / 1000) + "s ago";
        String error = manager.lastError();
        String player = manager.player();
        embed.title("Stash Manager").primaryColor()
            .addField("State", toggleStr(CONFIG.manager) + " - " + (CONFIG.manager ? manager.status() : "off"))
            .addField("Server", CONFIG.managerUrl)
            .addField("Key", StashManager.maskKey(CONFIG.managerKey))
            .addField("Stash Name / Player", "\"" + CONFIG.stashName + "\" / " + (player.isEmpty() ? "unknown" : player)
                + " (each bot on the website needs its own stash name; set it with `stash name <name>`)")
            .addField("Last Index Push", last)
            .addField("Item Types", manager.itemTypes() < 0 ? "none yet" : String.valueOf(manager.itemTypes()))
            .addField("Last Error", error == null ? "none" : error)
            .addField("Include Excluded", toggleStr(CONFIG.managerIncludeExcluded))
            .addField("Scan Interval", CONFIG.managerScanInterval + " seconds")
            .addField("Dashboard", toggleStr(CONFIG.dashboard) + " - " + manager.dashboardStatus() + "; console commands " + toggleStr(CONFIG.dashboardConsole) + ", proxy settings " + toggleStr(CONFIG.dashboardProxy) + " (both only change in the proxy terminal: `stash manager dashboard console|proxy on|off`)");
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

    private static String stashAreaLine() {
        Stash stash = Stashes.current();
        int[] b = stash == null || stash.area == null ? null : Stash.boundsOf(stash.area);
        return b == null ? "none (containers within the radius and height are used)" : Stash.describe(b);
    }

    @Override
    public void defaultEmbed(Embed embed) {
        Stash stash = Stashes.current();
        String stashLine;
        if (stash == null) stashLine = "None saved here yet";
        else {
            // The delivery container and the chests put up above it are not the stash
            var mine = stash.boxes.stream().filter(b -> !b.delivery && stash.inScope(b)).toList();
            long excluded = mine.stream().filter(b -> b.excluded).count();
            long dropOffs = mine.stream().filter(b -> b.dropOff).count();
            long items = 0;
            for (var b : mine) if (b.known()) for (var it : b.contents) if (it != null) items += it.count;
            stashLine = String.format("%d containers (%d read, %d excluded, %d drop-off), %,d items",
                mine.size(), mine.stream().filter(b -> b.known()).count(), excluded, dropOffs, items);
        }

        embed
            .primaryColor()
            .addField("Sorting", toggleStr(CONFIG.sorting) + " - " + MODULE.get(StashSorter.class).statusLine())
            .addField("Sort Items", toggleStr(CONFIG.sortItems) + (CONFIG.sortItems ? "" : " (scan only: containers are read and kept up to date, nothing is moved)"))
            .addField("Collect Ground Items", toggleStr(CONFIG.collectGround) + " - " + MODULE.get(StashSorter.class).groundState()
                + " (items the bot didn't drop or break, " + CONFIG.collectRange + " blocks around the stash, only while no other player is in render distance)")
            .addField("Stash", "\"" + CONFIG.stashName + "\": " + stashLine)
            .addField("Mode", CONFIG.mode.name())
            .addField("Log Level", CONFIG.logLevel.name() + " (quiet: steps, errors and alerts; alerts are also whispered to the whitelist)")
            .addField("Stash Area", stashAreaLine())
            .addField("Safe Walking", toggleStr(CONFIG.safeWalk) + " (no parkour, jumping off or falling more than a block; sneaks at ledges)")
            .addField("Area", "radius " + CONFIG.radius + ", height " + CONFIG.height + ", barrels " + toggleStr(CONFIG.barrels))
            .addField("Repack Shulkers", toggleStr(CONFIG.repack) + " (signs mode" + (CONFIG.compact ? "" : ", new bulk at " + CONFIG.newBulkAt + " stacks") + ")")
            .addField("Compact Shulkers", toggleStr(CONFIG.compact) + " - " + MODULE.get(StashSorter.class).compactStatus()
                + " (signs mode, top up at " + CONFIG.compactTopUpAt + " stacks, " + CONFIG.compactExclude.size() + " items excluded)")
            .addField("Split Durability", toggleStr(CONFIG.splitDurability) + " (signs mode, used gear goes in containers signed \"Used\", full gear everywhere else)")
            .addField("Stay On", toggleStr(CONFIG.stayOn) + " (recheck " + CONFIG.recheckSeconds + "s, " + (CONFIG.smartRecheck ? "smart: only drop-offs a hopper or dropper can fill, backing off to 5 min" : "every drop-off") + ")")
            .addField("Carry / Keep Slots", (CONFIG.carrySlots.isBlank() ? "every empty slot" : CONFIG.carrySlots) + " / " + (CONFIG.keepSlots.isBlank() ? "none" : CONFIG.keepSlots))
            .addField("Delay", CONFIG.delayTicks + " ticks, container delay " + CONFIG.containerDelay + " ticks")
            .addField("Gives Way To Other Modules", "waits " + CONFIG.quietSeconds + "s after they stop, pearl slot (hotbar 1) kept for PearlPlus " + toggleStr(CONFIG.reservePearlSlot))
            .addField("Watch Chests", toggleStr(CONFIG.watchAccess) + " (re-read " + CONFIG.accessRescanSeconds + "s after another player closes one; needs tracking on)")
            .addField("Auto Label", toggleStr(CONFIG.autoLabel) + " (signs mode, a sign every " + CONFIG.labelGapSeconds + "s at most, see `stash labels`)")
            .addField("Unsigned Overflow", toggleStr(CONFIG.unsignedOverflow) + " (signs mode, stacks with no room go in unsigned containers after misc/other/overflow ones)")
            .addField("Kit Sorting", toggleStr(CONFIG.kitSorting) + " (a shulker that scores as a kit goes to the chest picked on the website)")
            .addField("Orphaned Shulkers", orphanLine())
            .addField("Watch Signs", toggleStr(CONFIG.watchSigns) + " (changes used after " + CONFIG.signSettleSeconds + "s, a sign gone or blank for 60s; needs tracking on)")
            .addField("Auto Drop", toggleStr(CONFIG.autoDrop) + " (gives back what a player dropped near the bot, within " + (int) CONFIG.autoDropRange + " blocks)")
            .addField("Stash Manager", managerLine())
            .addField("Tracking", toggleStr(CONFIG.tracking))
            .addField("Whisper Search", toggleStr(CONFIG.whisperSearch) + ", prefix \"" + CONFIG.prefix + "\", " + whitelistCount() + " (`stash whitelist list` shows them)");
    }
}
