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

/** Genuine Unix declarations, with the selected glibc readdir ownership contract. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalDirectoryStreamsTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-directory-streams"
    private val operations = linkedMapOf("directoryOpen" to OriginalStdioOp.OPENDIR,
        "directoryFdOpen" to OriginalStdioOp.FDOPENDIR, "directoryClose" to OriginalStdioOp.CLOSEDIR,
        "directoryRead" to OriginalStdioOp.READDIR, "directoryName" to OriginalStdioOp.DIRENT_NAME,
        "directoryFree" to OriginalStdioOp.FREE_DIRENT)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + 0.toByte())
    private fun path(path: Path) = ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path))
    private fun bytes(pointer: ManagedAddress) = (0 until pointer.cStringLength()).map { pointer.readWord8(it) }
    private fun context() = NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST)
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun original(name: String, stage: String = "post") =
        OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(stage), name)).single()
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun setup(): Path = Files.createTempDirectory(directory, "case-").also { base ->
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(base.resolve("file"), "unchanged")
        Files.createDirectory(base.resolve("sub")); Files.createDirectory(base.resolve("denied"))
        Files.createDirectory(NativeDirectoryOwner.bytesPath(NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray() + byteArrayOf(47,-1,110)))
        Files.createSymbolicLink(base.resolve("link"), Path.of("sub"))
        Files.createSymbolicLink(base.resolve("dangling"), Path.of("missing"))
        Files.setPosixFilePermissions(base.resolve("denied"), emptySet())
    }

    @Test fun genuineStreamsMatchNativeAtEveryFirstInstalledEntry() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest["unixUnit"])); assertEquals(true, manifest["privateRebuiltUnix"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalDirectoryStreamsAudit.hs", "test/haskell-fixtures/OriginalDirectoryStreamsFixtures.hs",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/unix-source.json") +
            listOf("pre", "post").flatMap { stage -> listOf("$prefix/$stage.json") + operations.keys.map { "$prefix/$stage-$it.audit.json" } }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        assertEquals(8L, oracle["pointerBytes"]); assertEquals(true, oracle["processCwdUnchanged"])
        val pathRows = oracle["pathRows"] as List<Map<String, Any?>>
        val fdRows = oracle["fdRows"] as List<Map<String, Any?>>
        val specialRows = oracle["specialRows"] as List<Map<String, Any?>>
        assertEquals(12, pathRows.size); assertEquals(5, fdRows.size); assertEquals(3, specialRows.size)
        val processDirectory = Files.readSymbolicLink(Path.of("/proc/self/cwd"))
        for (stage in listOf("pre", "post")) {
            for ((name, op) in operations) {
                val audit = json("$prefix/$stage-$name.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
                val evidence = ArrayCoreEvidence(CoreModules.reachable(source(stage), name), name)
                assertEquals(1, evidence.bindings.size)
                assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
                assertEquals(op, validate(original(name, stage)))
                assertEquals(manifest["unixUnit"], (((original(name, stage)[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
            }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, source(stage) + ("instrument" to true))
                val targets = operations.keys.associateWith { executable.entryTarget(it) }
                val stdio = Language.currentState().stdio
                var compiled = false
                fun invoke(name: String, vararg args: Any): Any? {
                    val target = targets.getValue(name)
                    if (compiled) valid(target)
                    val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result = Calls.target(target, arrayOf(0L, *args))
                    if (compiled) {
                        assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong(), "$stage/$backend/$name")
                        valid(target)
                    }
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    return result
                }
                fun drain(stream: ManagedAddress, seed: Long): List<Map<String, Any?>> {
                    val result = mutableListOf<Map<String, Any?>>()
                    repeat(64) {
                        val storage = ManagedAddress.fromAllocation(ManagedAllocation.mutable(24, 8))
                        storage.fill(24, 165)
                        val output = storage.plus(8)
                        output.writeAddressElementIndex(0, ManagedAddress.nullAddress())
                        stdio.setErrno(seed)
                        val status = invoke("directoryRead", stream, output)
                        val error = stdio.errno()
                        val entry = output.readAddressElementIndex(0)
                        val isNull = entry.sameLocation(ManagedAddress.nullAddress())
                        var name: List<Long>? = null
                        var freePreserved = true
                        if (!isNull) {
                            val pointer = invoke("directoryName", entry) as ManagedAddress
                            name = bytes(pointer)
                            assertEquals(0L, invoke("directoryFree", entry))
                            freePreserved = name == bytes(pointer)
                        }
                        result.add(mapOf("status" to status, "errno" to error, "null" to isNull, "name" to name,
                            "guardsIntact" to (0L..7L).all { storage.readWord8(it) == 165L && storage.readWord8(it + 16) == 165L }, "freePreserved" to freePreserved))
                        if (isNull) return result
                    }
                    fail<Unit>("Directory stream did not reach EOF")
                    return result
                }
                // Enumeration order is unspecified. Preserve every observation while
                // comparing entries by raw name, including the separate EOF row.
                fun compareReads(expected: Any?, actual: List<Map<String, Any?>>) {
                    val rows = expected as List<Map<String, Any?>>
                    fun keyed(values: List<Map<String, Any?>>) = values.associateBy { it["name"] }
                    assertEquals(rows.size, keyed(rows).size); assertEquals(actual.size, keyed(actual).size)
                    assertEquals(keyed(rows), keyed(actual))
                }
                fun checkOpened(expected: Map<String, Any?>, stream: ManagedAddress, openError: Long) {
                    assertEquals(expected["null"], stream.sameLocation(ManagedAddress.nullAddress()))
                    assertEquals(expected["errno"], openError)
                    if (expected["null"] == false) {
                        compareReads(expected["reads"], drain(stream, 0))
                        stdio.setErrno(9)
                        assertEquals(expected["closeStatus"], invoke("directoryClose", stream))
                        assertEquals(expected["closeErrno"], stdio.errno())
                    }
                }
                fun exercise() {
                    for (row in pathRows) {
                        val base = setup()
                        try {
                            assertEquals(0L, stdio.changeDirectory(path(base)))
                            val suffix = (row["path"] as List<Long>).map(Long::toByte).toByteArray()
                            val name = if (row["absolute"] == true) NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray() + byteArrayOf(47) + suffix else suffix
                            stdio.setErrno(9)
                            val stream = invoke("directoryOpen", address(name)) as ManagedAddress
                            checkOpened(row["result"] as Map<String, Any?>, stream, stdio.errno())
                        } finally { Files.setPosixFilePermissions(base.resolve("denied"), PosixFilePermissions.fromString("rwx------")) }
                    }
                    for (row in fdRows) {
                        val base = setup()
                        try {
                            val name = row["name"]
                            val fd = if (name == "invalid") -1L else stdio.open(path(if (name == "regular") base.resolve("file") else base), stdio.flagConstant(OriginalStdioOp.O_RDONLY), 0)
                            val alias = if (name == "alias") stdio.duplicate(fd) else null
                            if (name == "closed") assertEquals(0L, stdio.close(fd))
                            stdio.setErrno(9)
                            val stream = invoke("directoryFdOpen", fd) as ManagedAddress
                            val error = stdio.errno()
                            if (alias != null) assertEquals(0L, stdio.close(alias))
                            val preserved = if (stream.sameLocation(ManagedAddress.nullAddress()) && name == "regular") stdio.close(fd) == 0L else true
                            assertEquals(row["failureFdPreserved"], preserved)
                            checkOpened(row["result"] as Map<String, Any?>, stream, error)
                        } finally { Files.setPosixFilePermissions(base.resolve("denied"), PosixFilePermissions.fromString("rwx------")) }
                    }
                    for (row in specialRows) {
                        val base = setup()
                        val moved = base.resolveSibling(base.fileName.toString() + "-renamed")
                        try {
                            val stream = invoke("directoryOpen", path(if (row["name"] == "deleted") base.resolve("sub") else base)) as ManagedAddress
                            assertFalse(stream.sameLocation(ManagedAddress.nullAddress()))
                            if (row["name"] == "renamed") Files.move(base, moved)
                            if (row["name"] == "deleted") Files.delete(base.resolve("sub"))
                            compareReads(row["reads"], drain(stream, if (row["name"] == "sticky-eof") 9 else 0))
                            assertEquals(row["closeStatus"], invoke("directoryClose", stream))
                        } finally {
                            if (Files.exists(moved)) Files.move(moved, base)
                            Files.setPosixFilePermissions(base.resolve("denied"), PosixFilePermissions.fromString("rwx------"))
                        }
                    }
                    assertEquals(0, NativeFileProvider.current().directoryStreams.liveCount())
                }
                exercise()
                targets.values.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                compiled = true
                exercise()
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
        assertEquals(processDirectory, Files.readSymbolicLink(Path.of("/proc/self/cwd")))
    }

    @Test fun originalUnitCapiOwnerSafetyAndStoredOperandsStayAuthoritative() {
        for ((name, op) in operations) {
            val call = original(name)
            assertEquals(op, validate(call))
            for (unit in listOf("main", "ghc-internal", "unix-2.8.7.0-inplace", "unix-2.8.8.0-ABCD", "unix-2.8.8.0-460b:forged")) {
                val bad = copy(call) as MutableList<Any?>
                (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            val convention = if (op in listOf(OriginalStdioOp.OPENDIR, OriginalStdioOp.FDOPENDIR)) "ccall" else "capi"
            for ((key, value) in listOf("safety" to "safe", "convention" to convention, "arity" to 99L, "suppliedArity" to 99L)) {
                val bad = copy(call) as MutableList<Any?>
                ((bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                for (index in (call[2] as List<*>).indices) assertThrows(RuntimeFault::class.java) {
                    program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
                }
                val shadowed = OriginalStdioChecks.rawModule(call, source("post"))
                val body = (shadowed["bindings"] as List<Map<String, Any?>>).single()["expr"]
                val changed = OriginalStdioChecks.foreignCalls(body).single() as MutableList<Any?>
                changed[1] = listOf("var", "p0", (changed[1] as List<*>)[2])
                assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
            } }
        }
    }

    @Test fun invalidStateCannotOpenAdvanceOrCloseAnOriginalStream() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val service = NativeFileProvider.current().directoryStreams
            val stdio = Language.currentState().stdio
            val stream = stdio.openDirectory(path(directory))
            val output = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8,8))
            assertEquals(0L, service.read(stream, output))
            val entry = output.readAddressElementIndex(0)
            val beforeName = bytes(service.name(entry))
            stdio.setErrno(9)
            for ((name, op) in operations) {
                val target = program(language, backend, OriginalStdioChecks.rawModule(original(name), source("post"))).entryTarget("entry")
                val operands = when (op) {
                    OriginalStdioOp.OPENDIR -> arrayOf<Any>(path(directory))
                    OriginalStdioOp.FDOPENDIR -> arrayOf<Any>(-1L)
                    OriginalStdioOp.READDIR -> arrayOf<Any>(stream, output)
                    OriginalStdioOp.CLOSEDIR -> arrayOf<Any>(stream)
                    else -> arrayOf<Any>(entry)
                }
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *operands, 7L)) }
                assertEquals(9L, stdio.errno()); assertEquals(beforeName, bytes(service.name(entry)))
                assertEquals(1, service.liveCount())
            }
            assertEquals(0L, service.closeStream(stream))
        } }
    }
}
