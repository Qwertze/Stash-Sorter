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
- AquariusProxy on a **`java`** release channel (the native build can't load plugins), Minecraft 1.21.4.

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
| `stash newBulkAt <stacks>` | Start a new bulk shulker from an empty one at this many stacks of one item (9). 0 only tops up existing ones |
| `stash carry <slots>` / `clear` | Slots to carry stash items in, like `28-36`. Default: every empty slot |
| `stash keep <slots>` / `clear` | Slots never to touch |
| `stash rescan` | Read every container again on the next run |
| `stash forget` | Delete the saved stash and its marks |
| `stash tracking on/off` | Keep the stash up to date when containers are opened (on) |
| `stash whisper on/off` | Whisper search (off) |
| `stash whitelist add/del <player>` | Who can search by whisper |
| `stash prefix <word>` / `clear` | Whispers starting with this are searches (`find`) |

Slots: 1-9 is the hotbar, 10-36 the inventory from the top left.

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

Once everything is in place, partial stacks of the same item in each container are merged ("downstacked"), so a
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
