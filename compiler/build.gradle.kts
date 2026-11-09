import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.security.MessageDigest
import java.util.Properties

plugins {
    // Kotlin and antlr-kotlin are pinned together: antlr-kotlin's runtime is built with one Kotlin version, and 1.0.8 is
    // the last release built with Kotlin 2.2.20 (its later releases need a later Kotlin). Move them together, and run the
    // corpus check on every target after, as a new Kotlin can change what Kotlin/Wasm or Kotlin/Native writes.
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.strumenta.antlr-kotlin") version "1.0.8"
    `maven-publish`
    // Bundles the publications for the Central Portal, as one deployment. drivine4j publishes with the same version.
    id("com.gradleup.nmcp") version "0.0.8"
}

group = "org.drivine"
version = providers.gradleProperty("compiler.version").getOrElse("0.0.0-SNAPSHOT")

/** The JVM the compiler is built and tested with, as drivine4j pins it: Temurin 21, of any update. */
val pinnedJvm = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

/**
 * The exact JDK of gradle/pinned-jdk.properties, which writes the zone ids and the JVM's answers: a JDK brings its own
 * tzdb, so the zones the compiler takes are those of one JDK, changed only by a reviewed change of the pin.
 */
val pinnedJdk = Properties().apply { layout.projectDirectory.file("gradle/pinned-jdk.properties").asFile.inputStream().use(::load) }
val pinnedJdkVersion: String = pinnedJdk.getProperty("version")
val pinnedTzdb: String = pinnedJdk.getProperty("tzdb")

/**
 * The `java` of the pinned JDK: the toolchain's where it is that release (CI installs it), else Adoptium's archive of it,
 * fetched once into ~/.gradle/drivine-jdks and checked against the pinned SHA-256.
 */
