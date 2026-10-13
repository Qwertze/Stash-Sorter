import java.io.File

plugins {
    java
    // Bundles the plugin (and any dependencies you choose to shade) into a single jar
    id("com.gradleup.shadow") version "9.4.1"
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("plugin_version").get()
val mc = providers.gradleProperty("mc").get()
val pluginId = providers.gradleProperty("plugin_id").get()
val pluginName = providers.gradleProperty("plugin_name").get()

// The Java version your plugin's bytecode targets.
// Must be <= the Java version AquariusProxy runs on (the `java` release channel runs Java 21+).
val javaReleaseVersion = 21

java {
    toolchain {
        // The JDK used to *compile*. Must be >= the JDK AquariusProxy was built with (25)
        // because compilation runs AquariusProxy's bundled annotation processor.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // Repos used by AquariusProxy/ZenithProxy — only needed if you `shade` extra dependencies
    maven("https://maven.2b2t.vc/releases")
    maven("https://maven.2b2t.vc/remote")
}

// --- AquariusProxy API jar ----------------------------------------------------
// Plugins compile against the AquariusProxy "fat" jar — the exact jar the launcher
// runs. It bundles the entire API plus the @Plugin annotation processor that emits
// your plugin's metadata file (zenithproxy.plugin.json). No Maven publishing needed.
val aquariusJarPath: String = providers.gradleProperty("aquarius_jar")
    .orElse(layout.projectDirectory.file("libs/AquariusProxy.jar").asFile.absolutePath)
    .get()
val aquariusJar = files(aquariusJarPath)

dependencies {
    // `compileOnly` + `annotationProcessor`: the proxy provides these classes at runtime,
    // and running it as an annotation processor generates your plugin's metadata file.
    compileOnly(aquariusJar)
    annotationProcessor(aquariusJar)

    // To bundle a runtime dependency *into* your plugin jar, declare it here and relocate
    // it in the shadowJar block below, e.g.:
    // implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
}

// Fail early with a helpful message if the API jar is missing
val checkAquariusJar = tasks.register("checkAquariusJar") {
    val path = aquariusJarPath
    doFirst {
        if (!File(path).exists()) {
            throw GradleException(
                "\nAquariusProxy API jar not found at:\n  $path\n\n" +
                "Place the AquariusProxy fat jar (the one the launcher runs) at libs/AquariusProxy.jar,\n" +
                "or point the build at it with: -Paquarius_jar=/path/to/AquariusProxy.jar\n" +
                "You can copy it from your AquariusProxy install dir or download it from:\n" +
                "  https://github.com/aquariusnetwork9/AquariusProxy/releases\n"
            )
        }
    }
}

// --- BuildConstants templating ------------------------------------------------
// Replaces ${...} placeholders in src/main/templates with values from gradle.properties
val templateProps = mapOf(
    "version" to version.toString(),
    "mc_version" to mc,
    "plugin_id" to pluginId,
    "maven_group" to group.toString(),
)
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    inputs.properties(templateProps)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand(templateProps)
    filteringCharset = "UTF-8"
}

sourceSets.named("main") {
    java.srcDir(generateTemplates.map { it.destinationDir })
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkAquariusJar)
    options.encoding = "UTF-8"
    options.release = javaReleaseVersion
}

// --- Packaging ----------------------------------------------------------------
tasks.named<Jar>("jar") {
    enabled = false // the shadow jar is the plugin artifact
}

tasks.named<Jar>("shadowJar") {
    archiveBaseName = pluginName
    archiveClassifier = ""
    // If you shade dependencies, relocate them to avoid clashing with other plugins, e.g.:
    // (this requires casting to the ShadowJar type — see the wiki for an example)
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// --- Local testing ------------------------------------------------------------
// `./gradlew run` launches AquariusProxy with this plugin loaded in the ./run directory.
// First run walks you through the normal proxy setup (account, target server, etc.).
val pluginJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

val installPluginToRun = tasks.register<Copy>("installPluginToRun") {
    from(pluginJar)
    into(layout.projectDirectory.dir("run/plugins"))
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run AquariusProxy with this plugin loaded (local testing)"
    dependsOn(installPluginToRun)
    notCompatibleWithConfigurationCache("Interactive proxy console reads System.in")
    classpath = aquariusJar
    mainClass = "com.aquarius.Proxy"
    workingDir = layout.projectDirectory.dir("run").asFile
    standardInput = System.`in`
    jvmArgs = listOf(
        "-Xmx300m",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
    outputs.upToDateWhen { false }
}
