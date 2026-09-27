// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.security.MessageDigest

// This distinct build artifact adds only an explicitly unprofiled protocol
// conditional. All API/runtime dependencies retain their upstream coordinates.
val protocolProcessorVersion = "25.3.4.1"
val protocolProcessorSources = configurations.create("protocolProcessorSources") {
    isCanBeConsumed = false
    isTransitive = false
}
val protocolProcessorUpstream = configurations.create("protocolProcessorUpstream") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(protocolProcessorSources.name, "org.graalvm.truffle:truffle-dsl-processor:$protocolProcessorVersion:sources")
    add(protocolProcessorUpstream.name, "org.graalvm.truffle:truffle-dsl-processor:$protocolProcessorVersion")
}
fun checkArchive(file: File, expected: String) {
    val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    check(actual == expected) { "Pinned processor archive hash mismatch: ${file.name}" }
}
val prepareProtocolProcessor = tasks.register<Sync>("prepareProtocolProcessor") {
    from(provider { zipTree(protocolProcessorSources.singleFile) })
    into(layout.buildDirectory.dir("protocol-processor/source"))
    inputs.file("tools/truffle-protocol/unprofiled-if-then.patch")
    doFirst {
        checkArchive(protocolProcessorSources.singleFile,
            "5098f0923e52f26ef4ce49060663982a2f7028d87b17317088860fb88b0d5b2a")
    }
    doLast {
        providers.exec {
            workingDir(destinationDir)
            // This extracted source is not the enclosing THC Git worktree.
            // Prevent Git from treating its paths as a repository subdirectory.
            environment("GIT_CEILING_DIRECTORIES", destinationDir.parentFile.absolutePath)
            commandLine("git", "apply", "--no-index", file("tools/truffle-protocol/unprofiled-if-then.patch").absolutePath)
        }.result.get().assertNormalExitValue()
        check(destinationDir.resolve("com/oracle/truffle/dsl/processor/bytecode/model/BytecodeDSLBuiltins.java")
            .readText().contains("m.unprofiledIfThenOperation")) { "Processor patch was not applied" }
    }
}
val compileProtocolProcessor = tasks.register<JavaCompile>("compileProtocolProcessor") {
    source(prepareProtocolProcessor)
    include("**/*.java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("protocol-processor/classes"))
    options.release.set(21)
    options.compilerArgs.add("-proc:none")
}
tasks.register<Jar>("protocolProcessorJar") {
    group = "build"
    description = "Build the pinned source processor with the explicit protocol-branch extension."
    archiveBaseName.set("thc-truffle-dsl-processor")
    archiveVersion.set("$protocolProcessorVersion-protocol1")
    destinationDirectory.set(layout.buildDirectory.dir("protocol-processor"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(compileProtocolProcessor.flatMap { it.destinationDirectory })
    // The source artifact omits these two non-class resources. Retain their
    // exact upstream bytes; every Java class is rebuilt from the source jar.
    from(provider { zipTree(protocolProcessorUpstream.singleFile) }) {
        include("META-INF/services/javax.annotation.processing.Processor",
            "com/oracle/truffle/dsl/processor/expression/Expression.g4")
    }
    from(prepareProtocolProcessor) { include("META-INF/maven/**") }
    from("tools/truffle-protocol") { include("LICENSE-*.txt"); into("META-INF") }
    inputs.files(protocolProcessorUpstream)
    doFirst {
        checkArchive(protocolProcessorUpstream.singleFile,
            "3c6e191eea09fd2ffa6c35c22abc5722c9950409de3f2d3c99e66eeeffdf669c")
    }
    manifest.attributes("Implementation-Title" to "THC explicit protocol-branch Truffle DSL processor",
        "Implementation-Version" to "$protocolProcessorVersion-protocol1",
        "Upstream-Source-SHA256" to "5098f0923e52f26ef4ce49060663982a2f7028d87b17317088860fb88b0d5b2a")
}
