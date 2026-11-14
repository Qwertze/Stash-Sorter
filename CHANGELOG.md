# Changelog

All notable changes to Stash Sorter are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.20.0] - Unreleased

### Added
- **The farmer role** (FARM-CONTRACT v1.0 draft 2.4, item 2.6; needs Stash Manager server 1.5.0 and the 2.6p proxy patches; not tried on a real farmer CT yet, see "Tested" below). A bot with the farmer role replays a recorded farm program (made with the addon's recorder, 0.12.0) and keeps the farm running: it walks the recorded loop, corrects drift at the recorded anchors and stops on STOP, danger or time. M2 runs the modes `custom` and `afk_still`; every other mode is refused with `mode_not_ready`.
  - **Off by default.** `stash farm on` (proxy terminal only) turns the role on; the server must also give the bot the `farmer` role. With the role on the dashboard link, the index and heartbeat, the viewer, map tiles, delivery and kit reports and the pearl heartbeat fields are off, enforced in code (`FarmerGuard`): nothing of a stash leaves this bot and no farm setting is shown on the dashboard.
  - **No coordinates ever leave the bot.** Programs hold positions relative to a secret origin; the origin lives only in an encrypted location store. Every body to the server goes through `ReportGuard` (no position key, no position-like string, closed vocabularies). `FarmLogGuard` is a logback filter on the terminal, file and debug-file appenders that drops the proxy's position-bearing lines (the INFO "Server teleport", the pathfinder goal, the VisualRange and CoordObfuscation lines and the debug ones) by source and message prefix, then by the position and a decimal-triple pattern.
  - **Encrypted stores** in `plugins/qwerte-stash/farm/`: `<farmId>.qse`, `draft.qse` and `locations.qse` (AES-256-GCM through `qwerte-core` `Seal`; the program's hash is in the authenticated data, so a file cannot be swapped or rolled back). The key is `farm/farm.key` (32 random bytes, created atomically with mode 0600, the folder 0700) or the environment variable `QWERTE_FARM_KEY` (base64 of exactly 32 bytes). A file system that cannot prove those modes (Windows) fails closed with `key_perms`: use the environment key there. **A key file that is short or empty is `key_invalid` and is never deleted or replaced: remove or fix it by hand.** Sealed files with no key are `key_missing`: a new key is never made over them. Losing the key loses every stored program and origin; each farm must then be recorded again.
  - **Uploads** over the Bridge (`qwerte/farm/*`): hello, begin, chunks, origin, end. Handlers run on the network thread, validate before they allocate (C6), catch `Throwable` and answer a closed code; inflating, checking and sealing run on one worker. Off until the owner types `stash farm accept [minutes]` in the proxy terminal (1 to 10 minutes, default 10; it refuses the dashboard, Discord, the console route and Bridge), and it turns itself off after one successful upload. At most 4 uploads in 10 minutes, none while a session is open or parked, one terminal line per upload (mode, counts, 8 hex of the hash, a code). The Bridge `ready` message gives farm names only to the sessions of `stash farm owner add <player>` (empty by default). A farm id that is not in the last synced list is refused `busy` (use the draft); `stash farm attach <id>` binds the draft.
  - **`TaskSync`**: `tasks/sync` every 5 s while a task runs and every 15 s idle, claim, report and leases against the server's routes. A 401 is retried (2, 4, 8 s, then every 15 s for 2 minutes) before it counts as a key problem; a `409 lease_lost` is never retried; reports carry only closed vocabulary (`checkpoint.data.anchorId`, a result only with a terminal state, an error code that belongs to the state). After the first sync a bot with no role says one minimal `bot/hello` (no scopes, no settings) so the owner can find it to give it the role.
  - **Playback** (`FarmPlayer`): movement keys, jumps, sneaks, sprints and the look advance one track tick per bot tick, so the bot's physics steps match the recording at any tick rate and every one-tick tap is applied; only click spacing (the use cadence, the attack cooldown) is kept in wall-clock time, and a click waits for the held slot to be the chosen item. Each click carries its own tick's rotation. Drift is checked at every anchor and at the 20-tick samples; a recovery paths to the anchor (30 s), walks to the stand point, sets the look, checks the block fingerprint (`farm_changed`) and restarts the track at that anchor. Three failed recoveries in a session or more than six in an hour end `drifted`. Three click misses in a row start a recovery. A pause (a player connects, a disconnect, the server unreachable for `farmOfflineGraceSeconds`) never trusts the step counter: the session resumes only by the recovery to the last anchor passed.
  - **Safety**: STOP (server or `stash tasks stop`, which works with the server down and persists) parks within a tick; a non-friend within 8 blocks pauses the inputs, within the farm's radius for its seconds ends `player_near`; falling health at or below `healthStop`, no food with nothing to eat, no totem in the off hand for 2 s, no free slot for 30 s end the session. The start checks (`FarmerGuard.preflight`) refuse with `key_*`, `unsafe_bridge`, `unsafe_discord`, `unsafe_logging`, `unsafe_rotation`, `module_conflict` or `guard_off` (the CT table is in the README).
  - **Commands** (proxy terminal only, except where said): `stash farm on|off|status|list|accept [minutes]|start <id>|stop|forget <id>|attach <id>|selftest|owner add|del <player>`, `stash tasks stop|resume|list` (`stop` also from Discord and in game, `resume` from the terminal or Discord, neither from the dashboard). `stash farm selftest` prints the key source and its 4-byte id (never the key), a seal round trip, the proxy build ids and each start check.
  - **Settings** (not on the dashboard): `farmerEnabled`, `farmInputPriority` (6500), `farmOfflineGraceSeconds` (600), `farmResumeAfterController` (true), `farmOwners`.
  - **Not in this build** (refused `mode_not_ready` when a program has them): container steps (`take-from`, `put-into`), `hold-use`, `attack` steps and `wait-until` (M3).
- `qwerte-core` 1.1.0 (the `farm` package) is synced into the plugin.
- Needs patch 08 of the 2.6p set: the patched proxy sends `hello(side="proxy")` on its own when a player logs in (Bridge on), so the addon can wait for it before it sends anything. The plugin now accepts the Bridge build id `2.6p-2` only (`BridgeProtocol.FARM_HARDENING`); the Discord id stays `2.6p-1`. A proxy built from patches 01 to 07 only (`2.6p-1`) refuses with `unsafe_bridge`.

### Review fixes (code and security review of 1.20.0)
- **`unsafe_viewer`:** sessions, uploads and `stash farm accept` are refused while the proxy's viewer API is on (`server.viewer.enabled` or `control`, or unreadable); the `stash farm` and resume commands also refuse while viewer control is on. `stash farm selftest` shows both values, `server.verifyUsers` and the whisper search.
- **Logging:** the log filter is checked on all three appenders at every session start and resume (a logback reconfigure drops it) and put back if missing; the cause chain and E-notation numbers are scanned.
- **No position feeds:** whisper search and alerts are off while the role is on (and a whisper search on is a `module_conflict`); every feed gate is also wired at its real call site (map tiles, delivery contents, kit report, pearl heartbeat fields, viewer feed); `stash` subcommands that show positions are off and the stash status block is not printed.
- **Sync:** a task whose report is refused (4xx, a conflict, the guard) or whose lease is lost is dropped at once, its reports cleared and never reported as running again; the STOP flag in the journal is compared with the first sync; an unreadable journal means stopped; a failed journal write is logged and retried.
- **Threads:** the session is the bot tick's only. Stopped and Starting are queued for it (the server is told of a park at once from a snapshot); `stash farm off` ends the session through the tick and reports it before the workers stop; uploads time out and register their handlers without ticks, and a disconnected peer drops its upload; replies to a peer are throttled.
- **Uploads:** the worker re-checks the role, the switch and the session before storing; a session start waits for an active upload; `ready` is built from memory; a program and the location store are staged and replaced together with a rollback.
- **Playback:** resume verifies with a grace period (a blank dimension or a stale position is not an end) and returns to the phase it was parked in; a recovery pauses near a non-friend; a session parked as `pathfinder_busy` retries and ends `error` after 5 minutes.
- **Small:** the manager path is appended to the base URL, `maxMs` is clamped to the farm's limit, a farm the owner disabled is not started locally, the build ids are matched as full strings, a Click module with a non-zero extra reach is a conflict.

### Tested
- Unit tests for the guards, the log filter, the accept switch, upload validation, the stores, `TaskSync`, playback and drift in a simulator, and pause and resume. A scratch run (a patched proxy, an offline server, a headless client, the real Stash Manager server 1.5.0 build) did `afk_still` and a three-loop `custom` program end to end, with the upload over the Bridge, claim, reports, STOP, a local STOP, drift recovery and a pause and resume. It is **not** tried on a real farmer CT or on 2b2t.

### Changed
- Needs the 2.6p proxy patch set (Bridge hardening, the size cap, no coordinates in the Discord death and spawn-patrol embeds, no goal in the pathfinder log line, and the build ids the farmer checks). An unpatched proxy keeps the farm handlers off (`unsafe_bridge`, `unsafe_discord`).

## [1.19.0] - Unreleased

### Added
- **Enchant orders** (ENCHANT-CONTRACT v1.0 draft 3.3, needs Stash Manager server 1.4.0; not tried in the game yet). The bot makes an enchanted item from a base item and enchanted books taken from the stash, with experience bottles for the levels, and packs it into the order's shulker. Off by default
  (`enchantOrders`, `stash enchantorders on`, set in game or in the proxy terminal only).
  - **Library.** The plugin carries a synced copy of `qwerte-core` (the anvil rules, the 1.21.4 and 1.21.11 tables and the planner) under `com/porter/qwerte/core`, sources and the `enchant-*.json` tables (they are in the shaded jar). `tools/sync-qwerte-core.ps1` and `./gradlew syncQwerteCore` refresh it; `checkQwerteCore` runs before
    `compileJava` and fails the build when the copy differs from `../Qwertze/qwerte-core` (line endings ignored; a missing library folder is a warning and a skip). The check's code (`buildSrc`, `CoreSync`) is also compiled into the tests.
  - **Index.** Item entries of books and gear carry `ench` (enchantments with levels, namespaced ids), `rc` (the stored repair cost, left out when unknown: a stash saved before this release) and `named` (custom name). Items now remember their `REPAIR_COST` (`Item.repairCost`, -1 in older saves).
  - **Heartbeat and claim.** `enchant: {v: 1, enabled, mc, carry}` (`carry` = free carry slots after the gear is stowed; an explicit null once when the option is turned off); the claim carries `enchantOrders: 1` (and `player`, as every claim does) with the option on, a supported Minecraft version and none of `Enchanter`, `KitMaker` or `OrderFiller` on, or while an enchant order is held.
    The reply's `enchant.bottlesReserved` is passed as `heldOrderBottles` to `RepairRules.usable` in both places repair uses it (a figure older than 5 minutes counts as 0).
  - **`EnchantLoads`.** Every enchant item is a load of its bin (a new lot state, `ENCHANT`), made at the anvil before the bin is built. `OrderFlow` keeps an `EnchantLedger` (the budget of the order's `take`, every item's progress, the journal, `result.enchant`) saved in `Saved.enchant`.
  - **Preflight** (`EnchantPreflight`, pure): the anvil, then the base, the books, all the bottles with their margin and one spare anvil, by the `take` keys only with a per-key budget (as `CraftLedger`); the carry formula (`EnchantCarry`); the plan made again with the real items under `Limits.DEFAULT` (`EnchantReplan`: `cost_mismatch` when it costs more XP points, or carries a curse nobody agreed to); `timeout` after 10 minutes without a stack taken or more than `12 + 4 x leaves` trips.
  - **Steps** (`EnchantTrip`, pure, behind a `Port`): the damaged Mending gear is stowed and the bottles go to a selected hotbar slot (`GearStow`, with the ledger `RepairTrip` uses; see Review fixes: the off hand is not touched); per step `XpLeveler` throws single bottles to the level (batches while far, singles within 3 bottles of it), the bot walks to the anvil, puts the left item in slot 0 (exactly one) and the right in slot 1,
    compares the shown cost, takes the result and checks its enchantments (`wrong_result`). A broken anvil is replaced by the spare. Out of bottles it takes more from the stash (the repair reserve may be used, other open orders' bottles not); with none it waits and says so. A player within 8 blocks or a player controlling the bot pauses it between steps; a pause past step 1 has no time limit.
  - **Cancel.** Before step 1 as before. Past it the cancel is queued: the item is finished, then the order is cancelled and everything returned. A force-cancel (the server answers a report with 409) stops at the next step boundary; while a cancel is queued the bot reports only between steps, so the report that acknowledges a force-cancel comes at a boundary.
  - **Journal.** Each step is saved (the plan made with the real items and the steps done). After a reconnect carried stacks are found again by what they are, a step whose result is carried is done, and a resume that is impossible ends the item short and returns everything.
  - **Guards.** An order is refused as `module_conflict` while the proxy's `Enchanter`, `KitMaker` or `OrderFiller` is on, and as `mc_mismatch` when the order's Minecraft version is not the bot's. (`AutoMend` and `AutoArmor` were added to this list in the review fixes below.)
  - **Result.** `result.enchant` (`made`, `short` with reasons from the closed set only, `levels`, `bottlesUsed`, `anvilsBroken`, `returned`); no positions anywhere, in the report, the logs or the chat.

- **Crafting a missing base for an enchant order** (ENCHANT-CONTRACT draft 3.4, item 1.9; needs a server that sends `craft.forEnchant`, i.e. one with craft orders and enchant orders both on; not tried in the game yet). When the stash has no usable base for an enchant line, the server adds the base to the order's `craft` block (`craft.forEnchant: [ {key, count} ]`) and marks the plan's base material `crafted: true` (a fresh plain item, not in `enchant.take`). The bot:
  - **Parses** `craft.forEnchant` (optional; there but wrong makes the craft block unreadable, so every craft line is short with `recipe_mismatch`) and `crafted` (a crafted base that is enchanted, has a repair cost or an index key, or is marked on a book, makes the enchant block unreadable). `Order.hasCraft()` is true for an order with only `forEnchant`, so an enchant order with no craft lines still gets its craft stage; `CraftOrder.check` also refuses a `forEnchant` the craft lines do not cover. Without the fields nothing changes.
  - **Crafts first.** The bases are loads of the last bin that has enchant items (`Order.Lot.toBase`, variant `enchantbase`), added after planning so they are never packed, counted or delivered. They are made like any craft load (the same ledger, table, lots, take budget and chunks) before anything else in the order (`OrderFlow.next`), a base before the bin's other craft loads.
  - **Uses the crafted item as the plan's base.** `EnchantPreflight.decide(owned, craftedBases, liveCarry)`: nothing is taken for a crafted base (no gather, never charged to the ledger, so a real base of the same key keeps its budget), the plan is made again with the real crafted stack (a fresh stack of the plain id: no enchantments, no repair cost, not named), and the base is counted in the carry formula (its slot is part of the live carry). The sorter keeps the crafted stacks set aside (`orderCraftHeld`) so an item's put-back does not return them and a gather does not count them as its own. The anvil trip finds a base by what it is (fresh item), so it works on resume too.
  - **Returns what was not used.** When the bin's enchant items are all made (or the order ends) the unused bases stop being the bin's loads (`craftBasesReleased`): they are surplus of the craft, put back with the leftovers and counted in `result.craft.returned`. An item that ends short never drops its base load (the first step may be a book merge): a base still carried stays an unused base, returned and reported; a stale load is harmless.
  - **A craft that fails** (`result.craft.short`, a recipe table that does not match, no table) makes the plans that need a crafted base short with `no_base` (their preflight finds none); the other plans go on. This is before step 1 of each item, so it never fails a running item.
  - **Reports** `result.craft` as usual (`wanted` counts the bases, `made`, `short`, `used`, `returned`); the bases are never "crafted items not delivered". The claim needs both flags as before; there is no new capability flag.
  - **Slots (one formula with the server).** For every item in plan order the server checks `slotsNeeded + L <= carry`, `L` = the later items whose base is crafted (1 slot each). The bot's live carry is the free slots after the gear is stowed plus the slots its owned stacks take now, plus 1 for this item's own crafted base (`EnchantCarry.ownBaseSlot`); the later bases are already in the inventory, so they are not free. Same answer (3 bases, 6 slots an item: carry 7 refuses 3 items and fits 2, carry 8 fits 3).
  - **Never packed.** Made bases are kept out of every bin's match (`BaseSlots`, fresh plain stacks only, as many as the made loads say), so a plain line of the same item never takes one; a slot whose base was used up and now holds the enchanted result is not a base. Base loads have their own wait and round counters (`baseLoadsWaitSince`, `baseLoadRounds`), so a stall shorts only bases.
  - **Known limit.** `CraftLedger` `made` and `wanted` are per item key: with an ordered craft line and a base of the same item, a short craft can't say which of the two it was.
  - **Journal and resume.** The base loads are saved with the order's plan (state PENDING, then CRAFTED) and the craft ledger with them, so a restart between crafting and enchanting does not craft again; `craftResumed` matches the carried stacks to the made base loads slot by slot, and the journal matches them by what they are.

### Review fixes (1.19.0)
- **Guard (owner's decision).** `AutoMend` and `AutoArmor` now refuse enchant orders with `module_conflict` like `Enchanter`, `KitMaker` and `OrderFiller` (planning, each preflight, heartbeat `enabled:false`). `AutoTotem` may stay on: the bottles are thrown from the MAIN hand and `GearStow` no longer touches the off hand (it selects a hotbar slot for the bottles and selects the old one back).
- **Trip.** The gear is always put back when the trip is aborted during stowing; a window that closes mid-step opens again (3 tries, then `NEED_ANVIL`, never `SHORT` for that); a result still in the anvil slot is taken again; the result is taken with one quick-move click when it lands in a carry slot; a force-cancel in a level pause or while throwing ends the trip at once with `FORCE_CANCELLED`; `specOf` can't recurse on a bad plan.
- **Resume past step 1.** A running item is not failed by `enchantAbandon`: a proxy module on makes it wait ("waiting: turn off <module>"), another Minecraft version or the option off let it finish and refuse the items not started. `enchantBoundary` is reset on reset and on taking an order up.
- **Journal.** `EnchantLedger.restore` parses everything first and applies it only if all of it parsed; each saved plan is checked (step indices, costs, `Planner.verify`) and steps done are capped; an unsound plan makes the item short with `wrong_result` and its stacks go back. Top-ups are counted per item and capped at twice its planned bottles; the wait message says whether there is no free carry slot, the limit is used or the stash is out.
- **Limits.** An order block with more than 2,048 bottles an item, a take count above 100,000, points above 1,000,000 or a step that points at a missing item is unreadable; `EnchantCarry.slotsNeeded` uses long math.
- **Files.** The saved stash file and its `.tmp` are made owner-only where the system allows (best effort, as the pearl journal), and a file above 256 MiB is not loaded (logged, left untouched). `CoreSync.sync` refuses when the library has no package folder, so it never wipes the copy; a relative `-Pqwerte_core` is resolved against the project directory.

### Changed
- A cancelled order that had enchant lines reports its `result.enchant` (as craft and kit orders do).
- A report answered with 409 while the order is still running stops it as before (`OrderFlow.cancelHard`); for an enchant item past its first step it stops at the next step boundary.

### Tests
- New: `XpLevelerTest`, `EnchantTripTest` (against a fake anvil that uses the real anvil rules), `EnchantPreflightTest`, `EnchantLedgerTest`, `EnchantFlowTest`, `EnchantCarryTest` (the contract's worked numbers), `GearStowTest`, `EnchantIndexTest` (index fields, heartbeat, `bottlesReserved`), `CoreDriftTest` (the drift check), `EnchantServerIntegrationTest` (against the server jar; runs only with `-PstashManagerJar`). New for the missing-base craft: `EnchantCraftBaseTest` (parsing, the preflight with a crafted base, and the order end to end over `OrderSim` and `EnchantSim`: craft first, nothing taken for the base, an unused base returned, a failed craft gives `no_base` for those plans only, a restart does not craft again); `OrderSim` gained an enchant hook.

## [1.18.0] - 2026-11-11

### Added
- **Pearl travel** (PEARL-CONTRACT v1.0 rev 7, needs Stash Manager server 1.3.0; not tried in the game yet). A traveller bot goes from its stash to a pearl station and back by
  whispering `load <pearl id>` to the station's loader bot (PearlPlus), and re-arms the pearl it came through. Off by default (`pearlTravel`, `stash pearl travel on`). New package
  `pearl/`: `PearlTrip` (a pure state machine in the style of `OrderFlow`, run by the shared test vectors), `PearlTripDriver` (the world, the journal and the heartbeat),
  `AwayGuard`, `PearlJournal` (`plugins/qwerte-stash/pearl-trip.json`), `PearlReply`.
  - **Away guard.** From the whisper until the bot is home again the sorter, the index push, a website rescan and the repair passes do nothing, and the order blocker says
    `travelling/away`, so no order is claimed. A way back (`back`) clears it when it ends and asks for one rescan. A failed pull turns it off only when the bot is still where it
    whispered (the guard of a failed `back` stays on: the bot is still away); anything else is `hold` and the owner clears it on the website or with `stash pearl home`.
  - **Trip rules.** Local precheck (in the game for at least the grace period, with its chat ready (online for 15 s), out of the queue, in the tab list, sorter at a checkpoint, no order or repair pass, nobody
    within 16 blocks, health and food, at least 2 pearls; `busy_order`, `busy_repair`, `sorter_busy` wait without using up tries, the others are retried 3 times 30 s apart), one
    whisper and one retry, the pull deadline from the server (150 s by default), arrival as a jump of 8 blocks in one tick or any dimension change (the new dimension is reported),
    the late-pull watch for 5 minutes, the pickup (5 s) and settle (2 s) before the throw, and the throw through PearlDrop (`beginDepositNear`, the nearest free chamber within 4 blocks
    as a fallback). A trip is resumed from the journal after a disconnect or a restart.
  - **Loader, home chamber, PearlDrop.** A trip starts only when the loader reports within 60 s and is in its station's tab list (otherwise `loader_stale`; at `go` the bot waits up to 90 s before it fails). The way back needs a return pearl at home: the throw takes a free chamber within 2 blocks of where the bot stands first, and only when there is none the nearest free one within 4 blocks, so stand next to the home chamber when the server asks for `arm_home`. If the throw fails the trip does not start. PearlDrop switches itself on the first time a throw starts, so nothing needs enabling.
  - **Heartbeat and reply.** `pearlTravel` in the heartbeat (every 3 s and at once while a trip is on, 15 s otherwise; an explicit `null` once when travel is turned off) and the
    reply's `pearlTrip` and `pearlAwayClear`. The loader name and the pearl id are taken only from the reply, and only when they have the shape of a player name and a pearl id.
    A pull that comes before the server has heard of the whisper is reported as `awaiting_pull` with its teleport time (the server takes the whisper only from that step).
  - **Away clear.** The server repeats `pearlAwayClear` in every reply until the bot's heartbeat says `away: null` (the acknowledgement), for at most 10 minutes (rev 7). The bot turns its away guard off on it, and a repeat changes nothing.
  - **Commands.** `stash pearl travel on/off`, `loader on/off`, `grace <seconds>`, `go <station id>`, `back`, `home`, `home force`, `stop`, `status`. `travel` and `grace` are refused from the dashboard console (set them in game or in the proxy terminal); `home force` is terminal only. `home` asks the server to clear the away mark
    (it does so only for a bot it sees away with no trip and no alert).
- **Pearl loader report** (with PearlPlus auto load on; `pearlLoader`, on by default). Reads PearlPlus the way the activity feed does and sends `POST /api/v1/bot/pearls`
  (signed, with the bot id) every 30 s and at once when a load changes, `pearlGoneAt` / `requesterNearAt` are seen or a chamber's presence changes. `PearlReporter`, `PearlSource`,
  `PearlReport`, `LoadTracker`, `LoadAuth`.
  - **Only what the owner approved.** The server's `allow` answer (the travellers the owner approved, each with the uuid the owner pinned) is the only authority: before the first
    answer the report has no chambers; a pearl whose owner is not listed, or whose uuid (the one PearlPlus keyed it by, from the game server) is not the pinned one, is not reported and
    no load for it is reported; a listed name with no pin is shown as an unknown owner. The list is forgotten 10 minutes after the last answer and after a 409. The server's binding and
    the owner's approval of this bot as the loader (on the website) are needed before any list arrives.
  - **Presence.** `armed` only for a pearl entity that is seen. Not seen is `empty` only after the grace period (the bot, and the owner in the tab list, have been in the world for
    `pearlGraceSeconds`, 45 s) and in range (PearlPlus's `autoDetect.temporaryRemovalRange`, 32 blocks by default), in the dimension the chamber was last seen armed in; the owner not in the tab list is `owner_offline`; everything else is `unknown`.
  - **Loads.** PearlPlus sends no event, so a load is seen from the pathfinder's interact process: `loading`, then `loaded` when the pearl entity is gone after the walk ended, else
    `aborted` after 5 s (`path_failed` after three path tries, `disconnected`, else `timeout`). The loader never says `loaded` for a pearl that is still there.
- New settings `pearlTravel` (off), `pearlLoader` (on), `pearlGraceSeconds` (45, 15 to 300).

### Review fixes (1.18.0)
- Loader: `loaded` is now reported (the pearl asked about is the one of the load in progress when none was watched before); not seen must last 5 s before `empty`; PearlPlus's own range rule is
  used; the copy of PearlPlus's players is guarded; a load whose requester is no longer approved is dropped and a null load sent; the Pearl Loader activity text names a requester only when
  the owner approved that traveller (else "a pearl"); a link error from the loader role clears on success.
- Traveller: a queue position, a death or a respawn is never read as a pull (a death after the whisper is held for the owner); the sorter starts no new job from the checkpoint to the whisper, and the go
  re-checks the sorter, bystanders and the loader in the tab list (a wait, 90 s, then `loader_stale` / `player_near` / `stopped`); the whisper (and the retry) is written ahead to the journal and is
  not sent if that fails; offers that do not fit (a way back from home, a trip while away) are ignored; an away clear during an open trip is ignored; pull deadlines are clamped to the next
  10 minutes; `home` works with travel off and the bot keeps reporting while away; `stash pearl home force` (terminal only); `stash pearl travel` / `grace` are refused from the dashboard and
  `pearlTravel` is read only on the website; a new way back is let through during the watch of a failed one; at least 5 minutes between `go` trips; the journal is validated (word lists,
  clamped counts, dimension, finite numbers) and written owner-only where the OS allows it (on Windows it keeps the folder's permissions); a failed `armHome` says to stand next to the home chamber.
- Last round: the sorter hold of a trip that does not go (travel off, or no go within the wait and the server's 2 minutes, or the manager off) is released; `joinedAt` is reset on a disconnect; the
  loader looks at the pearl of a new load in progress; a pearl first seen in range during a load that started out of range can still be reported gone; journal times are clamped to the clock; the loader
  tab-list match ignores case; a whisper that could not be sent revokes the trip (no `load_sent`); the first OCCUPIED after a reconnect mid-throw is a success; the end of the watch is journaled; the
  journal is written for an away clear only when something changed; `pearlGraceSeconds` is read only on the website.
- Round 3: a global limit on whispers of any kind (at least 30 s apart, at most 10 in a rolling hour; both kept in the journal, so a restart does not reset them): the go (and a way back) waits locally,
  then fails with `stopped`; a retry is skipped until the limit passes. The 5-minute spacing stays for `go` only. A reply with a missing or unknown `state` is refused whole. A server `hold` on a trip that
  never whispered is only a failed trip (the guard is not turned on). A new login without a disconnect (a server switch; `ClientConnectEvent`, or a changed join time) resets the movement baseline and the
  join time. The `arm_home` throw is strict (PearlDrop's 2-block tolerance only, no fallback); the re-arm on arrival keeps its 4-block fallback. A local `stash pearl go` / `back` / `home` that gets no answer in
  four heartbeats now says so (an alert and a status note: "needs Stash Manager 1.3.0, or the server refused it").

### Fixed
- The Pearl Loader activity item no longer shows the pearl's position ("at x y z"): coordinates are not sent in the activity text.

### Notes
- Whispers are never read for trip logic and no sender is believed from chat text: the traveller only sends the `load` whisper, and the loader reports for the uuid PearlPlus keyed the
  pearl by (taken from the game server), checked against the owner-pinned uuid.
- No coordinates leave the bot in pearl travel: stations, trips, reports, whispers and logs carry ids only. The journal holds the place the bot whispered from, locally only.
- Not changed: `ProxySources` still reports the bot's own block position in the connection state of the activity feed (existing behaviour, not part of pearl travel).
- Compatibility: plugin 1.18.0 needs Stash Manager server 1.3.0 for pearl travel; an older server just means no trips.
- Older servers: without `pearlTrip` in the reply the bot never starts a website trip, and a local `go` or `back` that gets no answer in four heartbeats raises an alert and a status note ("needs Stash Manager 1.3.0, or the server refused it"). A loader of a server older than 1.3.0 gets no
  `allow` and reports no chambers.

### Not tested in game
- Nothing in pearl travel or the loader has been run in game. The in-game spike (`PEARL-SPIKE.md`) was skipped, so these are unverified: pearls after a rejoin and in the queue (the plugin treats a pearl that is not seen as `unknown` until the grace period has passed, and a trip never starts from the queue), the whisper's sender identity, a pull across dimensions, the arrival and the re-arm, and which chamber PearlDrop picks.
- The loader can't see PearlPlus's own queue, so it reports `loading` and `loaded` only.
- Automated tests cover the state machine, the report rules and the shared vectors. They do not run the game.
- The Qwerte addon's pearl support is not part of this release.

## [1.17.0] - 2026-11-10

### Added
- **Repair passes** (REPAIR-CONTRACT v2, with "opening unnamed shulkers"; not tried in the game yet). When the bot is idle it finds low-durability items in the stash (below
  the website's threshold, default 50%; exactly half is not low), takes the Mending ones (loose, or out of unnamed shulker boxes it opens) and bottles from the stash,
  repairs them fully with the v1.2 throw (RepairTrip), and lets normal sorting put every item in its best chest. Settings come from the website's Repair panel (repair mode
  and automatic passes on by default; threshold, bottle reserve 1728, needs-repair chest, open unnamed boxes) and are cached in `repair-settings.json`.
  - **Bottle reserve:** a pass never takes more than `max(0, stashBottles - heldOrderBottles - reserve)`; an item whose bottles don't fit is skipped with `reserve`.
  - **Needs-repair routing:** with a needs-repair chest set, a loose low item without Mending has that chest as its only destination (after kit routing, before tiers and
    signs); the chest is reserved (nothing else is sorted into it); Mending items are never routed there; if it is also kit-reserved, kit reservation wins and routing is off.
  - **Boxes:** an unnamed, non-kit shulker box holding a Mending candidate is opened (one at a time, nobody within 16 blocks, at most `repairPassMaxBoxes` (8) per pass, not
    again for 24 h unless its contents change); named boxes and kits are never opened and their low items are counted. Items that came out of a box and could not be
    repaired are not packed into a shulker again for 24 h.
  - **Yielding:** a pass gives way at every safe point to an order (waiting, claimed or resumable), a player within 8 blocks, a stop, or the sorter being switched off,
    puts everything back and reports `yielded`; an item that came back unrepaired is not retried by automatic passes for 6 h (manual passes retry it).
  - **Settings here:** `repairPass` (on), `repairPassMaxItems` (64), `repairPassMaxMinutes` (20), `repairPassEveryMinutes` (30), `repairPassMaxBoxes` (8). Commands:
    `stash repair run | stop | status`, `stash repairpass on/off`.
  - **Protocol:** the heartbeat carries `repairHash` and `repairPass` (state, why, progress, candidates, bottles, `runDone`, `stopDone`, `reportHash`) while repair passes are on;
    the reply's `repairSettings`, `repairRun`, `repairStop` and `needRepairReport` are handled (a run or stop is acknowledged once accepted); the report goes to
    `POST /api/v1/repair/report` (signed like the index, at most every 10 s). A server without passes is never asked.
- **Review fixes for repair passes.** The run and stop ids the bot acknowledged are kept per stash in `repair-settings.json`, so a restart does not run the last website
  request again. A pass counts friends as players near (8 blocks, which also stops a throw; 16 blocks before a box is placed); an order repair is unchanged. The box a pass
  emptied is put back before the next load and before the pass ends. Items tried with no headway in a pass are left out of its later plans, manual passes too. A pass waits
  for the first heartbeat reply after connecting. A queued manual run is dropped while repair is disabled on the website and expires after 10 minutes. The scan and the
  bottle count are kept per stash version. The needs-repair chest takes back the items routed to it. With no kit settings yet, the default kit score keeps kit-looking unnamed
  boxes closed. An unknown needs-repair chest id switches routing off (warned once, without coordinates). Settings from an older server with no `shulkers` field read as
  false (boxes stay closed) and keep their own hash. The cache file is read with the depth limit.
  - **New local limits (also for manual runs):** `repairPassMinGapManualMinutes` 5, `repairPassMaxPerHour` 4 (the start times are kept in `repair-passes.json`, so a restart does
    not reset them; 0 is no limit), `repairMinReserve` 0 (the reserve is the higher of the website one and this).
  - **Box cooldowns are memory only** (24 h, lost on a restart); a restart can open a box again sooner than 24 h. The foreign-module and quiet-time facts of the gate are the
    sorter own: it never starts a job while another module works or before the quiet time has passed, and the heartbeat state now says so.
  - **Not in this version:** orders do not take `newKey` items for an `oldKey` a pass repaired (the 24 h key memory is kept, but unused); `toNeedsRepair` is the change in
    the number of needs-repair items in the chest over the pass; `rehomed` counts repaired items whose best chest is not where they were. The report's `to` is always null (not reported yet).

### Notes
- Jar `StashSorter-1.17.0.jar`, SHA-256 `af3798575142bc607cc193a62fb3fc26a5c3b352a87f2bca6c5047dcb26cf6ea`, 813 automated tests.
- Repair passes have not been tried in the game yet. With a Stash Manager server before 1.2.0 the bot makes no automatic passes (its cached settings are kept); `stash repair run` still works locally. The Qwerte addon has no repair passes yet.

## [1.16.1] - 2026-11-09

### Fixed
- **A stash area set on the website left the sorter waiting forever.** The website writes the area's dimension as `minecraft:overworld`, the bot's own is `overworld`, and the
  check that the area applies here compared them as plain text, so `stash area` said "in another dimension" and the sorter did nothing (a website rescan read nothing).
  Every comparison of dimensions now goes through one helper (`Dimensions.same`), which treats the two forms as the same; a stash file that already holds the namespaced
  dimension works as it is (the stored form is kept, because the area hash is made from it). A real wait for the area's dimension is now said once a minute with both
  dimensions, and a website rescan that has waited 2 minutes behind it reports the reason to the server as its status.

## [1.16.0] - 2026-11-09

### Added
- **Kit sorting v2, phase 3a** (KIT-CONTRACT v2 draft, revisions 3 and 4). A shulker box is now a kit by the seven types of the shared
  `kit-types.json` table (pvp, travel, redstone, farming, building, starter, other; fingerprint `c3ffa894...`) instead of the v1 score: an assortment covers
  at least three quarters of its slots (half with the word kit, or a word of the type, in its name), a box of a single kind of item is stock and never a
  kit, and the stash's `treatAs` overrides win. The v1 score is used until the server sends v2 settings (an older server never does).
- **Kit templates.** A shulker that matches a template of the website's Kit Builder (every item present, counts met, no extras; variants by match key) is a
  complete copy; a box that carries the template's name with some of it missing is an incomplete copy (`template_incomplete`) and the report says what is
  missing. Match keys leave out the custom name, damage, repair cost, custom data, container and bundle contents (and a shulker's block entity data), so
  a damaged or renamed copy still matches; the index carries them as `items[].match`, including shulker contents.
- **Chain of chests.** A kit goes to the first usable of: its name's rule, its template's chest, its category's chest, the general kits chest. A stored kit
  only moves up its chain, never down because a chest filled up. A full chain is not a warning; a chain with no existing chest is. Category and template
  chests are kit-reserved like the kits chest.
- **Report and heartbeat.** The kit report carries `classifier`, `types[]` (with `why`), `others`, `templates` (complete, incomplete, elsewhere, carried,
  missing) and `restock` (its state; see phase 3c). The heartbeat carries `kitsHash2`, the hash of the cached v2 settings (null without them).

- **Kit orders, phase 3b** (KIT-CONTRACT v2 revisions 3 and 4). The bot takes orders with `type: "kit"` lines (the claim carries `kitOrders: 1`; the heartbeat
  carries `kitOrders {v, enabled, restock, craft}` while kit sorting is on and the type table is readable; `stash` setting `kitOrders`). Before any click the
  kit block is checked (version 1, 27 slots, counts within the stack size); a failure makes every kit short with `template_mismatch` and the rest of the order
  goes on. Complete copies are fetched from pickable containers (renamed when the name differs); each other kit is built from the fullest incomplete copy
  in its chain target (when every stack of it sits in a template slot with that slot's count), else an empty shulker of the template colour, else any
  (noted `colour_substituted`). Items are gathered by match key within `take` (exact slots: least damaged first; `#*` slots: plain first, then least damaged,
  then the lowest key), one stack of each kind first, through the existing bin builder (ClickPlanner, BinStep), named at the anvil with the template name,
  and delivered. Routed kits (incomplete copies included) are never unpacked as sources. A kit that cannot be completed is never delivered: with two or more
  kinds and a name it stays in the inventory as an incomplete copy for the sorter to file in its chain target, otherwise it is reported `returned`.
  `result.kit` has `made`, `short` (per item, with `stored`) and `notes`; the phase reads `kit n/m: name`; cancel and giving way work as for bins.
- **Kit restock, phase 3c** (not tried in the game yet). An idle job after everything is put away and before compaction: while kit sorting and kit orders are on,
  `kitRestock` (on) offers the order machine a restock order for one template with a keep count: top-ups of the fullest incomplete copy in its chain target
  first, then new copies (most missing first, then by id), only from unreserved stock, never crafted, experience bottles kept above the reserve of 1728, at
  least `kitRestockMinEmpty` (2) empty shulkers left, at most `kitRestockPerHour` (12) jobs an hour (the stamps are kept in
  `plugins/qwerte-stash/kit-restock.json`, so a restart does not reset the cap). The job is the kit-order build (same bin builder, naming at the anvil); it is never
  reported to the server and never saved, nothing is delivered, and the named kit stays carried for the sorter to file in its chain target (an incomplete copy when
  it fell short with two kinds and a name, otherwise its items are put back). It gives way at once to a waiting order, a player, the queue or a switch-off: it is
  cancelled and what it carries is put back. The report's `restock` shows `idle`, `building` (`topping_up`), `waiting` or `blocked` with the reason, and the
  heartbeat says `kitOrders.restock`.
- **Review fixes (code and security review of 1.16.0).** A base copy may hold part of a slot (40 of 64): the missing count is added to that stack, and the bin
  tracker counts a stack that is the sum of two lots of its kind. A kit holds at most 27 stacks (same-variant pieces merge; a slot that would need more is
  unavailable). A kit that is not built (no shulker, fewer than two kinds) gives back what it drew. A kit that cannot be completed and cannot be stored, or a
  cancelled build, is emptied back (the shulker taken apart, items and shulker put away), never left mixed. Restock does not start while an order is saved to
  resume, before the first heartbeat reply of a connection, or with orders off, and stops when orders are switched off; its wording is "Kit restock" in logs and
  the Now-doing card. Skipped kits report their real reason. Restock prefers plain, then unnamed stacks, and never takes enchanted stacks for any-variant
  slots. The restock record drops stamps far in the future and sorts the rest. `ShulkerNames` refuses names with control, zero-width or bidi format characters, or unpaired surrogates (in
  templates and at the anvil). One scan of the stash serves all templates of a restock decision (at most 16). Claimed orders are parsed with `SafeJson` (depth limit 32).
  A take entry must be a slot match key of the block's templates. The kit lookup of the router is built once per view.
- **Re-review fixes.** A topped-up slot counts as one stack against the 27, so a full template with a part-filled base can be completed. The kit to empty back is
  tied to the unit's own unnamed shulker (a stored incomplete copy is never taken apart), waits while shulker handling is paused, and the queue is cleared on
  reset; a complete kit is no longer emptied when an order is cancelled (it is filed in its chain target). Heartbeat replies are parsed with `SafeJson` (depth limit 32). Restock
  looks at the first 16 kits with a keep count (by id); the rest are not restocked.
- **Fix** (found by the restock tests): kit lines were also counted by the item planner, which made a kit-only order fail with "none usable in the stash".

### Changed
- Kit settings may carry a `types` part (categories, chests, treatAs, templates) with its own hash; the v1 hash formula is unchanged, and settings without the
  part work as before.

### Notes
- Kit orders and restock have not been tried in the game yet. Restock starts only after the first heartbeat reply of a connection, with
  orders on and no order held or to be resumed; it is not timed from the connection.
- Exact-amount gathering takes whole stacks only.
- The Qwerte addon does kit sorting with the v1 score only. Its kit v2 and Kit Builder bot work come in a later addon release, and its
  local `build-kits` stays until then.
- Jar `StashSorter-1.16.0.jar`, SHA-256 `9b408bab0a84f07e7f2e0317e560aa4a647897fd91abc25bd24093ab3616f4e4`, 788 automated tests.

## [1.15.2] - 2026-11-09

### Added
- **Stash area editor, the plugin side** (area contract v1.0 draft, see the Stash Manager server's AREA-CONTRACT). The stash area (the perimeter) can be set,
  expanded and cleared from the website; the bot takes it from the heartbeat reply (`areaHash`, `area`). The heartbeat carries `area`: `hash` (SHA-1 of
  `"area-v1\n" + minX,minY,minZ,maxX,maxY,maxZ,dimension + "\n"`, or of no area), the `area` in use, `derived` (the bot's working box when none is set: the
  read, in-scope containers' blocks widened by 2 sideways and 1 up and down, cut to 256×128×256 around its centre), `edited` (changed in game, until the
  server has it), `pending` (`hash` and `why`: `busy`, `far` or `dimension`) and `containersOutside`. It needs the player name, and is left out without one.
- **An area is applied only between tasks.** A received area is checked (shape, 256×128×256, the Y limits of its dimension, and that it is the stash's
  dimension), then queued; the sorter's tick applies it once no order is held or resumable, no job runs, no shulker is handled, placed or pending, no
  repair, craft, anvil or naming run is going, and nothing is carried for a task. A change of area never cuts a walk, an order or a pass short. A website area
  more than 128 blocks (horizontally) from the bot waits (`far`), and so does one for another dimension (`dimension`).
- **`stash area` changes also wait** (`here1`/`here2`, `<x1> <y1> <z1> <x2> <y2> <z2>`, `clear`): the command thread only queues the change and the sorter's tick
  applies it between tasks (with the sorter neither on nor driven, nothing could apply it, so it is applied at once). `stash area` shows what is waiting and
  why. A local change sets `edited` so the server takes it.
- Everything is optional both ways: an older server sends no `areaHash`, and the bot keeps its own area.

## [1.15.1] - 2026-11-09

### Fixed
- **Every crafting table showed as `unreachable` on a stash without a perimeter.** The 1.15.0 check for the website table (the stash's dimension and
  within 64 blocks of the stash area) read the area from `stash area`, which most stashes never set, and refused the table when there was none.
  A stash without a perimeter now uses the box of its containers widened by the search radius (`stash radius`, `stash height`) as its area, and
  the dimension the containers are read in when none was recorded. A table is out of bounds only when it is really in another dimension or far away.

### Changed (review)
- A table set by hand (`stash craftingtable`) that is outside the limits is never used for craft orders, and never forgotten or overwritten: craft orders
  use a table of their own (found, placed or crafted) meanwhile, and `auto` is not reported unless another route exists.
- The area of a stash without a perimeter is its containers' box widened by the search radius, at most 128 across and 64 high around the bot (the region
  the table search looks through); the dimension is taken from the bot only when it read the stash's containers this session (else `no_dimension`). Found
  tables are judged against the same area.
- A held craft order is always claimed with `craftOrders: 1`, whatever the table is like for the moment.
- **Older servers:** a server that answers 400 to the newer `craftOrders` fields (`table: "auto"`, `tableWhy`, `botTable`) gets the 1.15.0 shape from then
  on (`auto` as `unset`, no new fields), for that server and session. Deploy the server first.
- The bot's own table is made of plain stacks only (no named or enchanted items), and at most 1 table is put down for an order and 3 an hour (counted in
  the settings file, shown in `stash craftorders`). A found table that turns out unusable is skipped and the search is made once more; a start that
  starts nothing is retried after a short wait and given up on after three tries. The survey of the stash for table materials is kept until the stash
  changes.

- **Crafting a table for shulker boxes** (1.10.0) now uses plain, unnamed planks, logs or crafting tables only: named, enchanted or otherwise patched stacks
  are never used to make or place a table (the same rule as for craft orders).
- Whether a table is near the stash no longer depends on where the bot stands: with no perimeter it must be within the search radius (and height) plus 64 blocks
  of one of the stash's containers. The dimension a container was read in is remembered for the session and used instead of the bot's current one. Only a 400
  whose error names the craft fields (`craftOrders`, `tableWhy`, `botTable`, `table`) switches to the 1.15.0 shape, which is tried again every 10 minutes; any
  other 400 is a normal error. A table is counted against the caps (1 per order, 3 per hour) only when it is really put down.

- New `tableWhy` code `no_material`: no table is known or found, none is in the stash, and there are no planks or logs to make one of. With crafting and
  craft orders on and a recipe table held, `craftOrders` is now always sent, with `table: "unset"`, `enabled: true` and the reason, so the website shows why
  (the claim flag stays off); it is `null` only when crafting or craft orders are off or there is no recipe table. The 1.15.0 shape for an older server
  still leaves `tableWhy` out.

### Added
- **Craft orders no longer need a website table.** With none set, the bot finds a crafting table standing in the stash area, else takes one out of
  the stash and puts it down at a safe spot, else crafts one (4 planks, or a log made into planks first, in the 2x2 grid) and puts that down, as for
  shulker box crafting. That table is kept as the bot's own and reused; if it goes missing the search starts again. All of it within the same limits
  (the stash's dimension, within 64 blocks of the area, height -64 to 320). A website table still takes priority. The planks, logs or table the bot
  uses for itself are never taken from what the order's craft lines count on. If none can be had the lines are short with `no_table`.
- Heartbeat `craftOrders`: `table` can be `"auto"` (no website table; the bot finds, places or crafts one, counted as usable), `tableWhy`
  (`no_area`, `no_dimension`, `wrong_dimension`, `too_far`, `not_table`, `no_stand`, `path_failed`, `not_loaded` or null) and `botTable`
  (`{"pos":"x,y,z","source":"found"|"placed"|"crafted"}` or null; the server hides it from streamers). The claim carries `craftOrders: 1` when crafting is on,
  craft orders are on, a recipe table is held, and either the website table works or the auto path is available (a table is found, or the stash holds a table,
  planks or logs, and the stash has an area and a dimension). `stash craftorders` shows the state, the reason and the bot's own table (no positions).

## [1.15.0] - 2026-11-09

### Added
- **Crafting from the stash** (craft contract v1.0; see the Stash Manager server's CRAFT-CONTRACT.md and PROTOCOL.md). The build planner and the
  Printer can order items the stash does not hold but can make from what it does hold. The order has `craft` lines and a `craft` block (the server's
  plan: the recipes, the grid fills, the ingredients it may take from the stash, what is left over). The bot crafts them at the crafting table set
  for the stash **on the website** (not the one of `stash craftingtable`), and delivers them in the order's shulkers. It needs `stash crafting on`
  and `stash craftorders on` (the default), and a recipe table fetched from the server (below).
  - **Before any click** the bot checks the order's craft block (version 1; the block names the hash of the bot's own recipe table; every recipe
    is in the table with the same result and count; every ingredient is accepted by a cell of its recipe; the grid fills are within `craftOrderMaxFills`;
    every ingredient id is a plain id). If anything is wrong every craft line is short with `recipe_mismatch`, nothing is crafted and the rest of the
    order goes on.
  - **Craft loads join the packer** as loads of the result item, and are crafted per shulker just before it is built: a sub-plan with the server's
    own algorithm, but only the recipes of the order's `craft.steps` and only the ingredients of its `take` (never more of an id than `take` over the
    whole order, never an alternative outside it), at most 4 levels deep. Ingredients come from the stash by the same rules as the order's item lines,
    plain stacks only. Several recipes' worth is crafted in chunks as big as the free carry slots allow (the stacks taken from the stash, one for each
    step's product, the result and a spare); a chunk is never more than one stack of the item, and the result is made exactly the load's size. The
    surplus of the steps between, and of the item itself, stays carried from one shulker to the next and goes back to the stash by the normal put-back
    when the order ends (also after a cancel or a failure); `result.craft.returned` lists it.
  - **A craft never fails an order.** A line that can not be made is short with a reason (`no_table`, `table_missing`, `table_blocked`, `ingredients_short`,
    `inventory_full`, `recipe_mismatch`, `grid_stuck`, `timeout`, `interrupted`, `cap`, `cancelled`), the shulker is built without it, and the order's message
    gets "<n> item(s) not crafted: <reasons>". A table that is not set, is no crafting table, or can not be reached or opened (twice) ends crafting for the rest
    of the order. Yielding to players is as for other crafts (one retry, then `interrupted`). A cancel stops at a safe point, empties the grid, puts everything
    back and reports `cancelled` with `result.craft`.
  - The status carries `result.craft` (`fills`, `made`, `short`, `used`, `returned`) and the phase "crafting 3/11 fills: Spruce Stairs". A restart in the middle
    carries on from the saved ledger.
  - **Heartbeat.** `craftOrders` (`v`, `enabled`, `recipes`, `mc`, `table`: `unset`, `ok`, `unloaded`, `not_table`, `unreachable` or `other_dimension`;
    `maxFills`; `seen`: up to 8 crafting tables standing in the stash area, for the website's quick pick) and `craftHash`; the reply may carry `craft` (the
    website's crafting table, checked against its own hash, cached atomically in `plugins/qwerte-stash/craft-settings.json`, never logged) and
    `recipesHash`. The claim carries `craftOrders: 1` only while the bot can craft; without it the server never gives it a craft order. A bot with no
    recipe table sends `craftOrders: null`.
  - New settings: `stash craftorders on/off` (`craftOrders`, default on; needs crafting on), `stash craftorders maxfills <n>` (`craftOrderMaxFills`, 2048) and
    `stash craftorders maxperhour <n>` (`craftOrderMaxPerHour`, 4096 grid fills an hour), all on the website too.
- **Recipe tables are fetched, not shipped.** `GET /api/v1/bot/recipes?mc=<version>` (signed like every other request, with `If-None-Match`) gives the table the
  server built from the game jar; the plugin checks its shape, computes its `tableHash` (SHA-1 over the recipes sorted by id, as the contract defines it) and
  caches it atomically as `plugins/qwerte-stash/recipes-<mc>.json`, with the hash stored alongside: a cached file whose hash computed again does not match is
  discarded. It is fetched at start (when there is none) and whenever a heartbeat reply's `recipesHash` is not the hash of the table the bot holds, at most once
  a minute, with a back-off after a failure.

### Notes
- The heartbeat's `craftOrders.seen` (up to 8 crafting tables standing in the stash area, as "x,y,z") is always sent while the bot can craft; the website
  needs it for its quick pick, and the server hides it from forced-streamer users. The website crafting table itself is never logged by the plugin. The
  table must be in the stash's dimension and within 64 blocks of the stash area (y from -64 to 320); otherwise it is reported as `unreachable` /
  `other_dimension` and crafting stops with `table_blocked`.
- Craft orders are refused (`recipe_mismatch`) when a craft line asks for more than 100,000, the lines for more than 1,000,000 in all, a line is not covered
  by the steps that make it, or the lines would need more than 4096 loads; order lines with a count over 1,000,000 are not read.
- The craft ledger is saved with the surplus carried: after a restart the carried stacks are set aside again slot by slot, and surplus that is no longer
  carried is given back to the budget. The budget is charged from what was gathered for a chunk, never from a later plan. The final report waits (at most 90
  seconds) for the crafted leftovers to be put back, so `result.craft.returned` is what really went back; crafted items that did not reach a delivered
  shulker add "<n> crafted item(s) not delivered" to the message.

### Changed
- **The bundled recipe table (`qwerte-stash/recipes/1.21.4.json`) was removed from the plugin and is now fetched from the Stash Manager server.**
  **Shulker box crafting (1.8.0) now needs a Stash Manager server that has the recipes route** (`GET /api/v1/bot/recipes`; the server update that is
  deployed together with this plugin). It uses the same table, so until a table has been fetched nothing is crafted: shulker box crafting is off, `stash
  crafting` and the log say "no recipe table", the heartbeat reports shulker box crafting as not enabled, and no craft orders are taken. **With an older
  server (no recipes route) shulker box crafting stays off for good**; update the server first, or stay on plugin 1.14.0.
- Shulker box crafting and craft orders use the same crafting code; a crafted stack is merged into a stack of the same kind the craft owns instead of taking a
  new slot for every fill, and a grid slot is never given more than the stack size of its item.

## [1.14.0] - 2026-11-08

### Added
- **Kit sorting** (kit contract v1.1; see the Stash Manager server's PROTOCOL.md, "Additions: kits"). A shulker box of any
  colour that scores as a "kit" goes to the chest you pick on the Stash Manager website. The score is points: 2 for the word "kit" or "kits" in
  its custom name (a whole word: "PvP Kit", "kit_3" count, "Kitchen", "Toolkit" and "Kitsune" do not), 3 for an item mix (for example three or
  more pieces of gear, five wool colours), 1 when all 27 slots are used, 4 for at least `opMin` OP items (netherite or diamond gear, totems,
  crystals, elytra...; counted per item, so 3 totems, or a sword and 2 god apples, qualify; `opMin` is 3 by default and is set per stash). A box is a
  kit when its total reaches the threshold (4 points by default). A box of a single item is a kit only when all 27 slots are filled, and an empty
  box never is. The points, the threshold and `opMin` are set per stash on the website. The item lists are a shared table (`kit-items.json`,
  a copy of the contract's file).
  A named kit can get its own chest by name (the name needs no word "kit"); an unnamed kit only ever goes to the kits chest. Once a kit name
  has its chest, every future shulker with that name that scores as a kit goes there, in Tiers mode and in Signs mode, and the bot keeps doing
  so from the rules it saved (`plugins/qwerte-stash/kit-settings.json`, written atomically, checked on every load) while the server is offline
  or too old to know kits. A kit with no rule and no kits chest is sorted as before; a mixed shulker in a chest signed "kit" keeps going to it
  as before when no kit setting applies. If the chosen chest is full the kit takes the normal path: one already stored stays until the chest has
  room, one from a drop-off goes to overflow. If it is missing (not read yet, excluded, a drop-off, unreachable) the kit has no destination: a
  stored one stays, one from a drop-off goes to overflow, and a warning is given once per kit until the settings change. New settings, a new
  scoring included, make the sorter plan the whole stash again. Each shulker is scored once and the result is kept for that stack. The settings the server sends are checked against their own hash and refused whole when the content does not hash to it.
- The kits chest and every kit rule's chest are reserved: nothing but kits goes in. They are left out of overflow (misc and unsigned),
  sign scoring, the Tiers plan, compacting, repacking and auto-labelling.
- Kit routing never applies while an order, a repair, a put-back, a craft, an anvil run or a naming run holds the carried stacks: those are handled by that job, not by kit routing.
- A kit with a target (a rule or the kits chest, found or missing) is kept whole: repacking never takes it apart. With no target it is repacked as before.
- The heartbeat carries `kitsHash` and `kitsReportHash`; the reply may carry `kits` (checked whole: keys, container ids, at most 200 rules,
  or the object is ignored and a warning is logged once), `kitsHash` and `needKitsReport`. The bot sends `POST /api/v1/kits/report` (its
  containers with free slots, where each kit is (with its score), and which kits it carries; no sign text beyond the first line, cut to 32
  characters) only after a reply has carried `needKitsReport`, at most once every 10 seconds and only when the report changed or the server
  asked. Everything is optional both ways: an older server or bot ignores it.
- Setting `kitSorting` (`stash kitSorting on/off`, default on, "Kit sorting" on the website): off turns the feature off here (no routing, no
  reserved chests, nothing about kits sent). The saved rules are kept.

## [1.13.0] - 2026-11-07

### Added
- **Repair items with XP bottles before delivery** (order repair contract v1.2, [PROTOCOL.md](https://github.com/Qwertze/Qwertze/blob/dev/stash-manager-server/PROTOCOL.md),
  "Additions: repair with XP bottles"). An order can ask for "Repair all items" or "Repair" on single lines.
  Before the stacks of a shulker are packed, each wanted item is checked live. A shulker box gives "not a single item". Otherwise the
  checks are "no Mending", then "not damaged", then "not a single item" (a stackable item). Under "Repair all items" only damaged lines are
  wanted (no entries for undamaged tools or stackable items). Each item that is not fully repaired gets its own entry in `partial` or
  `skipped`; repaired items are only counted. The bot checks first that it has free slots for the gear it will put aside, takes
  Experience Bottles from the stash (`ceil(damage / 14)` per item plus a few spare), holds the bottles in the off hand and the item to repair in the
  main hand (a bottle can only be thrown from a hand, so one item at a time), stands still looking down and throws in small batches,
  checking the durability after each. The bot's own damaged Mending armour, the off-hand and main-hand items are stored in free inventory slots first so
  they take no XP, and everything it moved is put back: on a cancel or a failure, when another module takes the bot over (before it gives way),
  when the module is switched off (best effort), and, if the bot went offline in between, at the next start before anything else (the moves
  are kept in the stash file and checked against what is there: an entry whose item does not fit its slot is dropped). A player within 8 blocks,
  or orbs that vanish without repairing, stop the throwing and it is tried once more per order after it has been quiet for a while; then the item is
  delivered as it is ("interrupted"). At most about 2 minutes of throwing per order (kept across a restart; with none left the items are
  skipped as "interrupted" before anything is gathered or moved). Leftover bottles go back to the stash, also after a cancel or a timeout. A park
  drops the run without a report. Repairing never fails or holds up an order. Bottles in shulkers are not used (loose stacks only).
- The index marks stacks with Mending (`mending: true`), the heartbeat says `repair: true`, the order's `repairAll` and line `repair` are
  read, and the result carries `repair` (`bottlesUsed`, `repaired`, `partial`, `skipped`); the message gets "N item(s) not fully repaired: reasons".
  Everything is optional both ways: an older server or bot ignores it.

## [1.12.2] - 2026-11-06

### Added
- **Container delay** (`stash containerDelay <0-100>`, setting "Container Delay" on the website, default 0): extra ticks of pause
  around opening containers. A container is not opened until this many ticks after the last one the bot closed, and the first click in a
  freshly opened one waits this long too, on top of the click delay. It applies to every container the sorter opens, the placed shulker,
  the crafting table and the anvil. Ticks held back do not count against the open timeouts or the retry pause. 0 is exactly as before.
  A higher value is slower but gentler on the server; 2b2t can kick or rubber-band when containers are opened very quickly. Mirrors the
  Qwerte addon's setting.

### Fixed
- A read container kept and skipped for being outside the stash area could still be walked to and read after `stash rescan`
  (or a recheck): a container that is excluded or skipped is now never read again by those. `stash exclude` and `stash dropoff`
  on such a container change nothing and say that it is outside the stash area (widen the area to use it again).
- A container you excluded yourself is never removed, even one that was never read and lies outside the area.
- Merging the two halves of a double chest keeps the "skipped, outside the area" mark, and the count of known shulkers ignores
  excluded and skipped containers.

## [1.12.1] - 2026-11-06

### Added
- **`stash friends add/del/list` and a "Friends" setting** (on the website): players (name or UUID, empty by default) that do
  not count as "someone else near the bot" - your own alts. They no longer hold back going back to a shulker, nor the pickup of
  ground items. Not the whisper whitelist.

### Fixed
- **A shulker problem no longer stops shulker handling (and orders) for good.** Seen live: the server put a broken order shulker
  back five times in 20 s, the bot gave up ("Couldn't break the shulker ... it's still there"), and every order then showed
  "paused: a shulker could not be picked up" until sorting was restarted.
  - A refused break is asked again after a growing wait (5, 15, 30, 60, 90 s, five times) instead of every 4 s, and the wait
    does not count towards the break timeout. A refusal seen before (the shulker back after "broken") now counts as a retry.
  - Giving up on a shulker (break, return, pickup or the 10 minute finish) is no longer a stop: its record is kept, orders wait
    ("finishing a shulker"), and after 20 s plus 15 s with no other player within 32 blocks of the shulker or the bot, the bot
    goes back (walking there first if it is far) and finishes or picks it up. Each shulker is gone back to at most 3 times.
  - If the shulker is clearly gone (nothing standing, nothing on the ground near), the orphan is forgotten and shulkers pause for
    3 minutes only.
  - A real stop (after the 3 goes, or a way that leaves the stash area) now ends by itself once the shulker is picked up or gone
    from the world, not only when sorting is restarted.
  - Time spent giving way no longer counts against the 10 minute finish deadline in any case.
  - New INFO lines to grep: `Shulker handling PAUSED (will retry ...)`, `Shulker handling PAUSED (stopped)`,
    `Shulker handling PAUSED (3 min)`, `Shulker handling: the area is quiet, going back`, `Shulker handling RESUMED`.
- The cap of 3 goes back per shulker and the quiet wait now survive a restart (saved in the in-flight record, no format change).
- Going back to a shulker no longer waits for ever for a player: after 5 minutes the bot goes back anyway. It says who it waits for
  at INFO (first time, then every minute).
- A shulker given up on is saved as given up (it is picked up, not packed on), the order bin is kept (not replaced by an empty one),
  an idle sorter checks once a second whether the area is quiet, and the pause state is read safely from command and web threads.
- **Scans no longer stall on a container that is not there.** A container that was never read and is outside the stash
  (outside the perimeter, or with no perimeter over 20000 blocks from every container read, with at least 5 read ones to judge by)
  is dropped at every start, at INFO, after the stash file is copied beside it (.prune-bak-, 3 kept; no copy, no drop). A read container is never dropped this way,
  and nothing is dropped when the stash file is only loaded. A container with nothing there (air in a loaded chunk), or one
  the pathfinder finds no way to 4 times in a row, is skipped at once with the reason at INFO; a chunk that is not loaded is walked
  to (a wide stash is fine). A read that gets nowhere is tried again later, never marked unreachable for good. `stash exclude`, `dropoff` and
  `compactSkip` with a position refuse to add a new container outside the perimeter. The last container's progress line ("reading containers: 48/48; done.") is
  printed too.
- **Read containers are kept, not deleted.** Setting or changing the perimeter (and every start) now drops only never-read
  containers outside it. A read one stays in the stash file and is skipped (marked excluded by the sorter, undone when the perimeter
  holds it again or goes), and is left out of the website index, the local search index and the status counts.
- **The delivery container can no longer plant a stray container.** `stash delivery <x> <y> <z>` refuses an unloaded position more
  than 128 blocks from the bot (a position meant for another bot); changing or clearing the delivery container removes its never-read stand-in box and resets its no-compact mark.
- `stash friends add` checks the name; the friends and whitelist lists are replaced, not changed in place.
- A read that failed is tried again after 1 minute, then 2, then every 5 minutes, each container on its own schedule (it was all of them
  every minute), and a read gets 30 s without getting nearer instead of a minute of pathing.

## [1.12.0] - 2026-11-06

### Added
- **The live viewer shows the bot's whole inventory:** the viewer frame's `hud` gains `inv`, the 27 main inventory slots (window 0
  slots 9-35, top left to bottom right, null for empty), next to the hotbar, armour and offhand it already had. Every HUD item
  (hotbar, armour, offhand, inventory) also gains `d` (damage) and `md` (max damage), only for damageable items, read the way the
  index reads durability, so the website draws the same durability bars as in the stash grid. Items still carry only their id,
  count and durability: never a custom name, nothing positional.
  - The inventory is part of the HUD: a change there is sent on the next 200 ms viewer tick (one HUD per tick at most), and the
    whole HUD at least every 5 s, as before.
  - Needs a Stash Manager server that knows `inv`, `d` and `md` (an older server drops them; the page then shows no inventory).

## [1.11.1] - 2026-11-06

### Added
- **An INFO log line when the delivery report is taken by the Stash Manager server:** "Delivery report sent: N chests". It never
  has positions or names, and is throttled to about one a minute (the first success after a failure is always logged). The Qwerte
  addon logs the same line.

## [1.11.0] - 2026-11-06

### Added
- **The delivery storage's contents are reported to the Stash Manager server**, so the website can show what is already in the
  delivery chests (left over from past orders) and whether a new order fits. A new signed `POST /api/v1/delivery` carries every
  delivery chest (the delivery container and the chests put up above it) in fill order (chests not known to be full first, lowest
  first, then the full ones): size, whether it is full, when it was last read, and per slot the item id, hover name (at most 64
  characters), count, max stack, damage, and for shulker boxes a summary of what is inside (at most 27 `{id, count}` entries,
  grouped by id). No positions. A chest the bot has never read has `readAt` 0 and `items` null.
  - Taken from what the sorter reads through an open delivery chest window: delivering into it, looking at a full chest again
    (a player opened it, or the 2-minute check), and verifying a delivery. Saved with the stash like any other container contents.
  - Sent at most once every 5 seconds, only when the contents changed (the hash leaves the read times out) or the server asks
    (`needDelivery` in the heartbeat reply). The heartbeat gains `deliveryHash` (left out when no delivery container is set).
    A 409 (stash name in use) is handled like the index's; an older server without the route (404) is not asked again until
    the contents change.
  - These contents are never part of the index, the search or the stock counts; that rule is unchanged.
  - Only sent to a server whose heartbeat reply carries a `needDelivery` key (an older server never gets it); last in line after the
    index, the heartbeat and the order requests; a failure never touches the manager's backoff or status, and has a retry timer of
    its own (60 s, doubling to 10 min). A 400 or 413 is not retried for the same contents. A report over about 230 KB drops the
    shulker contents first, then the last chests' items.

## [1.10.0] - 2026-11-06

### Added
- **A crafting table is found, put down or crafted when crafting needs one and none is set.** Same order of preference as the anvil
  (and the Meteor addon), looked for only when a craft that is possible is about to start (shulker boxes for orders and compaction,
  chests for the delivery storage), never at heartbeat time, and never before the craft itself was found possible:
  1. the table set with `stash craftingtable` (or one remembered from before);
  2. a placed crafting table standing in the stash area (the perimeter, or the containers widened by the container radius; at most
     128 blocks across; loaded chunks only; one search every 10 s), the nearest one the bot can stand next to (remembered as "found");
  3. a crafting table taken from the stash and put down by the same spot rules as the anvil (remembered as "placed");
  4. a crafting table crafted in the inventory's own 2x2 grid from 4 planks (any wood), or from logs made into planks first, from
     planks and logs in the stash only, and put down (remembered as "crafted").
  With none of that the craft fails with "no crafting table available" (with the cause when it is known), and the order goes on or
  goes short exactly as it does without a table. A remembered table that is gone is forgotten and the search starts again; a found,
  placed or crafted one the bot can't reach or open is forgotten too (a hand-set one never is). The 2x2 grid is only clicked
  with plain pick-up clicks (no shift-click), and is emptied, with the cursor, on every way out (stop, disconnect, cancel,
  respawn, another module's window): with no room left the bot keeps trying and says so.
  - `stash craftingtable`, `stash crafting` and `stash status` say how the table came to be (set, found, placed or crafted); the
    position is shown only where the delivery container's is.
  - The heartbeat's `crafting` gains `tableSource` ("set", "found", "placed", "crafted" or "none"); `table` is unchanged.

- **Live viewer feed for the Stash Manager website.** While a browser has the viewer page open for this bot (the server says so in
  its `bot/next` reply), the plugin posts a frame five times a second to `bot/viewer`: the map tiles around the bot (vanilla map
  colours and shading, with water depth, from the loaded chunks), the bot's position and heading, the players, mobs and dropped
  items nearby (players first, at most 256), the HUD (health, food, experience, armour points, hotbar, armour, off-hand) and new
  chat lines. Tiles are sent when they change (block updates, chunk loads, and a re-check of every tile every 2 s), when they come
  into or leave the radius, and all of them on a new session, a resync or a change of dimension.
  - Costs nothing when nobody watches: no requests, no chunk scanning, no thread. Only the last 50 chat lines are kept in memory.
  - One request at a time, never queued; stops at once when the server says nobody watches, and when the dashboard link or the
    module is turned off. Chat text, positions and player names are never written to the log.

## [1.9.0] - 2026-11-05

### Added
- **Delivery storage that grows (`stash delivery expand on`, off by default; "Auto expand delivery storage" on the website).**
  When every chest of the delivery storage is full, the bot takes a chest from the stash and puts another chest up one block above
  the highest one. A double chest gets a double chest above both halves, the same way round; a single chest (or a barrel) gets a
  single chest. This goes on for as long as the new chests could be opened and the bot can reach them: it stops at the build height
  limit, at something in the way, at a solid block above the new chest (a lid does not open under one), and where there is nowhere to
  stand that can reach it. From the floor the first row above the delivery container is within reach; higher ones need something to
  stand on beside the stack (the top of a chest is only in sight from an eye above it). The order then waits with "delivery storage
  full: can't place higher: <why>".
  - The chests come from the stash by the order source rules (loose or inside shulkers, never a chest another line of the order asks
    for, nothing that holds something). With none left and crafting on, chests are crafted (chest from planks from logs, at the
    crafting table).
  - A chest is not placed against a chest by a plain click (that opens it): the bot sneaks. The first half is put up by a sneaking
    click on the top of the chest below, standing on the side the chest should face (the front is opposite to where the bot looks).
    The second half by a sneaking click on the side of the first, which joins it as the other half of the same double chest. What
    stands at the end is read back from the world: a double chest, or two single ones if they did not join.
  - The lowest chest with room is filled first; the chests stay and are used again. A full chest is passed over until it is known to
    have room.
  - **A chest a player emptied is used again.** The bot sees the lid of the delivery chests (the block event with the number of
    players who have it open). A full chest opened by a player and closed again is looked at once (the bot opens it and reads what
    is in it); if it has room it is usable. Where the lid could not be seen (the bot was out of range), a full chest is looked at
    again every 2 minutes while an order waits for space.
  - The chests are part of the delivery set: their positions are never on the website (not in the settings, the activity or the
    heartbeat), the sorter never sorts into them or takes from them, and they are not in the index or the search. They are saved with
    the stash. A different delivery container forgets the chests put up above the old one; a chest a player breaks leaves the storage.
  - `stash delivery expand [on|off]` shows or sets it; `stash delivery` and `stash orders` say "3 chests, 1 full, can expand" or
    "blocked: why". The heartbeat gets a new optional `deliveryStorage` ({`autoExpand`, `chests`, `full`, `canExpand`, `blocked`},
    no positions); the existing fields are as they were.
- **An order waits for delivery space instead of ending.** When every delivery chest is full and no chest can be put up (or auto
  expand is off), the order is paused with "paused: waiting for delivery space: <why>" and goes on once space is confirmed (a chest
  emptied by a player, or a chest put up). It waits for 2 hours at most (longer than the 10 minutes a held-up bot is given, because
  what it waits for is a player claiming an earlier order), then ends as before with "delivery container full (x/y)". The finished
  shulker stays in the inventory throughout (the sorter does not sort while an order is in hand) and, if the order ends, is put back
  into the stash like any leftover; nothing is dropped.

- The stash index carries the durability of damaged gear: each `items[]` entry of a damaged, damageable item has `damage` (at least 1)
  and `maxDamage`; undamaged and undamageable items have neither. The index hash changes for stashes that hold damaged gear.

- Changing or clearing the delivery container turns the chests put up above the old one into ordinary stash containers, and the
  sorter then sorts what is in them (the shulkers delivered there included). The console and the command reply say how many
  ("N extra delivery chests are now ordinary stash containers; their contents will be sorted"). Going back to 1.8.1 does the same:
  it knows nothing of them. The 2-hour wait for delivery space is kept in memory only, so a restart of the proxy starts it again.

### Changed
- The shulker just delivered is looked for in the chest it was put into, which is kept with the saved order (`deliveringAt`) so a
  restart looks in the right one.

## [1.8.1] - 2026-11-05

### Added
- **An anvil is found or put down when none is set.** When an order wants its shulkers named, the anvil set with
  `stash anvil` comes first, then one remembered from before. With neither, the bot looks for an anvil (any damage)
  standing in the stash area and remembers it as found. The area is the perimeter if one is set, else the stash's
  containers widened by the radius it looks for containers in; at most 128 blocks across, loaded chunks only, at most once
  every 10 seconds. It takes the nearest one it can stand next to. With none standing, it takes an anvil from the stash
  (loose or in a shulker, by the order source rules, never one another line needs), puts it down where a shulker could go
  (PlaceSearch rules: a floor, reachable, not on a container, not next to the delivery container or the crafting table,
  inside the perimeter) and remembers it as placed. With neither, the shulker is delivered unnamed with "no anvils
  available to rename with", and the order goes on. A found or placed anvil that is gone (anvils break with use) or gives
  trouble is forgotten and another looked for; a set one that is gone is looked past too, and the step line says so.
  `stash anvil` says whether it was set, found or placed (the position still only in the proxy terminal and the website
  console), and the heartbeat's `naming` gets `anvilSource`: "set", "found", "placed" or "none".

### Fixed
- Crafting:
  - The shulker box kept after a craft is the very one the table gave. Before, an emptied plain source shulker could be
    kept instead, and the crafted box put back into a chest.
  - A craft trip made again after being cut short keeps the chests and planks the first trip made. Before, the retry
    failed with "the inputs aren't all in the inventory".
  - A craft trip is bounded as a whole (2 minutes plus 20 seconds a grid fill), as are its clicks into the grid, its takes
    and its retrieves. Past that it ends cleanly: the grid is taken back out, then the table closed.
  - Items left in the crafting table's grid with no room for them now hold the sorter, with the table kept open, until
    they are taken back out; this is tried again as slots free up, and said every minute until then. Before, the bot
    carried on, and the next container it opened made the server close the table.
  - Closing the table never leaves something in its grid: the cursor stack and then the grid are taken back out first,
    when giving way and when finishing a job. Turning the module off takes the grid out in the same request as the close,
    and says so if there is no room.
  - A stack left on the cursor (a turned-down click) is put into a free slot before anything in the grid is touched.
  - Named and plain stacks of one item are kept to their own grid slots. Before, a craft could fail with "the grid didn't
    fill as it should" though there was enough.
  - Results and stacks taken back out of the grid go into a free slot before being merged onto a stack.
  - Gathering that needs to take items out of a shulker gives way while shulker handling is paused, instead of trying to
    put one down.
  - The hourly count and the never-use list are safe to read from the command thread.
- Orders: two put-back trips to one container that put nothing in now give that container up for the run, and leftovers
  stay carried after that, instead of trying for ever.
- The containers the sorter gave up on for now are looked at again once for each order shulker (was: once per order plan).
- Placing a shulker: a spot the bot itself stands in is never taken, and both halves of a double-chest delivery container
  are kept clear.
- Putting an anvil down: it goes into an empty hotbar slot (else a carry slot; a slot with the bot's own things only
  last), and the swap is undone when the run ends, placed or not, and when the module stops or starts over. An anvil
  already in the inventory is put down before another is fetched.
- Items held in the crafting table's grid: the alert backs off (after 1, 5 and 15 minutes, then every hour). After 20
  minutes, when the grid holds only the craft's own inputs, the table is closed (the server puts them in the inventory,
  or drops them at the bot's feet with no room), said in an alert, and what is carried is put away next.
- The search for an anvil standing is spread over ticks (at most 20,000 block lookups a tick, one look for an unloaded
  chunk) and only as high as the stash goes plus the sorter's height. An anvil given up on starts a new search; anvils
  given up on are forgotten when a new order is planned. A hand-set anvil that is gone is looked past for the order, its
  setting kept; a found or placed one in another dimension is looked past and one searched for here.
- Put-back trips that came to nothing count from scratch for each order, and a put-back that puts something in clears
  the count for that container.
- The crafting table's grid and results only go into carry slots (no room otherwise); a stack on the cursor may go
  into another free slot so it isn't lost, and that is said. Only grid requests that went out count towards the limit (6
  for each slot).

## [1.8.0] - 2026-11-05

### Added
- **Crafting, phase 1: plain shulker boxes.** With `stash crafting on` and a crafting table set (`stash craftingtable
  here`), the bot crafts a plain `minecraft:shulker_box` when an order's shulker has no empty shulker to be built in, when
  an order's empty-shulker line is short, or when compaction has no empty shulker. It goes up to three steps deep: a
  shulker box from 2 shulker shells and a chest, a chest from 8 planks of any kind, planks from a log. Kits never craft.
  - The recipes come from a table made from the 1.21.4 game jar and shipped in the plugin
    (`qwerte-stash/recipes/1.21.4.json`): since 1.21.2 the server sends no full recipes.
  - Inputs come from loose stacks and from inside shulkers, by the same source rules as orders (kit, excluded, drop-off
    and delivery containers, stale, blocked and badUnpack are skipped). Named and enchanted stacks may be used, counted
    under their item id. Never used: damaged items, anything that holds something (a filled shulker, bundle, or a chest or
    barrel item with contents or block-entity data), shulker boxes, and items on `craftNeverUse`. A craft never takes what
    another line of the order needs: the plan, the check of the empty shulkers and the run count craftable shulker boxes
    from the same pool.
  - Crafting is done with window clicks at the table (exact counts placed in the grid, the result taken with a left
    click, never a shift-click, and the server's update waited for between takes). The table window is never closed with
    something in its grid: it is taken back out first, and with no room for it the window is left open and an alert
    raised. Leftovers and the steps between go back where they came from; the crafted shulker is kept until it is used.
  - Caps: `craftMaxPerOrder` (27), `craftMaxOpsPerTask` (128 grid fills), `craftMaxPerHour` (54). A table that couldn't be
    reached or opened, or gave no result, is not tried again for that order.
  - A unit whose shulker couldn't be crafted is given up with "no empty shulker; couldn't craft one: <why>" (a craft cut
    short is tried once more). The order's end message says "crafted N Shulker Box".
  - Step lines at every log level ("crafting: 1 Shulker Box (1 Shulker Box from 2 Shulker Shell + 1 Chest; ...)"), a
    "Crafting: Shulker Box xN" card on the website's activity page, and `"crafting": {"enabled", "table"}` on the heartbeat
    (no position). `stash orders` shows whether crafting is on and a table is set.
- Commands: `stash crafting on|off`, `stash crafting` (status: on or off, table set, crafted this hour, caps),
  `stash craftingtable here | <x> <y> <z> [dimension] | clear`, `stash craftingtable` (the position only in the proxy
  terminal and the website console), `stash craftNeverUse add|remove <item>|list`.
- Settings `enableCrafting` (off), `craftMaxPerOrder`, `craftMaxOpsPerTask`, `craftMaxPerHour`, `craftNeverUse` (editable on
  the website) and the crafting table's position (hidden there). Older settings files load with crafting off.

### Changed
- **No room to put a shulker down beside the bot no longer pauses shulker handling.** The bot looks for the nearest good
  spot within 5 blocks that it can walk to (a solid floor, room above for the lid, a cell to stand next to it, not on top
  of a container, not on or next to the delivery container, the anvil or the crafting table, inside the perimeter, and
  the usual rules: no hopper next to it, not on a chest, nobody standing there), walks there and puts it down. The step
  line gives how far away it is, never where. Only when there is none does it pause as before, now with the reason ("no
  spot within 5 blocks: unreachable", "... outside the perimeter", ...). It applies to order shulkers, source shulkers being
  emptied, compaction and repacking alike. A cancel or park during the walk stops it with the shulker still carried.

### Fixed
- **An order shulker is no longer given up when one of its loads has no source.** Until now the first load the bot
  could not find a source for ended the whole shulker, and it went out with what it held (order o-16d278f3d6b8: 1 totem
  of 12 stacks). The load is now left out, said in a step line with why, and the rest is still gathered and packed; the
  shulker goes out short of just those loads, and its note names them.
- A source shulker emptied for an order is no longer put back where it no longer belongs (an empty shulker in a chest
  signed for totems, which the sorter would take straight back out and in the end give up on that chest). It goes where
  the sorter would put it. An order's own put-back trips never count towards a container being given up on.
- When the containers holding a load were only given up on for now by the sorter itself (blocked, a source shulker
  that couldn't be taken), they are looked at again once per order plan before the load is left out.

## [1.7.2] - 2026-11-05

### Fixed
- **Orders no longer fail with "nothing could be delivered: not enough left to pack" while the stash still holds the
  items.** The plan counted containers the run then refused to take from (stalled, a sign being changed, a trip that
  came back empty, a source shulker that couldn't be taken), and the replan was made the same way, so it failed again.
  The plan, the replan and the run now use one rule for sources, and what the sorter gave up on for now is forgotten
  when an order is claimed and when a shulker is planned again.
- Too few free carry slots to pick up a source shulker no longer ends the shulker as "not enough left to pack". What is
  carried and not part of the shulker is put away first; only if that can't be done does it stop, as "no carry room".
- A shulker that was given up on but holds part of its loads is delivered as it is (short) instead of being put away.
  The order's note says "shulker N delivered short: <why>", and "delivered less than asked" lists the lines. A named
  order still names it, and a restart still knows it as that shulker. Only the order's own shulker (its colour, empty
  when first put down, holding exactly what was last packed into it) is delivered short; when that can't be told for
  sure the shulker is planned again as before.
- **Containers a player broke are forgotten without a perimeter.** The sorter used to try to read them every minute for
  ever ("No block is at ..."). A container whose blocks are all loaded and none is a chest or barrel any more is now
  dropped (with everything kept about it) between jobs, on "Rescan stash" / `stash rescan`, and when a job is about to
  open it (then only that one). A double chest whose saved half was broken is kept as the half left, and read again.
  The log says "N containers are gone, forgetting them". Containers in unloaded chunks are kept; the delivery container
  is never dropped. Nothing is dropped unless all of these hold: the stash's dimension is known (it is now saved in the
  stash file, recorded on the next container read for older files) and the bot is in it; this world has shown it is the
  stash's (a container of it read, or one seen standing, since the last login or dimension change); the world was
  joined at least 30 seconds ago; the container looked gone on two checks at least 5 seconds apart; the bot is online
  and not in the queue; and not every container in view looks gone at once.

### Changed
- When an order shulker is given up on, a step line (shown at every log level) gives the reason, the next load and how
  many containers holding it were turned down and why (stale, blocked, signPending, badUnpack, other).
- The status shown after `stash` commands gives the whitelist as a count ("whitelist: 3 players"). `stash whitelist`
  or `stash whitelist list` shows the names.

## [1.7.1] - 2026-11-05

### Added
- **Shulker names for orders.** An order from the website can name its shulkers: one name for all of them, or one per
  shulker. The i-th shulker of the plan (packed shulker lines, then empty shulker lines, then the packed bins) gets the
  i-th name. After a shulker is built or fetched, the bot walks to the anvil, renames it (about 1 XP level each) and
  delivers it as usual.
- **Anvil setting** (`stash anvil here`, `stash anvil <x> <y> <z> [dimension]`, `stash anvil clear`, `stash anvil`):
  where the shulkers are named. It works like the delivery container: anvil, chipped anvil or damaged anvil; it may be
  outside the stash area; it is never sorted, read, indexed or searched. Its position is shown only in the proxy
  terminal and the website console, never in the website's settings or Activity titles. `stash orders` says whether
  one is set.
- The heartbeat sends `naming: { anvil, xpLevels }`, so the website can warn when names are wanted but the bot has no
  anvil or too few levels.

### Changed
- Naming never fails or pauses an order. If a shulker can't be named (no anvil set, the block there is not an anvil,
  the anvil can't be reached or opened, too few XP levels, a name that breaks the anvil's rules, or no result within
  about 30 seconds), it is delivered unnamed, and the order gets one note: "<n> shulker(s) delivered unnamed:
  <reasons>".
- The checks that find an order's shulker (in the inventory, the stash and the delivery container, also after a
  restart) recognise a shulker renamed for the order, so a custom name never makes it look missing. The saved order
  keeps the names, so a resume still names the rest correctly.

## [1.6.2] - 2026-11-05

### Fixed
- An order shulker broken after a shulker of another colour was unpacked is looked for as its own colour again. The
  bot used to search the ground (and ask the pathfinder to pick up) the colour of the last shulker it had put down, so
  it never saw its own shulker lying there and never moved. Going back to a shulker left standing, and looking for a
  lost one, now use the colour that shulker was put down as; the record saved for a resume keeps that colour too.
- Going back to a shulker left standing no longer measures the pickup against the shulker count of another shulker put
  down and picked up meanwhile. That made the shulker look picked up the moment it was broken ("Picked up a shulker
  ... but couldn't tell which slot it is in"), and the empty spot was then taken for a lost shulker.
- A pickup whose slot can't be told only counts once the shulker count has stayed up for half a second with nothing
  like ours left on the ground; otherwise the bot keeps collecting. When it does count, the shulker is no longer taken
  as still standing, so its empty spot doesn't start a search on the ground.
- AutoDrop no longer gives a player the bot's own shulker. A shulker it would give back is kept (with a warning naming
  the player) when the bot is collecting a shulker of that colour off the ground, or when it appeared within 4 blocks of
  a shulker of ours of that colour within 5 seconds of breaking it; the sorter carries on with it. Shulkers left
  standing between batches, waiting to be resumed or given up on keep nothing, so a player's shulker is still given back.
- A pickup the bot can't tell the slot of is no longer held up for good by another shulker of the same colour lying
  nearby: after a minute it counts as picked up, instead of the bot chasing the other one and giving its own up as lost.
- Shulker handling being paused (a shulker couldn't be picked up, a shulker left standing is being finished, or the
  3-minute pause after a placing problem) no longer holds the whole order. Finished shulkers are still fetched and
  delivered and leftovers put back; only building the next shulker waits, for up to 10 minutes. Before, the order
  stopped altogether and failed after 10 minutes with finished shulkers still in the inventory.

### Changed
- Collecting a broken shulker is logged at every log level, Quiet included: the item looked for, when it comes into or
  goes out of sight (at most every 5 seconds) and how far the bot is from it (every 10 seconds while in sight), and how
  it ended: picked up (and into which slot), given up on, or interrupted (giving way to another module, or a player
  taking control).
- Walking to a dropped shulker goes to its cell or one beside it, whichever can be reached, instead of only its own
  cell. A walk or pickup that gets no nearer to it for 10 seconds is let go of and asked for again.

## [1.6.1] - 2026-11-05

### Fixed
- Orders no longer fail at once when they start while shulker handling is paused (after a shulker problem, while a
  shulker left standing is finished, or after one couldn't be picked up). The order shows "paused: shulker handling is
  paused: <why>" and waits, for up to 10 minutes; a pause that starts mid-shulker retries that shulker instead of
  failing it. The same reason is shown as the order blocker, so no new order is taken meanwhile.
- A shulker planned again after a failed try now counts items inside shulkers too, not only loose stacks, so items
  that are only inside shulkers are no longer dropped from the order.
- An order's failure message starts with the reason, and fits the 300 characters the server keeps.
- "No room to put a shulker down beside the bot" and the other shulker-placing problems are shown at every log level
  and in the Activity feed, since they hold orders back.
- `stash manager status` and the key confirmation no longer show the last characters of the manager key, only
  "set" or "not set".

### Changed
- The website console runs every command the terminal can, with the same permissions; it no longer refuses
  `whitelist`, `multi`, `update` and the like. Commands the proxy only allows from its own terminal (such as
  `stash manager dashboard console/proxy`, `terminal`, `unsupported`) still refuse it. The console still has to be
  turned on in the terminal with `stash manager dashboard console on`.

## [1.6.0-dev.1] - 2026-11-05

A big test build: the Stash Manager website and stash orders, matching Qwerte 0.4.0-dev.1. Not fully tested in game yet.

### Added
- **Stash Manager link** (`stash manager on/off`, off; `stash manager url <url>`, `stash manager key <key>`,
  `stash manager status`): sends an index of the saved stash to the Stash Manager server on your network, keeps a
  heartbeat going and reads every container again when the website asks for a rescan. Works with sorting off. Only
  changes are sent, checked every `stash manager scanInterval <seconds>` (10); `stash manager includeExcluded on/off`
  lists excluded containers too. The key is never shown in command output or logs.
- **Dashboard** (`stash manager dashboard on/off`, on): the website shows this plugin's settings and applies changes made
  there, and has an Activity view of what the bot is doing. The website may run console commands only after
  `stash manager dashboard console on`, and change proxy and other plugins' settings only after
  `stash manager dashboard proxy on`. Both can only be turned on in the proxy terminal. Secrets are never sent to the
  website.
- **Orders** (`stash orders on/off`, off; `stash orders` shows why orders can't run, if anything stops them): the bot
  takes orders placed on the website. It gathers the items from the stash, loose stacks first and then out of
  shulkers, packs them into empty shulkers (up to 54 per order) and puts them into the delivery container. Leftovers go
  back where they came from, and so do the shulkers it unpacked. Orders come before sorting, and the two never run at
  once. An order pauses while a player controls the bot, in the queue or while offline, and is saved with its plan so it
  carries on after a reconnect or restart. A cancel from the website stops it at the next safe point.
- **Delivery container** (`stash delivery here`, `stash delivery <x> <y> <z> [dimension]`, `stash delivery clear`,
  `stash delivery`): where finished orders go. It may be outside the stash area. It is never sorted, compacted or
  indexed, and never listed by search, whisper search or the index file. Its position is only printed in the terminal
  and the website console.
- `useNamedShulkers` (on): orders may take items out of shulkers with a custom name.
- `freshMinutes` (5): a container read within that many minutes isn't read again when sorting or an order starts,
  unless something marked it changed. 0 reads everything every time.
- **Split durability** (signs mode, `splitDurability`): used gear goes to containers signed "used", and compacting never
  mixes used and full items.

### Changed
- `stash mark` refuses typed coordinates that are unloaded and more than 128 blocks away, and says when a container is
  outside the stash area.
- Activity titles never contain coordinates; positions only appear in the detail line, which the website blurs in
  streamer mode.
- Existing settings files load unchanged; every new setting has a default.

## [1.4.0-dev.2] - 2026-10-16

### Added
- **Log levels** (`stash log quiet|normal|debug`, normal): quiet shows only step lines ("what now, what next", for
  example `Stash: sorting: taking 14 stacks from "Totems" at 10 64 -3; next: put them away (3 containers, nearest 12 64
  -3).`), errors and alerts; the usual info and warnings go to the debug log. Debug adds the reasons behind every
  decision (job choice and candidates, stalls, clicks sent, walk/open/place/break/pickup requests with their outcome and
  time, open latency, yield reasons, job durations, compaction scoring, ground item eligibility), marked `[debug]`. In
  quiet mode the proxy's `Pathfinder` and `Cache` info lines are kept off the terminal by a filter on the terminal
  appender (log files untouched).
- **Alerts**: things that need a person (a shulker left standing, lost or abandoned, one that can't be broken, nowhere to
  put stacks, sorting going in circles, no signs for new categories, no free carry slot, stopping while carrying, a sign
  it doesn't understand, a container it can't reach or open, going offline mid-job) are also whispered to the
  `stash whitelist` players: the same text at most once in 10 minutes, one whisper per 5 seconds, plain text up to 256
  characters, never while the bot is offline.
- **Offline and stop safety net**: a shulker of the bot's own standing in the world (repacking, or compacting between
  rounds) is saved with the stash (`inFlight`, plus which carry slots hold stash items, `held`) on a disconnect or
  `stash off`. On the next start it is finished first, before exploring or reading: back to the shulker, open it, finish,
  break it, pick it up, put the contents away, then every container is read again. It only breaks a shulker that is the
  block that was put down and holds what the record says; another dimension holds the record and blocks placing shulkers;
  a gone shulker is looked for nearby; it gives up after 10 minutes or 3 failed walks. The stash file is written
  atomically, orphans record their dimension, and orphans that have been picked up by someone are forgotten.
- `stash smartRecheck on/off` (on): stay-on drop-off checks.
- `stash unstuck`: drops whatever the bot is doing and starts sorting over. The unfinished shulker record is cleared so
  nothing is resumed, and the "no more shulkers until restarted" block is lifted. A shulker of the bot's own still standing
  is left where it is and listed with the orphaned shulkers. With sorting off it only clears the saved record.

### Changed
- Stay-on drop-off checks only look into drop-offs something can fill without moving a lid (a hopper, dropper or
  dispenser next to or above them, or a chunk that isn't loaded) and back off from 30 s to 5 min while they come up
  empty; the others are read when their lid moves. `stash smartRecheck off` keeps the old behaviour; the back-off resets when a check finds the contents changed.
- Planning is cheaper: the room each container has to take items is worked out once per pass, and the signs are scored
  once per kind of stack (id and data) per pass.
- Ground items are picked up in batches while two carry slots are free and eligible items are left, then put away.
- A container another player used (and `stash rescan`) is marked to be read again instead of being forgotten: search and
  planning keep working with what was saved, and a container marked this way is not taken from until it is read.
- With room in the carry slots, a container to take from that is nearer than the one to put away into is visited first.
- A job that made headway at an open container carries on in place with the take or merge that container also has to
  do, instead of closing and opening it again (at most four in a row).
- While exploring the stash area, containers within reach of each waypoint are read before moving on.
- A compaction shulker with fewer than 3 stacks left to add is broken and picked up instead of left standing.
- A stop with a shulker standing no longer records it as an orphan and warns; it is saved to be finished (see above).

### Fixed
- A repacked shulker the bot had already picked up could be looked for on the ground for 2 minutes and then abandoned:
  the shulker count could be off by one (a stale slot in the proxy's inventory), and a resume after a reconnect
  expected one more shulker than the bot could hold. Picking up is now told from the count just before the break, and
  failing that, after 3 s with nothing dropped in sight, from the one held shulker that matches it (colour, stack count
  and contents), only while standing near the spot and with the block gone.
- Matches Qwerte addon 0.3.2-dev.2.

## [1.4.0-dev.1] - 2026-10-15

### Added
- **Stash perimeter** (`stash area <x1> <y1> <z1> <x2> <y2> <z2>`, `stash area here1` / `here2` / `clear`, `stash area`):
  an inclusive block box (at most 256×128×256) saved with the stash. When set it replaces the radius and height: the
  containers inside it belong to the stash, none outside is ever added, and the ones outside a new perimeter are
  dropped. The bot explores it first (on `stash on`, when it changes and on `stash rescan`), walking to the middle of
  each 16×16 column it hasn't seen, nearest first, since it only knows loaded chunks; columns it can't reach are skipped
  and counted. Ground items are collected relative to the perimeter. It is shown in the `stash` status, and is only used
  in the dimension it was set in. One exploration run is capped at 30 minutes.

- **Safe walking** (`stash safewalk on/off`, on): while the sorter has a walk outstanding the pathfinder is set to never
  parkour (`allowParkour`, `allowParkourAscend`, `allowParkourPlace`), never diagonal-descend (`allowDiagonalDescend`),
  never take a fall of two blocks or more (`maxFallHeightNoWater` 1, `allowLongFall` off), and the settings are put back
  afterwards. Standing within 0.3 blocks of the edge of a drop of two blocks or more, the bot sneaks (only when no other
  module is moving it).
- New containers in the perimeter are looked for every 5 seconds while resting, and a walk that leaves the perimeter
  (plus 2 blocks) is given up.

- **Bundles**: a bundle with something in it, in a chest, a drop-off, a shulker, on the ground or carried, is emptied
  into free carry slots ("Emptied a bundle: N stacks taken out.") and what comes out is sorted like everything else.
  The empty bundle goes to a chest signed for bundles or containers, else the one signed for shulkers, else the
  catch-alls. Nothing is ever packed into a bundle.

### Fixed
- A repack no longer fails with a proxy error when a bundle is on the cursor: a left click that would put a bundle down
  on an empty slot (which the proxy's inventory code can't do) is never sent, bundles are moved with shift-clicks, and a
  container whose clicks never complete is given up on after a few seconds instead of stopping the sorter.

### Changed
- A container that can't be reached is now reported as "Can't reach the chest at [x, y, z] without breaking or placing
  blocks, skipping it." (and "Couldn't open the chest at [x, y, z], skipping it." when it can't be opened). Every walk the sorter asks Baritone for already had
  breaking and placing switched off; that is now checked for all of them.

## [1.3.0] - 2026-10-15

### Added
- **Collect ground items** (`stash collect on/off`, on; `stash collectRange <blocks>`, 8): the bot picks up items lying
  in the stash area (every container's bounding box widened by the range sideways and 4 up and down) and sorts them.
  Only items it didn't drop or break out of a block itself, only while no other player is in render distance (a trip
  in progress is dropped the moment one appears), only items that have lain still for 3 seconds, only between jobs with
  a free carry slot, and it gives way to other modules as always. An item that can't be reached or isn't picked up
  within about 20 seconds is left alone for 5 minutes. What it picks up counts as stash cargo, so picked-up tools and
  food are put away and not mistaken for the bot's own. The status line shows `off`, `off (scan only)`, `nothing on the ground`,
  `waiting for <player> to leave (N items)`, `picking up N <item>` or `no free carry slot`.
- **Scan only** (`stash sort on/off`, on): with sorting off, `stash on` still reads unknown and stale containers, the
  full re-read on login, the chest and sign watching, the index and search, but never moves an item: no taking,
  depositing, dumping, repacking, compacting, labelling or ground collection, and it only opens a container to read it.
  Status and log say "scan only".

## [1.2.0] - 2026-10-15

Everything from 1.2.0-dev.1 below, plus these fixes found while testing it:

### Fixed
- A PearlPlus pearl pull could be taken over by sorting (most visibly the full re-read after coming online): the
  sorter re-requested pathing in the same tick the pull started. Another module's pathing or interaction now always
  wins straight away ("Giving way to ..."), the sorter never requests over anyone, and it waits a moment after its own
  request fails before trying again.
- Auto drop could recognise whose item it picked up but not find it in the inventory to give back ("couldn't find
  the ... to give back"). It now finds the slot the item landed in, including the hand and offhand, waits for the
  inventory to update, and ignores pickups made while a player is controlling the account.
- The README now states the plugin needs AquariusProxy 5.13.0+ (java.1.21.4) and does not load on stock ZenithProxy.

## [1.2.0-dev.1] - 2026-10-15

### Added
- **Compact shulkers** (`stash compact on/off`, on, signs mode): whenever there is a full shulker's worth of an item
  in the stash (27 full stacks of it, wherever they are), the bot packs it into an empty shulker and puts that in the
  item's own chest, so 27 slots become 1. Part-filled shulkers of one item are topped up first, once `stash
  compactTopUpAt <1-26>` (9) full stacks are waiting. It never makes mixed shulkers, never uses a named shulker, and
  leaves kit chests alone. Gear of the same item id packs together whatever its enchantments, damage or name, while
  potions, tipped arrows, enchanted books, suspicious stew, firework rockets and stars, goat horns, ominous bottles, maps,
  heads, written books, banners and shields pack only with the same kind. It works with whatever carry room there is, in rounds, and leaves the shulker standing between
  rounds. It runs only once everything else is sorted, and the status shows what it has done and what is holding it
  back.
- Things that don't stack (tools, weapons, armor, flint and steel...) are compacted as soon as there are two of the
  same item in the stash, filling a shulker with as many as there are up to 27, and added to a part-full shulker of
  them whenever one is waiting. Other items still need 27 full stacks for a new shulker.
- **Full re-read when the bot comes online**: every container is read again before anything is sorted or packed, since
  players may have changed things while it was away. Search keeps using what was saved until each is re-read.
- **Stash index**: a readable list of what is in the stash and where, kept next to the stash file as
  `<server>_<stash>.index.txt` and rewritten (at most every 30 seconds) whenever the stash is saved. `stash index`
  shows the totals and the items there are most of.
- `stash compactExclude add/remove <item id>` / `list`: items that are never compacted, like `ender_pearl`.
- `stash compactSkip [x y z]`: a container that is never used to gather stacks or shulkers for compacting.

- **Auto drop** (`stash autoDrop on/off`, off, `stash autoDrop range <blocks>`, 16): when the bot picks up an item a
  nearby player threw, or left by breaking a block, it looks at them and drops it back to them. It only does this for
  items it can tie to a player by seeing them appear, never for blocks or drops of its own, shulkers it is collecting,
  ender pearls, or mob and death drops.

### Changed
- Shulker placing is safer: never next to a hopper or on top of a chest, at least 5 seconds apart, and a shulker that
  can't be broken or picked up is reported in game and left alone instead of stopping sorting. The bot waits for a free
  slot before breaking a shulker.
- `newBulkAt` only applies while `compact` is off.
- The bot's own gear, food, totems and ender pearls in its carry slots are never treated as stash items unless the
  bot took them from the stash itself.
- Packing into a shulker matches stacks by item id, and by kind for potions, books and the like, so such items no
  longer end up mixed.
- Taking items out of a mixed shulker keeps one slot free for picking the shulker up.
- A shulker the bot put down and could not pick up is reported in game, saved in the stash file with what it holds and
  where, and listed in the `stash` status as "Orphaned Shulkers". Stacks the bot took from the stash stay its to put
  away across a reconnect or turning sorting off and on.

## [1.1.0] - 2026-10-15

### Added
- **Unsigned overflow** (`stash unsignedOverflow on/off`, on, signs mode): stacks with no chest that has room for them
  now go to a chest signed `Misc`, `Other` or `Overflow` first, then to unsigned chests (ones already in use before
  empty ones).
- **Auto labelling** (`stash autoLabel on/off`, on, signs mode): when an item has no sign for it and there is an empty
  unsigned chest next to the signed ones, the bot puts a sign on that chest for a new category and sorts the item into
  it. It only places signs, never chests, at most one every 10 seconds. `stash labels` lists the signs it has put up
  and any it gave up on.
- **Gives way to other modules** (`stash quiet <0-60>`, default 3 seconds): sorting lets go of its container and waits
  until other modules (such as pathing, inventory actions or someone else's open container) have been idle for that
  long before carrying on. Each further interruption within a few minutes doubles the wait, up to a minute.
- **Pearl slot** (`stash pearlSlot on/off`, on): the bot never carries stash items in, or holds shulkers in, the first
  hotbar slot, so it stays free for PearlPlus pearls.
- **Watch chests** (`stash watch on/off`, on, needs tracking on): when another player opens and closes a stash chest,
  the bot re-reads it so whatever they moved gets sorted. `stash watchDelay <0-60>` (default 5 seconds) is how long the
  chest must stay closed first.
- **Watch signs** (`stash watchSigns on/off`, on, needs tracking on): a sign that is put up, changed or taken down is
  noticed and the stash is sorted by the new labels. `stash signDelay <0-60>` (default 10 seconds) is how long a
  changed sign is left alone first. A sign that stays gone or blank for a minute is treated as removed.

### Changed
- Items are identified by their item ID, never by an anvil or custom name. Renamed items sort by what they are, and
  logs and search results show the name as `Diamond Sword (named steak)`. Stashes saved by 1.0.0 are converted when
  they are loaded.
- The `End Blocks` sign category is narrower: shulker boxes and elytra no longer match it, only end blocks do.
- Sorting waits for other modules rather than fighting them, and the log says so ("Waiting for another module
  (pathing)", then "Resuming.").
- Extra diagnostic lines at debug level, kept out of the normal log, to help track down problems: for example
  "Container at ... has been the target of N jobs without the backlog shrinking", "... did nothing" and "Sign at ...
  reported changing, waiting for it to settle". Also new warnings for stopped runs and blocked shulker placement.
- The `stash` status and help now list the new settings.

### Fixed
- The bot could loop forever opening and closing the same chests without moving any items, most often in columns of
  stacked chests where the neighbouring chest's update was taken for a sign edit. Only real sign text changes count
  now, every job skips chests with a sign that is still being written, and stuck work is detected: a container that
  keeps coming back without any real progress is given up on, and a pass that goes in circles stops with a
  "Sorting went in circles" warning.
- After a sign was changed, the bot moved 0 stacks, claiming its carried stacks had nowhere to go. Re-sorting after a
  sign change now works.
- Auto labelling wrote signs that were left blank, because the sign editor was detected in the wrong place. Text is now
  written and checked, the bot retries its own blank signs, and after repeated failures it stops labelling and keeps
  sorting.
- The bot no longer waits forever on its own pathing or placement request.
- When a player or entity was in the way of a shulker being placed, the bot now tries other spots. If none work, it
  pauses repacking shulkers for 3 minutes.

## [1.0.0] - 2026-10-12

### Added
- Initial release. Sorts a saved stash of chests and barrels into tiers or by the signs on them, repacks mixed
  shulkers, merges partial stacks, empties drop-off chests, keeps the stash sorted in stay-on mode, and answers
  `find <item>` whispers from whitelisted players.

## Versioning
Major breaking changes: a new x.0.0 version. New features, bug fixes and more: 1.x.0. Minor bug fixes, single one-off patches or hotfixes: x.y.z.