fun pinnedJava(): File {
    val toolchain = pinnedJvm.get()
    if (toolchain.metadata.javaRuntimeVersion.startsWith(pinnedJdkVersion)) return toolchain.executablePath.asFile
    val os = System.getProperty("os.name").lowercase().let { when { it.contains("mac") -> "mac"; it.contains("windows") -> "windows"; else -> "linux" } }
    val arch = System.getProperty("os.arch").let { if (it == "aarch64" || it == "arm64") "aarch64" else "x64" }
    val sha256 = requireNotNull(pinnedJdk.getProperty("sha256.$os-$arch")) { "gradle/pinned-jdk.properties pins no JDK for $os-$arch: install Temurin $pinnedJdkVersion." }
    val home = File(gradle.gradleUserHomeDir, "drivine-jdks/temurin-$pinnedJdkVersion-$os-$arch")
    val java = { home.walkTopDown().maxDepth(6).firstOrNull { it.isFile && (it.name == "java" || it.name == "java.exe") && it.parentFile.name == "bin" } }
    java()?.let { return it }
    val archive = File(home.parentFile, "temurin-$pinnedJdkVersion-$os-$arch" + if (os == "windows") ".zip" else ".tar.gz")
    archive.parentFile.mkdirs()
    val url = "https://api.adoptium.net/v3/binary/version/jdk-${pinnedJdkVersion.replace("+", "%2B")}/$os/$arch/jdk/hotspot/normal/eclipse"
    logger.lifecycle("Fetching Temurin $pinnedJdkVersion ($os-$arch), the pinned JDK, from $url")
    uri(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
    val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
    if (digest != sha256) {
        archive.delete()
        throw GradleException("Temurin $pinnedJdkVersion ($os-$arch) has SHA-256 $digest, not $sha256 as gradle/pinned-jdk.properties pins.")
    }
    // The system's tar (bsdtar on Windows, which reads a zip too), which keeps the JDK's file modes.
    home.deleteRecursively()
    home.mkdirs()
    val untar = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", home.absolutePath).inheritIO().start()
    if (untar.waitFor() != 0) throw GradleException("tar could not unpack $archive.")
    archive.delete()
    return requireNotNull(java()) { "No bin/java in Temurin $pinnedJdkVersion's archive." }.also { it.setExecutable(true) }
}

val generateGrammar = tasks.register<AntlrKotlinTask>("generateGrammar") {
    source = fileTree(layout.projectDirectory.dir("src/commonMain/antlr")) { include("**/*.g4") }
    packageName = "org.drivine.cypher.grammar"
    arguments = listOf("-visitor", "-no-listener")
    outputDirectory = layout.buildDirectory.dir("generated/antlr/org/drivine/cypher/grammar").get().asFile
}

/** The zone ids of the pinned JDK, and the tables of powers the double text and parse use: see gen/Generate.java. */
val generateTables = tasks.register<Exec>("generateTables") {
    val generator = layout.projectDirectory.file("gen/Generate.java")
    val out = layout.buildDirectory.dir("generated/tables")
    inputs.file(generator)
    inputs.file(layout.projectDirectory.file("gradle/pinned-jdk.properties"))
    outputs.dir(out)
    doFirst {
        delete(out)
        executable(pinnedJava().absolutePath)
    }
    args(generator.asFile.absolutePath, out.get().asFile.absolutePath, pinnedJdkVersion, pinnedTzdb)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    // The schema specs' DefaultConstructorMarker is an expect class: see its file.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("drivine-cypher-compiler")
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    // The shared library (drivine_compile, drivine_free) a host without a JVM or JS loads, such as Python over ctypes.
    // macosArm64 is built and checked on macOS; the others build anywhere Kotlin/Native runs, and linuxX64 is checked in CI.
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries.sharedLib("drivinecypher")
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // The module calls its own declarations that are drivine4j's to call too: see org.drivine.DrivineInternalApi.
        all { languageSettings.optIn("org.drivine.DrivineInternalApi") }
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/antlr"))
            kotlin.srcDir(generateTables)
            dependencies {
                implementation("com.strumenta:antlr-kotlin-runtime:1.0.8")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                // Named in the module's public signatures (a temporal parameter, a decimal), so a caller compiles against them.
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                api("com.ionspin.kotlin:bignum:0.3.10")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmTest {
            // The spec's files, read as drivine4j reads its copy of them.
            resources.srcDir(layout.buildDirectory.dir("spec"))
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.2")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}

// Node 22 or later, as the npm package asks: the tests read the JVM's answers with process.getBuiltinModule (Node 22.3).
extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> { version.set("22.12.0") }

/** The spec's files, at the top of this repository, as `cypher/<file>` on the JVM tests' classpath. */
val specResources = tasks.register<Copy>("specResources") {
    from(layout.projectDirectory.dir("..")) { include("*.yaml", "README.md") }
    into(layout.buildDirectory.dir("spec/cypher"))
}
tasks.named("jvmTestProcessResources") { dependsOn(specResources) }

tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(generateGrammar, generateTables) }
// The root publication's sourcesJar too, which publishing adds.
tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateGrammar, generateTables) }

/**
 * What the other targets are checked against, written by the JVM build: every spec statement compiled for Postgres, Oracle
 * and SQLite (corpus.jsonl), a sample of them mutated (mutants.jsonl, -Pmutants.count=<n>), and doubles as Java writes
 * and reads them (doubles.txt). See org.drivine.cypher.oracle.Oracle.
 */
val oracleDir = layout.buildDirectory.dir("oracle")
val jvmOracle = tasks.register<Exec>("jvmOracle") {
    description = "Writes the JVM's answers the other targets are checked against, into build/oracle."
    val compilation = kotlin.jvm().compilations.getByName("test")
    dependsOn(compilation.compileTaskProvider, "jvmTestProcessResources")
    val classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    // Such as a flight recording: -Poracle.jvmArgs="-XX:StartFlightRecording=…".
    val jvmArgs = providers.gradleProperty("oracle.jvmArgs").orNull?.split(' ') ?: emptyList()
    val oracleArgs = listOf(
        layout.projectDirectory.dir("..").asFile.absolutePath,
        oracleDir.get().asFile.absolutePath,
        providers.gradleProperty("fuzz.count").getOrElse("100000"),
        providers.gradleProperty("fuzz.seed").getOrElse("646"),
        providers.gradleProperty("mutants.count").getOrElse("10000"),
    )
    outputs.dir(oracleDir)
    outputs.upToDateWhen { false }
    // The pinned JDK, whose zones the other targets take: the JVM's answers are its.
    doFirst {
        executable(pinnedJava().absolutePath)
        args(listOf("-Xmx2g") + jvmArgs + listOf("-cp", classpath.asPath, "org.drivine.cypher.oracle.Oracle") + oracleArgs)
    }
}

// The double fuzz on each target reads the JVM's answers.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    dependsOn(jvmOracle)
    environment("DRIVINE_ORACLE", oracleDir.get().asFile.absolutePath)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(jvmOracle)
    environment("DRIVINE_ORACLE", oracleDir.get().asFile.absolutePath)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    dependsOn(jvmOracle)
    environment("DRIVINE_ORACLE", oracleDir.get().asFile.absolutePath)
}

