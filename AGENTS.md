# Engineering instructions

## Project and compatibility

This is the AquariusProxy Java plugin for Minecraft 1.21.4. Compile in Gradle with JDK 25 and release 21 bytecode. The proxy supplies the game API, Gson, logging, protocol types and a metadata processor. Keep plugin ID, command syntax and existing stash JSON compatible. See [architecture](docs/ARCHITECTURE.md).

Start at `QwerteStashPlugin.onLoad` for registration. `module/` connects to ticks, connections and inventory. `stash/` owns persisted state and indexes. `orders/` owns planning and recovery transitions. `manager/` handles signed transport, dashboard data and settings. The CLI is under `command/`. Edit the source template under `src/main/templates` when generated constants must change.

## Making a change

- Read the real command, event or worker entry point and its callers. Keep game actions on the owning tick thread. Bound work per tick and queued network work. Publish snapshots before doing background I/O.
- Use four spaces, LF and the neighboring Java style. Check nullable inputs at trust boundaries. Keep structured failure reasons and preserve interrupt status or exit a worker on interruption. Avoid empty catches. Explain a recoverable fallback at its call site.
- Keep API keys, whispers, private chat, coordinates and API payloads out of diagnostic examples. Keep TLS, signed reply verification, local console restrictions and private storage in place. Use generated fixture data.
- Review new dependencies for compatibility, licensing, provenance, advisories and distribution size. Pin versions, check the external API digest and keep test-only dependencies out of the plugin.
- Keep generated files, third-party binary contents and local runtime data out of source edits. Preserve unrelated changes. Record user-visible behavior, file format changes, risks and migration steps in the relevant documentation and changelog.

## Before completion

Run `python3 scripts/check_format.py`, `./gradlew clean build` and `python3 scripts/verify_artifact.py build/libs/*.jar` on a POSIX shell. Run `./gradlew --no-configuration-cache dependencyCheckAnalyze` on dependency or build changes. Use the separate real-manager suite for protocol changes when a fixture is available.

Add a regression for a confirmed defect, check the normal entry point and exercise the failure or retry path. Review the diff for unrelated deletions, secret exposure, temporary files and new warnings. Report actual executions, failures, skipped tests, counts and coverage. Never claim an unexecuted check passed, weaken an assertion to make it green, silently disable a check, commit a secret or make an undocumented public behavior change.
