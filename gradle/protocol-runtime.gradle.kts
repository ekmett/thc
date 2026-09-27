// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
import java.security.MessageDigest
import java.util.jar.JarFile

val protocolRuntimeVersion = "25.3.4.1"
val protocolRuntimeSource = configurations.create("protocolRuntimeSource") { isCanBeConsumed = false; isTransitive = false }
val protocolRuntimeBinary = configurations.create("protocolRuntimeBinary") { isCanBeConsumed = false; isTransitive = false }
val protocolRuntimeCompile = configurations.create("protocolRuntimeCompile") { isCanBeConsumed = false }
dependencies {
    add(protocolRuntimeSource.name, "org.graalvm.truffle:truffle-runtime:$protocolRuntimeVersion:sources")
    add(protocolRuntimeBinary.name, "org.graalvm.truffle:truffle-runtime:$protocolRuntimeVersion")
    add(protocolRuntimeCompile.name, "org.graalvm.truffle:truffle-runtime:$protocolRuntimeVersion")
    // The API is replaced by a file artifact, so declare its options/Polyglot compile input explicitly.
    add(protocolRuntimeCompile.name, "org.graalvm.polyglot:polyglot:$protocolRuntimeVersion")
}
fun protocolRuntimeDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun requireRuntimeArchive(file: File, hash: String) {
    check(protocolRuntimeDigest(file.readBytes()) == hash) { "Pinned Truffle runtime archive hash mismatch: ${file.name}" }
}
val prepareProtocolRuntime = tasks.register<Sync>("prepareProtocolRuntime") {
    from(provider { zipTree(protocolRuntimeSource.singleFile) })
    include("com/oracle/truffle/runtime/OptimizedCallTarget.java")
    into(layout.buildDirectory.dir("protocol-runtime/source"))
    inputs.file("tools/truffle-protocol/declared-return-runtime.patch")
    doFirst {
        requireRuntimeArchive(protocolRuntimeSource.singleFile,
            "bc365db6f4765d57bc4779508dbe0fcdd2c379b4027eb954fd5b0c9e55c8a519")
    }
    doLast {
        providers.exec {
            workingDir(destinationDir)
            environment("GIT_CEILING_DIRECTORIES", destinationDir.parentFile.absolutePath)
            commandLine("git", "apply", "--no-index", file("tools/truffle-protocol/declared-return-runtime.patch").absolutePath)
        }.result.get().assertNormalExitValue()
        check(destinationDir.resolve("com/oracle/truffle/runtime/OptimizedCallTarget.java").readText()
            .contains("declaredReturnPolicyVersion")) { "Declared return runtime patch was not applied" }
    }
}
val compileProtocolRuntime = tasks.register<JavaCompile>("compileProtocolRuntime") {
    source(prepareProtocolRuntime)
    include("**/*.java")
    classpath = files(tasks.named<Jar>("materializableApiJar").flatMap { it.archiveFile }) + protocolRuntimeCompile
    destinationDirectory.set(layout.buildDirectory.dir("protocol-runtime/classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
    // --release hides JVMCI; these are the pinned runtime's existing JVMCI inputs.
    options.compilerArgs.addAll(listOf("-proc:none", "--add-modules=jdk.internal.vm.ci",
        "--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED"))
}
val protocolRuntimeJar = tasks.register<Jar>("protocolRuntimeJar") {
    group = "build"
    description = "Build the pinned runtime with an explicit per-root polymorphic completion declaration."
    archiveBaseName.set("thc-truffle-runtime")
    archiveVersion.set("$protocolRuntimeVersion-return1")
    destinationDirectory.set(layout.buildDirectory.dir("protocol-runtime"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(compileProtocolRuntime.flatMap { it.destinationDirectory })
    from(provider { zipTree(protocolRuntimeBinary.singleFile) }) {
        exclude("com/oracle/truffle/runtime/OptimizedCallTarget.class", "com/oracle/truffle/runtime/OptimizedCallTarget$*.class",
            "META-INF/MANIFEST.MF")
    }
    from("tools/truffle-protocol/LICENSE-UPL.txt") { into("META-INF") }
    inputs.files(protocolRuntimeBinary)
    doFirst {
        requireRuntimeArchive(protocolRuntimeBinary.singleFile,
            "d74ff0b8eb9a5fec4a571f78bc21750ee41dfd454877b8cce8b33641efd90ae3")
        JarFile(protocolRuntimeBinary.singleFile).use { jar ->
            manifest.attributes(jar.manifest.mainAttributes.entries.associate { it.key.toString() to it.value.toString() })
        }
        manifest.attributes("Implementation-Title" to "THC declared root completion Truffle runtime",
            "Implementation-Version" to "$protocolRuntimeVersion-return1",
            "Upstream-Source-SHA256" to "bc365db6f4765d57bc4779508dbe0fcdd2c379b4027eb954fd5b0c9e55c8a519")
    }
}
configurations.configureEach {
    if (name !in setOf(protocolRuntimeSource.name, protocolRuntimeBinary.name, protocolRuntimeCompile.name)) {
        exclude(group = "org.graalvm.truffle", module = "truffle-runtime")
    }
}
dependencies {
    add("implementation", files(protocolRuntimeJar.flatMap { it.archiveFile }))
    // Preserve the upstream runtime POM dependency after replacing its binary with a file artifact.
    add("implementation", "org.graalvm.truffle:truffle-compiler:$protocolRuntimeVersion")
}
tasks.register("verifyProtocolRuntimeSelection") {
    group = "verification"
    dependsOn(protocolRuntimeJar, "verifyMaterializableApiSelection")
    doLast {
        val runtime = configurations.getByName("runtimeClasspath").files
        check(runtime.none { it.name == "truffle-runtime-$protocolRuntimeVersion.jar" }) { "Stock runtime remains on runtime classpath" }
        val overlay = protocolRuntimeJar.get().archiveFile.get().asFile
        check(runtime.count { it.name == overlay.name } == 1) { "Expected exactly one declared runtime artifact" }
        fun replaced(name: String) = name == "META-INF/MANIFEST.MF" ||
            name == "com/oracle/truffle/runtime/OptimizedCallTarget.class" ||
            name.startsWith("com/oracle/truffle/runtime/OptimizedCallTarget$")
        JarFile(protocolRuntimeBinary.singleFile).use { stock ->
            JarFile(overlay).use { declared ->
                val original = stock.entries().asSequence().filter { !it.isDirectory }.toList()
                val current = declared.entries().asSequence().filter { !it.isDirectory }.toList()
                check(current.map { it.name }.toSet().minus(original.map { it.name }.toSet())
                    .all { replaced(it) || it == "META-INF/LICENSE-UPL.txt" }) { "Unexpected added runtime payload" }
                for (entry in original.filterNot { replaced(it.name) }) {
                    val target = checkNotNull(declared.getJarEntry(entry.name)) { "Missing runtime entry: ${entry.name}" }
                    check(protocolRuntimeDigest(stock.getInputStream(entry).readBytes()) ==
                        protocolRuntimeDigest(declared.getInputStream(target).readBytes())) { "Unrelated runtime entry changed: ${entry.name}" }
                }
                check(declared.manifest.mainAttributes.getValue("Multi-Release") == stock.manifest.mainAttributes.getValue("Multi-Release"))
                layout.buildDirectory.file("protocol-runtime/verified-artifact.sha256").get().asFile.writeText(
                    protocolRuntimeDigest(overlay.readBytes()) + "  " + overlay.name + "\n" +
                    protocolRuntimeDigest(file("tools/truffle-protocol/declared-return-runtime.patch").readBytes()) + "  declared-return-runtime.patch\n" +
                    "Preserved upstream nonreplacement entries: " + original.count { !replaced(it.name) } + "\n")
            }
        }
    }
}
tasks.named("check") { dependsOn("verifyProtocolRuntimeSelection") }

// One compiled control runs against matching stock and declared API/runtime binaries.
val returnPolicyMain = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().named("main")
val returnPolicyRuntime = files(returnPolicyMain.map { it.output }, returnPolicyMain.map { it.runtimeClasspath })
val compileReturnPolicyControl = tasks.register<JavaCompile>("compileReturnPolicyControl") {
    source("tools/truffle-protocol/tests/ReturnPolicyProbe.java")
    classpath = returnPolicyRuntime
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
    destinationDirectory.set(layout.buildDirectory.dir("protocol-runtime/checks/classes"))
}
val returnPolicyModes = listOf("stock", "overlay").map { mode ->
    tasks.register<JavaExec>("testReturnPolicy" + mode.replaceFirstChar { it.uppercaseChar() }) {
        group = "verification"
        description = "Check explicit completion policy, unchanged arguments, AOT and public split against $mode binaries."
        dependsOn("verifyProtocolRuntimeSelection")
        val selectedApi = if (mode == "stock") files(configurations.getByName("materializableApiBinary"))
            else files(tasks.named<Jar>("materializableApiJar").flatMap { it.archiveFile })
        val selectedRuntime = if (mode == "stock") files(protocolRuntimeBinary)
            else files(protocolRuntimeJar.flatMap { it.archiveFile })
        classpath = files(compileReturnPolicyControl.flatMap { it.destinationDirectory }) + selectedApi + selectedRuntime +
            returnPolicyRuntime.filter { !it.name.startsWith("thc-truffle-api-") && !it.name.startsWith("thc-truffle-runtime-") }
        mainClass.set("protocolprobe.ReturnPolicyProbe")
        args(mode)
        enableAssertions = true
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
        maxHeapSize = "1g"
    }
}
tasks.register("testReturnPolicy") { group = "verification"; dependsOn(returnPolicyModes) }
tasks.named("check") { dependsOn("testReturnPolicy") }

val compileReturnContinuationControl = tasks.register<JavaCompile>("compileReturnContinuationControl") {
    source("tools/truffle-protocol/tests/ReturnContinuationRoot.java", "tools/truffle-protocol/tests/ReturnContinuationProbe.java")
    classpath = files(compileReturnPolicyControl.flatMap { it.destinationDirectory }) + returnPolicyRuntime
    options.annotationProcessorPath = files(tasks.named<Jar>("protocolProcessorJar").flatMap { it.archiveFile }) + returnPolicyRuntime
    options.release.set(21)
    options.compilerArgs.add("-proc:full")
    destinationDirectory.set(layout.buildDirectory.dir("protocol-runtime/checks/continuations"))
    options.generatedSourceOutputDirectory.set(layout.buildDirectory.dir("protocol-runtime/checks/generated"))
}
tasks.register<JavaExec>("testReturnContinuations") {
    group = "verification"
    description = "Check actual generated first and nested yields, source splits and serialized return declarations."
    dependsOn("verifyProtocolRuntimeSelection")
    classpath = files(compileReturnContinuationControl.flatMap { it.destinationDirectory },
        compileReturnPolicyControl.flatMap { it.destinationDirectory }) + returnPolicyRuntime
    mainClass.set("protocolprobe.ReturnContinuationProbe")
    enableAssertions = true
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
    maxHeapSize = "1g"
}
tasks.named("check") { dependsOn("testReturnContinuations") }

val compileRuntimeLinkageControl = tasks.register<JavaCompile>("compileRuntimeLinkageControl") {
    source("tools/truffle-protocol/tests/RuntimeLinkageProbe.java")
    classpath = files()
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
    destinationDirectory.set(layout.buildDirectory.dir("protocol-runtime/checks/linkage"))
}
for (mode in listOf("stock", "overlay")) {
    val task = tasks.register<JavaExec>("testRuntimeLinkage" + mode.replaceFirstChar { it.uppercaseChar() }) {
        dependsOn("verifyProtocolRuntimeSelection")
        val api = if (mode == "stock") files(configurations.getByName("materializableApiBinary"))
            else files(tasks.named<Jar>("materializableApiJar").flatMap { it.archiveFile })
        val runtime = if (mode == "stock") files(protocolRuntimeBinary)
            else files(protocolRuntimeJar.flatMap { it.archiveFile })
        classpath = files(compileRuntimeLinkageControl.flatMap { it.destinationDirectory }) + api + runtime +
            returnPolicyRuntime.filter { !it.name.startsWith("thc-truffle-api-") && !it.name.startsWith("thc-truffle-runtime-") }
        mainClass.set("protocolprobe.RuntimeLinkageProbe")
        args(mode)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
        maxHeapSize = "512m"
    }
    tasks.named("testReturnPolicy") { dependsOn(task) }
}
