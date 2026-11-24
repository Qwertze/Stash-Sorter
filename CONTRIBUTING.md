# Contributing

Install JDK 25, Python 3 and Git. The wrapper selects the project's Gradle version. Put the reviewed fat JAR from your AquariusProxy Java 1.21.4 distribution in `libs/AquariusProxy.jar`. The proxy launcher and the API JAR are different artifacts. Keep the fat JAR local. Configure the CI tag and checksum as described in [Testing](docs/TESTING.md).

Create a short-lived branch with a descriptive name such as `fix/order-counts`. Use an imperative commit subject that identifies the behavior changed. Keep mechanical formatting separate from changes to game logic. Follow the neighboring Java and Kotlin formatting and the [engineering instructions](AGENTS.md).

## Local checks

```sh
python3 scripts/check_format.py
./gradlew clean build
python3 scripts/verify_artifact.py build/libs/StashSorter-1.12.4.jar
./gradlew --no-configuration-cache dependencyCheckAnalyze
```

A bug report should identify the plugin, proxy and Minecraft versions, the smallest sequence that demonstrates an ordinary failure, expected behavior and redacted diagnostics. Report security issues privately using [SECURITY.md](SECURITY.md).

## Pull request review

- Describe user impact, the root cause and why the correction belongs at this boundary.
- For a fix, add a regression with an observable failure condition. For a feature, exercise normal use plus an error or cancellation path. Use the order simulator, ephemeral loopback server or temporary filesystem as appropriate.
- Explain compatibility with existing config and data. Record a migration when behavior changes. Update README usage, architecture or testing notes as applicable, and add a changelog entry.
- Inspect unit and integration results, coverage XML/HTML, artifact contents, compiler warnings and dependency findings. Report any environment restrictions and the exact unexecuted check. Attach redacted fixtures only.