/** The npm package's name, from gradle.properties: the one name the package, its check and the release use. */
val npmName: String = providers.gradleProperty("compiler.npmName").get()

/** The npm package, [npmName], in build/npm: the production wasm, its ES module loaders, the CJS entry, and the types of each. */
val npmPackage = tasks.register<Sync>("npmPackage") {
    description = "Assembles the npm package $npmName in build/npm."
    val distribution = tasks.named("wasmJsNodeProductionLibraryDistribution")
    from(distribution) { include("*.wasm", "*.mjs", "*.d.mts") }
    from(layout.projectDirectory.dir("npm")) { include("index.cjs", "index.d.cts") }
    into(layout.buildDirectory.dir("npm"))
    val packageVersion = project.version.toString()
    doLast {
        // Kotlin's own package.json names the loader's npm dependencies (kotlinx-datetime's @js-joda/core).
        @Suppress("UNCHECKED_CAST")
        val kotlinPackage = groovy.json.JsonSlurper().parse(distribution.get().outputs.files.singleFile.resolve("package.json")) as Map<String, Any?>
        val packageJson = linkedMapOf(
            "name" to npmName,
            "version" to packageVersion,
            "description" to "Drivine's Cypher compiler: Cypher as SQL for Postgres, Oracle and SQLite, built for Wasm.",
            "license" to "Apache-2.0",
            "repository" to mapOf("type" to "git", "url" to "https://github.com/liberation-data/drivine-cypher-spec.git", "directory" to "compiler"),
            "type" to "commonjs",
            "main" to "index.cjs",
            "types" to "drivine-cypher-compiler.d.mts",
            // Each condition names its types: TypeScript's node16 resolution of a `require` reads no top-level `types`.
            "exports" to mapOf(
                "." to mapOf(
                    "import" to mapOf("types" to "./drivine-cypher-compiler.d.mts", "default" to "./drivine-cypher-compiler.mjs"),
                    "require" to mapOf("types" to "./index.d.cts", "default" to "./index.cjs"),
                ),
            ),
            "engines" to mapOf("node" to ">=22"),
            "dependencies" to kotlinPackage["dependencies"],
        )
        destinationDir.resolve("package.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(packageJson)) + "\n")
    }
}

/** The host's Kotlin/Native target, whose shared library the native corpus check loads. */
val hostNative: String? = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") && arm -> "macosArm64"
        os.contains("linux") && arm -> "linuxArm64"
        os.contains("linux") -> "linuxX64"
        os.contains("windows") -> "mingwX64"
        else -> null
    }
}

/**
 * The corpus check on wasmJs: the npm package, loaded through its CommonJS entry by Node 22 or later (`node` on the PATH,
 * or -Pnode=<path>), compiles each request of the JVM's corpus and of its mutants, and its answer must be the JVM's, byte
 * for byte.
 */
tasks.register<Exec>("wasmJsCorpusCheck") {
    description = "Checks the wasmJs build's answers against the JVM's, over the spec's corpus and its mutants."
    group = "verification"
    dependsOn(npmPackage, jvmOracle, "kotlinWasmNpmInstall")
    executable(providers.gradleProperty("node").getOrElse("node"))
    // The loader's npm dependencies, as Kotlin installed them for the wasm tests.
    environment("NODE_PATH", layout.buildDirectory.dir("wasm/node_modules").get().asFile.absolutePath)
    // The name the package must have: a package built under another than the release publishes fails the check.
    environment("DRIVINE_NPM_NAME", npmName)
    args(
        layout.projectDirectory.file("check/corpus-node.cjs").asFile.absolutePath,
        layout.buildDirectory.dir("npm").get().asFile.absolutePath,
        oracleDir.get().file("corpus.jsonl").asFile.absolutePath,
        oracleDir.get().file("differences-wasmJs.jsonl").asFile.absolutePath,
        oracleDir.get().file("mutants.jsonl").asFile.absolutePath,
        oracleDir.get().file("differences-mutants-wasmJs.jsonl").asFile.absolutePath,
    )
}

