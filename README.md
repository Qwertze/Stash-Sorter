# Qwerte Stash

An [AquariusProxy](https://github.com/aquariusnetwork9/AquariusProxy) plugin: the stash sorter from the
[Qwerte](https://github.com/Qwertze/Qwerte) Meteor addon, running on your proxy bot instead of your client.

- Sorts a stash of chests and barrels into **tiers** (filled shulkers, empty shulkers, x64, x16, singles) or by the
  **signs** on them, written in your own words (`Totems`, `Crystals`, `Building Blocks`, `PvP`, `Misc`...).
- Shulkers are sorted by what they're mostly full of: a shulker of totems goes to the `Totems` chest.
- **Repacks shulkers** (signs mode): items that belong elsewhere are taken out of mixed shulkers, and loose items are
  packed into shulkers that already hold only that item, before everything is put away. See below.
- Scans the stash once and saves it. After that, opening a container through the proxy keeps it up to date.
- Excluded and drop-off containers, stay-on mode, carry and keep slots.
- Search from the terminal, Discord or in-game (`stash search <item>`), or let whitelisted players whisper the bot
  `find <item>`. It replies `/whisper <player> <Item> Can be Found At <x, y, z> @ <stash>`.

Sorting only runs while nobody is connected to the proxy, so it pauses while you play and picks up again when you
leave. It never breaks or places blocks to reach a container; the only blocks it places and breaks are shulkers it's
repacking.

## Requirements
- AquariusProxy on a **`java`** release channel (the native build can't load plugins), Minecraft 1.21.4.

## Install
1. Stop the proxy.
2. Put `QwerteStash-<version>.jar` in the proxy's `plugins` folder.
3. Start the proxy. `plugins` in the console should list `qwerte-stash`.

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
| `stash dropoff [x y z]` | Empty this container into the stash, for others to drop items in. Toggles |
| `stash radius <blocks>` / `height <blocks>` | How far around the bot to look for new containers (8 / 3) |
| `stash barrels on/off` | Include barrels (on) |
| `stash delay <ticks>` | Ticks between inventory clicks (3). Raise it if the server rejects clicks |
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

Left as they are: named shulkers, and any shulker when a sign asks for shulkers whole, like `Kits` or `Full
Shulkers`. If you keep kits, give them a `Kits` chest so they aren't taken apart.

## Building
Needs JDK 25. Copy your proxy's `AquariusProxy.jar` into `libs/`, then run `./gradlew build`. The jar is in
`build/libs/`.
