# Changelog

All notable changes to Stash Sorter are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
