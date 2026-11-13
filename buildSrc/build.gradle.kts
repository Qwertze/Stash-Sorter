plugins {
    java
}

repositories {
    mavenCentral()
}

// Compiled by the JVM that runs Gradle (21 here); no toolchain, so it never asks for another JDK
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}
