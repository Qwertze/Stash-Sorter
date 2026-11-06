# Changelog

All notable changes to Stash Sorter are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
