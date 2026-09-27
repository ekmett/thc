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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalDirectoryPathsTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-directory-paths"
    private val operations = linkedMapOf("pathRemoveDirectory" to OriginalStdioOp.RMDIR, "executableReadlink" to OriginalStdioOp.READLINK)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + 0.toByte())
    private fun address(value: String) = address(value.toByteArray())
    private fun path(path: Path) = ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path))
    private fun raw(base: Path, name: ByteArray) = NativeDirectoryOwner.bytesPath(NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray() + byteArrayOf(47) + name)
    private fun bytes(row: Map<String, Any?>, key: String) = (row[key] as List<Long>).map(Long::toByte).toByteArray()
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
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun setup(): Path = Files.createTempDirectory(directory, "case-").also { base ->
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(base.resolve("file"), "unchanged")
        for (name in listOf("empty", "nonempty", "denied")) Files.createDirectory(base.resolve(name))
        Files.createDirectory(raw(base, byteArrayOf(-1,110)))
        Files.writeString(base.resolve("nonempty/file"), "unchanged")
        Files.createDirectory(base.resolve("denied/child"))
        Files.createSymbolicLink(base.resolve("link"), Path.of("empty"))
        Files.createSymbolicLink(base.resolve("dangling"), Path.of("missing"))
        Files.createSymbolicLink(base.resolve("raw-link"), Path.of("/").relativize(NativeDirectoryOwner.bytesPath(byteArrayOf(47,-1,110))))
        Files.createSymbolicLink(base.resolve("absolute-link"), Path.of("/thc-native-readlink"))
        Files.setPosixFilePermissions(base.resolve("denied"), emptySet())
    }
    private fun restorePermissions(base: Path) = Files.setPosixFilePermissions(base.resolve("denied"), PosixFilePermissions.fromString("rwx------"))
    private fun remaining(base: Path): List<List<Long>> =
        (listOf("empty", "nonempty", "file", "link", "dangling", "raw-link", "absolute-link", "denied", "denied/child")
            .map { it.toByteArray() } + listOf(byteArrayOf(-1,110))).filter { Files.exists(raw(base, it), LinkOption.NOFOLLOW_LINKS) }
            .map { name -> name.map { it.toLong() and 255 } }

    @Test fun genuineInstalledOwnersMatchNativeAndEveryFirstInstalledEntry() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertTrue(isOriginalUnixUnit(manifest["unixUnit"])); assertEquals("ghc-internal", manifest["readlinkUnit"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalDirectoryPathsAudit.hs",
            "test/haskell-fixtures/OriginalDirectoryPathsFixtures.hs", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") + listOf("pre", "post").flatMap { stage ->
            listOf("$prefix/$stage.json") + operations.keys.map { "$prefix/$stage-$it.audit.json" } }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val removeRows = oracle["removeRows"] as List<Map<String, Any?>>
        val readRows = oracle["readRows"] as List<Map<String, Any?>>
        assertEquals(15, removeRows.size); assertEquals(12, readRows.size)
        assertEquals(15, removeRows.map { it["name"] }.toSet().size); assertEquals(12, readRows.map { it["name"] }.toSet().size)
        assertEquals(true, oracle["processCwdUnchanged"])
        val processDirectory = Files.readSymbolicLink(Path.of("/proc/self/cwd"))
        for (stage in listOf("pre", "post")) for ((name, operation) in operations) {
            val audit = json("$prefix/$stage-$name.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(source(stage), name) + ("instrument" to true)
            val evidence = ArrayCoreEvidence(linked, name)
            assertEquals(1, evidence.bindings.size); assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
            assertEquals(operation, validate(original(name, stage)))
            assertEquals(if (operation == OriginalStdioOp.RMDIR) manifest["unixUnit"] else "ghc-internal",
                (((original(name, stage)[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget(name)
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    for (row in if (operation == OriginalStdioOp.RMDIR) removeRows else readRows) {
                        val base = setup()
                        try {
                            assertEquals(0L, stdio.changeDirectory(path(base)))
                            val suffix = bytes(row,"path")
                            val argument = if (row["absolute"] == true) address(NativeDirectoryOwner.pathBytes(base).dropLast(1).toByteArray() + byteArrayOf(47) + suffix) else address(suffix)
                            val capacity = (row["capacity"] as? Long ?: 64L).toInt()
                            val image = ByteArray(capacity + 16) { 90 }
                            stdio.setErrno(9)
                            if (compiled) valid(entry)
                            val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val args = if (operation == OriginalStdioOp.RMDIR) arrayOf<Any>(0L, argument)
                                else arrayOf<Any>(0L, argument, ManagedAddress.fromByteArray(image).plus(8), capacity.toLong())
                            val result = Calls.target(entry, args)
                            assertEquals(row["status"], result, "$stage/$backend/$name/${row["name"]}")
                            assertEquals(row["errno"], stdio.errno())
                            if (compiled) {
                                assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            restorePermissions(base)
                            if (operation == OriginalStdioOp.RMDIR) assertEquals(row["remaining"], remaining(base))
                            else assertArrayEquals(bytes(row,"image"), image)
                            assertEquals("unchanged", Files.readString(base.resolve("file")))
                            assertEquals("unchanged", Files.readString(base.resolve("nonempty/file")))
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                        } finally { restorePermissions(base) }
                    }
                }
                exercise(false)
                entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true)
                valid(entry); exercise(true)
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
        assertEquals(processDirectory, Files.readSymbolicLink(Path.of("/proc/self/cwd")))
    }

    @Test fun originalPackageSafetyResultAndOperandGuardsRemainRequired() {
        for ((name, operation) in operations) {
            val call = original(name)
            assertEquals(operation, validate(call))
            val units = listOf("main", "unix-2.8.7.0-inplace", "unix-2.8.8.0-ABCD", "unix-2.8.8.0-460b:forged", "ghc-internal:forged") +
                if (operation == OriginalStdioOp.RMDIR) listOf("ghc-internal") else emptyList()
            for (unit in units) {
                val bad = copy(call) as MutableList<Any?>
                (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for ((key, value) in listOf("safety" to "safe", "convention" to "capi", "arity" to 99L, "suppliedArity" to 99L)) {
                val bad = copy(call) as MutableList<Any?>
                ((bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            val wider = copy(call) as MutableList<Any?>
            val meta = wider[6] as MutableMap<String, Any?>
            for (rep in listOf(meta["rep"], (meta["foreignCall"] as Map<*, *>)["resultRep"])) {
                val proof = rep as MutableMap<String, Any?>
                proof["primReps"] = listOf("Int64Rep")
                ((proof["components"] as List<*>)[1] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
            }
            assertThrows(RuntimeFault::class.java) { validate(wider) }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                for (index in (call[2] as List<*>).indices) assertThrows(RuntimeFault::class.java) {
                    program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
                }
                val module = OriginalStdioChecks.rawModule(call, source("post"))
                val body = (module["bindings"] as List<Map<String, Any?>>).single()["expr"]
                val changed = OriginalStdioChecks.foreignCalls(body).single() as MutableList<Any?>
                changed[1] = listOf("var", "p0", (changed[1] as List<*>)[2])
                assertThrows(RuntimeFault::class.java) { program(language, backend, module) }
            } }
        }
    }

    @Test fun invalidStateAndRenamedCwdPreserveNamespaceAuthority() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val base = setup(); val moved = base.resolveSibling(base.fileName.toString() + "-moved")
            val stdio = Language.currentState().stdio
            try {
                assertEquals(0L, stdio.changeDirectory(path(base)))
                val remove = program(language, backend, OriginalStdioChecks.rawModule(original("pathRemoveDirectory"), source("post"))).entryTarget("entry")
                val read = program(language, backend, OriginalStdioChecks.rawModule(original("executableReadlink"), source("post"))).entryTarget("entry")
                val image = ByteArray(32) { 90 }; val output = ManagedAddress.fromByteArray(image).plus(8)
                stdio.setErrno(9)
                assertThrows(RuntimeFault::class.java) { Calls.target(remove, arrayOf(0L, address("empty"), 7L)) }
                assertThrows(RuntimeFault::class.java) { Calls.target(read, arrayOf(0L, address("link"), output, 16L, 7L)) }
                assertTrue(Files.isDirectory(base.resolve("empty"))); assertTrue(image.all { it == 90.toByte() }); assertEquals(9L, stdio.errno())
                Files.move(base,moved); Files.createDirectory(base); Files.createDirectory(base.resolve("empty"))
                assertEquals(0L, Calls.target(remove, arrayOf(0L, address("empty"), Unit)))
                assertFalse(Files.exists(moved.resolve("empty"))); assertTrue(Files.isDirectory(base.resolve("empty")))
                assertEquals(5L, Calls.target(read, arrayOf(0L, address("link"), output, 16L, Unit)))
                assertArrayEquals("empty".toByteArray(), image.copyOfRange(8,13)); assertEquals(90.toByte(), image[13])
            } finally {
                if (Files.exists(moved)) restorePermissions(moved) else restorePermissions(base)
            }
        } }
    }
}
