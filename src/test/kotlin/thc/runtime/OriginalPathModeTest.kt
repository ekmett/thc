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

/** Genuine installed GHC and Unix declarations, with native-compiled consumers. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalPathModeTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-path-mode"
    private val operations = linkedMapOf("pathMkdir" to OriginalStdioOp.MKDIR, "pathChmod" to OriginalStdioOp.CHMOD)
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

    private fun path(directory: Path, bytes: ByteArray): ManagedAddress {
        val prefix = if (bytes.isEmpty()) byteArrayOf() else
            (Path.of(Language.currentState().env.currentWorkingDirectory.path).relativize(directory).toString() + "/").toByteArray()
        return ManagedAddress.fromByteArray(prefix + bytes + byteArrayOf(0))
    }
    private fun unixMode(path: Path): Long =
        if (Files.exists(path)) (Files.getAttribute(path, "unix:mode") as Number).toLong() and 511L else -1L
    private fun rawPath(directory: Path, bytes: ByteArray): Path =
        Path.of(URI(directory.toUri().toASCIIString() + bytes.joinToString("") { "%%%02X".format(it.toInt() and 255) }))
    private fun setup(): Path = Files.createTempDirectory(directory, "case-").also { scratch ->
        Files.writeString(scratch.resolve("target"), "unchanged")
        Files.setPosixFilePermissions(scratch.resolve("target"), PosixFilePermissions.fromString("rw-r--r--"))
        Files.createDirectory(scratch.resolve("sub"))
        Files.setPosixFilePermissions(scratch.resolve("sub"), PosixFilePermissions.fromString("rwx------"))
        Files.createSymbolicLink(scratch.resolve("link"), Path.of("target"))
        Files.createSymbolicLink(scratch.resolve("dangling"), Path.of("absent-target"))
    }

    @Test fun originalNativeModesMatchBothBackendsAndFirstInstalledCalls() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertEquals("unix-2.8.8.0-inplace", manifest["unixUnit"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalPathModeAudit.hs",
            "test/haskell-fixtures/OriginalPathModeFixtures.hs", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage ->
                listOf("$prefix/$stage.json") + operations.keys.map { "$prefix/$stage-$it.audit.json" }
            }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val rows = oracle["rows"] as List<Map<String, Any?>>
        assertEquals(listOf("create", "zero", "wide", "existing-file", "existing-directory", "missing-parent",
            "not-directory", "empty", "relative", "raw"), rows.filter { it["entry"] == "pathMkdir" }.map { it["name"] })
        assertEquals(listOf("file", "zero", "wide", "directory", "link", "dangling", "missing",
            "not-directory", "empty", "relative", "raw"), rows.filter { it["entry"] == "pathChmod" }.map { it["name"] })
        // Observe this process's inherited creation permissions without changing
        // umask; native fixtures may have been cached under another umask.
        val probe = Files.createDirectory(directory.resolve("mask-probe"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")))
        val creationMask = unixMode(probe)
        val nativeMask = oracle["creationMask"] as Long
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
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget(name)
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    for (row in rows.filter { it["entry"] == name }) {
                        val scratch = setup()
                        val bytes = (row["path"] as List<Long>).map { it.toByte() }.toByteArray()
                        if (name == "pathChmod") Files.createSymbolicLink(rawPath(scratch, byteArrayOf(-1, 'm'.code.toByte())), Path.of("target"))
                        val mode = row["mode"] as Long
                        assertEquals(-1L, stdio.close(-1))
                        val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (compiled) valid(entry)
                        assertEquals(row["status"], Calls.target(entry, arrayOf(0L, path(scratch, bytes), mode)),
                            "$stage/$backend/$name/" + row["name"])
                        assertEquals(row["errno"], stdio.errno())
                        if (compiled) {
                            assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                            valid(entry)
                        }
                        val observed = if (bytes.isEmpty()) -1L else unixMode(rawPath(scratch, bytes))
                        if (name == "pathMkdir" && row["status"] == 0L) {
                            assertEquals(mode and nativeMask and 511L, row["observedMode"])
                            assertEquals(mode and creationMask and 511L, observed)
                            assertTrue(Files.isDirectory(rawPath(scratch, bytes)))
                        } else assertEquals(row["observedMode"], observed)
                        assertEquals(row["targetMode"], unixMode(scratch.resolve("target")))
                        // Restore access after observations so zero-mode paths can be cleaned.
                        if (row["status"] == 0L) Files.setPosixFilePermissions(rawPath(scratch, bytes), PosixFilePermissions.fromString("rwx------"))
                        Files.setPosixFilePermissions(scratch.resolve("target"), PosixFilePermissions.fromString("rw-r--r--"))
                        assertEquals("unchanged", Files.readString(scratch.resolve("target")))
                        assertTrue(Files.isSymbolicLink(scratch.resolve("link")))
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
            for (unit in listOf("main", "unix-2.8.7.0-inplace", "unix-2.8.8.0-abcd",
                if (operation == OriginalStdioOp.MKDIR) "ghc-internal" else "unix-2.8.8.0-inplace")) {
                val bad = copy(call) as MutableList<Any?>
                (((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)["target"] as MutableMap<String, Any?>)["unit"] = unit
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
            } }
        }
    }


    @Test fun stateModesAndPathOwnershipAreCheckedBeforeMutation() {
        for (name in operations.keys) for (backend in listOf("ast", "bytecode"))
            context().use { context -> entered(context) { language ->
                val scratch = setup()
                val entry = program(language, backend, raw(original(name))).entryTarget("entry")
                val stdio = Language.currentState().stdio
                val selected = if (name == "pathMkdir") "new" else "target"
                fun invoke(address: ManagedAddress = path(scratch, selected.toByteArray()), mode: Long = 448L, state: Any = Unit) =
                    Calls.target(entry, arrayOf(0L, address, mode, state))
                assertEquals(-1L, stdio.close(-1)); val prior = stdio.errno()
                assertThrows(RuntimeFault::class.java) { invoke(state = 9L) }
                for (mode in listOf(-1L, 0x1_0000_0000L))
                    assertThrows(RuntimeFault::class.java) { invoke(mode = mode) }
                assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.nullAddress()) }
                val allocation = ManagedAllocation.mutable(16, 8)
                allocation.writeAddressByteOffset(0, ManagedAddress.nullAddress())
                assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.fromAllocation(allocation)) }
                assertEquals(prior, stdio.errno())
                assertFalse(Files.exists(scratch.resolve("new")))
                assertEquals(420L, unixMode(scratch.resolve("target")))
                released(language)
            } }
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            val destination = directory.resolve("denied")
            val entry = program(language, backend, raw(original("pathMkdir"))).entryTarget("entry")
            assertEquals(-1L, Calls.target(entry, arrayOf(0L, cstring(destination.toString()), 448L, Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
            assertFalse(Files.exists(destination))
        } }
    }

    @Test fun nativePathAliasesRetainContextAndLifetimeChecks() {
        for (backend in listOf("ast", "bytecode")) context().use { first -> entered(first) { language ->
            val destination = Files.createTempDirectory(directory, "owned-").resolve("new")
            val bytes = destination.toString().toByteArray() + byteArrayOf(0)
            val state = Language.currentState()
            val base = state.nativeAllocations.malloc(bytes.size.toLong() + 8)
            val alias = base.plus(8)
            val entry = program(language, backend, raw(original("pathMkdir"))).entryTarget("entry")
            try {
                ManagedAddress.fromByteArray(bytes).copyNonOverlappingTo(alias, bytes.size.toLong())
                context().use { second -> entered(second) { other ->
                    val foreign = program(other, backend, raw(original("pathMkdir"))).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) { Calls.target(foreign, arrayOf(0L, alias, 448L, Unit)) }
                    assertFalse(Files.exists(destination)); released(other)
                } }
                assertEquals(0L, Calls.target(entry, arrayOf(0L, alias, 448L, Unit)))
                assertTrue(Files.isDirectory(destination))
            } finally { state.nativeAllocations.free(base) }
            assertThrows(RuntimeFault::class.java) { Calls.target(entry, arrayOf(0L, alias, 448L, Unit)) }
            released(language)
        } }
    }
}
