# Testing

Use JDK 25 with the wrapper and a reviewed AquariusProxy Java 1.21.4 JAR. Place it at `libs/AquariusProxy.jar` or pass `-Paquarius_jar=/absolute/path/AquariusProxy.jar`. Python 3 runs source and packaging checks. Windows can use `gradlew.bat` and `py -3`. Run from the checkout root.

| Command | Scope | Evidence |
|---|---|---|
| `python3 scripts/check_format.py` | Source whitespace, including test sources and build files | File and line diagnostics |
| `./gradlew test` | Unit tests and simulated ordering, recovery, crafting and delivery | `build/test-results/test/` and `build/reports/tests/test/` |
| `./gradlew integrationTest` | Plugin registration against the real API interface, signed loopback HTTP, dashboard workers, filesystem persistence and recovery | `build/test-results/integrationTest/` |
| `./gradlew test integrationTest jacocoTestReport` | Combined JaCoCo measurement | `build/reports/jacoco/test/html/index.html` and `build/reports/jacoco/test/jacocoTestReport.xml` |
| `./gradlew endToEndTest -PstashManagerJar=/absolute/path/stash-manager-server.jar` | Launch a separate Stash Manager process, pair a fixture, claim and deliver simulated orders | `build/test-results/endToEndTest/` |
| `./gradlew clean build` | Compile, run unit and local integration suites, create coverage reports and package the plugin | `build/libs/StashSorter-1.12.4.jar` |
| `python3 scripts/verify_artifact.py build/libs/StashSorter-1.12.4.jar` | CRC, class versions, metadata, entry point, recipes, license and class ownership | Exit code and diagnostics |
| `./gradlew --no-configuration-cache dependencyCheckAnalyze` | Scan build and test dependencies and the supplied API JAR against vulnerability feeds | `build/reports/dependency-check-report.html` and JSON |

## Fixtures and debugging

Place ordinary JUnit Jupiter tests beside their package under `src/test/java`. Use `@Tag("integration")` for component interactions and `@Tag("e2e")` for tests requiring the real manager. To rerun a case, use the owning task, for example `./gradlew integrationTest --tests '*ManagerTransportIntegrationTest*' --info`. Check the first failing assertion and the individual XML report.

`OrderSim` supplies deterministic inventory, clock and world state. Exercise errors through observable outcomes such as a cancelled order, unresolved shulker or preserved stash. Use synthetic coordinates and fixture-only keys. Local HTTP tests bind an ephemeral loopback port. Storage tests use JUnit temporary directories. The registration test records the actual plugin entry point's registration calls against the host API interface.

The end-to-end task requires a compatible standalone manager server JAR. It selects a free loopback port and uses a new child data directory. To specify a port or scratch parent, pass `-PstashManagerPort=18791` or `-PstashManagerData=/path/to/scratch`. The custom directory is now a parent so existing configuration is preserved. Child data under a custom parent can be removed after testing. The old `test -PstashManagerJar=...` invocation still schedules the server tests. In the real-manager suite one naming scenario may be skipped for older servers. Inspect its skip reason. A live Minecraft connection is a separate compatibility check.

## Coverage policy

All owned production classes count, including the full sorter. Only generated `BuildConstants` is excluded. Unit and local integration execution data are combined. The default build produces XML and HTML. The November 20 [audit](AUDIT_REPORT.md) and its archived reports cover version 1.12.3.

The targets are 90% lines, 85% branches, 95% lines in the critical order and security classes named in the build, and 90% of modified executable lines. To evaluate the overall and critical-class gates, run `./gradlew jacocoTestCoverageVerification`. For a pull request, `python3 scripts/coverage_summary.py build/reports/jacoco/test/jacocoTestReport.xml --base <base-commit> --enforce-changes` checks the changed-line gate. The summary also runs in CI. Source changes outside executable JaCoCo lines have no denominator.

The current offline run is below target. After a full connected build establishes the JaCoCo baseline and the team agrees to enforce the targets, set repository variable `STASH_ENFORCE_COVERAGE_TARGETS=true`. Tests, compiler warnings, packaging checks and the dependency scan fail the workflow independently.

## Version 1.12.4 verification

On November 23, 2026, the released JAR passed 478 unit methods and 18 local integration methods. The 476 methods from 1.12.3 also passed against the baseline JAR. The 20 additions cover slot selection, perimeter geometry, waypoint timing and sorter integration with the AquariusProxy inventory, chunk cache, pathfinder and registered tick handler. The host fixture supplies a simulated player's readiness and block position.

The runs used Java 21, the project’s pinned AquariusProxy API digest and a workspace-only runner for the existing Jupiter test sources. It provided the annotations, assertions, per-test setup/teardown and temporary-directory support used by these suites. The packaged JAR and an instrumented compilation passed the same set of methods. Independent bytecode probes produced the following counts. JaCoCo remains the CI reference.

| Scope | Executable lines reached | Branch outcomes reached |
|---|---:|---:|
| Three extracted components | 76 / 78 (97.44%) | 70 / 70 (100%) |
| Production lines changed since 1.12.3 | 119 / 128 (92.97%) | — |
| Whole plugin | 6,099 / 17,273 (35.31%) | 4,036 / 16,843 (23.96%) |

A comparison of 5,000 seeded inputs against 1.12.3 returned identical results for legacy slot specifications, chunk order and 50,215 waypoint positions. A separate before-and-after check reproduced the separator-only parser failure on 1.12.3 and passed on 1.12.4. Direct compilation treated unchecked and deprecation warnings as errors. A clean extraction of the source ZIP produced an identical JAR, including the generated plugin metadata.

The Gradle build and dependency scanner stopped at the wrapper download because the distribution host was unreachable. The nine real-manager tests require a separate server JAR that was unavailable. Live Minecraft login and movement were also unverified.

## CI prerequisites

Configure non-secret repository variables `AQUARIUS_PROXY_TAG` and `AQUARIUS_PROXY_SHA256` with the exact upstream release and independently reviewed digest. GitHub Actions checks the wrapper bootstrap hash, verifies the API JAR before executing its processor, and runs the Gradle build on JDK 25. The scanner requires reachable vulnerability feeds and fails on errors and findings scored 7.0 or higher. Fat JARs may lose the metadata needed to identify embedded libraries. Read the host inventory and upstream advisories alongside the scan.

If the wrapper download fails, restore repository access or use a correctly provisioned cache. A failure before compilation yields no JUnit or JaCoCo result. A missing or mismatched API JAR must be corrected before invoking the build.
