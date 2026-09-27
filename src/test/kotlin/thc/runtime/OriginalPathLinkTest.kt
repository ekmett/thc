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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Genuine installed GHC and Unix declarations, with native-compiled consumers. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalPathLinkTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-path-link"
    private val operations = linkedMapOf("pathSymlink" to OriginalStdioOp.SYMLINK, "pathReadlink" to OriginalStdioOp.READLINK)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun cstring(value: String) = ManagedAddress.fromByteArray(value.toByteArray() + byteArrayOf(0))
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context() = NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST)
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun valid(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun original(name: String, stage: String = "post") =
        OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(stage), name)).single()
    private fun raw(call: List<Any?>) = OriginalStdioChecks.rawModule(call, source("post"))

    private fun bytes(row: Map<String, Any?>, name: String) = (row[name] as List<Long>).map { it.toByte() }.toByteArray()
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + byteArrayOf(0))
    private fun path(directory: Path, bytes: ByteArray): ManagedAddress {
        val prefix = if (bytes.isEmpty()) byteArrayOf() else
            (Path.of(Language.currentState().env.currentWorkingDirectory.path).relativize(directory).toString() + "/").toByteArray()
        return address(prefix + bytes)
    }
    private fun rawPath(directory: Path, bytes: ByteArray): Path =
        Path.of(URI(directory.toUri().toASCIIString() + bytes.joinToString("") { "%%%02X".format(it.toInt() and 255) }))
    private fun rawTarget(bytes: ByteArray): Path {
        val absolute = rawPath(Path.of("/"), if (bytes.firstOrNull() == '/'.code.toByte()) bytes.drop(1).toByteArray() else bytes)
        return if (bytes.firstOrNull() == '/'.code.toByte()) absolute else absolute.subpath(0, absolute.nameCount)
    }
    private fun setup(): Path = Files.createTempDirectory(directory, "case-").also { scratch ->
        Files.writeString(scratch.resolve("target"), "unchanged")
        Files.createDirectory(scratch.resolve("sub"))
        Files.createSymbolicLink(scratch.resolve("existing-link"), Path.of("target"))
    }

    @Test fun genuineNativeLinksMatchBothBackendsAndFirstInstalledCalls() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertTrue(isOriginalUnixUnit(manifest["unixUnit"]))
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalPathLinkAudit.hs",
            "test/haskell-fixtures/OriginalPathLinkFixtures.hs", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage ->
                listOf("$prefix/$stage.json") + operations.keys.map { "$prefix/$stage-$it.audit.json" }
            }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val symlinkRows = oracle["symlinkRows"] as List<Map<String, Any?>>
        val readlinkRows = oracle["readlinkRows"] as List<Map<String, Any?>>
        assertEquals(listOf("relative", "dot-segments", "parent-relative", "absolute", "dangling", "raw-target", "raw-link",
            "empty-target", "empty-path", "existing-file", "existing-link", "missing-parent", "not-directory"), symlinkRows.map { it["name"] })
        assertEquals(listOf("full", "exact", "short", "one", "zero", "dangling", "raw-target", "raw-link",
            "missing", "regular", "not-directory", "empty"), readlinkRows.map { it["name"] })
        for (stage in listOf("pre", "post")) for ((name, operation) in operations) {
            val audit = json("$prefix/$stage-$name.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(source(stage), name) + ("instrument" to true)
            val evidence = ArrayCoreEvidence(linked, name)
            assertEquals(1, evidence.bindings.size)
            assertEquals(1, evidence.guestLambdas(evidence.root["expr"]).size)
            assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
            assertEquals(operation, validate(original(name, stage)))
            assertEquals(manifest["unixUnit"], (((original(name, stage)[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
            val rows = if (name == "pathSymlink") symlinkRows else readlinkRows
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget(name)
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    for (row in rows) {
                        val scratch = setup()
                        val pathBytes = bytes(row, "path")
                        val target = bytes(row, "target")
                        val capacity = (row["capacity"] as? Long ?: 0L).toInt()
                        val output = ByteArray(capacity + 16) { 90 }
                        if (name == "pathReadlink") {
                            Files.createSymbolicLink(scratch.resolve("link"), rawTarget(target))
                            if (row["name"] == "raw-link") Files.createSymbolicLink(rawPath(scratch, pathBytes), rawTarget(target))
                        }
                        assertEquals(-1L, stdio.close(-1))
                        val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (compiled) valid(entry)
                        val actual = if (name == "pathSymlink")
                            Calls.target(entry, arrayOf(0L, address(target), path(scratch, pathBytes)))
                        else Calls.target(entry, arrayOf(0L, path(scratch, pathBytes),
                            ManagedAddress.fromByteArray(output).plus(8), capacity.toLong()))
                        assertEquals(row["status"], actual, "$stage/$backend/$name/" + row["name"])
                        assertEquals(row["errno"], stdio.errno())
                        if (compiled) {
                            assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                            valid(entry)
                        }
                        if (name == "pathSymlink") {
                            val link = if (pathBytes.isEmpty()) null else rawPath(scratch, pathBytes)
                            val observed = if (link != null && Files.isSymbolicLink(link)) Files.readSymbolicLink(link) else null
                            val expected = (row["linkTarget"] as? List<Long>)?.map { it.toByte() }?.toByteArray()?.let(::rawTarget)
                            assertEquals(expected, observed)
                        } else {
                            assertEquals(row["bufferHex"], output.joinToString("") { "%02x".format(it.toInt() and 255) })
                            val count = actual as Long
                            if (count >= 0L) {
                                assertEquals(minOf(target.size.toLong(), capacity.toLong()), count)
                                assertArrayEquals(target.copyOfRange(0, count.toInt()), output.copyOfRange(8, 8 + count.toInt()))
                                assertTrue(output.drop(8 + count.toInt()).all { it == 90.toByte() })
                            } else assertTrue(output.all { it == 90.toByte() })
                        }
                        assertEquals("unchanged", Files.readString(scratch.resolve("target")))
                        released(language)
                    }
                }
                exercise(false)
                entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true)
                valid(entry)
                exercise(true)
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
    }

    @Test fun exactDescriptorOwnerStateAndStoredOperandProofsRemainRequired() {
        for ((name, operation) in operations) {
            val call = original(name)
            assertEquals(operation, validate(call))
            for ((key, value) in listOf("safety" to "safe", "convention" to "capi",
                "arity" to 2L, "suppliedArity" to 2L)) {
                val bad = copy(call) as MutableList<Any?>
                ((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for (unit in listOf("main", "unix-2.8.7.0-inplace", "unix-2.8.8.0-ABCD", "unix-2.8.8.0-nothex",
                "ghc-internal")) {
                if (operation == OriginalStdioOp.READLINK && unit == "ghc-internal") continue
                val bad = copy(call) as MutableList<Any?>
                (((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            val widened = copy(call) as MutableList<Any?>
            val meta = widened[6] as MutableMap<String, Any?>
            for (rep in listOf(meta["rep"], (meta["foreignCall"] as Map<*, *>)["resultRep"])) {
                val proof = rep as MutableMap<String, Any?>
                proof["primReps"] = listOf("Int64Rep")
                ((proof["components"] as List<*>)[1] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
            }
            assertThrows(RuntimeFault::class.java) { validate(widened) }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                for (unit in listOf("unix-2.8.8.0-inplace", "unix-2.8.8.0-460b")) {
                    val installed = copy(call) as MutableList<Any?>
                    (((installed[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                    assertEquals(operation, validate(installed))
                    program(language, backend, raw(installed))
                }
                for (index in (call[2] as List<*>).indices) assertThrows(RuntimeFault::class.java) {
                    program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
                }
                val shadowed = raw(call)
                val body = (shadowed["bindings"] as List<Map<String, Any?>>).single()["expr"]
                val copied = OriginalStdioChecks.foreignCalls(body).single() as MutableList<Any?>
                copied[1] = listOf("var", "p0", (copied[1] as List<*>)[2])
                assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
            } }
        }
    }



    @Test fun stateBoundsAndWritableByteStoragePrecedeEffects() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val scratch = setup()
            val symlink = program(language, backend, raw(original("pathSymlink"))).entryTarget("entry")
            val readlink = program(language, backend, raw(original("pathReadlink"))).entryTarget("entry")
            val stdio = Language.currentState().stdio
            val outputBytes = ByteArray(32) { 90 }
            val output = ManagedAddress.fromByteArray(outputBytes).plus(8)
            val missing = path(scratch, "missing".toByteArray())
            assertEquals(-1L, stdio.close(-1)); val prior = stdio.errno()
            assertThrows(RuntimeFault::class.java) {
                Calls.target(symlink, arrayOf(0L, address("target".toByteArray()), path(scratch, "new".toByteArray()), 9L))
            }
            assertFalse(Files.exists(scratch.resolve("new")))
            fun read(destination: ManagedAddress = output, capacity: Long = 16L, token: Any = Unit) =
                Calls.target(readlink, arrayOf(0L, missing, destination, capacity, token))
            assertThrows(RuntimeFault::class.java) { read(token = 9L) }
            for (capacity in listOf(-1L, Int.MAX_VALUE.toLong() + 1))
                assertThrows(RuntimeFault::class.java) { read(capacity = capacity) }
            for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(ByteArray(15)),
                ManagedAddress.fromHex("5a".repeat(16)), output.plus(9)))
                assertThrows(RuntimeFault::class.java) { read(bad) }
            val allocation = ManagedAllocation.mutable(32, 8)
            allocation.writeAddressByteOffset(8, output)
            assertThrows(RuntimeFault::class.java) { read(ManagedAddress.fromAllocation(allocation)) }
            assertSame(output, allocation.readAddressByteOffset(8))
            assertEquals(prior, stdio.errno()); assertTrue(outputBytes.all { it == 90.toByte() })
            assertEquals(-1L, read()); assertEquals(StdioHostAbi.load().error(1), stdio.errno())
            assertTrue(outputBytes.all { it == 90.toByte() })
            // Path and destination can alias: snapshot the full path before publication.
            val aliasedBytes = scratch.resolve("existing-link").toString().toByteArray() + byteArrayOf(0)
            val alias = ManagedAddress.fromByteArray(aliasedBytes)
            assertEquals(6L, Calls.target(readlink, arrayOf(0L, alias, alias, aliasedBytes.size.toLong(), Unit)))
            assertArrayEquals("target".toByteArray(), aliasedBytes.copyOfRange(0, 6))
            assertEquals(0L, Calls.target(symlink, arrayOf(0L, address("../target".toByteArray()), path(scratch, "new".toByteArray()), Unit)))
            assertEquals(Path.of("../target"), Files.readSymbolicLink(scratch.resolve("new")))
            released(language)
        } }
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            val destination = directory.resolve("denied")
            val symlink = program(language, backend, raw(original("pathSymlink"))).entryTarget("entry")
            assertEquals(-1L, Calls.target(symlink, arrayOf(0L, address("target".toByteArray()), cstring(destination.toString()), Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
            assertFalse(Files.exists(destination))
            val bytes = ByteArray(16) { 90 }
            val readlink = program(language, backend, raw(original("pathReadlink"))).entryTarget("entry")
            assertEquals(-1L, Calls.target(readlink, arrayOf(0L, cstring(destination.toString()), ManagedAddress.fromByteArray(bytes), 16L, Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
            assertTrue(bytes.all { it == 90.toByte() })
        } }
    }

    @Test fun nativeOutputAliasesKeepContextAndLifetimeChecks() {
        for (backend in listOf("ast", "bytecode")) context().use { first -> entered(first) { language ->
            val scratch = setup()
            val state = Language.currentState()
            val base = state.nativeAllocations.malloc(32)
            val alias = base.plus(8)
            val entry = program(language, backend, raw(original("pathReadlink"))).entryTarget("entry")
            try {
                base.fill(32, 90)
                assertEquals(6L, Calls.target(entry, arrayOf(0L, path(scratch, "existing-link".toByteArray()), alias, 16L, Unit)))
                assertArrayEquals("target".toByteArray(), ByteArray(6) { alias.readWord8(it.toLong()).toByte() })
                for (index in 0L..7L) assertEquals(90L, base.readWord8(index))
                for (index in 14L..31L) assertEquals(90L, base.readWord8(index))
                context().use { second -> entered(second) { other ->
                    val foreign = program(other, backend, raw(original("pathReadlink"))).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(foreign, arrayOf(0L, cstring(scratch.resolve("existing-link").toString()), alias, 16L, Unit))
                    }
                    released(other)
                } }
            } finally { state.nativeAllocations.free(base) }
            assertThrows(RuntimeFault::class.java) {
                Calls.target(entry, arrayOf(0L, path(scratch, "existing-link".toByteArray()), alias, 16L, Unit))
            }
            released(language)
        } }
    }
}
