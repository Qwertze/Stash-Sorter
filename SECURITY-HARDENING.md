# Security-hardened fork (source changes)

This patch prioritizes protecting Minecraft base coordinates and proxy owner authority.

## Security changes
- The dashboard live viewer no longer records/exports whispers, including via direct ChatRing calls.
- Plain HTTP is accepted only when the URL host is exactly `localhost`, `127.0.0.1`, or `::1`; remote server URLs must use HTTPS. Existing remote HTTP configurations must be updated.
- Remote `command` jobs are rejected regardless of the legacy `dashboardConsole` toggle. Use the proxy terminal instead.
- Remote `set` jobs are restricted to the plugin's own scope, regardless of `dashboardProxy`. Other proxy/plugin settings are not remotely writable.
- Dashboard sources cannot pass the proxy account-owner validation check.
- Stash JSON, index snapshots and new backup copies use owner-only POSIX permissions (`0600`) from creation. Existing older files and backups, parent directory permissions and Windows ACLs still need attention.

## Remaining risks / limitations
- Inventory/coordinate indexes and underlying stash files remain plaintext at rest. Use volume encryption and restrict backups. No backward-incompatible on-disk encryption was added.
- The remote manager/server is outside the supplied source. The separately available AquariusProxy API JAR was used for compilation and a partial component inventory. See [the current security audit](docs/SECURITY_AUDIT.md).
- The dashboard can still access stash data; treat that service and API key as highly sensitive. Disable the integration if not needed.
- CI now requires repository variables `AQUARIUS_PROXY_TAG` and `AQUARIUS_PROXY_SHA256`; without them it intentionally fails instead of downloading unpinned dependency code. Obtain these values from a trusted release before running GitHub Actions.
- Proxy and other-plugin string configuration values are masked in dashboard schema (instead of relying on name heuristics).
- No full integration test with a live AquariusProxy server was performed.
- The old `dashboard console on` and `dashboard proxy on` settings remain in the UI for config compatibility but cannot grant the disabled remote privileges in this fork.
