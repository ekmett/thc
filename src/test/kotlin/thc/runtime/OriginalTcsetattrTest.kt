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
import org.junit.jupiter.api.BeforeEach
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
class OriginalTcsetattrTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-tcsetattr"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalTcsetattrAudit", "THC.InterfaceClosure")
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
        val path: String = try { Json.parse(line()) as String } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        fun observe(action: Long, echo: Long): List<Long> {
            input.write("[$action,$echo]\n"); input.flush()
            return Json.parse(line()) as List<Long>
        }
        fun reset() { input.write("[]\n"); input.flush(); check(Json.parse(line()) == emptyList<Any>()) }
        override fun close() {
            try { input.close() } finally {
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                output.close()
            }
        }
    }

    @BeforeEach fun verifyOriginalInputsAndArtifacts() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(true, manifest["supported"]); assertEquals(22L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalTcsetattrAudit.hs",
            "compiler/test-fixtures/OriginalTcsetattrNative.hs", "test/haskell-fixtures/OriginalTcsetattrFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/native/oracle") +
            listOf("pre", "post").map { "$prefix/$it/originalTcsetattr.audit.json" }, "$prefix/")
    }

    @Test fun originalPrivatePtyChangesMatchNativeBeforeAndAfterCompilation() {
        val recorded = json("$prefix/oracle.json")
        assertEquals(size().toLong(), recorded["size"])
        val rows = recorded["rows"] as List<List<Any?>>
        assertEquals(22, rows.size)
        val abi = StdioHostAbi.load()
        for (row in rows) {
            val native = row[3] as List<Long>
            assertEquals(2 * (size() + 16) + 2, native.size)
            for (block in native.drop(2).chunked(size() + 16))
                assertTrue((block.take(8) + block.takeLast(8)).all { it == 77L })
            val kind = row[0] as Long; val action = row[1] as Long
            assertEquals(if (kind == 0L && action in 0L..2L) 0L else -1L, native[0])
            assertEquals(when { kind == 2L -> abi.error(4); kind == 1L -> abi.notTerminal()
                action !in 0L..2L -> abi.error(5); else -> 0L }, native[1])
        }
        Oracle().use { oracle ->
            for (stage in listOf("pre", "post")) {
                val audit = json("$prefix/$stage/originalTcsetattr.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertEquals(listOf(OriginalStdioOp.TCSETATTR.symbol), (audit["foreignCalls"] as List<Map<*, *>>).map { it["symbol"] })
                val source = module(stage)
                assertEquals(OriginalStdioOp.TCSETATTR, validate(OriginalStdioChecks.foreignCalls(source).single()))
                for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                    val io = Language.currentState().stdio
                    val terminal = io.open(address(oracle.path), 2L or 0x100L, 0)
                    assertTrue(terminal >= 3)
                    val plain = directory.resolve("plain-$stage-$backend"); Files.writeString(plain, "data")
                    val regular = io.open(address(plain.toString()), 0, 0); assertTrue(regular >= 3)
                    val program = load(language, backend, CoreModules.reachable(source, "originalTcsetattr", true) + ("instrument" to true))
                    val entry = program.entryTarget("originalTcsetattr")
                    fun exercise(compiled: Boolean) {
                        for (row in rows) {
                            val kind = row[0] as Long; val action = row[1] as Long; val echo = row[2] as Long
                            val expected = oracle.observe(if (kind == 0L) action else -1L, echo)
                            val supplied = expected.drop(2).take(size() + 16).map { it.toByte() }.toByteArray()
                            val original = supplied.copyOf()
                            val descriptor = when (kind) { 0L -> terminal; 1L -> regular; else -> -1L }
                            try {
                                assertEquals(-1L, io.close(-1)); val sticky = io.errno()
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                val result = Calls.target(entry, arrayOf(0L, descriptor, action, ManagedAddress.fromByteArray(supplied).plus(8)))
                                val recordedStatus = row[3] as List<Long>
                                assertEquals(recordedStatus[0], result)
                                assertEquals(if (result == 0L) sticky else recordedStatus[1], io.errno())
                                if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                assertArrayEquals(original, supplied, "Const input and canaries must remain unchanged")
                                val observed = image(90)
                                assertEquals(0L, io.tcgetattr(terminal, ManagedAddress.fromByteArray(observed).plus(8)))
                                assertArrayEquals(expected.takeLast(size() + 16).map { it.toByte() }.toByteArray(), observed)
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.depth)
                            } finally { oracle.reset() }
                        }
                        // const struct termios accepts genuinely immutable byte storage too.
                        val expected = oracle.observe(0, 0)
                        val input = expected.drop(2).take(size() + 16)
                        val immutable = ManagedAddress.fromHex(input.joinToString("") { it.toString(16).padStart(2, '0') }).plus(8)
                        try { assertEquals(0L, Calls.target(entry, arrayOf(0L, terminal, 0L, immutable))) }
                        finally { oracle.reset() }
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

    @Test fun stateCIntsAndCompleteConstImageAreValidatedBeforeEffects() {
        val source = module("pre")
        val original = OriginalStdioChecks.foreignCalls(source).single()
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val target = load(language, backend, OriginalStdioChecks.rawModule(original, source)).entryTarget("entry")
            val io = Language.currentState().stdio
            val bytes = image(90); val valid = ManagedAddress.fromByteArray(bytes).plus(8)
            fun invoke(fd: Long, action: Long, address: ManagedAddress = valid, state: Any = Unit) =
                Calls.target(target, arrayOf(0L, fd, action, address, state))
            assertEquals(-1L, io.close(-1)); val sticky = io.errno()
            assertThrows(RuntimeFault::class.java) { invoke(-1, 0, state = 9L) }
            for (bad in listOf(Long.MIN_VALUE, 2147483648L)) {
                assertThrows(RuntimeFault::class.java) { invoke(bad, 0) }
                assertThrows(RuntimeFault::class.java) { invoke(-1, bad) }
            }
            val pointerCell = ManagedAddress.fromAllocation(PinnedMemory.allocate(size().toLong(), 8))
            pointerCell.writeAddressElementIndex(0, valid)
            for (bad in listOf(ManagedAddress.nullAddress(), valid.plus(9), pointerCell,
                ManagedAddress.fromByteArray(ByteArray(size() - 1))))
                assertThrows(RuntimeFault::class.java) { invoke(-1, 0, bad) }
            assertArrayEquals(image(90), bytes); assertEquals(sticky, io.errno())
            assertSame(valid, pointerCell.readAddressElementIndex(0))
            for (index in 0..3) assertThrows(RuntimeFault::class.java) {
                load(language, backend, OriginalStdioChecks.rawModule(original, source, index)) }
            for ((field, value) in listOf("convention" to "ccall", "safety" to "safe", "arity" to 3L)) {
                val changed = Json.parse(Json.stringify(original)) as List<Any?>
                ((changed[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[field] = value
                assertThrows(RuntimeFault::class.java) { validate(changed) }
            }
        } }
    }

    @Test fun writesFollowTheOriginalLeaseAcrossAliasesReuseAndContextDisposal() {
        Oracle().use { oracle ->
            val first = context(); val second = context()
            lateinit var resource: OpenedNativeFile
            try {
                entered(first) {
                    val io = Language.currentState().stdio
                    val fd = io.open(address(oracle.path), 2L or 0x100L, 0); assertTrue(fd >= 3)
                    val alias = io.duplicate(fd); assertTrue(alias >= 3)
                    assertEquals(0L, io.close(fd))
                    val file = directory.resolve("reused"); Files.writeString(file, "data")
                    assertEquals(fd, io.open(address(file.toString()), 0, 0))
                    val expected = oracle.observe(0, 0)
                    val bytes = expected.drop(2).take(size() + 16).map { it.toByte() }.toByteArray()
                    val original = bytes.copyOf(); val input = ManagedAddress.fromByteArray(bytes).plus(8)
                    try {
                        assertEquals(-1L, io.tcsetattr(fd, 0, input)); assertEquals(StdioHostAbi.load().notTerminal(), io.errno())
                        assertEquals(0L, io.tcsetattr(alias, 0, input))
                        val observed = image(90)
                        assertEquals(0L, io.tcgetattr(alias, ManagedAddress.fromByteArray(observed).plus(8)))
                        assertArrayEquals(expected.takeLast(size() + 16).map { it.toByte() }.toByteArray(), observed)
                        assertArrayEquals(original, bytes)
                    } finally { oracle.reset() }
                    resource = NativeFileProvider.current().openRaw(oracle.path.toByteArray() + byteArrayOf(0), 2 or 0x100, 0)
                }
                entered(second) { assertThrows(RuntimeFault::class.java) { resource.writeTermios(0, ByteArray(size())) } }
                entered(first) {
                    resource.close()
                    assertThrows(ClosedChannelException::class.java) { resource.writeTermios(0, ByteArray(size())) }
                    resource = NativeFileProvider.current().openRaw(oracle.path.toByteArray() + byteArrayOf(0), 2 or 0x100, 0)
                }
                first.close(); assertFalse(resource.isOpen); resource.close()
            } finally { first.close(); second.close() }
        }
    }
}
