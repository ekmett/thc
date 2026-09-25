// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.ContextProfile
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(60)
class OriginalTcgetattrTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-tcgetattr"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalTcgetattrAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun context() = NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST)
    private fun address(path: String) = ManagedAddress.fromByteArray(path.toByteArray() + byteArrayOf(0))
    private fun size() = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0).toInt()
    private fun image(fill: Int) = ByteArray(size() + 16) { index -> if (index in 8 until size() + 8) fill.toByte() else 77 }
    private fun <T> entered(context: Context, body: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
    }
    private fun load(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>,
        (call[6] as Map<*, *>)["rep"])
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }.filter { it.rootNode is GuestRoot }.forEach(::visit)
            result.add(target)
        }
        visit(entry); return result
    }
    private inner class Oracle : AutoCloseable {
        private val process = ProcessBuilder(File(root, "$prefix/native/oracle").absolutePath, "--serve")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = process.outputStream.bufferedWriter()
        private val output = process.inputStream.bufferedReader()
        private fun line() = CompletableFuture.supplyAsync { output.readLine() }.get(5, TimeUnit.SECONDS)
            ?: error("Native PTY oracle exited before its response")
        val path: String = try { Json.parse(line()) as String } catch (failure: Throwable) { close(); throw failure }
        fun observe(fill: Int): List<Long> {
            input.write("$fill\n"); input.flush()
            return Json.parse(line()) as List<Long>
        }
        override fun close() {
            try { input.close() } finally {
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                output.close()
            }
        }
    }

    @Test fun originalImagesMatchPrivatePtyAtInterpretedAndFirstInstalledEntries() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(true, manifest["supported"]); assertEquals(12L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalTcgetattrAudit.hs",
            "compiler/test-fixtures/OriginalTcgetattrNative.hs", "test/haskell-fixtures/OriginalTcgetattrFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/native/oracle") +
            listOf("pre", "post").map { "$prefix/$it/originalTcgetattr.audit.json" }, "$prefix/")
        val recorded = json("$prefix/oracle.json")
        assertEquals(size().toLong(), recorded["size"])
        val rows = recorded["rows"] as List<List<Any?>>
        assertEquals(12, rows.size)
        val abi = StdioHostAbi.load()
        for ((index, row) in rows.withIndex()) {
            assertEquals((index / 4).toLong(), row[0]); assertEquals(listOf(0L, 90L, 165L, 255L)[index % 4], row[1])
            val observation = row[2] as List<Long>
            assertEquals(size() + 18, observation.size)
            assertTrue((observation.drop(2).take(8) + observation.takeLast(8)).all { it == 77L })
            if (row[0] == 0L) assertEquals(listOf(0L, 0L), observation.take(2))
            else {
                assertEquals(listOf(-1L, if (row[0] == 1L) abi.notTerminal() else abi.error(4)), observation.take(2))
                assertArrayEquals(image((row[1] as Long).toInt()), observation.drop(2).map { it.toByte() }.toByteArray())
            }
        }
        Oracle().use { oracle ->
            for (stage in listOf("pre", "post")) {
                val audit = json("$prefix/$stage/originalTcgetattr.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertEquals(listOf(OriginalStdioOp.TCGETATTR.symbol), (audit["foreignCalls"] as List<Map<*, *>>).map { it["symbol"] })
                val source = module(stage)
                assertEquals(OriginalStdioOp.TCGETATTR, validate(OriginalStdioChecks.foreignCalls(source).single()))
                for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                    val io = Language.currentState().stdio
                    val fd = io.open(address(oracle.path), 2L or 0x100L, 0) // Linux O_RDWR | O_NOCTTY.
                    assertTrue(fd >= 3)
                    val plain = directory.resolve("plain-$stage-$backend"); Files.writeString(plain, "data")
                    val regular = io.open(address(plain.toString()), 0, 0); assertTrue(regular >= 3)
                    val program = load(language, backend, CoreModules.reachable(source, "originalTcgetattr", true) + ("instrument" to true))
                    val entry = program.entryTarget("originalTcgetattr")
                    fun exercise(compiled: Boolean) {
                        for (fill in listOf(0, 90, 165, 255)) for (descriptor in listOf(fd, regular, -1L)) {
                            val native = if (descriptor == fd) oracle.observe(fill) else null
                            val bytes = image(fill)
                            assertEquals(-1L, io.close(-1)); val sticky = io.errno()
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val result = Calls.target(entry, arrayOf(0L, descriptor, ManagedAddress.fromByteArray(bytes).plus(8)))
                            if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            if (native != null) {
                                assertEquals(0L, result); assertEquals(listOf(0L, 0L), native.take(2))
                                assertArrayEquals(native.drop(2).map { it.toByte() }.toByteArray(), bytes)
                                assertEquals(sticky, io.errno())
                            } else {
                                assertEquals(-1L, result); assertEquals(if (descriptor == regular) abi.notTerminal() else abi.error(4), io.errno())
                                assertArrayEquals(image(fill), bytes)
                            }
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                    }
                    exercise(false)
                    val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    targets(entry).forEach {
                        cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                        assertEquals(true, cls.getMethod("isValidLastTier").invoke(it))
                    }
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode", cls).invoke(runtime, entry)
                    exercise(true)
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                } }
            }
        }
    }

    @Test fun preflightStateAndExactForeignProofsGuardTheWholeImage() {
        val source = module("pre")
        val original = OriginalStdioChecks.foreignCalls(source).single()
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val target = load(language, backend, OriginalStdioChecks.rawModule(original, source)).entryTarget("entry")
            val io = Language.currentState().stdio
            val bytes = image(90); val valid = ManagedAddress.fromByteArray(bytes).plus(8)
            assertEquals(-1L, io.close(-1)); val sticky = io.errno()
            assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, -1L, valid, 9L)) }
            for (bad in listOf(Long.MIN_VALUE, 2147483648L))
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, bad, valid, Unit)) }
            val pointerCell = ManagedAddress.fromAllocation(PinnedMemory.allocate(size().toLong(), 8))
            pointerCell.writeAddressElementIndex(0, valid)
            for (bad in listOf(ManagedAddress.nullAddress(), valid.plus(9), pointerCell,
                ManagedAddress.fromByteArray(ByteArray(size() - 1)), ManagedAddress.fromHex("00".repeat(size()))))
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, -1L, bad, Unit)) }
            assertArrayEquals(image(90), bytes); assertEquals(sticky, io.errno())
            assertSame(valid, pointerCell.readAddressElementIndex(0))
            for (index in 0..2) assertThrows(RuntimeFault::class.java) {
                load(language, backend, OriginalStdioChecks.rawModule(original, source, index)) }
            for ((field, value) in listOf("convention" to "ccall", "safety" to "safe", "arity" to 2L)) {
                val changed = Json.parse(Json.stringify(original)) as List<Any?>
                ((changed[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[field] = value
                assertThrows(RuntimeFault::class.java) { validate(changed) }
            }
        } }
    }

    @Test fun descriptorAliasesReuseContextAndDisposalKeepTheirOriginalResource() {
        Context.create("thc").use { ordinary -> entered(ordinary) {
            val bytes = image(90)
            val io = Language.currentState().stdio
            assertEquals(-1L, io.tcgetattr(0, ManagedAddress.fromByteArray(bytes).plus(8)))
            assertEquals(StdioHostAbi.load().error(7), io.errno())
            assertArrayEquals(image(90), bytes, "Embedding streams grant no native terminal authority")
        } }
        Oracle().use { oracle ->
            val first = context(); val second = context()
            lateinit var resource: OpenedNativeFile
            try {
                entered(first) {
                    val io = Language.currentState().stdio
                    val fd = io.open(address(oracle.path), 2L or 0x100L, 0)
                    assertTrue(fd >= 3)
                    val alias = io.duplicate(fd); assertTrue(alias >= 3)
                    assertEquals(0L, io.close(fd))
                    val file = directory.resolve("reused"); Files.writeString(file, "data")
                    assertEquals(fd, io.open(address(file.toString()), 0, 0))
                    val bytes = image(165); val destination = ManagedAddress.fromByteArray(bytes).plus(8)
                    assertEquals(-1L, io.tcgetattr(fd, destination)); assertEquals(StdioHostAbi.load().notTerminal(), io.errno())
                    assertArrayEquals(image(165), bytes)
                    assertEquals(0L, io.tcgetattr(alias, destination))
                    assertArrayEquals(oracle.observe(165).drop(2).map { it.toByte() }.toByteArray(), bytes)
                    resource = NativeFileProvider.current().openRaw(oracle.path.toByteArray() + byteArrayOf(0), 2 or 0x100, 0)
                }
                entered(second) { assertThrows(RuntimeFault::class.java) { resource.readTermios(ByteArray(size())) } }
                entered(first) {
                    resource.close()
                    assertThrows(ClosedChannelException::class.java) { resource.readTermios(ByteArray(size())) }
                    resource = NativeFileProvider.current().openRaw(oracle.path.toByteArray() + byteArrayOf(0), 2 or 0x100, 0)
                }
                first.close(); assertFalse(resource.isOpen); resource.close()
            } finally { first.close(); second.close() }
        }
    }
}
