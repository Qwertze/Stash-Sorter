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
- Scans the stash once and saves it. After that, opening a container through the proxy keeps it up to date.
- **Drop-offs** are emptied completely: anything without a home, or whose chest is full, goes to a `Misc` chest, then
  anywhere with room. With stay-on mode, the bot checks them every few seconds and keeps the stash sorted.
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
| `stash exclude [x y z]` | Never sort into or out of this container. Toggles. With no position, the container you last right-clicked while connected |
| `stash dropoff [x y z]` | Empty this container into the stash, for others to drop items in. Toggles. Always emptied completely: anything without a home, or whose chest is full, goes to a `Misc` chest, then anywhere with room |
| `stash safewalk on/off` | Safe walking (on): while walking the bot never parkours, jumps off or falls more than one block, and it sneaks when it stands at the edge of a drop of two blocks or more |
| `stash area <x1> <y1> <z1> <x2> <y2> <z2>` | Set the stash's perimeter, an inclusive block box (at most 256×128×256), saved with the stash. `stash area here1` / `here2` use the bot's own block as a corner, `stash area clear` removes it, `stash area` shows it |
| `stash radius <blocks>` / `height <blocks>` | How far around the bot to look for new containers (8 / 3) |
| `stash barrels on/off` | Include barrels (on) |
| `stash delay <ticks>` | Ticks between inventory clicks (3). Raise it if the server rejects clicks |
| `stash quiet <seconds>` | Seconds other modules (like PearlPlus) must be idle before sorting carries on (3). Doubles for repeated interruptions, up to a minute |
| `stash pearlSlot on/off` | Keep the first hotbar slot free of stash items and shulkers, for PearlPlus's pearls (on) |
| `stash watch on/off` | Re-read a chest after another player has opened and closed it (on). Needs tracking on |
| `stash watchDelay <seconds>` | How long a chest must stay closed before it's re-read (5) |
| `stash watchSigns on/off` | Sort by a sign's new text when it's changed (on). Needs tracking on |
| `stash signDelay <seconds>` | How long a changed sign is left alone before sorting by it (10) |
| `stash autoLabel on/off` | Signs mode: when an item has no sign, put up a sign for a new category on an empty unsigned chest next to the signed ones (on) |
| `stash unsignedOverflow on/off` | Signs mode: stacks with no room in their own chest go to `Misc`/`Other`/`Overflow` chests, then unsigned chests (on) |
| `stash labels` | Show the signs the bot has put up, and any it gave up on |
| `stash stayOn on/off` | Keep running after sorting, sorting again whenever a container changes |
| `stash recheck <seconds>` | How often stay-on checks drop-offs (30) |
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
| `stash forget` | Delete the saved stash and its marks |
| `stash tracking on/off` | Keep the stash up to date when containers are opened (on) |
| `stash whisper on/off` | Whisper search (off) |
| `stash whitelist add/del <player>` | Who can search by whisper |
| `stash prefix <word>` / `clear` | Whispers starting with this are searches (`find`) |

Slots: 1-9 is the hotbar, 10-36 the inventory from the top left.

## Stash area (perimeter)
Instead of looking around the bot (`radius` / `height`), you can give the stash a perimeter with `stash area`. It is
saved in the stash file, so each stash name on each server has its own.

- Every chest, trapped chest and barrel (if `barrels` is on; double chests as always one container) inside the box
  belongs to the stash, and none outside it is ever added. Setting a perimeter drops the containers outside it, with
  what was saved for them. `stash area clear` goes back to looking around the bot.
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
anywhere else, and doesn't stop repacking.

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
  disappears or can't be picked up.
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

## Building
Needs JDK 25. Copy your proxy's `AquariusProxy.jar` into `libs/`, then run `./gradlew build`. The jar is in
`build/libs/`.

## License
GPL-3.0, the same as [Qwerte](https://github.com/Qwertze/Qwerte), which this is ported from.
