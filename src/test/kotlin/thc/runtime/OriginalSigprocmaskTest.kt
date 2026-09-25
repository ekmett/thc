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
import thc.ContextProfile
import thc.CoreModules
import thc.Json
import thc.Language
import thc.withContextProfile
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(90)
class OriginalSigprocmaskTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-sigprocmask"
    private val nil get() = ManagedAddress.nullAddress()
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalSigprocmaskAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun context() = Context.newBuilder("thc").allowNativeAccess(true)
        .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build()
    private fun size() = TermiosImage.scalar(OriginalStdioOp.SIZEOF_SIGSET, nil, 0).toInt()
    private fun constant(operation: OriginalStdioOp) = TermiosImage.scalar(operation, nil, 0)
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes)
    private fun platform(body: () -> Unit) {
        val task = FutureTask { body() }
        Thread.ofPlatform().name("thc-sigttou-control").start(task)
        task.get(75, TimeUnit.SECONDS)
    }
    private fun <T> entered(context: Context, body: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
    }
    private fun load(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
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

    @BeforeEach fun verifyFixture() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(true, manifest["supported"]); assertEquals(8L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalSigprocmaskAudit.hs",
            "compiler/test-fixtures/OriginalSigprocmaskNative.hs", "test/haskell-fixtures/OriginalSigprocmaskFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/native/oracle") +
            listOf("pre", "post").map { "$prefix/$it/originalSigprocmask.audit.json" }, "$prefix/")
    }

    private fun snapshot(service: ManagedSignalMask): ByteArray = ByteArray(size()).also {
        assertEquals(0L, service.call(-1, nil, address(it))) // NULL set ignores invalid how.
    }
    private fun token(signal: Long): ByteArray = ByteArray(size()).also {
        val stdio = Language.currentState().stdio
        assertEquals(0L, SigsetImage.execute(OriginalStdioOp.SIGADDSET, address(it), signal, stdio))
    }
    private fun toggle(bytes: ByteArray, bit: ByteArray) = bytes.indices.forEach { i ->
        bytes[i] = (bytes[i].toInt() xor bit[i].toInt()).toByte()
    }

    @Test fun originalNativeSequenceMatchesBothBackendsAndFirstInstalledEntries() = platform {
        val rows = json("$prefix/oracle.json")["rows"] as List<List<Any?>>
        assertEquals(8, rows.size)
        val before = listOf(0L, 0L, 0L, 1L, 1L, 0L, 1L, 1L)
        val after = listOf(0L, 0L, 1L, 1L, 0L, 1L, 1L, 0L)
        for ((index, row) in rows.withIndex()) {
            assertEquals(index.toLong(), row[0]); assertEquals(if (index == 6) -1L else 0L, row[1])
            assertEquals(if (index == 6) StdioHostAbi.load().error(5) else 0L, row[2])
            assertEquals(before[index], row[3]); assertEquals(after[index], row[4])
            assertEquals(listOf(true, true, true), row.drop(5))
        }
        for (stage in listOf("pre", "post")) {
            val audit = json("$prefix/$stage/originalSigprocmask.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            assertEquals(listOf(OriginalStdioOp.SIGPROCMASK.symbol), (audit["foreignCalls"] as List<Map<*, *>>).map { it["symbol"] })
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val service = Language.currentState().signalMask
                val baseline = snapshot(service)
                val bit = token(constant(OriginalStdioOp.SIGTTOU))
                val normal = baseline.copyOf().also { bytes -> bytes.indices.forEach { i ->
                    bytes[i] = (bytes[i].toInt() and bit[i].toInt().inv()).toByte()
                } }
                val setmask = constant(OriginalStdioOp.SIG_SETMASK)
                val block = constant(OriginalStdioOp.SIG_BLOCK)
                val program = load(language, backend, CoreModules.reachable(module(stage), "originalSigprocmask", true) + ("instrument" to true))
                val entry = program.entryTarget("originalSigprocmask")
                try {
                    fun exercise(compiled: Boolean) {
                        assertEquals(0L, service.call(setmask, address(normal), nil))
                        val changed = normal.copyOf().also { toggle(it, bit) }
                        val calls = listOf(Triple(-1L, nil, true), Triple(-1L, nil, false),
                            Triple(block, address(bit), true), Triple(block, address(bit), true),
                            Triple(1L, address(bit), true), // Selected Linux SIG_UNBLOCK.
                            Triple(setmask, address(changed), true), Triple(-1L, address(bit), true),
                            Triple(setmask, address(normal), true))
                        for ((index, call) in calls.withIndex()) {
                            val current = snapshot(service)
                            val bytes = ByteArray(size() + 16) { if (it in 8 until size() + 8) 165.toByte() else 77 }
                            val old = if (call.third) address(bytes).plus(8) else nil
                            val io = Language.currentState().stdio
                            assertEquals(-1L, io.close(-1)); val sticky = io.errno()
                            val count = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(rows[index][1], Calls.target(entry, arrayOf(0L, call.first, call.second, old)))
                            if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > count)
                            assertEquals(if (index == 6) rows[index][2] else sticky, io.errno())
                            val expected = ByteArray(size() + 16) { if (it in 8 until size() + 8) 165.toByte() else 77 }
                            if (index != 6 && call.third) current.copyInto(expected, 8, 0, 8)
                            assertArrayEquals(expected, bytes, "libc oldset padding and canaries")
                            val next = normal.copyOf().also { if (after[index] == 1L) toggle(it, bit) }
                            assertArrayEquals(next, snapshot(service), "all non-SIGTTOU mask bits preserved")
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
                } finally { assertEquals(0L, service.call(setmask, address(baseline), nil)) }
            } }
        }
    }

    @Test fun fabricatedRestoresBadCarriersAndBadProofsRejectBeforeEffects() = platform {
        context().use { context -> entered(context) { language ->
            val service = Language.currentState().signalMask
            val baseline = snapshot(service)
            val source = module("pre"); val original = OriginalStdioChecks.foreignCalls(source).single()
            val output = ByteArray(size()) { 90 }; val valid = address(output)
            val other = baseline.copyOf().also { toggle(it, token(10)) } // Actual Linux SIGUSR1 delta.
            try {
                for (backend in listOf("ast", "bytecode")) {
                    val target = load(language, backend, OriginalStdioChecks.rawModule(original, source)).entryTarget("entry")
                    fun invoke(how: Long, set: ManagedAddress, old: ManagedAddress, state: Any = Unit) =
                        Calls.target(target, arrayOf(0L, how, set, old, state))
                    // Existing non-SIGTTOU bits and unmaskable signals are
                    // legitimate no-ops: reject effective changes, not bytes.
                    assertEquals(0L, invoke(constant(OriginalStdioOp.SIG_BLOCK), address(baseline), nil))
                    for (signal in listOf(9L, 19L))
                        assertEquals(0L, invoke(constant(OriginalStdioOp.SIG_BLOCK), address(token(signal)), nil))
                    assertEquals(0L, invoke(constant(OriginalStdioOp.SIG_SETMASK),
                        ManagedAddress.fromHex(OriginalStdioChecks.hex(baseline)), nil))
                    assertThrows(RuntimeFault::class.java) { invoke(0, nil, valid, 9L) }
                    assertThrows(RuntimeFault::class.java) { invoke(2147483648L, nil, valid) }
                    assertThrows(RuntimeFault::class.java) { invoke(constant(OriginalStdioOp.SIG_SETMASK), address(other), valid) }
                    val pointer = ManagedAddress.fromAllocation(PinnedMemory.allocate(size().toLong(), 8))
                    pointer.writeAddressElementIndex(0, valid)
                    for (bad in listOf(address(ByteArray(size() - 1)), pointer, ManagedAddress.fromHex("00".repeat(size()))))
                        assertThrows(RuntimeFault::class.java) { invoke(0, nil, bad) }
                    assertThrows(RuntimeFault::class.java) { invoke(0, valid, valid) }
                    for (index in 0..3) assertThrows(RuntimeFault::class.java) {
                        load(language, backend, OriginalStdioChecks.rawModule(original, source, index))
                    }
                    assertArrayEquals(ByteArray(size()) { 90 }, output)
                    assertArrayEquals(baseline, snapshot(service))
                }
            } finally { assertEquals(0L, service.call(constant(OriginalStdioOp.SIG_SETMASK), address(baseline), nil)) }
        } }
    }

    @Test fun ownedNativeImagesQueryCopyBackAndRejectBeforeMaskEffects() = platform {
        context().use { context -> entered(context) {
            val state = Language.currentState()
            val service = state.signalMask
            val registry = state.nativeAllocations
            val count = size()
            val inputBase = registry.malloc(count.toLong() + 16)
            val outputBase = registry.malloc(count.toLong() + 16)
            val input = inputBase.plus(8)
            val output = outputBase.plus(8)
            fun bytes(base: ManagedAddress) = ByteArray(count + 16) { base.readWord8(it.toLong()).toByte() }
            fun seed(base: ManagedAddress) = (0 until count + 16).forEach { base.writeWord8(it.toLong(), 90) }
            val baseline = snapshot(service)
            try {
                seed(inputBase); seed(outputBase)
                val expected = ByteArray(count + 16) { 90 }
                assertEquals(-1L, state.stdio.close(-1)); val sticky = state.stdio.errno()
                assertEquals(0L, service.call(-1, nil, address(expected).plus(8)))
                assertEquals(0L, service.call(-1, nil, output))
                assertArrayEquals(expected, bytes(outputBase), "native query preserves padding and canaries")
                assertEquals(sticky, state.stdio.errno())
                address(baseline).copyNonOverlappingTo(input, count.toLong())
                seed(outputBase)
                // Exercise two distinct owned native allocations without
                // changing the mask: restore its exact current image.
                assertEquals(0L, service.call(constant(OriginalStdioOp.SIG_SETMASK), input, output))
                assertArrayEquals(expected, bytes(outputBase))
                assertArrayEquals(baseline, snapshot(service))
                seed(outputBase)
                val before = bytes(outputBase)
                assertThrows(RuntimeFault::class.java) { service.call(0, input, input) }
                assertThrows(RuntimeFault::class.java) { service.call(0, nil, outputBase.plus(17)) }
                val unsupported = baseline.copyOf().also { toggle(it, token(10)) } // SIGUSR1.
                address(unsupported).copyNonOverlappingTo(input, count.toLong())
                assertThrows(RuntimeFault::class.java) {
                    service.call(constant(OriginalStdioOp.SIG_SETMASK), input, output)
                }
                assertArrayEquals(before, bytes(outputBase), "rejection preserves the entire output image")
                assertArrayEquals(baseline, snapshot(service))
                assertEquals(sticky, state.stdio.errno())
            } finally { registry.free(inputBase); registry.free(outputBase) }
        } }
    }

    @Test fun nativeAuthorityPlatformThreadAndContextOwnershipAreMandatory() {
        Context.create("thc").use { context -> entered(context) {
            val failure = assertThrows(RuntimeFault::class.java) { Language.currentState().signalMask.call(0, nil, nil) }
            assertTrue(failure.message!!.contains("native access"))
        } }
        context().use { context ->
            val bytes = ByteArray(128) { 90 }
            val virtual = FutureTask { entered(context) {
                assertTrue(Thread.currentThread().isVirtual)
                // These invalid regions would fail preflight if it ran. The
                // platform check precedes memory access and native acquisition.
                val failure = assertThrows(RuntimeFault::class.java) {
                    Language.currentState().signalMask.call(0, address(byteArrayOf(1)), address(bytes))
                }
                assertTrue(failure.message!!.contains("platform thread"))
                assertArrayEquals(ByteArray(128) { 90 }, bytes)
            } }
            Thread.ofVirtual().start(virtual); virtual.get(15, TimeUnit.SECONDS)
            lateinit var service: ManagedSignalMask
            entered(context) { service = Language.currentState().signalMask }
            context().use { other -> entered(other) {
                assertThrows(RuntimeFault::class.java) { service.call(0, nil, nil) }
            } }
        }
    }
}
