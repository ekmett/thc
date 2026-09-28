// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.ContextProfile
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Original Unix declarations and independent native child observations. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalCurrentDirectoryTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-current-directory"
    private val operations = linkedMapOf("pathChdir" to OriginalStdioOp.CHDIR, "pathGetCwd" to OriginalStdioOp.GETCWD)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + byteArrayOf(0))
    private fun address(value: String) = address(value.toByteArray())
    private fun pathAddress(path: Path) = ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path))
    private fun bytes(row: Map<String, Any?>, key: String) = (row[key] as List<Long>).map { it.toByte() }.toByteArray()
    private fun rawPath(base: Path, value: ByteArray): Path =
        NativeDirectoryOwner.bytesPath(NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray() + byteArrayOf(47) + value)
    private fun context() = NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST)
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun original(name: String, stage: String = "post") = OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(stage), name)).single()
    private fun raw(call: List<Any?>) = OriginalStdioChecks.rawModule(call, source("post"))
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun setup(): Path = Files.createTempDirectory(directory, "case-").also { base ->
        Files.createDirectories(base.resolve("parent/child")); Files.createDirectory(base.resolve("sub"))
        Files.writeString(base.resolve("regular"), "unchanged")
        Files.createDirectory(rawPath(base, byteArrayOf(-1, 110)))
        Files.createSymbolicLink(base.resolve("link"), Path.of("sub"))
        Files.createDirectory(base.resolve("search-denied"))
        Files.setPosixFilePermissions(base.resolve("search-denied"), emptySet())
    }
    private fun name(stdio: ManagedStdio): ByteArray {
        val storage = ByteArray(65536)
        val output = ManagedAddress.fromByteArray(storage)
        assertSame(output, stdio.currentDirectory(output, storage.size.toLong()))
        return storage.takeWhile { it != 0.toByte() }.toByteArray()
    }

    @Test fun originalUnixCallsMatchNativeAndTheFirstInstalledEntry() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest["unixUnit"]))
        assertEquals(true, manifest["nativeIsolatedChild"])
        assertEquals(true, manifest["coordinatorCwdUnchanged"])
        assertEquals(true, manifest["privateRebuiltUnix"])
        assertEquals("a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e", manifest["unixArchiveSha256"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalCurrentDirectoryAudit.hs",
            "test/haskell-fixtures/OriginalCurrentDirectoryFixtures.hs", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") + listOf("pre", "post").flatMap { stage ->
            listOf("$prefix/$stage.json") + operations.keys.map { "$prefix/$stage-$it.audit.json" }
        }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val chdirRows = oracle["chdirRows"] as List<Map<String, Any?>>
        val getcwdRows = oracle["getcwdRows"] as List<Map<String, Any?>>
        assertEquals(11, chdirRows.size); assertEquals(12, getcwdRows.size)
        assertEquals(11, chdirRows.map { it["name"] }.toSet().size); assertEquals(12, getcwdRows.map { it["name"] }.toSet().size)
        val processDirectory = Files.readSymbolicLink(Path.of("/proc/self/cwd"))
        for (stage in listOf("pre", "post")) for ((entryName, operation) in operations) {
            val audit = json("$prefix/$stage-$entryName.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(source(stage), entryName) + ("instrument" to true)
            val evidence = ArrayCoreEvidence(linked, entryName)
            assertEquals(1, evidence.bindings.size)
            assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
            assertEquals(operation, validate(original(entryName, stage)))
            assertEquals(manifest["unixUnit"], (((original(entryName, stage)[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget(entryName)
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    for (row in if (operation == OriginalStdioOp.CHDIR) chdirRows else getcwdRows) {
                        val base = setup()
                        assertEquals(0L, stdio.changeDirectory(pathAddress(base)))
                        val baseBytes = NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray()
                        var longDepth = 0
                        try {
                            fun enter(value: String) = assertEquals(0L, stdio.changeDirectory(address(value)))
                            if (operation == OriginalStdioOp.GETCWD) when (row["setup"]) {
                                "base" -> Unit
                                "sub" -> enter("sub")
                                "raw" -> assertEquals(0L, stdio.changeDirectory(address(byteArrayOf(-1, 110))))
                                "physical-link" -> enter("link")
                                "renamed" -> { enter("sub"); Files.move(base.resolve("sub"), base.resolve("renamed")) }
                                "renamed-ancestor" -> { enter("parent/child"); Files.move(base.resolve("parent"), base.resolve("moved")) }
                                "deleted" -> { enter("sub"); Files.delete(base.resolve("sub")) }
                                "long" -> repeat((oracle["longDepth"] as Long).toInt()) {
                                    val component = address(bytes(oracle, "longComponent"))
                                    assertEquals(0L, stdio.pathMode(OriginalStdioOp.MKDIR, component, 448))
                                    assertEquals(0L, stdio.changeDirectory(component)); longDepth++
                                }
                                else -> fail<Unit>("Unknown native CWD setup")
                            }
                            val expectedName = (row["cwdSuffix"] as? List<Long>)?.let { baseBytes + it.map(Long::toByte).toByteArray() }
                                ?: if (row["setup"] == "base") baseBytes else null
                            val capacity = when (row["capacityKind"]) {
                                "exact" -> expectedName!!.size + 1
                                "short" -> expectedName!!.size
                                "one" -> 1
                                "zero" -> 0
                                else -> (row["capacity"] as? Long ?: 16384L).toInt()
                            }
                            val offset = (row["offset"] as? Long ?: 8L).toInt()
                            val storage = ByteArray(offset + capacity + 8) { 90 }
                            val output = ManagedAddress.fromByteArray(storage).plus(offset.toLong())
                            assertEquals(-1L, stdio.close(-1))
                            val priorError = stdio.errno()
                            if (compiled) valid(entry)
                            val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val actual = if (operation == OriginalStdioOp.CHDIR) {
                                val path = bytes(row, "path")
                                Calls.target(entry, arrayOf(0L, if (row["absolute"] == true) address(baseBytes + byteArrayOf(47) + path) else address(path)))
                            } else Calls.target(entry, arrayOf(0L, output, capacity.toLong()))
                            // Successful libc errno/scratch tail is unspecified (notably its
                            // long-name fallback). THC deliberately preserves both.
                            val succeeded = if (operation == OriginalStdioOp.CHDIR) row["status"] == 0L else row["returnedNull"] == false
                            assertEquals(if (succeeded) priorError else row["errno"], stdio.errno(), "$stage/$backend/$entryName/${row["name"]}")
                            if (compiled) {
                                assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            if (operation == OriginalStdioOp.CHDIR) {
                                assertEquals(row["status"], actual)
                                assertArrayEquals(expectedName, name(stdio))
                            } else {
                                val returned = actual as ManagedAddress
                                assertEquals(row["returnedNull"], returned.sameLocation(ManagedAddress.nullAddress()))
                                assertEquals(row["returnedSameBuffer"], returned.sameLocation(output))
                                if (row["returnedNull"] == true) assertTrue(storage.all { it == 90.toByte() }, "Staging preserves output on failure")
                                else {
                                    assertArrayEquals(expectedName, storage.copyOfRange(offset, offset + expectedName!!.size))
                                    assertEquals(0.toByte(), storage[offset + expectedName.size])
                                    assertTrue(storage.take(offset).all { it == 90.toByte() })
                                    assertTrue(storage.drop(offset + expectedName.size + 1).all { it == 90.toByte() })
                                }
                            }
                            released(language)
                        } finally {
                            repeat(longDepth) {
                                assertEquals(0L, stdio.changeDirectory(address("..")))
                                assertEquals(0L, stdio.unlinkAt(StdioHostAbi.load().atFdcwd, address(bytes(oracle, "longComponent")), StdioHostAbi.load().atRemoveDir))
                            }
                            assertEquals(0L, stdio.changeDirectory(pathAddress(base)))
                            Files.setPosixFilePermissions(base.resolve("search-denied"), PosixFilePermissions.fromString("rwx------"))
                        }
                    }
                }
                exercise(false)
                entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true)
                valid(entry)
                exercise(true)
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
        assertEquals(processDirectory, Files.readSymbolicLink(Path.of("/proc/self/cwd")))
    }

    @Test fun originalOwnerHeadAbiStateAndOperandProofsRemainRequired() {
        for ((entryName, operation) in operations) {
            val call = original(entryName)
            assertEquals(operation, validate(call))
            for (unit in listOf("main", "ghc-internal", "unix-2.8.7.0-inplace", "unix-2.8.8.0-ABCD", "unix-2.8.8.0-460b:forged")) {
                val bad = copy(call) as MutableList<Any?>
                (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for ((key, value) in listOf("safety" to "safe", "convention" to "capi", "arity" to 99L, "suppliedArity" to 99L)) {
                val bad = copy(call) as MutableList<Any?>
                ((bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            val badResult = copy(call) as MutableList<Any?>
            val metadata = badResult[6] as MutableMap<String, Any?>
            for (rep in listOf(metadata["rep"], (metadata["foreignCall"] as Map<*, *>)["resultRep"])) {
                val proof = rep as MutableMap<String, Any?>
                proof["primReps"] = listOf("Int64Rep")
                ((proof["components"] as List<*>)[1] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
            }
            assertThrows(RuntimeFault::class.java) { validate(badResult) }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                for (index in (call[2] as List<*>).indices) assertThrows(RuntimeFault::class.java) {
                    program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
                }
                val shadowed = raw(call)
                val body = (shadowed["bindings"] as List<Map<String, Any?>>).single()["expr"]
                val changed = OriginalStdioChecks.foreignCalls(body).single() as MutableList<Any?>
                changed[1] = listOf("var", "p0", (changed[1] as List<*>)[2])
                assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
            } }
        }
    }

    @Test fun invalidStateCapacityAndStoragePrecedeDirectoryObservationAndPublication() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val base = setup(); val stdio = Language.currentState().stdio
            assertEquals(0L, stdio.changeDirectory(pathAddress(base)))
            val chdir = program(language, backend, raw(original("pathChdir"))).entryTarget("entry")
            val getcwd = program(language, backend, raw(original("pathGetCwd"))).entryTarget("entry")
            val storage = ByteArray(8192) { 90 }; val output = ManagedAddress.fromByteArray(storage).plus(8)
            assertEquals(-1L, stdio.close(-1)); val error = stdio.errno()
            assertThrows(RuntimeFault::class.java) { Calls.target(chdir, arrayOf(0L, address("sub"), 9L)) }
            assertArrayEquals(NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray(), name(stdio))
            fun invoke(destination: ManagedAddress = output, capacity: Long = 4096, state: Any = Unit) =
                Calls.target(getcwd, arrayOf(0L, destination, capacity, state))
            assertThrows(RuntimeFault::class.java) { invoke(state = 9L) }
            for (capacity in listOf(-1L, Int.MAX_VALUE.toLong() + 1)) assertThrows(RuntimeFault::class.java) { invoke(capacity = capacity) }
            for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(ByteArray(4095)), ManagedAddress.fromHex("5a".repeat(4096))))
                assertThrows(RuntimeFault::class.java) { invoke(bad) }
            val cells = ManagedAllocation.mutable(4096, 8)
            cells.writeAddressByteOffset(8, output)
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.fromAllocation(cells)) }
            assertSame(output, cells.readAddressByteOffset(8))
            assertEquals(error, stdio.errno()); assertTrue(storage.all { it == 90.toByte() })
            val state = Language.currentState(); val native = state.nativeAllocations.malloc(8192); val alias = native.plus(8)
            try {
                native.fill(8192, 90)
                assertSame(alias, invoke(alias))
                for (index in 0L..7L) assertEquals(90L, native.readWord8(index))
                context().use { second -> entered(second) { other ->
                    val target = program(other, backend, raw(original("pathGetCwd"))).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, alias, 4096L, Unit)) }
                } }
            } finally { state.nativeAllocations.free(native) }
            assertThrows(RuntimeFault::class.java) { invoke(alias) }
            Files.setPosixFilePermissions(base.resolve("search-denied"), PosixFilePermissions.fromString("rwx------"))
            released(language)
        } }
    }

    @Test fun existingRawServicesAndSafeOpenFollowRenamedDirectoryIdentity() = context().use { context -> entered(context) {
        val stdio = Language.currentState().stdio; val abi = StdioHostAbi.load()
        val original = Files.createDirectory(directory.resolve("original")); val moved = directory.resolve("moved")
        Files.writeString(original.resolve("tmp"), "original")
        Files.write(rawPath(original, byteArrayOf(-1, 110)), byteArrayOf(42))
        assertEquals(0L, stdio.changeDirectory(pathAddress(original)))
        Files.move(original, moved); Files.createDirectory(original); Files.writeString(original.resolve("tmp"), "replacement")
        for (operation in listOf(OriginalStdioOp.OPEN, OriginalStdioOp.OPEN_SAFE, OriginalStdioOp.OPEN_INTERRUPTIBLE)) {
            val fd = stdio.open(address("tmp"), stdio.flagConstant(OriginalStdioOp.O_RDONLY), 0, operation)
            assertTrue(fd >= 0)
            val bytes = ByteArray(8)
            assertEquals(8L, stdio.read(fd, ManagedAddress.fromByteArray(bytes), 8)); assertEquals("original", String(bytes))
            assertEquals(0L, stdio.close(fd))
        }
        // Also exercise the Path-based private provider: /tmp is a host directory,
        // but context/tmp is a regular file and must not acquire a URI-added slash.
        NativeFileProvider.current().open("tmp", 0).use { assertEquals(8L, it.size()) }
        assertEquals(0L, stdio.access(address(byteArrayOf(-1, 110)), 0))
        assertEquals(0L, stdio.pathMode(OriginalStdioOp.MKDIR, address("created"), 448))
        assertEquals(0L, stdio.pathMode(OriginalStdioOp.CHMOD, address("tmp"), 384))
        assertEquals(0L, stdio.symlink(address("./tmp"), address("new-link")))
        val target = ByteArray(32) { 90 }
        assertEquals(5L, stdio.readlink(address("new-link"), ManagedAddress.fromByteArray(target), 32))
        assertArrayEquals("./tmp".toByteArray(), target.copyOfRange(0, 5))
        val size = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0)
        val image = ManagedAddress.fromByteArray(ByteArray(size.toInt()))
        assertEquals(0L, stdio.statAt(abi.atFdcwd, address("tmp"), image, 0))
        assertEquals(8L, PosixStat.execute(OriginalStdioOp.ST_SIZE, image, 0))
        assertEquals(0L, stdio.unlink(address("new-link")))
        assertEquals(0L, stdio.unlinkAt(abi.atFdcwd, address("created"), abi.atRemoveDir))
        assertFalse(Files.exists(moved.resolve("created")))
        assertEquals("replacement", Files.readString(original.resolve("tmp")))
    } }
}
