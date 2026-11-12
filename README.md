# Stash Sorter

A stash-sorting bot for [AquariusProxy](https://github.com/aquariusnetwork9/AquariusProxy), the 2b2t proxy built on
ZenithProxy. Point it at a room of chests and it reads them all, sorts every item into the chest whose sign describes
it, cleans out mixed shulkers, merges partial stacks and empties drop-off chests, then keeps it that way. Anyone you
trust can whisper the bot to ask where something is.

It's the stash sorter from the [Qwerte](https://github.com/Qwertze/Qwerte) Meteor addon, running on your proxy bot
instead of your client.

## Features

- Sorts a stash of chests and barrels into **tiers** (filled shulkers, empty shulkers, x64, x16, singles) or by the
  **signs** on them, written in your own words (`Totems`, `Crystals`, `Building Blocks`, `PvP`, `Misc`...).
- Shulkers are sorted by what they're mostly full of: a shulker of totems goes to the `Totems` chest.
- **Repacks shulkers** (signs mode): items that belong elsewhere are taken out of mixed shulkers, and loose items are
  packed into shulkers that already hold only that item, before everything is put away. See below.
- **Compacts shulkers** (signs mode): a full shulker's worth of one item is packed into a shulker and put back in the
  item's chest, freeing chest slots. See below.
- **Repairs order items** with Bottles o' Enchanting from the stash before they're delivered (orders from the website can
  ask for it). See below.
- **Repair passes** (new in 1.17.0): when idle, the bot repairs the low-durability items in the stash with bottles from the stash, and sends the items without Mending to a chest you pick on the Stash Manager website. See Repair mode below.
- **Pearl travel** (new in 1.18.0, needs Stash Manager server 1.3.0, off by default, not tried in the game yet): the bot goes to a pearl station and back by whispering the station's loader bot, and a loader bot reports its approved travellers' pearls to the server. See Pearl travel below.
- **Crafts what the stash can make for orders** (craft orders, new in 1.15.0): the website's build planner and the Printer can order items the stash does
  not hold but can make from what it holds; the bot crafts them at the crafting table set for the stash on the website. Needs a recipe table fetched
  from the Stash Manager server. See Crafting from the stash below.
- **Sends kit shulkers to their own chests** (kit sorting, on by default): a shulker box that is a kit goes to the
  chest you pick on the Stash Manager website. New in 1.14.0; kit types, the Kit Builder and kit orders came in 1.16.0 (not yet tested in game). See Kit sorting below.
- Scans the stash once and saves it. After that, opening a container through the proxy keeps it up to date.
- **Drop-offs** are emptied completely: anything without a home, or whose chest is full, goes to a `Misc` chest, then
  anywhere with room. With stay-on mode, the bot watches them (and checks the ones a hopper or dropper can fill) and keeps
  the stash sorted.
- **Downstacks**: partial stacks of the same item in a chest are merged.
- Excluded containers, and carry and keep slots so it never touches your own items.
- Search from the terminal, Discord or in-game (`stash search <item>`), or let whitelisted players whisper the bot
  `find <item>`. It replies `/whisper <player> <Item> Can be Found At <x, y, z> @ <stash>`.

Sorting only runs while nobody is connected to the proxy, so it pauses while you play and picks up again when you
leave. It never breaks or places blocks to reach a container; the only blocks it places and breaks are shulkers it's
repacking, and signs it writes for new categories (`stash autoLabel`).

## Requirements
- [AquariusProxy](https://github.com/aquariusnetwork9/AquariusProxy) **5.13.0** or newer on the **`java.1.21.4`** release
  channel (the native `linux` build can't load plugins), Minecraft 1.21.4.
- It does **not** load on stock ZenithProxy: AquariusProxy and ZenithProxy use different plugin APIs, and each refuses
  the other's plugins.
- Nothing else to install. The jar has no extra dependencies, and its settings file is created on first start.

## Install
1. Download `StashSorter-<version>.jar` from [Releases](https://github.com/Qwertze/Stash-Sorter/releases).
2. Stop the proxy.
3. Put the jar in the proxy's `plugins` folder, removing any older Stash Sorter or `QwerteStash` jar.
4. Start the proxy. `plugins` in the console should list `qwerte-stash`.

Settings are saved in `plugins/config/qwerte-stash.json`, stashes in `plugins/qwerte-stash/stashes/`.

## Use
1. Stand the bot among the containers, with some empty inventory slots (or set `stash carry <slots>`).
2. For signs mode, put signs on the containers and run `stash mode signs`.
3. `stash name <name>` to name the stash, then `stash on`. The first run reads every container, later runs go
   straight to sorting.

| Command | What it does |
|---|---|
| `stash` | Status and settings |
| `stash on` / `off` | Start or stop sorting |
| `stash search <item>` | Find items in the saved stash, including inside shulkers |
| `stash name <name>` | Which stash to use. Each name on each server is its own stash |
| `stash mode tiers` / `signs` | How to sort |
| `stash log quiet` / `normal` / `debug` | How much the sorter says (normal). Quiet: step lines, errors and alerts only. See Logging |
| `stash exclude [x y z]` | Never sort into or out of this container. Toggles. With no position, the container you last right-clicked while connected |
| `stash dropoff [x y z]` | Empty this container into the stash, for others to drop items in. Toggles. Always emptied completely: anything without a home, or whose chest is full, goes to a `Misc` chest, then anywhere with room |
| `stash safewalk on/off` | Safe walking (on): while walking the bot never parkours, jumps off or falls more than one block, and it sneaks when it stands at the edge of a drop of two blocks or more |
| `stash area <x1> <y1> <z1> <x2> <y2> <z2>` | Set the stash's perimeter, an inclusive block box (at most 256×128×256), saved with the stash. `stash area here1` / `here2` use the bot's own block as a corner, `stash area clear` removes it, `stash area` shows it |
| `stash radius <blocks>` / `height <blocks>` | How far around the bot to look for new containers (8 / 3) |
| `stash barrels on/off` | Include barrels (on) |
| `stash delay <ticks>` | Ticks between inventory clicks (3). Raise it if the server rejects clicks |
| `stash containerDelay [ticks]` | Extra pause around opening containers, 0 to 100 (0). A container is not opened until this many ticks after the last one was closed, and the first click in a new one waits as long. Slower but gentler on the server; raise it if 2b2t kicks or rubber-bands you when containers open quickly |
| `stash quiet <seconds>` | Seconds other modules (like PearlPlus) must be idle before sorting carries on (3). Doubles for repeated interruptions, up to a minute |
| `stash pearlSlot on/off` | Keep the first hotbar slot free of stash items and shulkers, for PearlPlus's pearls (on) |
| `stash watch on/off` | Re-read a chest after another player has opened and closed it (on). Needs tracking on |
| `stash watchDelay <seconds>` | How long a chest must stay closed before it's re-read (5) |
| `stash watchSigns on/off` | Sort by a sign's new text when it's changed (on). Needs tracking on |
| `stash signDelay <seconds>` | How long a changed sign is left alone before sorting by it (10) |
| `stash autoLabel on/off` | Signs mode: when an item has no sign, put up a sign for a new category on an empty unsigned chest next to the signed ones (on) |
| `stash unsignedOverflow on/off` | Signs mode: stacks with no room in their own chest go to `Misc`/`Other`/`Overflow` chests, then unsigned chests (on) |
| `stash craftorders on/off` | Take orders with craft lines (on; needs `stash crafting on` and a recipe table from the server). `stash craftorders` shows the recipe table, the website's crafting table state and the caps |
| `stash craftorders maxfills <n>` / `maxperhour <n>` | Grid fills one craft order may make (2048) and craft orders may make in an hour (4096) |
| `stash kitSorting on/off` | Kit sorting (on): shulkers that score as kits go to the chests picked on the Stash Manager website. Off: kits sort like any shulker and nothing about kits is sent. The saved rules are kept. See Kit sorting |
| `stash pearl travel on/off` | Pearl travel (off): this bot may travel from its stash to a pearl station and back. Set in game or in the proxy terminal only (the website's setting is read only). Needs the Stash Manager on and PearlDrop in the proxy. See Pearl travel below |
| `stash pearl loader on/off` | Report the pearls of the travellers you approved on the website to the Stash Manager server (on). Needs PearlPlus auto load on |
| `stash pearl grace <15-300 seconds>` | How long a pearl that is not seen is still believed to be there after its owner came back (45). Set in game or in the terminal only |
| `stash pearl go <station id>` / `stash pearl back` | Start a trip to a station (the id is on the website's Pearl travel panel), or the way back home |
| `stash pearl home` / `stash pearl home force` | Ask the server to clear the away mark. `force` (proxy terminal only) drops the mark and the trip here, whatever the server says |
| `stash pearl stop` | Drop a trip that has not whispered yet. After the whisper a pull can't be undone |
| `stash pearl status` | Where the pearl trip is and what it is waiting for |
| `stash labels` | Show the signs the bot has put up, and any it gave up on |
| `stash stayOn on/off` | Keep running after sorting, sorting again whenever a container changes |
| `stash recheck <seconds>` | How often stay-on checks drop-offs (30) |
| `stash smartRecheck on/off` | Stay-on drop-off checks (on). On: only drop-offs a hopper, dropper or dispenser next to or above them can fill, or whose chunk is not loaded, are looked into, each less often (30 s up to 5 min) while its contents stay unchanged; the others are read when their lid moves. Off: every drop-off every `recheck` seconds |
| `stash repack on/off` | Clean mixed shulkers and pack loose items into bulk shulkers, in signs mode (on) |
| `stash newBulkAt <stacks>` | Start a new bulk shulker from an empty one at this many stacks of one item (9). 0 only tops up existing ones. Only used while `compact` is off |
| `stash compact on/off` | Pack a full shulker's worth of an item into a shulker, in signs mode (on) |
| `stash compactTopUpAt <1-26>` | Top up a part-full bulk shulker once this many full stacks are waiting (9) |
| `stash compactExclude add/remove <item id>` / `list` | Items never compacted, like `ender_pearl` |
| `stash compactSkip [x y z]` | Toggle a container that is never used for compacting (the one you last right-clicked, or at a position) |
| `stash carry <slots>` / `clear` | Slots to carry stash items in, like `28-36`. Default: every empty slot |
| `stash keep <slots>` / `clear` | Slots never to touch |
| `stash index` | Write and show a list of what is in the stash and where (also kept as `plugins/qwerte-stash/stashes/<server>_<stash>.index.txt`) |
| `stash sort on/off` | Off is scan only: containers are still read (new, stale, the full re-read on login) and kept up to date, chest and sign changes are watched, the index and search work, but nothing is ever moved: no taking, depositing, repacking, compacting, labelling or ground pickup (on) |
| `stash collect on/off` | Pick up items lying on the ground in the stash area that the bot didn't drop or break out of a block itself, and sort them. Only while no other player is in render distance (on) |
| `stash collectRange <blocks>` | How far past the outermost containers ground items are picked up, sideways (8). Up and down it is always 4 |
| `stash autoDrop on/off` | Give back to a nearby player what they dropped or left by breaking a block and the bot picked up (off). `stash autoDrop range <blocks>` sets how far (16) |
| `stash rescan` | Read every container again on the next run |
| `stash unstuck` | Drop whatever the bot is doing and start over; a shulker job under way is not resumed (a standing shulker is left and listed as orphaned) |
| `stash forget` | Delete the saved stash and its marks |
| `stash tracking on/off` | Keep the stash up to date when containers are opened (on) |
| `stash whisper on/off` | Whisper search (off) |
| `stash whitelist add/del <player>` | Who can search by whisper |
| `stash whitelist` or `stash whitelist list` | Shows the whitelisted players (other status output only gives how many) |
| `stash friends add/del <player or uuid>` / `stash friends [list]` | Players who don't count as someone else near the bot (your own alts). They don't hold back going back to a shulker or the pickup of ground items. Not the whisper whitelist. Empty by default |
| `stash prefix <word>` / `clear` | Whispers starting with this are searches (`find`) |

Slots: 1-9 is the hotbar, 10-36 the inventory from the top left.

## Stash area (perimeter)
Instead of looking around the bot (`radius` / `height`), you can give the stash a perimeter with `stash area`. It is
saved in the stash file, so each stash name on each server has its own.

- Every chest, trapped chest and barrel (if `barrels` is on; double chests as always one container) inside the box
  belongs to the stash, and none outside it is ever added. Setting a perimeter drops the never-read containers outside
  it. A read one outside it is kept in the stash file and skipped (not sorted, searched or indexed) until the perimeter
  takes it in again. `stash area clear` goes back to looking around the bot.
- The bot only knows chunks that are loaded, so on `stash on`, when the perimeter changes and on `stash rescan` it first
  explores: it walks to the middle of each 16×16 column of the box it hasn't seen yet, nearest first, reading each
  column for containers as it comes into view, then reads the containers and sorts as usual. A column it can't get to
  is skipped (and counted in "Explored the stash area: found N containers (M chunks couldn't be reached)."). The
  log says "Exploring the stash area: N chunks to look at." when it starts.
- While resting (stay-on), the loaded part of the box is looked at again every 5 seconds, so a chest put up later is found.
- A walk that began inside the box (and 2 blocks around it) and takes the bot out of it is given up: the container is
  skipped ("Skipping the chest at [x, y, z]: the way there leaves the stash area.").
- Ground items are collected from the perimeter, widened by `collectRange` sideways and 4 up and down.
- The bot never breaks or places blocks to get anywhere (exploring, walking to containers, signs, shulkers or ground
  items; putting down and picking up its own shulkers and putting up signs are things it does on purpose). A container
  it can't get to that way is skipped and said once ("Can't reach the chest at [x, y, z] without breaking or placing
  blocks, skipping it."), and tried again after a new perimeter or `stash rescan`.

## Ground items
With `collect` on (the default) the bot also picks up items that lie on the ground in the stash area: the bounding box
of all the containers, widened by `collectRange` blocks sideways and 4 up and down. It never goes outside that area.

- Only items it didn't drop itself (including AutoDrop returns and PearlPlus pearls) and didn't break out of a block
  itself. Experience, arrows and thrown pearls are not items on the ground and are left alone. Items the bot threw or
  broke are remembered for the whole session.
- Only while no other player is in render distance. If one is, nothing is touched until every player is gone (they are
  assumed to be about to pick their things up), and a trip in progress is dropped the moment one appears.
- Only items that have lain still for 3 seconds, and only between jobs, when there is a free carry slot and nothing else
  is using the bot. Other modules' pathing always wins.
- It picks up in batches: while two or more carry slots are free and eligible items are left it fetches the next one, and
  only then puts them all away.
- An item that can't be reached, or isn't picked up within about 20 seconds, is left alone for 5 minutes.
- What it picks up is treated as stash cargo, even tools or food, and sorted like everything else.

## Scan only
`stash sort off` turns sorting off but leaves the rest running: `stash on` still reads new and stale containers and the
whole stash again on login, containers other players use and signs that change are noticed and read again, and `stash
index` and search stay current. The bot never takes, deposits, repacks, compacts, labels or collects from the ground, and
only opens a container to read it. The status says "scan only". Switch `stash sort on` to sort again.

## Repacking shulkers
In signs mode, the bot sorts what's inside shulkers too:

- **Mixed shulkers are cleaned out.** A shulker is kept for whatever it's mostly full of. Anything else in it that a
  sign has a home for is taken out, so a shulker of totems with a few rockets and some obsidian becomes a shulker of
  totems, and the rockets and obsidian go to their own chests.
- **Loose items are packed into bulk shulkers.** Before putting loose items away, the bot tops up a shulker that
  already holds only that item and has room, one it's carrying or one from the stash. With `newBulkAt` stacks or more
  of one item, it starts a new bulk shulker from an empty one.
- Then the shulkers go where their contents belong, like any other shulker.

To do this the bot puts the shulker down beside itself, opens it, moves the items, then breaks it and picks it back
up. It needs a free spot beside it on solid ground with air above, and a pickaxe makes breaking quicker.

Named shulkers are never taken apart. To keep kits whole, sign a chest `Kits` (or `Mixed Shulkers`): mixed shulkers
then go there as they are instead of being repacked. A chest signed `Shulkers` is just for shulkers that don't belong
anywhere else, and doesn't stop repacking. A kit with a chest under kit sorting is kept whole as well (see Kit sorting).

## Compacting shulkers
Once everything is in place, the bot looks for chances to free chest slots:

- **27 full stacks of one item** anywhere in the stash are packed into an empty shulker, which then goes to the item's
  own chest. Things that don't stack (tools, weapons, armor, flint and steel...) are packed as soon as there are
  **two** of the same item, filling a shulker with as many as there are up to 27.
- A **part-full bulk shulker** of an item is topped up first, once `compactTopUpAt` full stacks are waiting (for
  things that don't stack, as soon as one is).
- Stacks pack together by item id. Enchanted, damaged, plain and renamed gear of the same id share a shulker. Items
  whose id doesn't say what they are (potions, splash and lingering potions, tipped arrows, enchanted books, suspicious
  stew, firework rockets, goat horns, ominous bottles) pack only with the same kind.
- Any unnamed empty shulker will do, from a chest signed `Shulkers` first. Named shulkers are never used or opened,
  and nothing is taken from kit chests, containers marked with `stash compactSkip`, or drop-offs.
- With little carry room it works in rounds: gather a batch, put it in the shulker, repeat, with the shulker left
  standing between rounds and picked up when it's full. It needs at least two free carry slots. A shulker left
  standing for a few moments could be taken by another player, so the bot keeps close and reports in game if one
  disappears or can't be picked up. When fewer than 3 stacks are left to add, the shulker is picked up instead of left
  standing.
- The bot's own tools, armor, food, totems and ender pearls are never touched. Stash items of those kinds are packed
  like anything else. Use `stash compactExclude add ender_pearl` to keep an item out.

Partial stacks aren't packed. After compacting, partial stacks of the same item in each container are merged ("downstacked"), so a
chest with three stacks of 20 cobblestone ends up with one of 60.

## Signs
Write signs the way you'd talk, on the side of a chest or standing on top of it. Each line is read on its own and all
the lines together, and lists like `Logs, Planks & Wood` are split up.

- Item names and nicknames: `Totems`, `Crystals`, `Rockets`, `Obby`, `Gaps`, `Pearls`, `XP`, `Echests`, `Diamonds`.
- Categories: `PvP`, `Tools`, `Armor`, `Food`, `Building Blocks`, `Redstone`, `Wood`, `Stone`, `Mob Drops`,
  `Potions`, `Enchanted Books`, `Nether`, `Decor`, `Valuables` and hundreds more.
- Enchantments and potions: `Mending Books`, `Protection`, `Strength Potions`.
- Catch-alls for leftovers: `Misc`, `Other`, `Overflow`, `Random`, `*`.
- `Kits` or `Mixed Shulkers` to keep mixed shulkers whole.

With `autoLabel` on, an item that no sign describes can get a sign of its own: when there's an empty unsigned chest
next to the signed ones, the bot writes a new category on a sign there and sorts the item into it. It only writes
signs, never chests.

## Logging
`stash log quiet` / `normal` / `debug` (normal) sets how much the sorter writes to the terminal.

| What | Quiet | Normal | Debug |
|---|---|---|---|
| Errors | shown | shown | shown |
| Alerts (something needs a person) | shown, and whispered | shown, and whispered | shown, and whispered |
| Step lines | shown | shown | shown |
| The usual info and warnings | debug log only | shown | shown |
| Debug detail | not shown | debug log only | shown, marked `[debug]` |

A step line says what the sorter is doing and what comes next, and is never repeated, for example
`Stash: sorting: taking 14 stacks from "Totems" at 10 64 -3; next: put them away (3 containers, nearest 12 64 -3).`
Progress lines come at most every 10% or every 60 seconds. Debug adds the reason behind every decision: which job was
picked and why, what was stalled, the clicks sent, how long walking, opening, placing and breaking took, and so on.
What a level leaves out is still written to the proxy's debug log (when it writes one).

In quiet mode the proxy's own `Pathfinder` and `Cache` info lines are also kept off the terminal, with a filter on the
terminal appender (the log files and the logger levels are not touched; if the proxy's logging is set up differently,
only the sorter's own output is quiet).

Alerts are things that need a person: a shulker left standing, lost or abandoned, a shulker it can't break, nowhere to
put stacks, sorting going in circles, no signs for new categories, no free carry slot, stopping while carrying, a sign it
doesn't understand, a container it can't reach or open, going offline in the middle of a job. Each is also whispered to
the players on `stash whitelist`, at most one whisper every 5 seconds (at most 5 queued, the oldest dropped), the same text (ignoring digits) at most once in 10 minutes, sent with the proxy's whisper command as plain
text up to 256 characters, and never while the bot is offline.

## If the bot goes offline or is stopped
While the bot has a shulker of its own standing in the world (repacking, or compacting between rounds), what it was doing
is saved with the stash (an `inFlight` record, together with which carry slots hold things taken from the stash). When
sorting is started again, or the bot reconnects, that comes first, before the area is explored or any container read:
back to the shulker, open it, finish packing or cleaning, break it, pick it up and put what is in it away. Then every
container is read again as usual. This also happens after `stash off` with a shulker standing.

- It only breaks a shulker that is the same block that was put down and holds what the record says it should; anything
  else is left alone, said, and listed as an orphan.
- If the bot is in another dimension it waits, puts no shulkers down, and says so once.
- If the shulker is gone it looks for the dropped item nearby, and says so if it isn't found.
- It gives up after 10 minutes or three failed walks back, and then reports the shulker like any other it couldn't pick
  up.

## Stash Manager and orders
The [Stash Manager server](https://github.com/Qwertze/Qwertze/tree/dev/stash-manager-server) is a small website for
your network that shows the stash, takes orders and can show this plugin's settings and console. New in 1.6.0 and
still being tested.

1. Start the server and copy the API key from its console or its Link addon panel.
2. `stash manager url http://<server>:8765`, `stash manager key <key>`, `stash manager on`.
3. For orders, with the bot looking at a chest or barrel run `stash delivery here` (or give `x y z`), then `stash orders on`. `stash orders`
   tells you what is stopping orders, if anything. `stash delivery x y z` refuses a position in the bot's dimension that is not
   loaded and more than 128 blocks from the bot (usually a position meant for another bot). Walk the bot near the chest and use
   `stash delivery here`. Changing or clearing the delivery container removes the box the bot made for the old one if it
   was never read.
4. For orders that name their shulkers, with the bot looking at an anvil run `stash anvil here` (or
   `stash anvil <x> <y> <z> [dimension]`). `stash anvil` shows it and `stash anvil clear` removes it. The bot renames
   each shulker there before delivering it, for about 1 XP level each. Without an anvil, or without enough levels, the
   shulkers are delivered unnamed and the order says so. Like the delivery container, the anvil's position is only
   shown in the proxy terminal and the website console. Since 1.8.1 setting one is optional: without one the bot looks
   for an anvil standing in the stash area, and failing that puts one down from the stash, and remembers it either way
   (`stash anvil` says whether it was set, found or placed). With no anvil anywhere the shulkers are delivered unnamed
   with "no anvils available to rename with".

The website may run console commands only after `stash manager dashboard console on`, and change proxy settings only
after `stash manager dashboard proxy on`; both can only be turned on in the proxy terminal.

## Repairing order items
New in 1.13.0 and still being tested. An order can ask for repairs: "Repair all items" repairs every damaged item that has
Mending, and a line's "Repair" toggle repairs that item. The rules are in the Stash Manager server's
[PROTOCOL.md](https://github.com/Qwertze/Qwertze/blob/dev/stash-manager-server/PROTOCOL.md) ("Additions: repair with XP bottles").

- Only items that have Mending, are damaged and don't stack are repaired. A shulker box never is. One Bottle o' Enchanting
  repairs about 14 durability, so the bot takes `ceil(damage / 14)` bottles per item from the stash, plus a few spare.
  Bottles inside shulker boxes in the stash are not used: only loose stacks.
- Before a shulker is packed, the bot checks that it has enough free inventory slots, then stores its own damaged Mending
  armour and whatever is in its off hand and main hand, so none of it takes the XP. It holds the bottles in the off hand and
  the item in the main hand, one item at a time, looks down, and throws bottles in small batches, checking the durability
  after each batch.
- Everything it moved is put back: after a cancel or a failure, when another module takes the bot over, when the module is
  switched off (best effort), and at the next start if the bot went offline in between.
- A player within 8 blocks, or experience orbs that vanish without repairing, stop the throwing. The bot tries once more
  after a quiet spell; after that the item is delivered as it is, with the reason "interrupted". The throwing is capped at
  about 2 minutes per order.
- Repairing never fails or holds up an order. Items that aren't fully repaired are delivered as they are, the order's message
  says "N item(s) not fully repaired: <reasons>", and the result (bottles used, items repaired, partial and skipped items) goes
  to the website. Leftover bottles go back to the stash.
- There is no setting for it: it runs only for orders that ask for repairs. The Meteor addon can also take bottles out of
  shulker boxes; this plugin uses loose bottles only.

## Kit sorting
New in 1.14.0. A shulker box that is a kit goes to a chest you pick on the Stash Manager website's Kits tab: by kit type
from 1.16.0 (see below), by the score before that. The server's side (the pages, the settings and the storage) is described in the
[Stash Manager server README](https://github.com/Qwertze/Qwertze/tree/dev/stash-manager-server). Turn it off with
`stash kitSorting off`.

- **The v1 score.** Used while the server sends no v2 settings. Each shulker box gets points: `name` 2 for a custom name with the word "kit" or "kits" ("PvP Kit" and
  "kit_3" count; "Kitchen", "Toolkit" and "Kitsune" don't); `mix` 3 for a mix of useful items from the shared
  `kit-items.json` table (for example three pieces of gear, or five wool colours); `full` 1 when all 27 slots are used;
  and `op` 4 when it holds at least `opMin` OP items (3 by default), counted per item by stack count (3 totems, or a sword
  and 2 god apples, qualify). A box is a kit when its total reaches the threshold (4 by default). A box of one item is a
  kit only when all 27 slots are filled, and an empty box never is. The points, the threshold and `opMin` are set per
  stash on the website, and the bot re-scores its boxes when they change.
- **Where kits go.** A named kit with a rule for its name goes to that rule's chest. Otherwise it goes to the kits chest,
  and unnamed kits always go there. A kit with neither sorts as before.
- **Reserved chests.** The kits chest and every kit's chest hold only kits: nothing else is sorted into them, and they are
  left out of overflow, sign scoring, the Tiers plan, compacting, repacking and auto-labelling. A put-back into one
  happens only for a kit routed to it.
- **Kept whole.** A kit with a target (a rule or the kits chest, found or missing) is never taken apart by repacking.
- **A chest that can't take it.** A missing chest (not read yet, excluded, a drop-off or unreachable) gives the kit no
  destination: a kit already in the stash stays where it is, a kit from a drop-off goes to overflow, and the bot warns
  once per kit and chest until the settings change. A full chest takes the normal path, so kits in the stash move once
  it has room.
- **Offline.** The settings the server sends are kept in `plugins/qwerte-stash/kit-settings.json` (written atomically,
  checked on every load), so the rules keep working while the server is offline or too old to know kits. Settings that
  fail their check are ignored whole, and a warning is logged once.
- **The report.** The bot sends the server its containers (free slots, and whether each can take kits), where each kit is
  with its score, and the kits it carries. It sends this only when the server asks or the report changed, at most every
  10 seconds. Only the first line of a sign is sent, cut to 32 characters.
- **Off.** `stash kitSorting off` turns the feature off: kits sort like any shulker, nothing about kits is sent, and the
  saved rules are kept. `stash kitSorting on` turns it back on.


### Kit sorting v2 (1.16.0)
When the server sends v2 settings, the bot tells kits apart by type instead of by the score above. Kit orders and restock are
new in 1.16.0 and have not been tried in the game yet.

- **Types.** The seven types of the shared `kit-types.json` table: PvP / Crystal, Travel, Redstone, Farming / Food, Building,
  Starter / Base and Other kit. A box is a kit of a type when enough of its slots hold that type's items (three quarters of
  them, or half when its name has the word kit) and it has at least that type's minimum number of different items. A box of
  one kind of item is stock, never a kit.
- **Chests.** Each type and each custom category (up to 16, set on the website) can have a chest. A kit goes to the first usable
  of: its kit name's rule, its Kit Builder template's chest, its category's chest, the general kits chest. A stored kit only
  moves up that chain. "Treat as" on the website gives a kit name a kind, or makes its boxes never kits.
- **Kit Builder.** The website keeps templates of 27 slots. A box matches a template when it holds the same items in the same
  counts, with the same components. Item names, damage, repair cost and slot positions don't matter. A box with every item of
  the template is a complete copy. A box named after the template with some items missing is an incomplete copy: it is topped up
  and never delivered.
- **Kit orders** (`kitOrders`, on by default; needs kit sorting on). The bot builds and delivers the kits ordered on the website:
  complete copies are fetched, and the rest are built from the stash and named with the template's name. A kit it cannot
  complete is never delivered: with two or more kinds of item it is kept as an incomplete copy, otherwise its items go back into
  the stash. Plain shortfalls are crafted when craft orders are on.
- **Restock** (`kitRestock`, on by default; `kitRestockPerHour` 12, `kitRestockMinEmpty` 2). While the bot is idle it keeps each
  template's keep count in its chest, from unreserved stock only, and never by crafting. A template with keep 0 is not restocked,
  so nothing happens until a template has a keep count. Only the first 16 templates with a keep count (by id) are restocked.
  Restock starts only after the first heartbeat reply of a connection, with orders on and no order held or to be resumed. It
  gives way at once to a waiting order, a player, the queue or a switch-off.
- **Settings.** `kitOrders`, `kitRestock`, `kitRestockPerHour` (0 to 60) and `kitRestockMinEmpty` (0 to 54) are on the bot's
  Settings page on the Stash Manager website, and in `plugins/config/qwerte-stash.json`.
- **Older servers.** With an older server, which sends no v2 settings, the v1 score above is used, and no kit orders or restock
  are offered.

## Crafting from the stash
New in 1.15.0. An order can have craft lines: items the stash does not hold but can make from what it holds (the website's build planner shows them as
"craftable", and the Printer can order them). The Stash Manager server decides what is craftable and plans the crafts; the bot checks the plan and carries it out.

1. `stash crafting on` (crafting itself) and `stash craftorders on` (the default). The bot also needs the **recipe table**, which it fetches from the Stash
   Manager server by itself (`stash craftorders` shows whether it has one). Until it has, it takes no craft orders and crafts nothing, shulker boxes included.
2. Set the crafting table **on the website** (the stash's settings, "Crafting table"; quick picks from the tables the bot has seen). It does not use
   `stash craftingtable` for orders. The bot tells the website whether the table is usable (ok, not loaded, not a crafting table, can not be reached,
   in another dimension) and why not. **With none set** the bot finds a crafting table in the stash area, or puts one down from the stash, or crafts one
   and puts it down (4 planks, or a log), and keeps it as its own; the website shows it as "auto". A website table wins when it is set.

What it does: each shulker's craft loads are made just before the shulker is built, as many at a time as the free carry slots allow: it takes only the
ingredients the order's plan names, crafts them with the plan's recipes (never more of an item than the plan says), and puts exact stacks in the shulker.
Whatever is left over (a few items of the last fill, the steps between) is carried to the next shulker and goes back to the stash when the order ends.
What can not be made is left out with a reason, and the order says "<n> item(s) not crafted: <reasons>"; crafting never fails an order by itself.
Caps: `craftOrderMaxFills` grid fills per order (2048) and `craftOrderMaxPerHour` an hour (4096); the shulker box caps below are separate.

## Crafting shulker boxes
New in 1.8.0, off by default. Since 1.15.0 it also needs the recipe table (above). When an order needs an empty shulker (to build a shulker in, or for an empty-shulker line)
or compaction does, and the stash has none, the bot can craft a plain shulker box from what the stash holds: 2 shulker
shells and a chest, the chest from 8 planks of any kind, the planks from logs. Kits never craft.

1. With the bot looking at a crafting table run `stash craftingtable here` (or `stash craftingtable <x> <y> <z>
   [dimension]`). `stash craftingtable` shows it, `stash craftingtable clear` removes it. Its position is only shown in
   the proxy terminal and the website console.
2. `stash crafting on`. `stash crafting` shows whether it is on, whether a table is set, and how many were crafted this
   hour.

What it takes: loose stacks and what lies inside shulkers, from the containers an order may take from, never what
another line of the order needs. Named and enchanted stacks are used like plain ones; damaged items, anything that holds
something, shulker boxes and the items on `stash craftNeverUse add <item>` never are. It crafts at most `craftMaxPerOrder`
(27) for one order, `craftMaxPerHour` (54) an hour, and `craftMaxOpsPerTask` (128) grid fills in one go; these and
`craftNeverUse` can be changed on the website. The bot needs free carry slots for the inputs, the steps between and the
shulker boxes it makes, and makes room first if it has to. What is left over goes back where it came from. The window is
never closed with something in its grid; if there is no room to take it back out, the table is left open and you get an
alert.

When a shulker box can't be crafted, the order says why ("no empty shulker; couldn't craft one: needs a crafting table",
"... shulker shells can't be crafted", "... not enough planks ...", "... cap reached ...").

## Repair mode (1.17.0)
When the bot is idle it repairs low-durability items in the stash: Mending items with experience bottles from the stash (the throw of order repair), items without Mending
are sent to the needs-repair chest picked on the website. It is set up on the Stash Manager website's Repair panel (on by default once the bot has synced): threshold (default
below 50% durability), the bottle reserve it never goes under (1728), the needs-repair chest and "open unnamed shulker boxes" (named boxes and kits are never opened). A pass
gives way to orders, players within 8 blocks and a stop, and puts everything back. Here: `stash repairpass on/off`, `stash repair run | stop | status`, and the settings in the table below.

**When a pass runs.** Automatically: repair mode and automatic passes are on on the website, the bot is idle (the sorter is resting, no job runs, nothing is carried or stuck), no order is held or resumable and the last heartbeat showed none pending, no other module is working, the quiet time has passed, a Mending item's bottles fit the usable bottles, no player is within 8 blocks, and `repairPassEveryMinutes` has passed since the last automatic pass ended. A manual run (the website's **Run repair pass**, or `stash repair run`) waits for the same idle state and ignores `auto` and that gap. It still needs repair mode on the website: a queued run is dropped when repair is switched off there, and after 10 minutes.

**Yielding.** At every safe point a pass gives way to an order (waiting, claimed or resumable), a player within 8 blocks, a stop, or the sorter being switched off. Friends count as players here, because the thrown orbs don't ask who a friend is. The pass puts everything back (carried items go through normal sorting, bottles go back to the stash) and reports `yielded`, or `stopped` after a stop.

**Opening unnamed shulkers.** With `shulkers` on on the website and `repairPassMaxBoxes` above 0, a pass opens an unnamed box that isn't a kit and holds a Mending item to repair. One box is open at a time, only with nobody within 16 blocks (friends count), and at most `repairPassMaxBoxes` (8 by default) per pass. A box isn't opened again for 24 hours unless its contents change. That cooldown is in memory only, so a restart can open a box sooner. Named boxes and kits are never opened; their low items are only counted (in the bot's status, with the reason `in a named shulker` or `in a kit`). Items that came out of a box and weren't repaired aren't packed into a shulker again for 24 hours.

**Needs-repair routing.** With a needs-repair chest set on the website, a loose low item without Mending goes there, after kit routing and before tiers and signs. The chest is reserved, so nothing else is sorted into it, and Mending items never go there. If the chest is also a kit chest, kit routing wins and needs-repair routing is off (warned once). An unknown chest id switches routing off (warned once, without coordinates).

**Bottles and limits.** A pass takes only the bottles above the reserve: the website's reserve, or `repairMinReserve` when that is higher. An item whose bottles don't fit is skipped with the reason `reserve`. Items are repaired fully, not only to the threshold. An item that comes back unrepaired is not retried by automatic passes for 6 hours (a manual pass retries it). Per pass: at most `repairPassMaxItems` items and `repairPassMaxMinutes` minutes of throwing. Between passes: `repairPassMinGapManualMinutes` (also for manual runs; memory only, so a restart resets it) and `repairPassMaxPerHour` (kept in `repair-passes.json`, so a restart doesn't reset it).

| Setting | Default | Range | Meaning |
| --- | --- | --- | --- |
| `repairPass` | on | on or off | Off: no repair keys are sent, no passes run, and needs-repair items are sorted like any others. Also `stash repairpass on/off`. |
| `repairPassMaxItems` | 64 | 1 to 256 | Items one pass takes. |
| `repairPassMaxMinutes` | 20 | 1 to 120 | Minutes one pass spends throwing bottles. |
| `repairPassEveryMinutes` | 30 | 0 to 1440 | Least minutes between two automatic passes. |
| `repairPassMaxBoxes` | 8 | 0 to 54 | Unnamed boxes one pass opens (0: none, whatever the website says). |
| `repairPassMinGapManualMinutes` | 5 | 0 to 1440 | Least minutes between two passes, also for a manual run (memory only). |
| `repairPassMaxPerHour` | 4 | 0 to 60 | Passes started in an hour, manual ones included (0: no limit). |
| `repairMinReserve` | 0 | 0 to 1,000,000 | Least bottles a pass keeps, when that is more than the website's reserve. |

**Commands.** `stash repairpass on|off`, `stash repair run`, `stash repair stop`, `stash repair status` (the state, the candidates, the bottles and the last pass).

**Files.** The settings the server sent, and the run and stop ids the bot has acknowledged, are kept in `plugins/qwerte-stash/repair-settings.json`. The start times for the hourly cap are in `plugins/qwerte-stash/repair-passes.json`.

**Older servers.** A server without repair passes sends no `repairHash`. The bot keeps its cached settings, makes no automatic passes, and a local `stash repair run` still works (with the defaults when nothing is cached). A settings object from a server before the shulker setting has `shulkers` off, so boxes stay closed.

**Known limits.**
- An open order for an item that a pass repaired isn't moved to the repaired item. The bot keeps the old-to-new key for 24 hours, but orders don't use it yet.
- The report's `to` is never sent, so the website's list shows "stayed" for each item.
- `toNeedsRepair` (the change in the needs-repair chest's item count during the pass) and `rehomed` (repaired items whose best chest is not where they were) are approximations.
- A queued or unfinished manual run is lost on a restart. Its id is saved when it is received, so it isn't sent again.
- Box cooldowns reset on a restart.
- The Qwerte addon has no repair passes yet.

It has not been tried in the game yet.

## Pearl travel (1.18.0)
Needs Stash Manager server 1.3.0. With an older server the bot makes no trips and nothing else changes.

A **traveller** bot goes from its stash to a pearl **station** (a loader bot with stasis chambers, run by PearlPlus) and back. It whispers `load <pearl id>` to the loader, PearlPlus pulls it
through its chamber, the bot and the loader both tell the Stash Manager server what they saw, and the bot throws a fresh pearl into the chamber it came through (PearlDrop). The way back is
the same thing in reverse, through a pearl thrown at home before leaving. Stations, the approvals below and the Go / Back / Stop buttons are on the website's Pearl travel panel (Stash
Manager server 1.3.0; contract `PEARL-CONTRACT-v1.0.md` rev 7). Nothing here sends a position: a station and a trip carry only ids, and the chamber positions stay in PearlPlus.

**Traveller (the bot that travels).** `stash pearl travel on` (needs the Stash Manager on and PearlDrop in the proxy; off by default; PearlDrop switches itself on the first time the bot throws). A trip comes from the website, or from
`stash pearl go <station id>` and `stash pearl back` (the id is shown on the panel). While the bot is away from its stash the **away guard** is on: sorting, the index push, website rescans,
repair passes and orders are off (the order blocker says `travelling/away`). `stash pearl status` says where a trip is; `stash pearl stop` drops one that has not whispered yet (after the
whisper a pull can't be undone); `stash pearl home` asks the server to clear the away mark (it works with travel off too, and the bot keeps telling the server while it is away);
`stash pearl home force` (proxy terminal only) drops the mark and the trip here without the server. `stash pearl travel` and `grace` can't be changed from the dashboard (the website's setting is read
only): turn travel on in game or in the terminal. A trip from the website needs the settings permission and a confirmation on the page. The bot starts at most one `go` trip per 5 minutes. Whispers of any kind (the first, the retry, a way back) are at least 30 s apart and at most 10 an hour, kept across restarts; a trip that hits the limit waits, then fails. A local `go` / `back` / `home` that the server never answers (an older server, or a refused request) is reported with an alert and in `stash pearl status`. Before the whisper the bot must be in the game for at least the grace period (`pearlGraceSeconds`,
45 s by default) with its chat ready (online for 15 s), out of the queue and in the tab list, and its loader reporting within 60 s and in the tab list, with the sorter at a checkpoint, no order or repair pass, nobody but friends within 16 blocks, health 10 and food 6, and at least 2 pearls.
The way back needs a return pearl at home: **stand next to your home chamber (within 2 blocks) when the server asks the bot to arm it.** This home throw is strict: only a free chamber within PearlDrop's own 2-block reach is used, never a nearer-looking one (a wrong chamber is worse than none); the re-arm after a pull, by contrast, may fall back to the nearest free chamber within 4 blocks. If the throw fails the trip does not start, the owner is alerted and `stash pearl status` says "Stand next to your home chamber (within 2 blocks) and try again". From the checkpoint to the whisper the sorter starts no new job; at the moment of `go` the sorter, bystanders and the loader being in the tab list are checked again and the bot waits (up to 90 s) instead of failing. The re-arm after a pull uses the
chamber nearest to where the bot landed.

**If something goes wrong.** No pull by the deadline and the bot still where it whispered: `pull_timeout`, the guard goes off, and for 5 minutes the bot still watches for a late pull. A
pull that already happened, or that arrives late: `unexpected_location`, the guard stays on and the owner is alerted; clear it on the website ("it's home") or with `stash pearl home`. A
disconnect or restart after the whisper is resumed from `plugins/qwerte-stash/pearl-trip.json` (it holds the place the bot whispered from, only compared locally, never logged or sent; the file is owner-only where the OS allows it (on Windows it keeps the folder's permissions), so keep the proxy folder private). A loader should stay within PearlPlus's presence range of its chambers (`autoDetect.temporaryRemovalRange`, 32 blocks by default): out of range a pearl is `unknown`, and a load that starts out of range is reported `loaded` only if the pearl is seen in range during the job and then gone. A death or a queue position after the whisper is never read as a pull: the bot is held until the owner clears it.

**Loader (a bot running PearlPlus with auto load on).** With `pearlLoader` on (the default) the plugin reports the pearls of the travellers the owner approved: whether each is in its chamber and
the loads that run. The owner approves this bot as the loader of a station on the website, adds the travellers to the station's allowed list and confirms each traveller's uuid; the server
answers every report with that list. Only a traveller whose name is on the list **and** whose uuid (the one PearlPlus keyed the pearl by, from the game server) equals the confirmed one is
reported or has a load reported; everything else stays on the loader. A pearl that is not seen counts as gone only after the grace period and only with the owner in the tab list (an owner in
the queue or offline is `owner_offline`, never `empty`). A load is seen from the outside (PearlPlus sends no event), and a pearl still there after the walk is `aborted`, never `loaded`.

| Setting | Default | Range | Meaning |
| --- | --- | --- | --- |
| `pearlTravel` | off | on or off | This bot may travel. `stash pearl travel on/off`. |
| `pearlLoader` | on | on or off | Report the approved travellers' pearls (needs PearlPlus auto load on). `stash pearl loader on/off`. |
| `pearlGraceSeconds` | 45 | 15 to 300 | Seconds a pearl that is not seen is still believed to be there after its owner (or the loader) came into the world; also the least time a traveller is in the game before a trip. `stash pearl grace <s>`. |

### Not tested in game

Nothing here has been run in game. The in-game spike (`PEARL-SPIKE.md`) was skipped, so these are unverified: pearls after a rejoin and in the queue (the plugin treats a pearl that is not seen as `unknown` until the grace period has passed, and a trip never starts from the queue), the whisper's sender identity, a pull across dimensions, the arrival and re-arm, and which chamber PearlDrop picks. The loader can't see PearlPlus's own queue, so it reports `loading` and `loaded` only. The owner's chamber positions are never sent, so the server shows them by id only. The Qwerte addon's pearl support is not part of this release.

## Versioning
Major breaking changes: a new x.0.0 version. New features, bug fixes and more: 1.x.0. Minor bug fixes, single one-off patches or hotfixes: x.y.z.

## Building
Needs JDK 25. Copy your proxy's `AquariusProxy.jar` into `libs/`, then run `./gradlew build`. The jar is in
`build/libs/`.

## License
GPL-3.0, the same as [Qwerte](https://github.com/Qwertze/Qwerte), which this is ported from.
