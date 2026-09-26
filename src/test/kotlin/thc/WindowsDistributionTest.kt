// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.runtime.ManagedAddress
import thc.runtime.ManagedMd5
import thc.runtime.RuntimeFault
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native Windows packaging and authority regressions; never a full-platform parity gate. */
@EnabledOnOs(OS.WINDOWS)
class WindowsDistributionTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    @TempDir lateinit var temporary: Path

    @Suppress("UNCHECKED_CAST")
    private fun verifiedReceipt(path: String): Map<String, Any?> {
        val receipt = Json.parse(File(root, path).readText()) as Map<String, Any?>
        assertEquals("9.14.1", receipt["ghc"])
        assertEquals("mingw32", receipt["system"])
        for (field in listOf("inputHashes", "artifactHashes")) {
            val hashes = receipt[field] as Map<String, String>
            assertTrue(hashes.isNotEmpty(), field)
            for ((file, hash) in hashes) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, file).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(hash, actual, file)
            }
        }
        val commands = receipt["commands"] as List<Map<String, Any?>>
        for (command in commands) assertEquals(command["expectedExit"], command["exit"], command.toString())
        return receipt
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun nativeSmokeInputsAndArtifactsHaveCurrentProvenance() {
        val receipt = verifiedReceipt("build/windows-smoke/provenance.json")
        assertEquals(133L, (receipt["nativeRows"] as Number).toLong())
        assertEquals(19, (receipt["entries"] as List<*>).size)
        val commands = receipt["commands"] as List<Map<String, Any?>>
        assertTrue(commands.any { (it["expectedExit"] as Number).toLong() == 1L })
        assertNotNull(javaClass.getResource("/thc/cbits/md5.dll"))
        for (path in listOf("/thc/cbits/iconv.bc", "/thc/cbits/strerror.bc", "/thc/cbits/strerror-locale.bc",
            "/thc/native/stdio-host-abi.json", "/thc/native/posix-stat-abi.json",
            "/thc/native/termios-abi.json", "/thc/native/sigset-abi.json"))
            assertNull(javaClass.getResource(path), path)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun publicDriverMatchesNativeCompletionInEveryBackendAndHandoffMode() {
        val receipt = verifiedReceipt("build/windows-driver/provenance.json")
        assertEquals(4L, (receipt["runs"] as Number).toLong())
        val commands = receipt["commands"] as List<Map<String, Any?>>
        assertEquals(6, commands.size)
        assertEquals(setOf(
            "ast" to "-Dthc.diagnostics=true -Dthc.handoffSlabs=false",
            "ast" to "-Dthc.diagnostics=true -Dthc.handoffSlabs=true",
            "bytecode" to "-Dthc.diagnostics=true -Dthc.handoffSlabs=false",
            "bytecode" to "-Dthc.diagnostics=true -Dthc.handoffSlabs=true"),
            commands.drop(2).map {
                val environment = it["environment"] as Map<String, String>
                environment["THC_BACKEND"] to environment["JAVA_OPTS"]
            }.toSet())
    }
    @Test fun relocatedBatchLauncherAcceptsPathsWithSpacesOnBothBackends() {
        val destination = temporary.resolve("THC distribution with spaces").toFile()
        assertTrue(File(root, "build/install/thc").copyRecursively(destination))
        val core = temporary.resolve("Core inputs with spaces").toFile().also { it.mkdirs() }
        val modules = listOf("THC.Prim", "THC.Fixtures").map {
            File(root, "build/core/$it.json").copyTo(File(core, "$it.json")).absolutePath
        }.joinToString(",")
        val evidence = File(root, "build/windows-launcher/" + UUID.randomUUID()).also { it.mkdirs() }
        val oracle = File(root, "build/native/oracle.tsv").readLines().map { it.split('\t') }
        for (backend in listOf("ast", "bytecode")) {
            val output = File(evidence, "$backend.stdout")
            val errors = File(evidence, "$backend.stderr")
            val command = listOf("cmd.exe", "/d", "/c", "call", File(destination, "bin/thc.bat").absolutePath,
                modules, "sumLoop", "10")
            val builder = ProcessBuilder(command).directory(temporary.toFile())
                .redirectOutput(output).redirectError(errors)
            builder.environment()["THC_BACKEND"] = backend
            builder.environment()["JAVA_OPTS"] = "-Dthc.handoffSlabs=" + System.getProperty("thc.handoffSlabs", "false")
            File(evidence, "$backend.command.json").writeText(Json.stringify(mapOf(
                "argv" to command, "backend" to backend, "handoffSlabs" to System.getProperty("thc.handoffSlabs"))))
            val child = builder.start()
            try {
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Launcher timed out: $evidence")
                File(evidence, "$backend.exit").writeText(child.exitValue().toString())
                assertEquals(0, child.exitValue(), errors.readText())
                assertEquals(oracle.single { it[0] == "sumLoop" && it[1] == "10" }[2], output.readText().trim())
                val diagnostics = errors.readLines().last { it.startsWith("{") }
                assertEquals(backend, (Json.parse(diagnostics) as Map<*, *>)["backend"])
            } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
        }
    }

    @Test fun nativeMd5NeedsNativeAuthorityButNoGuestFilesystemAuthority() {
        for (native in listOf(false, true, false, true)) {
            Context.newBuilder("thc").allowNativeAccess(native).allowIO(IOAccess.NONE).build().use { context ->
                context.initialize("thc")
                context.enter()
                try {
                    val bytes = ByteArray(88) { 0xa5.toByte() }
                    val address = ManagedAddress.fromByteArray(bytes)
                    if (!native) {
                        assertThrows(RuntimeFault::class.java) { ManagedMd5.init(address) }
                        assertArrayEquals(ByteArray(88) { 0xa5.toByte() }, bytes)
                    } else {
                        ManagedMd5.init(address)
                        val output = ByteArray(16)
                        ManagedMd5.update(address, ManagedAddress.fromHex("616263"), 3)
                        ManagedMd5.finish(ManagedAddress.fromByteArray(output), address)
                        assertArrayEquals(MessageDigest.getInstance("MD5").digest("abc".toByteArray()), output)
                        assertArrayEquals(ByteArray(88), bytes)
                    }
                } finally { context.leave() }
            }
        }
    }
}