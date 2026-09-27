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
class OriginalPathStatTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-path-stat"
    private val operations = linkedMapOf("pathStat" to OriginalStdioOp.STAT,
        "pathLstat" to OriginalStdioOp.LSTAT, "unixPathLstat" to OriginalStdioOp.UNIX_LSTAT)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun cstring(value: String) = ManagedAddress.fromByteArray(value.toByteArray() + byteArrayOf(0))
    private fun field(address: ManagedAddress, operation: OriginalStdioOp) = PosixStat.execute(operation, address, 0)
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
    private fun relativePath(bytes: ByteArray): ManagedAddress {
        // The fixed native filesystem keeps its context CWD. Resolve the test
        // directory relative to that CWD while retaining raw bytes and ./...
        val prefix = if (bytes.isEmpty()) byteArrayOf() else
            (Path.of(Language.currentState().env.currentWorkingDirectory.path)
                .relativize(directory).toString() + "/").toByteArray()
        return ManagedAddress.fromByteArray(prefix + bytes + byteArrayOf(0))
    }

    @Test fun originalNativePathImagesMatchBothBackendsAndFirstInstalledCalls() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(operations.keys.toList(), manifest["entries"])
        assertTrue(isOriginalUnixUnit(manifest["unixUnit"]))
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalPathStatAudit.hs",
            "test/haskell-fixtures/OriginalPathStatFixtures.hs", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage ->
                listOf("$prefix/$stage.json") + operations.keys.map { "$prefix/$stage-$it.audit.json" }
            }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val size = (oracle["size"] as Number).toInt()
        assertEquals(size.toLong(), field(ManagedAddress.nullAddress(), OriginalStdioOp.SIZEOF_STAT))
        val rows = oracle["rows"] as List<Map<String, Any?>>
        assertEquals(operations.keys.flatMap { entry ->
            listOf("file", "directory", "link", "dangling", "missing", "empty", "not-directory", "relative", "raw-link")
                .map { entry to it }
        }, rows.map { it["entry"] to it["name"] })
        val targetPath = directory.resolve("target")
        Files.write(targetPath, ByteArray(32) { it.toByte() })
        Files.setPosixFilePermissions(targetPath, PosixFilePermissions.fromString("rw-r-----"))
        val sub = Files.createDirectory(directory.resolve("sub"))
        Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwxr-x---"))
        Files.createSymbolicLink(directory.resolve("link"), Path.of("target"))
        Files.createSymbolicLink(directory.resolve("dangling"), Path.of("absent-target"))
        // URI decoding preserves the actual Unix 0xff byte; no replacement-character filename.
        Files.createSymbolicLink(Path.of(URI(directory.toUri().toASCIIString() + "%FFn")), Path.of("target"))
        val device = (Files.getAttribute(targetPath, "unix:dev") as Number).toLong()
        val inode = (Files.getAttribute(targetPath, "unix:ino") as Number).toLong()
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
            if (operation == OriginalStdioOp.UNIX_LSTAT)
                assertEquals(manifest["unixUnit"], (((original(name, stage)[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"])
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget(name)
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    for (row in rows.filter { it["entry"] == name }) {
                        val bytes = ByteArray(size + 16) { 90 }
                        val destination = ManagedAddress.fromByteArray(bytes).plus(8)
                        val path = relativePath((row["path"] as List<Long>).map { it.toByte() }.toByteArray())
                        assertEquals(-1L, stdio.close(-1))
                        val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (compiled) valid(entry)
                        assertEquals(row["status"], Calls.target(entry, arrayOf(0L, path, destination)), "$stage/$backend/$name/" + row["name"])
                        assertEquals(row["errno"], stdio.errno())
                        if (compiled) {
                            assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                            valid(entry)
                        }
                        assertEquals(true, row["guardsIntact"])
                        if (row["status"] == 0L) {
                            val mode = field(destination, OriginalStdioOp.ST_MODE)
                            val directory = PosixStat.execute(OriginalStdioOp.IS_DIR, ManagedAddress.nullAddress(), mode) != 0L
                            val values = listOf(if (directory) -1L else field(destination, OriginalStdioOp.ST_SIZE), mode and 65535L,
                                if (field(destination, OriginalStdioOp.ST_DEV) == device) 1L else 0L,
                                if (field(destination, OriginalStdioOp.ST_INO) == inode) 1L else 0L)
                            assertEquals(row["values"], values)
                            assertTrue((bytes.take(8) + bytes.drop(size + 8)).all { it == 90.toByte() })
                        } else assertTrue(bytes.all { it == 90.toByte() })
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
            for ((key, value) in listOf("safety" to "safe", "convention" to if (operation == OriginalStdioOp.UNIX_LSTAT) "ccall" else "capi",
                "arity" to 2L, "suppliedArity" to 2L)) {
                val bad = copy(call) as MutableList<Any?>
                ((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            for (unit in listOf("main", "unix-2.8.7.0-inplace", "unix-2.8.8.0-ABCD", "unix-2.8.8.0-nothex",
                if (operation == OriginalStdioOp.UNIX_LSTAT) "ghc-internal" else "unix-2.8.8.0-inplace")) {
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

    @Test fun installedLstatWrapperMustEncodeItsExactOwner() {
        val call = original("unixPathLstat")
        fun label(suffix: String) = "ghczuwrapperZC2ZCunixzm2zi8zi8zi0zm${suffix}ZCSystemziPosixziFilesziPosixStringZClstat"
        fun declaration(owner: String, symbol: String): MutableList<Any?> = (copy(call) as MutableList<Any?>).also {
            val target = (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)
            target["unit"] = owner; target["symbol"] = symbol
        }
        for (suffix in listOf("inplace", "460b", "deadbeef")) {
            val accepted = declaration("unix-2.8.8.0-$suffix", label(suffix))
            assertEquals(OriginalStdioOp.UNIX_LSTAT, validate(accepted))
            CoreOriginalStdio.validateHeads(accepted)
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                program(language, backend, raw(accepted))
                val shadowed = raw(accepted)
                val boundCall = OriginalStdioChecks.foreignCalls(shadowed).single() as MutableList<Any?>
                boundCall[1] = listOf("var", "p0", (boundCall[1] as List<*>)[2])
                assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
                val malformed = raw(accepted)
                val malformedCall = OriginalStdioChecks.foreignCalls(malformed).single() as MutableList<Any?>
                malformedCall[1] = listOf("var", 17L, (malformedCall[1] as List<*>)[2])
                assertThrows(RuntimeFault::class.java) { program(language, backend, malformed) }
            } }
            val otherSuffix = if (suffix == "inplace") "460b" else "inplace"
            assertThrows(RuntimeFault::class.java) { validate(declaration("unix-2.8.8.0-$suffix", label(otherSuffix))) }
        }
        for (badSymbol in listOf(label("460B"), label("nothex"), label(""),
            label("460b").replace("ZC2ZC", "ZC3ZC"), label("460b").replace("PosixString", "ByteString"),
            label("460b").replace("ZClstat", "ZCstat"), label("460b").replace("2zi8zi8zi0", "2zi8zi7zi0"))) {
            val rejected = declaration("unix-2.8.8.0-460b", badSymbol)
            assertNull(validate(rejected))
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                assertThrows(RuntimeFault::class.java) { program(language, backend, raw(rejected)) }
            } }
        }
        for (operation in OriginalStdioOp.entries.filter { it.waitStatus }) {
            assertFalse(operation.acceptsUnit("unix-2.8.8.0-460b"))
            assertFalse(operation.matchesSymbol(operation.symbol.replace("zminplaceZC", "zm460bZC")))
        }
    }

    @Test fun stateAndCompleteDestinationValidationPrecedePathObservation() {
        val size = field(ManagedAddress.nullAddress(), OriginalStdioOp.SIZEOF_STAT).toInt()
        for (name in operations.keys) for (backend in listOf("ast", "bytecode"))
            context().use { context -> entered(context) { language ->
                val executable = program(language, backend, raw(original(name)))
                val entry = executable.entryTarget("entry")
                val stdio = Language.currentState().stdio
                val bytes = ByteArray(size + 16) { 90 }
                val destination = ManagedAddress.fromByteArray(bytes).plus(8)
                fun invoke(output: ManagedAddress, token: Any = Unit) = Calls.target(entry, arrayOf(0L, relativePath("missing".toByteArray()), output, token))
                assertEquals(-1L, stdio.close(-1)); val prior = stdio.errno()
                assertThrows(RuntimeFault::class.java) { invoke(destination, 9L) }
                for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(ByteArray(size - 1)),
                    ManagedAddress.fromHex("00".repeat(size)), destination.plus(9)))
                    assertThrows(RuntimeFault::class.java) { invoke(bad) }
                val allocation = ManagedAllocation.mutable(size.toLong() + 16, 8)
                val pointer = ManagedAddress.fromAllocation(allocation)
                allocation.writeAddressByteOffset(8, destination)
                assertThrows(RuntimeFault::class.java) { invoke(pointer) }
                assertSame(destination, allocation.readAddressByteOffset(8))
                assertEquals(prior, stdio.errno()); assertTrue(bytes.all { it == 90.toByte() })
                assertEquals(-1L, invoke(destination)); assertEquals(StdioHostAbi.load().error(1), stdio.errno())
                assertTrue(bytes.all { it == 90.toByte() }); released(language)
            } }
        Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            val bytes = ByteArray(size) { 90 }
            val executable = program(language, "ast", raw(original("pathStat")))
            assertEquals(-1L, Calls.target(executable.entryTarget("entry"),
                arrayOf(0L, cstring(directory.toString()), ManagedAddress.fromByteArray(bytes), Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
            assertTrue(bytes.all { it == 90.toByte() })
        } }
    }

    @Test fun ownedNativeDestinationAliasesKeepContextAndLifetimeChecks() {
        val size = field(ManagedAddress.nullAddress(), OriginalStdioOp.SIZEOF_STAT)
        Files.writeString(directory.resolve("target"), "owned")
        for (backend in listOf("ast", "bytecode")) context().use { first -> entered(first) { language ->
            val state = Language.currentState()
            val base = state.nativeAllocations.malloc(size + 16)
            val alias = base.plus(8)
            val entry = program(language, backend, raw(original("pathStat"))).entryTarget("entry")
            try {
                base.fill(size + 16, 90)
                assertEquals(0L, Calls.target(entry, arrayOf(0L, relativePath("target".toByteArray()), alias, Unit)))
                assertEquals(5L, field(alias, OriginalStdioOp.ST_SIZE))
                for (index in 0L..7L) { assertEquals(90L, base.readWord8(index)); assertEquals(90L, base.readWord8(size + 8 + index)) }
                context().use { second -> entered(second) { other ->
                    val foreign = program(other, backend, raw(original("pathStat"))).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(foreign, arrayOf(0L, cstring(directory.resolve("target").toString()), alias, Unit))
                    }
                    released(other)
                } }
            } finally { state.nativeAllocations.free(base) }
            assertThrows(RuntimeFault::class.java) { Calls.target(entry, arrayOf(0L, relativePath("target".toByteArray()), alias, Unit)) }
            released(language)
        } }
    }
}
