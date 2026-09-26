// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.graalvm.polyglot.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import thc.Json
import thc.Language

class ProcessSignalsTest {
    private val descriptor = Json.parse(javaClass.getResource("/core/original-signal-install-descriptor.json")!!.readText()) as Map<String, Any?>
    private val arguments = (descriptor.getValue("argumentReps") as List<Map<String, Any?>>).map { it + ("evaluated" to true) }
    private val result = descriptor.getValue("resultRep") as Map<String, Any?>
    private val metadata = mapOf("rep" to result, "foreignCall" to descriptor)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val boxed = closure + ("kind" to "data")
    private val state = arguments.last()
    private val unitId = "ghc-internal:GHC.Internal.Tuple.()"
    private fun variable(id: String, proof: Map<String, Any?>) = listOf("var", id, mapOf("rep" to proof))
    private fun application() = listOf("app", variable("original-signal-fcall", closure),
        arguments.mapIndexed { i, rep -> variable("a$i", rep) }, List(4) { false }, false, false, metadata)
    private fun module(): Map<String, Any?> {
        val inputs = arguments.mapIndexed { i, rep -> mapOf("id" to "a$i", "lifted" to false, "rep" to rep) }
        val binding = mapOf("id" to "install", "name" to "install", "arity" to 4, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", inputs, application(), mapOf("rep" to closure, "resultRep" to result)))
        val ioResult = mapOf("kind" to "unknown", "primReps" to listOf("BoxedRep (Just Lifted)"),
            "evaluated" to false, "aggregate" to "unboxed-tuple", "components" to listOf(state, boxed))
        // Synthetic dispatcher consumer, not replacement evidence for original
        // Conc.Signal. It exercises the exact boxed Ptr/CInt/State transport.
        val dispatcher = mapOf("id" to CoreSignalForeign.dispatcher, "name" to "runHandlersPtr", "arity" to 3,
            "lifted" to true, "rep" to closure, "expr" to listOf("lam", listOf(
                mapOf("id" to "pointer", "lifted" to true, "rep" to boxed),
                mapOf("id" to "signal", "lifted" to true, "rep" to boxed),
                mapOf("id" to "token", "lifted" to false, "rep" to state)),
                listOf("app", listOf("con", "tuple2", 2, mapOf("rep" to closure)),
                    listOf(variable("token", state), listOf("con", unitId, 0, mapOf("rep" to boxed))),
                    listOf(false, true), true, true, mapOf("rep" to ioResult)),
                mapOf("rep" to closure, "resultRep" to ioResult)))
        fun constructor(id: String, name: String, fields: List<String>) = mapOf("id" to id, "name" to name,
            "kind" to "boxed", "arity" to fields.size, "tag" to 1, "fieldReps" to fields.map(::listOf),
            "strictFields" to fields.map { false }, "fieldLifted" to fields.map { false })
        return mapOf("bindings" to listOf(binding, dispatcher), "instrument" to true, "constructors" to listOf(
            constructor("ghc-internal:GHC.Internal.Ptr.Ptr", "Ptr", listOf("AddrRep")),
            constructor("ghc-internal:GHC.Internal.Int.I32#", "I32#", listOf("Int32Rep")),
            constructor(unitId, "()", emptyList()),
            mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }
    private fun <T> inside(body: (Language) -> T): T = Context.newBuilder("thc").allowCreateThread(true)
        .allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }

    @Test fun hardContextExitSurvivesBothHostCleanupSteps() {
        val death = ThreadDeath()
        val restoration = RuntimeFault("native restoration failure")
        val bookkeeping = RuntimeFault("thread bookkeeping failure")
        val cleaned = mutableListOf<String>()
        val observed = assertThrows(ThreadDeath::class.java) {
            finishSignalConsumer(death, {
                cleaned.add("native")
                throw restoration
            }, {
                cleaned.add("thread")
                throw bookkeeping
            })
        }
        assertSame(death, observed)
        assertEquals(listOf("native", "thread"), cleaned)
        assertEquals(listOf(restoration, bookkeeping), death.suppressed.toList())
        val laterDeath = ThreadDeath()
        val failure = RuntimeFault("reader failure")
        var unregistered = false
        assertSame(laterDeath, assertThrows(ThreadDeath::class.java) {
            finishSignalConsumer(failure, { throw laterDeath }, { unregistered = true })
        })
        assertTrue(unregistered)
        assertEquals(listOf(failure), laterDeath.suppressed.toList())
    }

    @Test fun genuineOriginalDeclarationRejectsWidthHeadStateAndDescriptorNearMisses() {
        // Original ghc-internal GHC.Internal.TopHandler core/246.json SHA256
        // 6dd8a0de3bfc8664c9ea1cd2cfee6d687438761adf15a192dfde3c8e21a6fbea.
        assertEquals(ProcessSignalOp.INSTALL, CoreSignalForeign.validate(metadata, arguments, List(4) { false }, result))
        CoreSignalForeign.validateHeads(application())
        for ((key, wrong) in listOf("schema" to 2L, "arity" to 3L, "suppliedArity" to 5L,
            "safety" to "safe", "convention" to "capi", "extra" to 0L))
            assertThrows(RuntimeFault::class.java) {
                CoreSignalForeign.validate(metadata + ("foreignCall" to (descriptor + (key to wrong))), arguments, List(4) { false }, result)
            }
        for (index in arguments.indices) {
            val malformed = arguments.toMutableList()
            malformed[index] = arguments[index] + ("primReps" to listOf("IntRep"))
            assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validate(metadata, malformed, List(4) { false }, result) }
        }
        assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validate(metadata, arguments, listOf(false, false, true, false), result) }
        assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validateHead(variable("original-signal-fcall", closure), true) }
        val forged = application().toMutableList().also { it[1] = listOf("prim", "stg_sig_install") }
        assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validateHeads(forged) }
    }

    @Test fun astRejectsDeliveryAndCompiledEmbeddingDenialInvalidatesAsHostFault() = inside { language ->
        assertThrows(UnsupportedCore::class.java) { Program(language, module()) }
        val program = BytecodeProgram(language, module(), true)
        val target = program.entryTarget("install")
        val state = Language.currentState()
        state.threads.enterCurrent()
        try {
            fun reject(token: Any = Unit) {
                val failure = assertThrows(RuntimeFault::class.java) {
                    Calls.target(target, arrayOf(0L, 2L, -5L, ManagedAddress.nullAddress(), token))
                }
                assertEquals(RuntimeFault::class.java, failure.javaClass)
                if (token === Unit) assertTrue(failure.message!!.contains("launcher authority"))
            }
            reject(); reject(1L)
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            reject()
            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            // Bytecode DSL resolveThrowable invalidates ordinary host faults;
            // only AbstractTruffleException and ControlFlowException bypass it.
            // Authority denial stays a RuntimeFault, not a catchable guest error.
            assertEquals(false, target.javaClass.getMethod("isValidLastTier").invoke(target))
            assertEquals(0, language.handoffState.get().results.depth)
            assertEquals(0, language.handoffState.get().results.retainedReferences())
            assertEquals(0, language.handoffState.get().arguments.depth)
            assertEquals(0, language.handoffState.get().arguments.retainedReferences())
        } finally { state.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
    }

    @Test fun contextTransportKeepsOldActionsAndClosesAfterDelivery() {
        assumeTrue(System.getProperty("os.name") == "Linux")
        inside { language ->
            val owner = Language.currentState()
            val events = LinkedBlockingQueue<ByteArray>()
            val delivered = CountDownLatch(1)
            val closed = AtomicInteger()
            val fake = object : ProcessSignalTransport {
                private var old = -1
                private var received = false
                override fun install(action: Int) = ProcessSignalTransport.Result(old.also { old = action }, 0)
                override fun take(): ByteArray? {
                    if (received) delivered.countDown()
                    return events.take().takeUnless { it.isEmpty() }?.also { received = true }
                }
                override fun wake() { events.offer(byteArrayOf()) }
                override fun resetWake() { events.removeIf { it.isEmpty() } }
                override fun close() { closed.incrementAndGet() }
            }
            val service = ManagedSignals(owner, language) { fake }
            val program = BytecodeProgram(language, module(), true)
            service.bind(program)
            assertThrows(RuntimeFault::class.java) { service.install(2L, -5L, ManagedAddress.nullAddress()) }
            service.authorizeLauncher()
            for (bad in listOf(1L to -5L, 2L to -3L, 2L to 1L))
                assertThrows(RuntimeFault::class.java) { service.install(bad.first, bad.second, ManagedAddress.nullAddress()) }
            assertEquals(listOf(-1L, -2L, -4L, -5L), listOf(-2L, -4L, -5L, -1L).map {
                service.install(2L, it, ManagedAddress.nullAddress()) })
            try {
                events.put(ByteArray(128) { it.toByte() })
                assertTrue(TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    TruffleSafepoint.InterruptibleFunction<CountDownLatch, Boolean> { it.await(5, TimeUnit.SECONDS) }, delivered),
                    "typed guest dispatcher must return")
                assertEquals(1, owner.nativeAllocations.liveCount(), "dispatcher image remains context-owned")
            } finally { service.close() }
            assertEquals(1, closed.get())
            assertThrows(RuntimeFault::class.java) { service.install(2L, -5L, ManagedAddress.nullAddress()) }
        }
    }

    @Test fun nativeChildControlsAndGhcActionOracleHaveMatchingInputs() {
        assumeTrue(System.getProperty("os.name") == "Linux")
        val root = File(System.getProperty("thc.projectRoot"))
        val manifest = Json.parse(File(root, "build/process-signals/manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/ProcessSignalsNative.hs",
            "src/main/c/native-process-signal-api.c", "src/test/c/native-process-signals-test.c",
            "src/test/resources/core/original-signal-install-descriptor.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("build/process-signals/oracle.txt",
            "build/process-signals/native-controls.txt"), "build/process-signals/")
        assertEquals("[-1,-2,-4,-5]\n", File(root, "build/process-signals/oracle.txt").readText())
        assertEquals("9 isolated native signal controls passed\n", File(root, "build/process-signals/native-controls.txt").readText())
    }
}
