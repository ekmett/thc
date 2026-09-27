// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
import java.security.MessageDigest
import java.lang.module.Configuration
import java.lang.module.FindException
import java.lang.module.ModuleFinder
import java.util.jar.JarFile

val materializableApiVersion = "25.3.4.1"
val materializableApiSource = configurations.create("materializableApiSource") {
    isCanBeConsumed = false; isTransitive = false
}
val materializableApiBinary = configurations.create("materializableApiBinary") {
    isCanBeConsumed = false; isTransitive = false
}
val materializableApiCompile = configurations.create("materializableApiCompile") {
    isCanBeConsumed = false
}
dependencies {
    add(materializableApiSource.name, "org.graalvm.truffle:truffle-api:$materializableApiVersion:sources")
    add(materializableApiBinary.name, "org.graalvm.truffle:truffle-api:$materializableApiVersion")
    add(materializableApiCompile.name, "org.graalvm.truffle:truffle-api:$materializableApiVersion")
}
fun requireApiArchive(file: File, hash: String) {
    check(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == hash) {
        "Pinned Truffle API archive hash mismatch: ${file.name}"
    }
}
val prepareMaterializableApi = tasks.register<Sync>("prepareMaterializableApi") {
    from(provider { zipTree(materializableApiSource.singleFile) })
    include("com/oracle/truffle/api/nodes/RootNode.java", "com/oracle/truffle/api/nodes/NodeAccessor.java",
        "com/oracle/truffle/api/bytecode/ContinuationRootNode.java")
    into(layout.buildDirectory.dir("materializable-api/source"))
    inputs.files("tools/truffle-protocol/materializable-root.patch", "tools/truffle-protocol/declared-return-api.patch",
        "tools/truffle-protocol/graph-budget-api.patch")
    doFirst {
        requireApiArchive(materializableApiSource.singleFile,
            "19b6c77ad407ca062d4334040177847e322d52ed2f04804953d54dd366579064")
    }
    doLast {
        providers.exec {
            workingDir(destinationDir)
            environment("GIT_CEILING_DIRECTORIES", destinationDir.parentFile.absolutePath)
            commandLine("git", "apply", "--no-index", file("tools/truffle-protocol/materializable-root.patch").absolutePath)
        }.result.get().assertNormalExitValue()
        providers.exec {
            workingDir(destinationDir)
            environment("GIT_CEILING_DIRECTORIES", destinationDir.parentFile.absolutePath)
            commandLine("git", "apply", "--no-index", file("tools/truffle-protocol/declared-return-api.patch").absolutePath)
        }.result.get().assertNormalExitValue()
        providers.exec {
            workingDir(destinationDir)
            environment("GIT_CEILING_DIRECTORIES", destinationDir.parentFile.absolutePath)
            commandLine("git", "apply", "--no-index", file("tools/truffle-protocol/graph-budget-api.patch").absolutePath)
        }.result.get().assertNormalExitValue()
        check(destinationDir.resolve("com/oracle/truffle/api/nodes/RootNode.java").readText()
            .contains("materializableFramePolicyVersion")) { "Materializable-root API patch was not applied" }
    }
}
val compileMaterializableApi = tasks.register<JavaCompile>("compileMaterializableApi") {
    source(prepareMaterializableApi)
    include("**/*.java")
    classpath = materializableApiCompile
    destinationDirectory.set(layout.buildDirectory.dir("materializable-api/classes"))
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
}
val materializableApiJar = tasks.register<Jar>("materializableApiJar") {
    group = "build"
    description = "Build pinned API source classes with declared materialization and completion contracts."
    archiveBaseName.set("thc-truffle-api")
    archiveVersion.set("$materializableApiVersion-protocol3")
    destinationDirectory.set(layout.buildDirectory.dir("materializable-api"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(compileMaterializableApi.flatMap { it.destinationDirectory })
    from(provider { zipTree(materializableApiBinary.singleFile) }) {
        exclude("com/oracle/truffle/api/nodes/RootNode.class", "com/oracle/truffle/api/nodes/RootNode$*.class",
            "com/oracle/truffle/api/nodes/NodeAccessor.class", "com/oracle/truffle/api/nodes/NodeAccessor$*.class",
            "com/oracle/truffle/api/bytecode/ContinuationRootNode.class", "META-INF/MANIFEST.MF")
    }
    from("tools/truffle-protocol/LICENSE-UPL.txt") { into("META-INF") }
    inputs.files(materializableApiBinary)
    doFirst {
        requireApiArchive(materializableApiBinary.singleFile,
            "f9efa18b1cb6c4e39862f5ac83a88ee4c9e31252c52c2ee5192c2a707cd9b27f")
        JarFile(materializableApiBinary.singleFile).use { jar ->
            manifest.attributes(jar.manifest.mainAttributes.entries.associate { it.key.toString() to it.value.toString() })
        }
        manifest.attributes("Implementation-Title" to "THC declared root protocol Truffle API",
            "Implementation-Version" to "$materializableApiVersion-protocol3",
            "Upstream-Source-SHA256" to "19b6c77ad407ca062d4334040177847e322d52ed2f04804953d54dd366579064")
    }
}
// Only the build-input configurations retain stock API. Every executable/compiler
// configuration receives the distinct artifact; never mutate a shared cache jar.
configurations.configureEach {
    if (name !in setOf(materializableApiSource.name, materializableApiBinary.name, materializableApiCompile.name)) {
        exclude(group = "org.graalvm.truffle", module = "truffle-api")
    }
}
dependencies {
    add("implementation", files(materializableApiJar.flatMap { it.archiveFile }))
    // File artifacts do not retain the replaced API POM's required JNI module.
    add("implementation", "org.graalvm.sdk:jniutils:$materializableApiVersion")
}
tasks.register("verifyMaterializableApiSelection") {
    group = "verification"
    dependsOn(materializableApiJar, configurations.named("runtimeClasspath"))
    doLast {
        val runtime = configurations.getByName("runtimeClasspath").files
        check(runtime.none { it.name == "truffle-api-$materializableApiVersion.jar" }) { "Stock Truffle API remains on runtime classpath" }
        check(runtime.count { it.name == materializableApiJar.get().archiveFileName.get() } == 1) { "Expected exactly one declared API artifact" }
        // Classpath execution alone does not check the preserved JPMS descriptors.
        val runtimeJars = runtime.filter { it.extension == "jar" }
        fun resolveTruffleModules(jars: List<File>): Configuration = Configuration.empty().resolve(
            ModuleFinder.of(*jars.map { it.toPath() }.toTypedArray()), ModuleFinder.ofSystem(),
            setOf("org.graalvm.truffle", "org.graalvm.truffle.runtime"))
        check(resolveTruffleModules(runtimeJars).findModule("org.graalvm.jniutils").isPresent) {
            "Declared Truffle modules must resolve the required JNI utility module"
        }
        val withoutJni = runtimeJars.filterNot { it.name == "jniutils-$materializableApiVersion.jar" }
        check(withoutJni.size == runtimeJars.size - 1) { "Expected exactly one pinned JNI utility artifact" }
        val missingJni = runCatching { resolveTruffleModules(withoutJni) }.exceptionOrNull()
        check(missingJni is FindException && missingJni.message.orEmpty().contains("org.graalvm.jniutils")) {
            "Removing JNI utilities must reject the preserved Truffle module graph"
        }
        val overlay = materializableApiJar.get().archiveFile.get().asFile
        fun replaced(name: String) = name == "META-INF/MANIFEST.MF" ||
            name == "com/oracle/truffle/api/nodes/RootNode.class" ||
            name.startsWith("com/oracle/truffle/api/nodes/RootNode$") ||
            name == "com/oracle/truffle/api/nodes/NodeAccessor.class" ||
            name.startsWith("com/oracle/truffle/api/nodes/NodeAccessor$") ||
            name == "com/oracle/truffle/api/bytecode/ContinuationRootNode.class"
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        JarFile(materializableApiBinary.singleFile).use { stock ->
            JarFile(overlay).use { declared ->
                val stockEntries = stock.entries().asSequence().filter { !it.isDirectory }.toList()
                val declaredEntries = declared.entries().asSequence().filter { !it.isDirectory }.toList()
                val allowedAdded = setOf("META-INF/LICENSE-UPL.txt")
                check(declaredEntries.map { it.name }.toSet().minus(stockEntries.map { it.name }.toSet())
                    .all { replaced(it) || it in allowedAdded }) { "Unexpected added API payload" }
                for (entry in stockEntries.filterNot { replaced(it.name) }) {
                    val counterpart = checkNotNull(declared.getJarEntry(entry.name)) { "Missing upstream API entry: ${entry.name}" }
                    check(digest(stock.getInputStream(entry).readBytes()) == digest(declared.getInputStream(counterpart).readBytes())) {
                        "Unrelated upstream API entry changed: ${entry.name}"
                    }
                }
                check(declared.manifest.mainAttributes.getValue("Multi-Release") == stock.manifest.mainAttributes.getValue("Multi-Release")) {
                    "Upstream multi-release behavior changed"
                }
                val report = layout.buildDirectory.file("materializable-api/verified-artifact.sha256").get().asFile
                report.writeText(digest(overlay.readBytes()) + "  " + overlay.name + "\n" +
                    digest(file("tools/truffle-protocol/materializable-root.patch").readBytes()) + "  materializable-root.patch\n" +
                    digest(file("tools/truffle-protocol/declared-return-api.patch").readBytes()) + "  declared-return-api.patch\n" +
                    digest(file("tools/truffle-protocol/graph-budget-api.patch").readBytes()) + "  graph-budget-api.patch\n" +
                    "Preserved upstream nonreplacement entries: " + stockEntries.count { !replaced(it.name) } + "\n")
            }
        }
    }
}



// Same compiled control classes execute with the real stock and declared API.
// The stock lane deliberately avoids linking the additive capability method.
val materializableMain = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().named("main")
val materializableChecks = layout.buildDirectory.dir("materializable-api/checks")
val materializableRuntime = files(materializableMain.map { it.output }, materializableMain.map { it.runtimeClasspath })
val compileMaterializationControl = tasks.register<JavaCompile>("compileMaterializationControl") {
    source("tools/truffle-protocol/tests/MaterializationProbe.java")
    classpath = materializableRuntime
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
    destinationDirectory.set(materializableChecks.map { it.dir("classes") })
}
val materializationModes = listOf("stock", "overlay").map { mode ->
    tasks.register<JavaExec>("testMaterialization" + mode.replaceFirstChar { it.uppercaseChar() }) {
        group = "verification"
        description = "Check materializable-root publication, split/copy policy and actual OSR against $mode API."
        dependsOn("verifyMaterializableApiSelection")
        val selectedApi = if (mode == "stock") files(materializableApiBinary)
            else files(materializableApiJar.flatMap { it.archiveFile })
        val selectedRuntime = providers.provider {
            if (mode == "stock") configurations.getByName("protocolRuntimeBinary").singleFile
            else tasks.named<Jar>("protocolRuntimeJar").get().archiveFile.get().asFile
        }
        dependsOn("protocolRuntimeJar")
        classpath = files(compileMaterializationControl.flatMap { it.destinationDirectory }) + selectedApi +
            files(selectedRuntime) + materializableRuntime.filter {
                it.name != materializableApiJar.get().archiveFileName.get() &&
                    !it.name.startsWith("thc-truffle-runtime-")
            }
        mainClass.set("protocolprobe.MaterializationProbe")
        args(mode)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
        maxHeapSize = "1g"
    }
}
tasks.register("testMaterializableApi") {
    group = "verification"
    dependsOn(materializationModes)
}
tasks.named("check") { dependsOn("testMaterializableApi") }
