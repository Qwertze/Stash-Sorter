# Security

## Scope and reporting

This review covers the source snapshot labeled 1.12.3, targeting the AquariusProxy Java distribution for Minecraft 1.21.4. Older snapshots, stock ZenithProxy, the separately deployed manager and other Minecraft versions have no validation in this review. The upstream README lists AquariusProxy 5.13.0+ as a requirement. See the [verification record](docs/AUDIT_REPORT.md) for the specific host artifact used.

Report a suspected vulnerability privately to the maintainer of the build you installed. For upstream issues use GitHub's private vulnerability reporting option if enabled, or request a private reporting channel from the maintainer. Give the affected version, impact and a sanitized description. Remove API keys, real coordinates, chat, tokens, stash JSON and authentication caches. Coordinate publication with the maintainer after remediation.

## Deployment and updates

The manager is optional. Use HTTPS for a remote manager. The dashboard exposes location and inventory information to the configured service. The API key is sensitive even though it is never sent as a request header value. Config and stash data are plaintext at rest. Restrict the proxy directory, Windows ACLs and backup access. On POSIX new stash and index files and their new backups are created with mode 0600.

Review the exact proxy release and digest before running a build or upgrading a host. Its fat JAR supplies runtime dependencies and executes an annotation processor during compilation. Compile and test against that reviewed build. Review build and dependency alerts and scan results, including host-supplied libraries. Use a compatible patched host to remediate findings in those libraries. See the [security audit](docs/SECURITY_AUDIT.md).
