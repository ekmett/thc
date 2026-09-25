// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.ExecutableProgram
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Both sender and target are exported Haskell running through the public parser. */
class ThreadAsyncNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    @Test fun yieldRequiresOneExactStateToken() {
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val wrong = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        CoreYield.validate(listOf(state), listOf(false), state)
        assertThrows(RuntimeFault::class.java) { CoreYield.validate(listOf(wrong), listOf(false), state) }
        assertThrows(RuntimeFault::class.java) { CoreYield.validate(listOf(state), listOf(true), state) }
        assertThrows(RuntimeFault::class.java) { CoreYield.validate(listOf(state), listOf(false), wrong) }
    }

    @Test fun maskedYieldDefersAnExternalRequestAndUnmaskedYieldCapturesOnce() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val threads = Language.currentState().threads
                val effects = AtomicInteger()
                val compiled = AtomicInteger()
                val stateProof = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val root = object : GuestRoot(language, FrameLayout().build()) {
                    @field:Child private var yielding = YieldThread(object : Expr() {
                        override fun execute(frame: VirtualFrame): Any {
                            effects.incrementAndGet()
                            if (CompilerDirectives.inCompiledCode()) compiled.incrementAndGet()
                            return Unit
                        }
                    }, true, stateProof)
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any = try { yielding.execute(frame) }
                    catch (cut: AstCapture) {
                        CompilerDirectives.transferToInterpreter()
                        cut.freeze(this, frame.materialize())
                    }
                }
                val target = root.callTarget
                val id = threads.enterCurrent()
                try {
                    repeat(5) { assertSame(Unit, Calls.target(target, arrayOf(0L))) }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    val sent = AtomicReference<AsyncRequest>()
                    val sender = Thread { sent.set(threads.send(id, "external")) }
                    sender.start(); sender.join(5000)
                    assertFalse(sender.isAlive)
                    val request = sent.get()
                    SynchronousMasking.set(root, MaskingState.MASKED_INTERRUPTIBLE)
                    assertSame(Unit, Calls.target(target, arrayOf(0L)))
                    assertEquals(AsyncRequestState.PENDING, request.state,
                        "yield# is an ordinary, noninterruptible guest poll")
                    SynchronousMasking.set(root, MaskingState.UNMASKED)
                    val cut = Calls.target(target, arrayOf(0L)) as AstContinuation
                    assertSame(request, cut.yielded)
                    assertEquals(AsyncRequestState.CLAIMED, request.state)
                    assertTrue(compiled.get() >= 2, "Both masked and unmasked effects entered compiled AST")
                    assertEquals(7, effects.get(), "The yield effect ran once per entry")
                    request.acknowledge()
                    assertSame(Unit, cut.continueWith(Unit))
                    assertEquals(7, effects.get(), "Resume must not replay the yield effect")
                    assertThrows(RuntimeFault::class.java) { cut.continueWith(Unit) }
                } finally {
                    SynchronousMasking.set(root, MaskingState.UNMASKED)
                    threads.leaveCurrent()
                }
            } finally { context.leave() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkReceipt() {
        val receipt = Json.parse(File(root, "build/thread-async/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val bytes = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                assertEquals(expected, bytes.joinToString("") { "%02x".format(it) }, "Stale $path")
            }
        assertEquals(listOf("43", "44"), File(root, "build/thread-async/oracle.txt").readLines())
        assertEquals(listOf("5", "-1", "-1"), File(root, "build/thread-async/extra-oracle.txt").readLines())
        assertEquals(listOf("37", "39"), File(root, "build/thread-async/yield-oracle.txt").readLines())
        assertEquals(listOf("52", "53"), File(root, "build/thread-async/lazy-oracle.txt").readLines())
    }

    private fun exercise(name: String, expected: List<Long>, module: String = "ThreadAsyncAudit") {
        checkReceipt()
        for (stage in listOf("pre", "post")) {
            val context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "thc-public-thread-test").apply { isDaemon = true }
            }
            try {
                val core = File(root, "build/thread-async/$stage/core/$module.json")
                val entry = context.eval("thc", CoreModules.request(listOf(core.path), name, backend = "bytecode"))
                val result = executor.submit<List<Long>> {
                    val interpreted = entry.execute(0L).asLong()
                    assertTrue(entry.invokeMember("compile").asBoolean())
                    listOf(interpreted, entry.execute(1L).asLong())
                }
                assertEquals(expected, result.get(30, TimeUnit.SECONDS), "$stage $name")
                val diagnostics = Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>
                assertEquals(0L, (diagnostics["unsupportedTraps"] as Number).toLong())
            } finally {
                context.close(true)
                executor.shutdownNow()
            }
        }
    }

    @Test fun publicForkAndThrowResumeTheSharedThunk() = exercise("forkAndThrow", listOf(43, 44))
    @Test fun uncaughtChildDeliveryReleasesTheSender() = exercise("killUncaught", listOf(5, 6))
    @Test fun selfDirectedThrowEntersTheOriginalHandler() = exercise("selfThrow", listOf(-1, 0))
    @Test fun outerUninterruptibleMaskStillCapturesCalleeThatUnmasksAndSelfThrows() =
        exercise("maskedUnmaskSelf", listOf(-1, 0))
    @Test fun forkedChildOwnsAndResumesTheSharedLazyActionHead() =
        exercise("lazyFork", listOf(52, 53), "LazyForkAudit")

    @Test fun yieldPreservesStateAndMaskInCompiledAstAndBytecode() {
        checkReceipt()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) {
            @Suppress("UNCHECKED_CAST")
            val module = CoreModules.merge(listOf(Json.parse(File(root,
                "build/thread-async/$stage/core/ThreadAsyncAudit.json").readText()) as Map<String, Any?>))
            for ((name, base) in listOf("yieldProbe" to 37L, "yieldMasked" to 39L)) {
                Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                            else BytecodeProgram(language, linked)
                        val function = context.asValue(EntryValue(program, name, 1))
                        assertEquals(base, function.execute(0L).asLong(), "$stage/$backend/$name")
                        val target: RootCallTarget = program.entryTarget(name)
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(base + 1, function.execute(1L).asLong(), "$stage/$backend/$name compiled")
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.rootNode))
                    } finally { context.leave() }
                }
            }
        }
    }
}
