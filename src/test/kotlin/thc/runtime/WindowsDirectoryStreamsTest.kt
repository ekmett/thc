// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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

@EnabledOnOs(OS.WINDOWS)
class WindowsDirectoryStreamsTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/windows-directory"
    private val operations = linkedMapOf("directoryFirst" to OriginalStdioOp.FIND_FIRST,
        "directoryNext" to OriginalStdioOp.FIND_NEXT, "directoryClose" to OriginalStdioOp.FIND_CLOSE,
        "directoryError" to OriginalStdioOp.LAST_ERROR)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String = "post") = json(prefix + "/" + stage + ".json")
    private fun path(value: String) = ManagedAddress.fromByteArray((value + '\u0000').toByteArray(Charsets.UTF_16LE))
    private fun buffer() = ManagedAddress.fromAllocation(ManagedAllocation.mutable(WindowsDirectoryStreams.Abi.size, 8))
    private fun context() = WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST)
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun service() = Language.currentState().windowsDirectories!!
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun name(output: ManagedAddress): List<Long> {
        val result = mutableListOf<Long>()
        repeat(WindowsDirectoryStreams.Abi.nameUnits.toInt()) { index ->
            val offset = WindowsDirectoryStreams.Abi.nameOffset + index * 2L
            val value = output.readWord8(offset) or (output.readWord8(offset + 1) shl 8)
            if (value == 0L) return result
            result.add(value)
        }
        error("Unterminated find-data name")
    }
    private fun original(name: String) = OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(), name)).single()
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])

    @Test fun genuineWindowsCoreMatchesNativeOracleInInterpreterAndEveryFirstCompiledCall() {
        val manifest = json(prefix + "/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertEquals("Win32-2.14.2.1-inplace", manifest["win32Unit"])
        assertEquals(true, manifest["privateRebuiltWin32"]); assertEquals(true, manifest["originalFCallIds"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/WindowsDirectoryAudit.hsc", "test/haskell-fixtures/WindowsDirectoryFixtures.hs",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"],
            setOf(prefix + "/pre.json", prefix + "/post.json", prefix + "/oracle.json", prefix + "/win32-source.json"), prefix + "/")
        val receipt = json(prefix + "/win32-source.json")
        assertEquals(true, receipt["sourcesUnchangedAfterBuild"])
        assertEquals("69d15a9fb4ef718353aaf8700a64c5885743d4f34a94f7da273fa12584df0315", receipt["archiveSha256"])
        val oracle = json(prefix + "/oracle.json")
        assertEquals(true, oracle["processCwdUnchanged"])
        assertEquals(listOf(WindowsDirectoryStreams.Abi.size, WindowsDirectoryStreams.Abi.nameOffset,
            WindowsDirectoryStreams.Abi.nameUnits, WindowsDirectoryStreams.Abi.noMoreFiles), oracle["layout"])
        val rows = oracle["rows"] as List<Map<String, Any?>>
        assertEquals(10, rows.size)
        assertEquals(false, rows.single { it["case"] == "extended" }["invalid"])
        assertEquals(true, rows.single { it["case"] == "extended-forward-slash" }["invalid"])
        val nativeCwd = Path.of("").toAbsolutePath()
        for (stage in listOf("pre", "post")) {
            for ((name, op) in operations) {
                assertEquals(op, validate(original(name)))
                val audit = json(prefix + "/" + stage + "-" + name + ".audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
            }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val base = Files.createTempDirectory(directory, "native-")
                val names = oracle["names"] as List<String>
                names.forEach { Files.writeString(base.resolve(it), "contents") }
                Files.createDirectory(base.resolve("empty"))
                Files.createDirectory(base.resolve(oracle["unicodeDirectory"] as String))
                Language.currentState().env.setCurrentWorkingDirectory(Language.currentState().env.getPublicTruffleFile(base.toString()))
                val executable = program(language, backend, source(stage) + ("instrument" to true))
                val targets = operations.keys.associateWith { executable.entryTarget(it) }
                var compiled = false
                fun invoke(entry: String, vararg args: Any): Any? {
                    val target = targets.getValue(entry)
                    if (compiled) valid(target)
                    val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result = Calls.target(target, arrayOf(0L, *args))
                    if (compiled) {
                        assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            stage + "/" + backend + "/" + entry)
                        valid(target)
                    }
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    return result
                }
                fun exercise() {
                    for (relative in listOf(false, true)) for (row in rows) {
                        val suffix = when (row["case"]) {
                            "populated" -> "*"
                            "empty" -> "empty\\*"
                            "missing" -> "missing\\*"
                            "regular" -> "ordinary.txt\\*"
                            "unicode-directory" -> oracle["unicodeDirectory"].toString() + "\\*"
                            "filter" -> "*.txt"
                            "no-match" -> "absent-*"
                            else -> ""
                        }
                        val query = when (row["case"]) {
                            "extended" -> "\\\\?\\" + base.toString() + "\\*"
                            "extended-forward-slash" -> "\\\\?\\" + base.toString() + "/*"
                            else -> if (relative || suffix.isEmpty()) suffix else base.toString() + "\\" + suffix
                        }
                        val size = WindowsDirectoryStreams.Abi.size
                        val storage = ManagedAddress.fromAllocation(ManagedAllocation.mutable(size + 16, 8))
                        storage.fill(size + 16, 165)
                        val output = storage.plus(8)
                        val handle = invoke("directoryFirst", path(query), output) as ManagedAddress
                        val invalid = handle.sameLocation(WindowsDirectoryStreams.invalidHandle())
                        assertEquals(row["invalid"], invalid, row["case"].toString())
                        if (invalid) assertEquals(row["error"], invoke("directoryError", 0L))
                        else {
                            val found = mutableListOf<List<Long>>()
                            try {
                                found.add(name(output))
                                var limit = 100
                                while (invoke("directoryNext", handle, output) != 0L) {
                                    assertTrue(--limit > 0); found.add(name(output))
                                }
                                assertEquals(row["error"], invoke("directoryError", 0L))
                                assertEquals(row["again"], invoke("directoryNext", handle, output))
                                assertEquals(row["againError"], invoke("directoryError", 0L))
                                assertEquals((row["names"] as List<List<Long>>).toSet(), found.toSet())
                                assertEquals((row["names"] as List<*>).size, found.size)
                                if (row["case"] == "populated") {
                                    val visible = found.map { chars -> chars.map { it.toInt().toChar() }.joinToString("") }
                                        .filter { it != "." && it != ".." }.toSet()
                                    assertEquals((oracle["listDirectory"] as List<String>).toSet(), visible)
                                }
                            } finally { assertNotEquals(0L, invoke("directoryClose", handle)) }
                        }
                        for (i in 0L..7L) {
                            assertEquals(165L, storage.readWord8(i)); assertEquals(165L, storage.readWord8(size + 8 + i))
                        }
                        assertEquals(0, service().liveCount())
                    }
                }
                exercise()
                targets.values.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                compiled = true
                exercise()
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
        assertEquals(nativeCwd, Path.of("").toAbsolutePath())
    }

    @Test fun badStorageCannotAcquireOrAdvanceAndNativeStorageIsBorrowed() {
        context().use { context -> entered(context) {
            val streams = service()
            val query = path(directory.toString() + "\\*")
            val output = buffer()
            for (invalid in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(ByteArray(2)),
                ManagedAddress.fromAllocation(ManagedAllocation.immutable(ByteArray(1024), 8)))) {
                assertThrows(RuntimeFault::class.java) { streams.first(query, invalid) }
                assertEquals(0, streams.liveCount())
            }
            val a = streams.first(query, output)
            val reference = streams.first(query, buffer())
            assertThrows(RuntimeFault::class.java) { streams.next(a, ManagedAddress.fromByteArray(ByteArray(2))) }
            val next = buffer()
            assertEquals(streams.next(reference, next), streams.next(a, output))
            assertEquals(name(next), name(output))
            // Win32's mallocForeignPtrBytes uses pinned byte-array storage.
            // The separate Linux libc malloc provider is not a Windows allocator.
            val owner = ManagedAllocation.mutable(WindowsDirectoryStreams.Abi.size + 16, 8, true)
            val memory = ManagedAddress.fromAllocation(owner)
            memory.fill(WindowsDirectoryStreams.Abi.size + 16, 165)
            val native = memory.plus(8)
            val handle = streams.first(query, native)
            assertTrue(name(native).isNotEmpty())
            for (i in 0L..7L) {
                assertEquals(165L, memory.readWord8(i))
                assertEquals(165L, memory.readWord8(WindowsDirectoryStreams.Abi.size + 8 + i))
            }
            owner.shrink(0)
            assertThrows(RuntimeFault::class.java) { streams.next(handle, native) }
            assertNotEquals(0L, streams.closeSearch(handle))
            assertNotEquals(0L, streams.closeSearch(a)); assertNotEquals(0L, streams.closeSearch(reference))
        } }
    }

    @Test fun exactContextOwnedHandlesCloseOnFailureAndDisposal() {
        val first = context()
        val second = context()
        lateinit var streams: WindowsDirectoryStreams
        lateinit var handle: ManagedAddress
        lateinit var output: ManagedAddress
        try {
            entered(first) {
                streams = service(); output = buffer()
                handle = streams.first(path(directory.toString() + "\\*"), output)
            }
            entered(second) {
                assertThrows(RuntimeFault::class.java) { service().next(handle, buffer()) }
                assertThrows(RuntimeFault::class.java) { streams.closeSearch(handle) }
            }
            entered(first) {
                assertThrows(RuntimeFault::class.java) { streams.closeSearch(handle.plus(1)) }
                val before = name(output)
                assertNotEquals(0L, streams.closeSearch(handle))
                assertEquals(before, name(output), "Win32 find-data is caller-owned after close")
                assertThrows(RuntimeFault::class.java) { streams.closeSearch(handle) }
                streams.first(path(directory.toString() + "\\*"), output)
                assertEquals(1, streams.liveCount())
            }
        } finally { first.close(); second.close() }
        assertEquals(0, streams.liveCount())
    }

    @Test fun fileAndNativeGrantsAloneDoNotAuthenticateAnArbitraryContext() {
        for (native in listOf(false, true)) for (io in listOf(IOAccess.NONE, IOAccess.ALL))
            Context.newBuilder("thc").allowNativeAccess(native).allowIO(io).build().use { context ->
                entered(context) { assertThrows(SecurityException::class.java) { WindowsDirectoryStreams.current(object : com.oracle.truffle.api.nodes.Node() {}) } }
            }
    }

    @Test fun realDeclarationsRejectWrongOwnerSafetyWidthAndForgedStateBeforeEffects() {
        for ((entry, op) in operations) {
            val call = original(entry)
            assertEquals(op, validate(call))
            for (unit in listOf("main", "ghc-internal", "unix-2.8.8.0-inplace", "directory-1.3.10.0-inplace",
                "Win32-2.14.2.2-inplace", "Win32-2.14.2.1-ABCD")) {
                val bad = Json.parse(Json.stringify(call)) as MutableList<Any?>
                (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                if (op == OriginalStdioOp.LAST_ERROR && unit == "ghc-internal") assertEquals(op, validate(bad))
                else assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for ((field, value) in listOf("safety" to "safe", "convention" to "stdcall", "arity" to 19L)) {
                val bad = Json.parse(Json.stringify(call)) as MutableList<Any?>
                ((bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[field] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                for (index in (call[2] as List<*>).indices) assertThrows(RuntimeFault::class.java) {
                    program(language, backend, OriginalStdioChecks.rawModule(call, source(), index))
                }
                val streams = service()
                val output = buffer()
                val handle = streams.first(path(directory.toString() + "\\*"), output)
                val before = name(output)
                val target = program(language, backend, OriginalStdioChecks.rawModule(call, source())).entryTarget("entry")
                val operands = when (op) {
                    OriginalStdioOp.FIND_FIRST -> arrayOf<Any>(path(directory.toString() + "\\*"), output)
                    OriginalStdioOp.FIND_NEXT -> arrayOf<Any>(handle, output)
                    OriginalStdioOp.FIND_CLOSE -> arrayOf<Any>(handle)
                    else -> emptyArray()
                }
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *operands, 7L)) }
                assertEquals(1, streams.liveCount()); assertEquals(before, name(output))
                assertNotEquals(0L, streams.closeSearch(handle))
            } }
        }
    }
}
