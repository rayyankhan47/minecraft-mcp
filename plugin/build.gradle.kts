plugins {
    java
}

group = "com.mcmcp"
version = "1.0.0"

// The Minecraft version lives in exactly one place: the VERSION file at the repo root.
// Reading it here means the Gradle build and the server jar can never drift apart.
val paperApiVersion: String = rootDir.resolve("../VERSION")
    .readLines()
    .firstOrNull { it.startsWith("PAPER_API_VERSION=") }
    ?.substringAfter("=")
    ?.trim()
    ?: error("PAPER_API_VERSION not found in ../VERSION")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // compileOnly, always. The Paper API is provided by the server at runtime.
    // There are deliberately NO other dependencies — everything else this plugin
    // needs is in the Java standard library, so there is nothing to shade or
    // relocate and nothing to resolve at runtime.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.jar {
    // Fixed name so scripts/deploy.sh and the server plugins dir stay predictable.
    archiveFileName = "mcmcp.jar"
}

// Fail loudly at build time rather than mysteriously at server start.
tasks.register("printPaperApiVersion") {
    val v = paperApiVersion
    doLast { println(v) }
}
