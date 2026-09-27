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
import java.nio.file.attribute.PosixFilePermissions

/** Native GHC observes real-ID access results; no assumptions about root's permissions. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalPathAccessTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-path-access"
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
    private fun original(stage: String = "post") =
        OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(stage), "pathAccess")).single()
    private fun raw(call: List<Any?>) = OriginalStdioChecks.rawModule(call, source("post"))
    private fun path(scratch: Path, bytes: ByteArray): ManagedAddress {
        val prefix = if (bytes.isEmpty()) byteArrayOf() else
            (Path.of(Language.currentState().env.currentWorkingDirectory.path).relativize(scratch).toString() + "/").toByteArray()
        return ManagedAddress.fromByteArray(prefix + bytes + byteArrayOf(0))
    }
    private fun rawPath(scratch: Path, bytes: ByteArray): Path =
        Path.of(URI(scratch.toUri().toASCIIString() + bytes.joinToString("") { "%%%02X".format(it.toInt() and 255) }))
    private fun permissions(path: Path, mode: String) =
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    private fun setup(): Path = Files.createTempDirectory(directory, "case-").also { scratch ->
        for ((name, mode) in listOf("target" to "rw-r-----", "executable" to "rwxr-x--x", "zero" to "---------")) {
            Files.writeString(scratch.resolve(name), "unchanged"); permissions(scratch.resolve(name), mode)
        }
        Files.createDirectory(scratch.resolve("sub")); permissions(scratch.resolve("sub"), "rwxr-x---")
        Files.createDirectory(scratch.resolve("locked"))
        Files.writeString(scratch.resolve("locked/target"), "unchanged"); permissions(scratch.resolve("locked/target"), "rw-r-----")
        permissions(scratch.resolve("locked"), "---------")
        Files.createSymbolicLink(scratch.resolve("link"), Path.of("target"))
        Files.createSymbolicLink(scratch.resolve("dangling"), Path.of("absent-target"))
        val raw = rawPath(scratch, byteArrayOf(-1, 'n'.code.toByte()))
        Files.writeString(raw, "unchanged"); permissions(raw, "rw-r-----")
    }

    @Test fun originalNativeAccessMatchesBothBackendsAndFirstInstalledCalls() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(listOf("pathAccess"), manifest["entries"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalPathAccessAudit.hs",
            "test/haskell-fixtures/OriginalPathAccessFixtures.hs", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { listOf("$prefix/$it.json", "$prefix/$it-pathAccess.audit.json") }, "$prefix/")
        val rows = json("$prefix/oracle.json")["rows"] as List<Map<String, Any?>>
        assertEquals(68, rows.size)
        assertEquals(listOf("file", "executable", "zero", "directory", "locked-child", "link", "dangling",
            "missing", "empty", "not-directory", "raw", "relative"), rows.take(60).chunked(5).map { it.first()["name"] })
        assertTrue(rows.map { it["mode"] }.containsAll(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L,
            -1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), 0x1_0000_0000L)))
        for (stage in listOf("pre", "post")) {
            val audit = json("$prefix/$stage-pathAccess.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(source(stage), "pathAccess") + ("instrument" to true)
            val evidence = ArrayCoreEvidence(linked, "pathAccess")
            assertEquals(1, evidence.bindings.size)
            assertEquals(1, evidence.guestLambdas(evidence.root["expr"]).size)
            assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
            assertEquals(OriginalStdioOp.ACCESS, validate(original(stage)))
            assertEquals("ghc-internal", (((original(stage)[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget("pathAccess")
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    val scratch = setup()
                    try {
                        for (row in rows) {
                            val bytes = (row["path"] as List<Long>).map { it.toByte() }.toByteArray()
                            assertEquals(-1L, stdio.close(-1))
                            val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (compiled) valid(entry)
                            assertEquals(row["status"], Calls.target(entry, arrayOf(0L, path(scratch, bytes), row["mode"])),
                                "$stage/$backend/" + row["name"] + "/" + row["mode"])
                            assertEquals(row["errno"], stdio.errno())
                            if (compiled) {
                                assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            released(language)
                        }
                        assertEquals("unchanged", Files.readString(scratch.resolve("target")))
                        assertTrue(Files.isSymbolicLink(scratch.resolve("link")))
                    } finally { permissions(scratch.resolve("locked"), "rwx------") }
                }
                exercise(false)
                entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true)
                valid(entry)
                exercise(true)
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
    }

    @Test fun exactOwnerAbiStateAndStoredOperandProofsRemainRequired() {
        val call = original()
        assertEquals(OriginalStdioOp.ACCESS, validate(call))
        for ((key, value) in listOf("safety" to "safe", "convention" to "capi",
            "arity" to 2L, "suppliedArity" to 2L)) {
            val bad = copy(call) as MutableList<Any?>
            ((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
            assertThrows(RuntimeFault::class.java) { validate(bad) }
        }
        for (unit in listOf("main", "unix-2.8.8.0-inplace", "unix-2.8.8.0-460b", "ghc-internal-forged")) {
            val bad = copy(call) as MutableList<Any?>
            (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
            assertThrows(RuntimeFault::class.java) { validate(bad) }
        }
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            for (index in 0..2) assertThrows(RuntimeFault::class.java) {
                program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
            }
            val shadowed = raw(call)
            val body = (shadowed["bindings"] as List<Map<String, Any?>>).single()["expr"]
            val copied = OriginalStdioChecks.foreignCalls(body).single() as MutableList<Any?>
            copied[1] = listOf("var", "p0", (copied[1] as List<*>)[2])
            assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
            for (rep in listOf("IntRep", "Word32Rep", "Word64Rep")) {
                val bad = copy(call) as MutableList<Any?>
                val descriptor = (bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                ((descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[1])["primReps"] = listOf(rep)
                assertThrows(RuntimeFault::class.java) { program(language, backend, raw(bad)) }
            }
        } }
    }

    @Test fun stateCanonicalModeAndPathOwnershipPrecedeObservation() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val entry = program(language, backend, raw(original())).entryTarget("entry")
            val stdio = Language.currentState().stdio
            val missing = cstring(directory.resolve("missing").toString())
            fun invoke(address: ManagedAddress = missing, mode: Long = 0L, state: Any = Unit) =
                Calls.target(entry, arrayOf(0L, address, mode, state))
            assertEquals(-1L, stdio.close(-1)); val prior = stdio.errno()
            assertThrows(RuntimeFault::class.java) { invoke(state = 9L) }
            for (mode in listOf(Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1, 0x1_0000_0000L))
                assertThrows(RuntimeFault::class.java) { invoke(mode = mode) }
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.nullAddress()) }
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.fromByteArray(byteArrayOf(65))) }
            val allocation = ManagedAllocation.mutable(16, 8)
            allocation.writeAddressByteOffset(0, ManagedAddress.nullAddress())
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.fromAllocation(allocation)) }
            assertEquals(prior, stdio.errno())
            released(language)
        } }
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            val entry = program(language, backend, raw(original())).entryTarget("entry")
            assertEquals(-1L, Calls.target(entry, arrayOf(0L, cstring(directory.toString()), 0L, Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
        } }
    }

    @Test fun nativePathAliasesRetainContextAndLifetimeChecks() {
        for (backend in listOf("ast", "bytecode")) context().use { first -> entered(first) { language ->
            val existing = Files.createTempFile(directory, "owned-", ".txt")
            val bytes = existing.toString().toByteArray() + byteArrayOf(0)
            val state = Language.currentState()
            val base = state.nativeAllocations.malloc(bytes.size.toLong() + 8)
            val alias = base.plus(8)
            val entry = program(language, backend, raw(original())).entryTarget("entry")
            try {
                ManagedAddress.fromByteArray(bytes).copyNonOverlappingTo(alias, bytes.size.toLong())
                context().use { second -> entered(second) { other ->
                    val foreign = program(other, backend, raw(original())).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) { Calls.target(foreign, arrayOf(0L, alias, 0L, Unit)) }
                    released(other)
                } }
                assertEquals(0L, Calls.target(entry, arrayOf(0L, alias, 0L, Unit)))
                // Successful observation retains the previous guest errno.
                assertEquals(-1L, state.stdio.close(-1)); val prior = state.stdio.errno()
                assertEquals(0L, Calls.target(entry, arrayOf(0L, alias, 0L, Unit)))
                assertEquals(prior, state.stdio.errno())
            } finally { state.nativeAllocations.free(base) }
            assertThrows(RuntimeFault::class.java) { Calls.target(entry, arrayOf(0L, alias, 0L, Unit)) }
            released(language)
        } }
    }
}
