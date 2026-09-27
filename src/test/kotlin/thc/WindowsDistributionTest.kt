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
import thc.runtime.CoreRepresentations
import thc.runtime.UnsupportedCore
import thc.runtime.RuntimeFault
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

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

    @Suppress("UNCHECKED_CAST")
    @Test fun genuineHaskellHostEntryPreservesTheThunkAndStrictIoSignature() {
        val receipt = verifiedReceipt("build/windows-driver/provenance.json")
        for (manifest in receipt["supportManifests"] as List<String>) {
            val output = File(root, manifest).parentFile.parentFile
            fun document(path: String) = Json.parse(File(output, path).readText()) as Map<String, Any?>
            val original = document("core/Main.json")["bindings"] as List<Map<String, Any?>>
            val host = document("core/THC.WindowsRunMain.json")["bindings"] as List<Map<String, Any?>>
            val main = original.single { it["id"] == "main:Main.main" }
            assertEquals(0L, main["arity"], "The actual no-interface-pragmas main remains a thunk")
            assertEquals("IO ()", main["type"])
            val bindings = original + host
            assertThrows(UnsupportedCore::class.java) { CoreRepresentations.ioUnitMainResult(main, bindings) }
            val entry = host.single { it["id"] == "main:THC.WindowsRunMain.thcRunMain" }
            val result = CoreRepresentations.ioUnitMainResult(entry, bindings)
            assertEquals(2, result.components!!.size)
            assertThrows(UnsupportedCore::class.java) {
                CoreRepresentations.ioUnitMainResult(entry + ("type" to "IO Int"), bindings)
            }
            val audit = document("audit.json")
            assertEquals(true, audit["accepted"])
            assertEquals(listOf(entry["id"]), audit["roots"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun nativeSourceArchiveAndRuntimeProjectionKeepExactModuleBytes() {
        val receipt = verifiedReceipt("build/windows-driver/provenance.json")
        val manifests = receipt["supportManifests"] as List<String>
        assertEquals(4, manifests.size)
        val bundles = manifests.map { path ->
            val manifest = Json.parse(File(root, path).readText()) as Map<String, Any?>
            val units = manifest["units"] as List<Map<String, Any?>>
            val unit = units.single { it["id"] == "ghc-internal" }
            val bundle = unit["bundle"] as Map<String, String>
            unit to bundle
        }
        assertEquals(1, bundles.map { it.second }.toSet().size, "All CLI modes must reuse the same exact support bundle")
        val (unit, bundle) = bundles.first()
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun digest(path: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hex(hash.digest())
        }
        assertEquals(bundle["sha256"], digest(bundle.getValue("path")))
        ZipFile(bundle.getValue("path")).use { projected ->
            fun document(zip: ZipFile, path: String) = Json.parse(zip.getInputStream(zip.getEntry(path)).reader(Charsets.UTF_8).use { it.readText() }) as Map<String, Any?>
            val inputs = document(projected, "inplace-manifest.json")
            val complete = inputs["completeSourceBundle"] as Map<String, String>
            val excluded = inputs["archiveOnlyModules"] as List<Map<String, String>>
            assertEquals(listOf("GHC.Internal.Conc.Bound"), excluded.map { it["module"] })
            assertEquals(complete["sha256"], digest(complete.getValue("path")))
            ZipFile(complete.getValue("path")).use { full ->
                val fullManifest = document(full, "manifest.json")
                val fullInputs = document(full, "inplace-manifest.json")
                assertEquals(fullInputs["generatedSources"], inputs["generatedSources"])
                val steps = (fullInputs["sourceBuild"] as Map<*, *>)["steps"] as List<Map<String, Any?>>
                assertEquals(235, steps.size)
                assertEquals(24, steps.count { it["boot"] == true })
                steps.forEach {
                    assertEquals(0L, it["exit"])
                    val arguments = it["arguments"] as List<String>
                    assertTrue(arguments.containsAll(listOf("-O2", "-dcore-lint", "-fwrite-if-simplified-core", "-DBIGNUM_GMP")))
                    assertFalse(arguments.contains("-fignore-interface-pragmas"))
                }
                val fullModules = fullManifest["modules"] as List<Map<String, Any?>>
                val selected = unit["modules"] as List<Map<String, Any?>>
                assertEquals(211, fullModules.size)
                assertEquals(210, selected.size)
                for (module in selected) {
                    assertEquals(module, fullModules.single { it["name"] == module["name"] })
                    val member = module["path"] as String
                    // Source-rich genuine modules can each exceed the test heap.
                    // Compare every byte and the checked digest with bounded buffers.
                    val hash = MessageDigest.getInstance("SHA-256")
                    full.getInputStream(full.getEntry(member)).use { expected ->
                        projected.getInputStream(projected.getEntry(member)).use { actual ->
                            val left = ByteArray(64 * 1024)
                            val right = ByteArray(left.size)
                            while (true) {
                                val count = expected.readNBytes(left, 0, left.size)
                                assertEquals(count, actual.readNBytes(right, 0, right.size), member)
                                if (count == 0) break
                                assertEquals(-1, java.util.Arrays.mismatch(left, 0, count, right, 0, count), member)
                                hash.update(right, 0, count)
                            }
                        }
                    }
                    assertEquals(module["sha256"], hex(hash.digest()))
                }
                val archived = fullModules.single { it["name"] == "GHC.Internal.Conc.Bound" }
                assertNull(projected.getEntry(archived["path"] as String))
                val module = document(full, archived["path"] as String)
                assertTrue(assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(module)) }
                    .message!!.contains("Unsupported foreign"))
            }
        }
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
