// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Json
import thc.Language
import thc.NativeIO.StandardEndpoint
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.IdentityHashMap

/** Genuine installed __hscore_fstat FCallIds; context descriptors, never host fds. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalFstatTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-posix-stat"
    private val entries = listOf("originalFstat", "originalFstatErrno")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalPosixStatAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun path(value: Path) = ManagedAddress.fromByteArray(value.toString().toByteArray() + byteArrayOf(0))
    private fun field(address: ManagedAddress, operation: OriginalStdioOp) = PosixStat.execute(operation, address, 0)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
    }
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)

    @Test fun nativeOriginalObservationsMatchBothBackendsAndEveryFirstInstalledCall() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(true, manifest["supported"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalPosixStatAudit.hs",
            "compiler/test-fixtures/OriginalPosixStatNative.hs", "test/haskell-fixtures/OriginalPosixStatFixtures.hs",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> entries.map { "$prefix/$stage/$it.audit.json" } +
                listOf("$prefix/$stage/core/OriginalPosixStatAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val size = (oracle["size"] as Number).toInt()
        val rows = oracle["fstats"] as List<List<Any?>>
        assertEquals(listOf("initial", "resized", "chmod", "renamed", "unlinked", "invalid", "closed"), rows.map { it[0] })
        for (row in rows) {
            val success = row[0] !in listOf("invalid", "closed")
            assertEquals(if (success) 0L else -1L, (row[1] as Number).toLong())
            assertEquals(StdioHostAbi.load().error(4), (row[2] as Number).toLong())
            assertEquals(if (success) listOf(if (row[0] == "initial") 256L else 17L, 1L, 1L, 1L,
                if (row[0] in listOf("initial", "resized")) 384L else 256L) else emptyList<Long>(),
                (row[3] as List<Number>).map { it.toLong() })
            assertEquals(true, row[4])
        }
        for (stage in listOf("pre", "post")) for (name in entries) {
            val audit = json("$prefix/$stage/$name.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(module(stage), name) + ("instrument" to true)
            val fstats = OriginalStdioChecks.foreignCalls(linked).filter {
                (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] == "__hscore_fstat"
            }
            assertEquals(1, fstats.size)
            assertEquals(OriginalStdioOp.FSTAT, validate(fstats.single()))
            for (backend in listOf("ast", "bytecode"))
                NativeFileProvider.createContext(emptySet(), synchronousCompilation = true).use { context -> entered(context) { language ->
                    val state = Language.currentState(); val files = state.files; val stdio = state.stdio
                    val program = load(language, backend, linked); val entry = program.entryTarget(name)
                    var active = emptyList<RootCallTarget>()
                    fun exercise(compiled: Boolean) {
                        val original = directory.resolve("file"); val renamed = directory.resolve("renamed")
                        Files.write(original, ByteArray(256) { it.toByte() })
                        Files.setPosixFilePermissions(original, PosixFilePermissions.fromString("rw-------"))
                        val fd = files.open(path(original), 3); assertTrue(fd >= 3)
                        val alias = files.duplicate(fd); assertTrue(alias >= 3)
                        val identity = files.statImage(fd).let { ManagedAddress.fromByteArray(it) }.let {
                            field(it, OriginalStdioOp.ST_DEV) to field(it, OriginalStdioOp.ST_INO)
                        }
                        for (row in rows) {
                            when (row[0]) {
                                "resized" -> assertEquals(0L, files.setSize(fd, 17))
                                "chmod" -> Files.setPosixFilePermissions(original, PosixFilePermissions.fromString("r--------"))
                                "renamed" -> { Files.move(original, renamed); Files.write(original, byteArrayOf(42)) }
                                "unlinked" -> Files.delete(renamed)
                                "closed" -> { assertEquals(0L, files.close(alias)); assertEquals(0L, files.close(fd)) }
                            }
                            val bytes = ByteArray(size + 16) { 90 }
                            val address = ManagedAddress.fromByteArray(bytes).plus(8)
                            val source = if (row[0] == "invalid") -1L else if (row[0] == "renamed") alias else fd
                            assertEquals(-1L, stdio.close(-1))
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (compiled) active.forEach(::valid)
                            val expected = (row[if (name == "originalFstat") 1 else 2] as Number).toLong()
                            assertEquals(expected, Calls.target(entry, arrayOf(0L, source, address)), "$stage/$backend/$name/${row[0]}")
                            assertEquals((row[2] as Number).toLong(), stdio.errno())
                            if (compiled) {
                                assertEquals(before + active.size, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                assertEquals(active, targets(entry)); active.forEach(::valid)
                            }
                            assertEquals(true, row[4], "Native destination/canary observation")
                            if ((row[1] as Number).toLong() == 0L) {
                                val mode = field(address, OriginalStdioOp.ST_MODE)
                                val observed = listOf(field(address, OriginalStdioOp.ST_SIZE),
                                    PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(), mode),
                                    if (field(address, OriginalStdioOp.ST_DEV) == identity.first) 1L else 0L,
                                    if (field(address, OriginalStdioOp.ST_INO) == identity.second) 1L else 0L, mode and 511L)
                                assertEquals((row[3] as List<Number>).map { it.toLong() }, observed)
                                assertTrue((bytes.take(8) + bytes.drop(size + 8)).all { it == 90.toByte() })
                            } else assertTrue(bytes.all { it == 90.toByte() })
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                            assertEquals(0, language.handoffState.get().results.retainedReferences())
                        }
                        Files.delete(original)
                    }
                    exercise(false)
                    active = targets(entry)
                    assertEquals(2, active.size, "Exactly entry plus its original runRW lambda")
                    val binding = (linked["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
                    assertEquals(OriginalStdioChecks.nodes(binding["expr"]).count { it.firstOrNull() == "lam" }, active.size)
                    for (target in active) { target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target) }
                    exercise(true)
                } }
        }
    }

    private fun original(): List<Any?> = OriginalStdioChecks.foreignCalls(module("pre")).first {
        (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] == "__hscore_fstat"
    }
    private fun validate(call: List<Any?>): OriginalStdioOp? {
        val metadata = call[6] as Map<*, *>
        return CoreOriginalStdio.validate(metadata, (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
            call[3] as List<*>, metadata["rep"])
    }

    @Test fun stateAndFullWritableByteRegionPrecedeAnyObservationOrMutation() {
        val size = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0).toInt()
        for (backend in listOf("ast", "bytecode")) NativeFileProvider.createContext(StandardEndpoint.entries.toSet(), true).use { context ->
            entered(context) { language ->
                val state = Language.currentState(); val bytes = ByteArray(size + 2) { 90 }
                val program = load(language, backend, OriginalPosixStatTest().rawModule(original()))
                val entry = program.entryTarget("entry")
                val address = ManagedAddress.fromByteArray(bytes).plus(1)
                fun invoke(fd: Long, output: ManagedAddress, token: Any = Unit) = Calls.target(entry, arrayOf(0L, fd, output, token))
                assertEquals(-1L, state.stdio.close(-1)); val errno = state.stdio.errno()
                assertThrows(RuntimeFault::class.java) { invoke(1, address, 9L) }
                assertThrows(RuntimeFault::class.java) { invoke(1L shl 32, address) }
                for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(ByteArray(size - 1)),
                    ManagedAddress.fromHex("00".repeat(size)), address.plus(2)))
                    assertThrows(RuntimeFault::class.java) { invoke(1, bad) }
                val allocation = ManagedAllocation.mutable(size.toLong() + 16, 8)
                val pointer = ManagedAddress.fromAllocation(allocation)
                allocation.writeAddressByteOffset(8, address)
                assertThrows(RuntimeFault::class.java) { invoke(1, pointer) }
                assertSame(address, allocation.readAddressByteOffset(8))
                assertEquals(errno, state.stdio.errno()); assertTrue(bytes.all { it == 90.toByte() })
                assertEquals(-1L, invoke(-1, address)); assertTrue(bytes.all { it == 90.toByte() })
                assertEquals(StdioHostAbi.load().error(4), state.stdio.errno())
                for (fd in 0L..2L) {
                    assertEquals(0L, invoke(fd, address))
                    assertEquals(90, bytes.first().toInt()); assertEquals(90, bytes.last().toInt())
                }
                for (index in 0..2) assertThrows(RuntimeFault::class.java) {
                    load(language, backend, OriginalPosixStatTest().rawModule(original(), index))
                }
            }
        }
        Context.newBuilder("thc").build().use { context -> entered(context) {
            val bytes = ByteArray(size) { 90 }; val stdio = Language.currentState().stdio
            assertEquals(-1L, stdio.fstat(1, ManagedAddress.fromByteArray(bytes)))
            assertEquals(StdioHostAbi.load().error(7), stdio.errno())
            assertTrue(bytes.all { it == 90.toByte() })
        } }
    }

    @Test fun genuineDescriptorRejectsEveryChangedAbiAndDefinedOrMalformedHead() {
        val original = original(); assertEquals(OriginalStdioOp.FSTAT, validate(original))
        fun reject(action: (MutableList<Any?>, MutableMap<String, Any?>) -> Unit) {
            val call = copy(original) as MutableList<Any?>
            val descriptor = (call[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
            action(call, descriptor)
            assertThrows(RuntimeFault::class.java) { validate(call) }
        }
        for (key in listOf("schema", "arity", "suppliedArity")) for (value in listOf(null, true, 3.0, "3", 0L, 1L shl 32))
            reject { _, d -> d[key] = value }
        for ((key, value) in listOf("convention" to "capi", "safety" to "safe", "extra" to 0L)) reject { _, d -> d[key] = value }
        for ((key, value) in listOf("unit" to "base", "kind" to "dynamic", "isFunction" to false, "extra" to true))
            reject { _, d -> (d["target"] as MutableMap<String, Any?>)[key] = value }
        for (index in 0..2) {
            reject { call, _ -> (call[3] as MutableList<Any?>)[index] = true }
            reject { _, d -> (d["argumentReps"] as List<MutableMap<String, Any?>>)[index]["primReps"] = listOf("WordRep") }
            reject { _, d -> (d["argumentReps"] as List<MutableMap<String, Any?>>)[index]["aggregate"] = "unboxed-tuple" }
        }
        for (key in listOf("resultRep", "rep")) reject { call, d ->
            ((if (key == "rep") call[6] as MutableMap<String, Any?> else d)[key] as MutableMap<String, Any?>)["primReps"] = listOf("WordRep")
        }
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            for (head in listOf(listOf("var", 17L), listOf("var", "entry", mapOf("rep" to OriginalStdioFixtures.closure())))) {
                val bad = copy(OriginalPosixStatTest().rawModule(original)) as Map<String, Any?>
                (OriginalStdioChecks.foreignCalls(bad).single() as MutableList<Any?>)[1] = head
                assertThrows(RuntimeFault::class.java) { load(language, backend, bad) }
            }
        } }
    }
}
