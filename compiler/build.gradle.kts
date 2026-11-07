import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    // Kotlin and antlr-kotlin are pinned together: antlr-kotlin's runtime is built with one Kotlin version, and 1.0.8 is
    // the last release built with Kotlin 2.2.20 (its later releases need a later Kotlin). Move them together, and run the
    // corpus check on every target after, as a new Kotlin can change what Kotlin/Wasm or Kotlin/Native writes.
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.strumenta.antlr-kotlin") version "1.0.8"
}

group = "org.drivine"
version = providers.gradleProperty("compiler.version").getOrElse("0.0.0-SNAPSHOT")

/** The JVM the compiler is built and tested with, as drivine4j pins it, and the JVM the zone ids are read from. */
val pinnedJvm = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

val generateGrammar = tasks.register<AntlrKotlinTask>("generateGrammar") {
    source = fileTree(layout.projectDirectory.dir("src/commonMain/antlr")) { include("**/*.g4") }
    packageName = "org.drivine.cypher.grammar"
    arguments = listOf("-visitor", "-no-listener")
    outputDirectory = layout.buildDirectory.dir("generated/antlr/org/drivine/cypher/grammar").get().asFile
}

/** The zone ids of the pinned JVM, and the tables of powers the double text and parse use: see gen/Generate.java. */
val generateTables = tasks.register<Exec>("generateTables") {
    val generator = layout.projectDirectory.file("gen/Generate.java")
    val out = layout.buildDirectory.dir("generated/tables")
    inputs.file(generator)
    inputs.property("jvm", pinnedJvm.map { it.metadata.javaRuntimeVersion })
    outputs.dir(out)
    doFirst { delete(out) }
    executable(pinnedJvm.get().executablePath.asFile.absolutePath)
    args(generator.asFile.absolutePath, out.get().asFile.absolutePath)
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
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/antlr"))
            kotlin.srcDir(generateTables)
            dependencies {
                implementation("com.strumenta:antlr-kotlin-runtime:1.0.8")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.ionspin.kotlin:bignum:0.3.10")
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
tasks.matching { it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateGrammar, generateTables) }

/**
 * What the other targets are checked against, written by the JVM build: every spec statement compiled for Postgres, Oracle
 * and SQLite (corpus.jsonl), and doubles as Java writes and reads them (doubles.txt). See org.drivine.cypher.oracle.Oracle.
 */
val oracleDir = layout.buildDirectory.dir("oracle")
val jvmOracle = tasks.register<JavaExec>("jvmOracle") {
    description = "Writes the JVM's answers the other targets are checked against, into build/oracle."
    val compilation = kotlin.jvm().compilations.getByName("test")
    dependsOn(compilation.compileTaskProvider, "jvmTestProcessResources")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.drivine.cypher.oracle.Oracle")
    javaLauncher.set(pinnedJvm)
    maxHeapSize = "2g"
    args(
        layout.projectDirectory.dir("..").asFile.absolutePath,
        oracleDir.get().asFile.absolutePath,
        providers.gradleProperty("fuzz.count").getOrElse("100000"),
        providers.gradleProperty("fuzz.seed").getOrElse("646"),
    )
    // Such as a flight recording: -Poracle.jvmArgs="-XX:StartFlightRecording=…".
    providers.gradleProperty("oracle.jvmArgs").orNull?.let { jvmArgs(it.split(' ')) }
    outputs.dir(oracleDir)
    outputs.upToDateWhen { false }
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

/** The npm package, @drivine/cypher-compiler, in build/npm: the production wasm, its ES module loaders and the CJS entry. */
val npmPackage = tasks.register<Sync>("npmPackage") {
    description = "Assembles the npm package @drivine/cypher-compiler in build/npm."
    val distribution = tasks.named("wasmJsNodeProductionLibraryDistribution")
    from(distribution) { include("*.wasm", "*.mjs", "*.d.mts") }
    from(layout.projectDirectory.dir("npm")) { include("index.cjs") }
    into(layout.buildDirectory.dir("npm"))
    val packageVersion = project.version.toString()
    doLast {
        // Kotlin's own package.json names the loader's npm dependencies (kotlinx-datetime's @js-joda/core).
        @Suppress("UNCHECKED_CAST")
        val kotlinPackage = groovy.json.JsonSlurper().parse(distribution.get().outputs.files.singleFile.resolve("package.json")) as Map<String, Any?>
        val packageJson = linkedMapOf(
            "name" to "@drivine/cypher-compiler",
            "version" to packageVersion,
            "description" to "Drivine's Cypher compiler: Cypher as SQL for Postgres, Oracle and SQLite, built for Wasm.",
            "license" to "Apache-2.0",
            "repository" to mapOf("type" to "git", "url" to "https://github.com/liberation-data/drivine-cypher-spec.git", "directory" to "compiler"),
            "type" to "commonjs",
            "main" to "index.cjs",
            "types" to "drivine-cypher-compiler.d.mts",
            "exports" to mapOf("." to mapOf("import" to "./drivine-cypher-compiler.mjs", "require" to "./index.cjs")),
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
 * or -Pnode=<path>), compiles each request of the JVM's corpus, and its answer must be the JVM's, byte for byte.
 */
tasks.register<Exec>("wasmJsCorpusCheck") {
    description = "Checks the wasmJs build's answers against the JVM's, over the spec's corpus."
    group = "verification"
    dependsOn(npmPackage, jvmOracle, "kotlinWasmNpmInstall")
    executable(providers.gradleProperty("node").getOrElse("node"))
    // The loader's npm dependencies, as Kotlin installed them for the wasm tests.
    environment("NODE_PATH", layout.buildDirectory.dir("wasm/node_modules").get().asFile.absolutePath)
    args(
        layout.projectDirectory.file("check/corpus-node.cjs").asFile.absolutePath,
        layout.buildDirectory.dir("npm").get().asFile.absolutePath,
        oracleDir.get().file("corpus.jsonl").asFile.absolutePath,
        oracleDir.get().file("differences-wasmJs.jsonl").asFile.absolutePath,
    )
}

/**
 * The corpus check on the host's native target: the release shared library, loaded over its C ABI by Python's ctypes
 * (`python3`, or -Ppython=<path>), compiles each request of the JVM's corpus, and its answer must be the JVM's.
 */
tasks.register<Exec>("nativeCorpusCheck") {
    description = "Checks the host's native shared library's answers against the JVM's, over the spec's corpus."
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
    )
}

/** Every metadata compilation: common code compiled alone, which catches what a target's own compilation lets through. */
tasks.register("metadataCompile") {
    description = "Compiles each shared source set's metadata."
    group = "verification"
    dependsOn(tasks.matching { it.name.startsWith("compile") && it.name.endsWith("KotlinMetadata") })
}
