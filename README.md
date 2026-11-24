# Stash Sorter

A stash-sorting bot for [AquariusProxy](https://github.com/aquariusnetwork9/AquariusProxy), the 2b2t proxy built on
ZenithProxy. It reads a room of chests, sorts every item into the chest its sign describes, cleans up shulkers and keeps
the stash that way. It takes orders from the Stash Manager website, and whitelisted players can ask it where something is.

It is the stash sorter of the [Qwerte](https://github.com/Qwertze/Qwerte) Meteor addon, running on your proxy bot instead of your client.

> **1.22.0 (pre-release)** · Minecraft 1.21.4 · AquariusProxy 5.13.0+ · GPL-3.0 · [Changelog](CHANGELOG.md)

## Features

| Feature | What it does | Status |
| --- | --- | --- |
| **Sorting** | Tiers (filled and empty shulkers, x64, x16, singles) or your own signs. Mixed shulkers are cleaned out, loose items packed | |
| **Drop-offs and stay-on** | Drop-off chests are emptied into the stash; stay-on keeps sorting as things change | |
| **Search** | `stash search <item>` in the terminal, or `find <item>` by whisper from whitelisted players | |
| **Scan only** | `stash sort off` reads and indexes containers but moves nothing | |
| **Orders** | Deliveries, repairs with XP bottles and shulker naming on an anvil, from the website | |
| **Repair passes** | When idle, repairs low items in the stash with bottles | 1.17.0 |
| **Kits** | Kit sorting, kit types, Kit Builder templates and kit orders; the index marks complete kits | `beta` |
| **Crafting** | Shulker boxes and order items the stash can make | 1.8.0, 1.15.0 |
| **Craft orders B1 to B5** | Stash preflight, smelting, firework rockets and stars, tipped arrows, stew, copies, shields, dyed armour | `beta` |
| **Hardening** | Stricter parsing, safer file writes, strict manager address rule (ideas from PR #1 by AwesomeHeroz) | `beta` |
| **Enchant orders** | Enchanted items from a base and books, with XP levels from bottles | off by default, not tested in game |
| **Pearl travel** | Trips to a pearl station and back, with a loader bot | off by default, not tested in game |
| **Farmer role** | Replays a farm program recorded with the Qwerte addon | off by default, not tried on a real farm |

Sorting only runs while nobody is connected to the proxy, so it pauses while you play and picks up again when you leave.
The bot never breaks or places blocks to reach a container. The only blocks it places or breaks are its own shulkers
while repacking, and signs it writes for new categories.

## Quick start

**Needs:** AquariusProxy **5.13.0** or newer on the **`java.1.21.4`** release channel (the native `linux` build can't
load plugins), Minecraft 1.21.4. It does **not** load on stock ZenithProxy. Nothing else to install.

**Install**

1. Download `StashSorter-<version>.jar` from [Releases](https://github.com/Qwertze/Stash-Sorter/releases).
2. Stop the proxy and put the jar in its `plugins` folder. Remove any older Stash Sorter or `QwerteStash` jar.
3. Start the proxy. The console should list `qwerte-stash`.

Settings are saved in `plugins/config/qwerte-stash.json`, stashes in `plugins/qwerte-stash/stashes/`.

**First run**

1. Stand the bot among the containers with some empty inventory slots (or set `stash carry <slots>`).
2. For signs mode, put signs on the containers and run `stash mode signs`.
3. Run `stash name <name>`, then `stash on`. The first run reads every container; later runs go straight to sorting.

Commands are typed in the proxy terminal. The most used:

| Command | What it does |
| --- | --- |
| `stash` | Status and settings |
| `stash on` / `stash off` | Start or stop sorting |
| `stash sort on` / `off` | Off is scan only: containers are read, nothing is moved |
| `stash name <name>` | Which stash to use |
| `stash mode tiers` / `signs` | How to sort |
| `stash search <item>` | Find items, including inside shulkers |
| `stash area <x1> <y1> <z1> <x2> <y2> <z2>` | Give the stash a perimeter (`here1`, `here2`, `clear`) |
| `stash index` | Write and show what is stored where |
| `stash log quiet` / `normal` / `debug` | How much the terminal shows |

<details>
<summary><b>Full command reference</b></summary>

| Command | What it does |
| --- | --- |
| `stash exclude [x y z]` | Never sort into or out of a container (toggles) |
| `stash dropoff [x y z]` | Empty a container into the stash, for others to drop items in (toggles) |
| `stash safewalk on/off` | Never parkour, jump off or fall more than one block while walking (on) |
| `stash radius <blocks>` / `height <blocks>` | How far around the bot to look for containers (8 / 3) |
| `stash barrels on/off` | Include barrels (on) |
| `stash delay <ticks>` | Ticks between inventory clicks (3) |
| `stash containerDelay [ticks]` | Extra pause around opening containers, 0 to 100 (0) |
| `stash quiet <seconds>` | Idle time other modules need before sorting resumes (3) |
| `stash pearlSlot on/off` | Keep the first hotbar slot free for PearlPlus pearls (on) |
| `stash watch on/off`, `watchDelay`, `watchSigns`, `signDelay` | Re-read chests other players use; sort by changed signs |
| `stash autoLabel on/off` | Signs mode: put up a sign for a new category on an empty chest (on) |
| `stash unsignedOverflow on/off` | Stacks with no room go to `Misc`/`Other`/`Overflow`, then unsigned chests (on) |
| `stash repack on/off` | Clean mixed shulkers and pack loose items, in signs mode (on) |
| `stash compact on/off`, `compactTopUpAt`, `compactExclude`, `compactSkip` | Pack a full shulker's worth of an item into a shulker (on) |
| `stash newBulkAt <stacks>` | Start a bulk shulker at this many stacks of one item (9) |
| `stash carry <slots>` / `clear`, `stash keep <slots>` / `clear` | Slots to use (like `28-36`), and slots never to touch |
| `stash collect on/off`, `collectRange <blocks>` | Pick up ground items in the stash area (on, 8) |
| `stash autoDrop on/off`, `autoDrop range <blocks>` | Give items back to a nearby player who dropped them (off, 16) |
| `stash stayOn on/off`, `recheck <seconds>`, `smartRecheck on/off` | Keep sorting as containers change; drop-off checks (on, 30) |
| `stash labels` | Show the signs the bot put up |
| `stash rescan` / `stash unstuck` | Read every container again / drop the current job and start over |
| `stash forget` | Delete the saved stash, its index and its marks |
| `stash tracking on/off` | Keep the stash up to date when containers are opened (on) |
| `stash whisper on/off`, `stash whitelist add\|del\|list <player>` | Whisper search (off) and who can use it |
| `stash friends add\|del <player or uuid>` / `list` | Your own alts: not counted as strangers near the bot |
| `stash prefix <word>` / `clear` | Whispers starting with this word are searches (`find`) |
| `stash manager url\|key\|on\|off\|status` | Stash Manager server (see below) |
| `stash manager dashboard console on/off`, `proxy on/off` | Let the website run console commands or change proxy settings (off; terminal only) |
| `stash manager includeExcluded on/off`, `scanInterval <seconds>` | Stash Manager options |
| `stash orders on/off` / `stash delivery here` | Take orders / set the delivery chest the bot is looking at |
| `stash anvil here` / `clear` | Anvil used to name shulkers for orders |
| `stash crafting on/off`, `stash craftingtable here` / `clear` | Craft empty shulker boxes; the crafting table to use |
| `stash craftorders on/off` | Take orders with craft lines (see Craft orders) |
| `stash craftNeverUse add\|remove\|list <item>` | Items never used for crafting |
| `stash kitSorting on/off` | Kit sorting (on) |
| `stash enchantorders on/off` | Take enchant orders (off) |
| `stash repairpass on/off`, `stash repair run\|stop\|status` | Repair passes |
| `stash pearl travel\|loader on/off`, `grace <seconds>` | Pearl travel (off) and loader (on) |
| `stash pearl go <station id>` / `back` / `home` / `stop` / `status` | Pearl trips |
| `stash farm on/off`, `status`, `accept [minutes]`, `start <id>`, `stop`, `selftest` | Farmer role (terminal only) |
| `stash tasks stop` / `resume` / `list` | Stop, resume or list tasks |

Slots: 1 to 9 is the hotbar, 10 to 36 the inventory from the top left.

</details>

## Stash Manager server

A Stash Manager server is a website for your
network. It shows the stash, takes orders and can show this plugin's settings.

1. Start the server and copy its API key from its console (or its Link addon panel).
2. In the proxy terminal:
   ```
   stash manager url https://stash.example.com
   stash manager key <key>
   stash manager on
   ```
3. For orders, stand the bot near a chest and run `stash delivery here`, then `stash orders on`. `stash orders` says what is stopping orders.

**Server address rule**

| Address | Result |
| --- | --- |
| `https://stash.example.com` | Accepted |
| `http://192.168.1.10:8765` (this machine or a private network) | Accepted. One warning is logged, because the traffic is signed but not encrypted |
| `http://localhost:8765` | Accepted, with the same one-time warning |
| `http://stash.example.com` (a public host over plain http) | Refused. Use https |

Public hosts need https. A host name other than `localhost` needs https too, because names are never looked up to decide.
A saved public `http://` address shows "bad url" until it is changed to https.

<details>
<summary><b>Sorting details: stash area, ground items, shulkers, signs</b></summary>

- **Stash area.** `stash area` sets an inclusive box (at most 256 × 128 × 256) saved with the stash. Containers outside it
  are never added. On `stash on`, when the box changes, and on `stash rescan`, the bot walks the loaded columns of the box
  to find containers. A walk that leaves the box is given up.
- **Ground items.** With `collect` on, items on the ground in the stash area (widened by `collectRange`) are picked up,
  except items the bot dropped or broke itself. Nothing is touched while another player is in render distance.
- **Repacking.** In signs mode, mixed shulkers are cleaned out and loose items go into shulkers that already hold only
  that item. Named shulkers and kits are never taken apart.
- **Compacting.** 27 full stacks of one item are packed into an empty shulker and put in the item's chest. Items that do
  not stack are packed in pairs or more. Partial stacks are merged afterwards.
- **Signs.** Write them the way you'd talk: `Totems`, `Crystals`, `Building Blocks`, `PvP`, `Misc`, `Kits`. Each line is
  read on its own and all the lines together.
- **Drop-offs.** Emptied completely: anything without a home, or whose chest is full, goes to `Misc`, then anywhere with room.
- **Interrupted jobs.** A shulker the bot had put down is saved; on the next start it is picked up again first.

</details>

<details>
<summary><b>Logging and alerts</b></summary>

| What | Quiet | Normal | Debug |
| --- | --- | --- | --- |
| Errors and alerts | shown, alerts whispered | shown, alerts whispered | shown, alerts whispered |
| Step lines | shown | shown | shown |
| Info and warnings | debug log only | shown | shown |
| Debug detail | not shown | debug log only | shown, marked `[debug]` |

Alerts are things that need a person (a shulker left standing, nowhere to put stacks, a container it can't reach). Each
goes by whisper to the players on `stash whitelist`, at most one every 5 seconds, and never while the bot is offline.

</details>

<details>
<summary><b>Craft orders (beta)</b></summary>

An order can have craft lines: items the stash does not hold but can make from what it holds. The server plans the crafts,
and the bot checks the plan before it takes anything.

1. `stash crafting on` and `stash craftorders on` (on by default). The bot fetches the recipe table from the server.
2. Set the crafting table on the website (the stash's settings). With none set, the bot finds, takes or crafts one in the stash area.

Each kind has its own switch (all on by default) and shows in `stash craftorders`:

| Kind | Switch | Notes |
| --- | --- | --- |
| Smelting (B2) | `smelt on\|off`, `maxsmelt <1-256>` (256) | One furnace per order; a journal keeps the items accounted for across restarts |
| Firework rockets and stars (B3) | `firework on\|off` | Planned and delivered by their spec |
| Tipped arrows, suspicious stew, copies and shields (B4) | `tipped\|stew\|copy on\|off` | Banner and book copies keep their source; only original books are copied |
| Dyed leather and wolf armour (B5) | `dyed on\|off` | Any colour: one dye to 8 dyes, or two coats |

Caps: `stash craftorders maxfills <n>` (2048 grid fills per order) and `maxperhour <n>` (4096 an hour).

Shulker boxes are also crafted when an order or compaction needs an empty one and the stash has none
(`stash craftingtable here`, `stash crafting on`; caps `craftMaxPerOrder` 27, `craftMaxPerHour` 54).

**Not tried in game yet.** The furnace trips, the firework colour order, the tipped and stew recipes, the copy and shield
cell order and the dye arithmetic have not been run in game.

</details>

<details>
<summary><b>Kit sorting and kit orders (beta)</b></summary>

- A shulker box that scores as a kit (or, from the Stash Manager's kit types, belongs to a kit type) goes to the chest
  you picked on the website's Kits tab. Unnamed kits go to the kits chest.
- Kit chests hold only kits. A kit with a target is never taken apart by repacking.
- With kit sorting on, the index marks each complete kit copy with `completeKit`. Off, nothing about kits is sent.
- Kit orders build and deliver the kits ordered on the website; restock keeps each template's keep count when idle.
  Both need kit sorting on.
- Settings the server sends are kept in `plugins/qwerte-stash/kit-settings.json`, so the rules work while the server is offline.
- `stash kitSorting off` turns it off; the saved rules are kept.

</details>

<details>
<summary><b>Repair passes (1.17.0)</b></summary>

When the bot is idle it repairs low-durability items in the stash with Bottles o' Enchanting from the stash. Mending items
are repaired; items without Mending go to the needs-repair chest picked on the website. A pass gives way to orders and to
players within 8 blocks, and puts everything back.

| Setting | Default | Meaning |
| --- | --- | --- |
| `repairPass` | on | `stash repairpass on/off` |
| `repairPassMaxItems` | 64 | Items one pass takes (1 to 256) |
| `repairPassMaxMinutes` | 20 | Minutes one pass spends throwing bottles (1 to 120) |
| `repairPassEveryMinutes` | 30 | Least minutes between automatic passes (0 to 1440) |
| `repairPassMaxBoxes` | 8 | Unnamed boxes one pass may open (0 to 54) |
| `repairMinReserve` | 0 | Least bottles a pass keeps, when above the website's reserve |

`stash repair run`, `stop` and `status` run one manually. Named boxes and kits are never opened.
Repair passes have not been tried in game yet.

</details>

<details>
<summary><b>Enchant orders (1.20.0)</b></summary>

Needs Stash Manager server 1.4.0. The orderer picks an item and its enchantments on the website. The server plans the
anvil steps with the fewest XP points, and the bot takes the base, books, anvils and bottles from the stash, levels up
with bottles and does the anvil work. Off until `stash enchantorders on`. Not tested in game yet.

</details>

<details>
<summary><b>Pearl travel (1.18.0)</b></summary>

Needs Stash Manager server 1.3.0. `stash pearl travel on` lets a traveller bot go from its stash to a pearl station
(a loader bot running PearlPlus) and back, with trips started on the website or with `stash pearl go <station id>`.
`stash pearl loader on` lets a loader bot report the pearls of the travellers you approved. Stations and approvals are
on the website's Pearl travel panel. Nothing here sends a position. Not tested in game yet.

</details>

<details>
<summary><b>Farmer role (1.21.0)</b></summary>

A farmer bot replays a farm program recorded with the Qwerte addon's recorder (modes `custom` and `afk_still`) and keeps
the farm running. It needs Stash Manager server 1.5.0 and the 2.6p patched proxy, and is off until `stash farm on`
(terminal only). The owner starts and stops sessions on the website.

- **No coordinate ever leaves the bot.** Programs hold positions relative to an origin kept only in an encrypted store.
  Do not name a farm after its coordinates.
- `plugins/qwerte-stash/farm/farm.key` is the key for those stores. Keep a backup; a lost key means each farm is recorded again.
  A short or empty key file is never replaced by the plugin.
- The farm has been run only in a scratch setup, not on a real farm.

</details>

<details>
<summary><b>If the bot goes offline or is stopped</b></summary>

If the bot has a shulker standing in the world (repacking, or compacting between rounds), what it was doing is saved with the
stash. When sorting starts again or the bot reconnects, that comes first: the shulker is opened, finished, broken and
picked up. It gives up after 10 minutes or three failed walks back and reports the shulker.

</details>

## Building

Needs **JDK 25** (the plugin is compiled for Java 21).

1. Copy your proxy's `AquariusProxy.jar` into `libs/` (or pass `-Paquarius_jar=<path>`).
2. Run `./gradlew build` (on Windows: `gradlew.bat build`). Tests: `./gradlew test`.
3. The plugin is `build/libs/StashSorter-1.22.0.jar`.

The build checks the synced `qwerte-core` copy against `../Qwertze/qwerte-core` when that folder exists. Run
`./gradlew syncQwerteCore` after changing the library.

## Versioning

Major breaking changes: a new x.0.0. New features, bug fixes and more: 1.x.0. Minor fixes and hotfixes: x.y.z.
Every change is listed in the [CHANGELOG](CHANGELOG.md).

## License

GPL-3.0, the same as [Qwerte](https://github.com/Qwertze/Qwerte), which this is ported from. See [LICENSE](LICENSE).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
