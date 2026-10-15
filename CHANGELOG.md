# Changelog

All notable changes to Stash Sorter are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
- Extra diagnostic INFO lines in the log to help track down problems, for example "Container at ... has been the
  target of N jobs without the backlog shrinking", "... did nothing", "Stalled containers (N) forgotten at ...", and
  "Sign at ... reported changing, waiting for it to settle". Also new warnings for stopped runs and blocked shulker
  placement.
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
