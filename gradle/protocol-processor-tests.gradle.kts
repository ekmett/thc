// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
import org.gradle.api.tasks.SourceSetContainer

// Separate javac invocations prove that stock serialized events retain their IDs.
val protocolMain = extensions.getByType<SourceSetContainer>().named("main")
val protocolChecks = layout.buildDirectory.dir("protocol-processor/checks")
val protocolRuntime = files(protocolMain.map { it.output }, protocolMain.map { it.runtimeClasspath })
val compileLegacyProtocol = tasks.register<JavaCompile>("compileLegacyProtocolControl") {
    source("tools/truffle-protocol/tests/BranchRoot.java", "tools/truffle-protocol/tests/LegacyProducer.java")
    classpath = protocolRuntime
    options.annotationProcessorPath = configurations.getByName("protocolProcessorUpstream") + protocolRuntime
    options.release.set(21)
    options.compilerArgs.add("-proc:full")
    destinationDirectory.set(protocolChecks.map { it.dir("legacy") })
    options.generatedSourceOutputDirectory.set(protocolChecks.map { it.dir("legacy-generated") })
}
val serializeLegacyProtocol = tasks.register<JavaExec>("serializeLegacyProtocolControl") {
    classpath = files(compileLegacyProtocol.flatMap { it.destinationDirectory }) + protocolRuntime
    mainClass.set("protocolprobe.LegacyProducer")
    val output = protocolChecks.map { it.file("stock-serialized.bin") }
    outputs.file(output)
    doFirst { args = listOf(output.get().asFile.absolutePath) }
}
val compileProtocolControl = tasks.register<JavaCompile>("compileProtocolControl") {
    source("tools/truffle-protocol/tests/BranchRoot.java", "tools/truffle-protocol/tests/BranchProbe.java")
    classpath = protocolRuntime
    options.annotationProcessorPath = files(tasks.named<Jar>("protocolProcessorJar").flatMap { it.archiveFile }) + protocolRuntime
    options.release.set(21)
    options.compilerArgs.add("-proc:full")
    destinationDirectory.set(protocolChecks.map { it.dir("extended") })
    options.generatedSourceOutputDirectory.set(protocolChecks.map { it.dir("extended-generated") })
}
val testProtocolProcessor = tasks.register<JavaExec>("testProtocolProcessor") {
    group = "verification"
    description = "Check explicit protocol branches against legacy serialization and ordinary branch profiling."
    dependsOn(serializeLegacyProtocol)
    classpath = files(compileProtocolControl.flatMap { it.destinationDirectory }) + protocolRuntime
    mainClass.set("protocolprobe.BranchProbe")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
    maxHeapSize = "1g"
    doFirst { args = listOf(protocolChecks.get().file("stock-serialized.bin").asFile.absolutePath) }
}
tasks.named("check") { dependsOn(testProtocolProcessor) }