/**
 * The corpus check on the host's native target: the release shared library, loaded over its C ABI by Python's ctypes
 * (`python3`, or -Ppython=<path>), compiles each request of the JVM's corpus and of its mutants, and its answer must be
 * the JVM's.
 */
tasks.register<Exec>("nativeCorpusCheck") {
    description = "Checks the host's native shared library's answers against the JVM's, over the spec's corpus and its mutants."
    group = "verification"
    val target = requireNotNull(hostNative) { "No Kotlin/Native target for this host." }
    val link = "linkDrivinecypherReleaseShared${target.replaceFirstChar { it.uppercase() }}"
    dependsOn(link, jvmOracle)
    val library = when {
        target.startsWith("macos") -> "libdrivinecypher.dylib"
        target.startsWith("mingw") -> "drivinecypher.dll"
        else -> "libdrivinecypher.so"
    }
    executable(providers.gradleProperty("python").getOrElse("python3"))
    args(
        layout.projectDirectory.file("check/corpus-native.py").asFile.absolutePath,
        layout.buildDirectory.file("bin/$target/drivinecypherReleaseShared/$library").get().asFile.absolutePath,
        oracleDir.get().file("corpus.jsonl").asFile.absolutePath,
        oracleDir.get().file("differences-$target.jsonl").asFile.absolutePath,
        oracleDir.get().file("mutants.jsonl").asFile.absolutePath,
        oracleDir.get().file("differences-mutants-$target.jsonl").asFile.absolutePath,
    )
}

/**
 * The module's Maven publications, one for each target and the root one that names them, as org.drivine:drivine-cypher-compiler
 * (the JVM's as drivine-cypher-compiler-jvm). A spec tag publishes them to Maven Central (README.md, "Releasing"). Between
 * tags, drivine4j builds against the JVM's, published to a local repository from a pinned commit:
 *
 *   ./gradlew publishJvmPublicationToMavenLocal publishKotlinMultiplatformPublicationToMavenLocal -Pcompiler.version=<v>
 */
publishing {
    publications.withType<MavenPublication>().configureEach {
        // Maven Central takes a publication only with a javadoc jar beside its jar and sources: an empty one, as the
        // module's API is documented in its KDoc and README. One for each publication, named for it.
        val publication = name
        artifact(tasks.register<Jar>("${publication}JavadocJar") {
            description = "The empty javadoc jar Maven Central asks of the $publication publication."
            archiveClassifier.set("javadoc")
            archiveBaseName.set("drivine-cypher-compiler-$publication")
        })
        // What Maven Central asks of a POM: a name, a description, a URL, a license, a developer and the source's place.
        pom {
            name.set("Drivine Cypher compiler")
            description.set("Drivine's compiler of Cypher to SQL for Postgres, Oracle and SQLite.")
            url.set("https://github.com/liberation-data/drivine-cypher-spec")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("jasperblues")
                    name.set("Jasper Blues")
                    email.set("jasper@liberation-data.com")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/liberation-data/drivine-cypher-spec.git")
                developerConnection.set("scm:git:ssh://git@github.com/liberation-data/drivine-cypher-spec.git")
                url.set("https://github.com/liberation-data/drivine-cypher-spec")
            }
        }
    }
}

/*
 * Every publication in one bundle, unsigned, for one deployment, so the Central Portal releases every target or none:
 * zipAggregationPublication builds it (build/nmcp/zip/publicationAggregated.zip). The release signs and uploads it
 * outside Gradle (.github/scripts/compiler-release-sign.sh and compiler-release-upload.sh), so the build never sees the
 * release key or the Portal token.
 */
nmcp {
    publishAllPublications {
        publicationType = "AUTOMATIC"
    }
    publishAggregation {
        project(":")
        publicationType = "AUTOMATIC"
    }
}

/** Every metadata compilation: common code compiled alone, which catches what a target's own compilation lets through. */
tasks.register("metadataCompile") {
    description = "Compiles each shared source set's metadata."
    group = "verification"
    dependsOn(tasks.matching { it.name.startsWith("compile") && it.name.endsWith("KotlinMetadata") })
}
