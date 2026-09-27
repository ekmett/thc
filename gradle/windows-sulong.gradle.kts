// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
import java.security.MessageDigest
import java.util.jar.JarFile

// Applied only on Windows. Rebuild the pinned upstream Java ABI class rather
// than shadowing it on the classpath or changing any guest filesystem policy.
val windowsSulongVersion = "25.3.4.1"
val windowsSulongSources = configurations.create("windowsSulongSources") { isCanBeConsumed = false; isTransitive = false }
val windowsSulongBinary = configurations.create("windowsSulongBinary") { isCanBeConsumed = false; isTransitive = false }
val windowsSulongCompile = configurations.create("windowsSulongCompile") { isCanBeConsumed = false }
dependencies {
    add(windowsSulongSources.name, "org.graalvm.llvm:llvm-language:$windowsSulongVersion:sources")
    add(windowsSulongBinary.name, "org.graalvm.llvm:llvm-language:$windowsSulongVersion")
    add(windowsSulongCompile.name, "org.graalvm.llvm:llvm-language:$windowsSulongVersion")
}
fun windowsSulongDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun requireWindowsSulongArchive(file: File, hash: String) {
    check(windowsSulongDigest(file.readBytes()) == hash) { "Pinned Sulong archive hash mismatch: ${file.name}" }
}
val prepareWindowsSulong = tasks.register<Sync>("prepareWindowsSulong") {
    from(provider { zipTree(windowsSulongSources.singleFile) })
    include("com/oracle/truffle/llvm/parser/coff/WindowsLibraryLocator.java")
    into(layout.buildDirectory.dir("windows-sulong/source"))
    inputs.file("tools/sulong-windows/optional-cwd.patch")
    doFirst {
        requireWindowsSulongArchive(windowsSulongSources.singleFile,
            "716562a9c6cbe9201f53bc972d9eafbb1a692a9d9aebb9f177a541c5e0913f66")
    }
    doLast {
        providers.exec {
            workingDir(destinationDir)
            environment("GIT_CEILING_DIRECTORIES", destinationDir.parentFile.absolutePath)
            commandLine("git", "apply", "--no-index", file("tools/sulong-windows/optional-cwd.patch").absolutePath)
        }.result.get().assertNormalExitValue()
    }
}
val compileWindowsSulong = tasks.register<JavaCompile>("compileWindowsSulong") {
    source(prepareWindowsSulong)
    include("**/*.java")
    classpath = files(tasks.named<Jar>("materializableApiJar").flatMap { it.archiveFile }) + windowsSulongCompile
    destinationDirectory.set(layout.buildDirectory.dir("windows-sulong/classes"))
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
}
val windowsSulongJar = tasks.register<Jar>("windowsSulongJar") {
    group = "build"
    description = "Build pinned Windows Sulong with an optional guest cwd library search."
    archiveBaseName.set("thc-llvm-language")
    archiveVersion.set("$windowsSulongVersion-windows1")
    destinationDirectory.set(layout.buildDirectory.dir("windows-sulong"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(compileWindowsSulong.flatMap { it.destinationDirectory })
    from(provider { zipTree(windowsSulongBinary.singleFile) }) {
        exclude("com/oracle/truffle/llvm/parser/coff/WindowsLibraryLocator.class", "META-INF/MANIFEST.MF")
    }
    from("tools/sulong-windows/LICENSE-BSD.txt") { into("META-INF/licenses/sulong-windows") }
    inputs.files(windowsSulongBinary)
    doFirst {
        requireWindowsSulongArchive(windowsSulongBinary.singleFile,
            "c835fc80abdc818b7487c9e0b9ecd6c7cd59a7bbb7ac7041d6e2cda2be4b9335")
        JarFile(windowsSulongBinary.singleFile).use { jar ->
            manifest.attributes(jar.manifest.mainAttributes.entries.associate { it.key.toString() to it.value.toString() })
        }
        manifest.attributes("Implementation-Title" to "THC Windows Sulong optional cwd lookup",
            "Implementation-Version" to "$windowsSulongVersion-windows1",
            "Upstream-Source-SHA256" to "716562a9c6cbe9201f53bc972d9eafbb1a692a9d9aebb9f177a541c5e0913f66")
    }
}
configurations.configureEach {
    if (name !in setOf(windowsSulongSources.name, windowsSulongBinary.name, windowsSulongCompile.name)) {
        exclude(group = "org.graalvm.llvm", module = "llvm-language")
    }
}
dependencies {
    add("runtimeOnly", files(windowsSulongJar.flatMap { it.archiveFile }))
    // Preserve the replaced binary's POM dependencies; truffle-api is already
    // supplied by the project's pinned materializable-API artifact.
    add("runtimeOnly", "org.graalvm.truffle:truffle-nfi:$windowsSulongVersion")
    add("runtimeOnly", "org.graalvm.shadowed:antlr4:$windowsSulongVersion")
    add("runtimeOnly", "org.graalvm.llvm:llvm-api:$windowsSulongVersion")
}
val verifyWindowsSulongSelection = tasks.register("verifyWindowsSulongSelection") {
    group = "verification"
    dependsOn(windowsSulongJar)
    doLast {
        val runtime = configurations.getByName("runtimeClasspath").files
        val patched = windowsSulongJar.get().archiveFile.get().asFile
        check(runtime.none { it.name == "llvm-language-$windowsSulongVersion.jar" }) { "Stock Sulong remains on Windows runtime classpath" }
        check(runtime.count { it == patched } == 1) { "Expected exactly one Windows Sulong artifact" }
        val changed = setOf("META-INF/MANIFEST.MF", "com/oracle/truffle/llvm/parser/coff/WindowsLibraryLocator.class")
        val license = "META-INF/licenses/sulong-windows/LICENSE-BSD.txt"
        JarFile(windowsSulongBinary.singleFile).use { stock ->
            JarFile(patched).use { fixed ->
                val original = stock.entries().asSequence().filter { !it.isDirectory }.toList()
                val current = fixed.entries().asSequence().filter { !it.isDirectory }.toList()
                check(current.map { it.name }.toSet() == original.map { it.name }.toSet() + license) { "Unexpected Windows Sulong payload" }
                for (entry in original.filterNot { it.name in changed }) {
                    check(stock.getInputStream(entry).use { it.readBytes() }.contentEquals(
                        fixed.getInputStream(fixed.getJarEntry(entry.name)).use { it.readBytes() })) {
                        "Unexpected Windows Sulong entry change: ${entry.name}"
                    }
                }
            }
        }
    }
}
tasks.withType<Test>().configureEach { dependsOn(verifyWindowsSulongSelection) }
tasks.named("startScripts") { dependsOn(verifyWindowsSulongSelection) }
