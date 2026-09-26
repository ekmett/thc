// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.CompilerAsserts
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.runtime.OptimizedCallTarget
import com.oracle.truffle.runtime.OptimizedTruffleRuntime
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.Language
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@Timeout(60)
class RuntimeJitServicesTest {
    private fun engine(failureAction: String = "Throw") = Engine.newBuilder().allowExperimentalOptions(true)
        .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", failureAction).build()

    private fun context(engine: Engine) = Context.newBuilder("thc").engine(engine).build()

    private inline fun <T> entered(context: Context, action: () -> T): T {
        context.initialize("thc")
        context.enter()
        return try { action() } finally { context.leave() }
    }

    private fun language() = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)

    private fun programs(language: Language): List<ExecutableProgram> {
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val module = mapOf("instrument" to true, "bindings" to listOf(mapOf("id" to "identity", "name" to "identity",
            "lifted" to true, "expr" to listOf("lam", listOf(mapOf("id" to "n", "lifted" to false, "rep" to long)),
                listOf("var", "n", mapOf("rep" to long)), mapOf("rep" to closure, "resultRep" to long)))))
        return listOf(Program(language, module), BytecodeProgram(language, module))
    }

    private fun call(program: ExecutableProgram, value: Long): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("identity"), arrayOf(value)))

    private fun count(service: RuntimeJitServices, selector: Int) = service.query(selector, 0, 0)

    @Test fun realCompilationsAndInvalidationsAreAttributedToTheirContextWithSharedEngine() {
        engine().use { engine -> context(engine).use { first -> context(engine).use { second ->
            val firstLanguage = entered(first) { language() }
            val secondLanguage = entered(second) { language() }
            assertNotSame(firstLanguage, secondLanguage, "EXCLUSIVE language identity is the attribution boundary")
            RuntimeJitServices(firstLanguage).use { firstService -> RuntimeJitServices(secondLanguage).use { secondService ->
                assertEquals(0L, count(firstService, 400))
                assertEquals(RuntimeServiceStatus.DISABLED, count(firstService, 401))
                assertEquals(0L, firstService.control(400, 1))
                assertEquals(0L, firstService.control(400, 1), "Repeated enable must not duplicate callbacks")
                assertEquals(0L, secondService.control(400, 1))
                entered(first) {
                    for (program in programs(firstLanguage)) {
                        assertEquals(37L, call(program, 37L))
                        val target = program.entryTarget("identity") as OptimizedCallTarget
                        val started = count(firstService, 401)
                        val succeeded = count(firstService, 402)
                        target.compile(true)
                        assertTrue(target.isValidLastTier)
                        assertEquals(started + 1, count(firstService, 401))
                        assertEquals(succeeded + 1, count(firstService, 402))
                        val entries = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(43L, call(program, 43L), "First compiled call retains semantics")
                        assertEquals(entries + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        assertTrue(target.isValidLastTier)
                        val invalidations = count(firstService, 404)
                        assertTrue(target.invalidate("RuntimeJitServicesTest explicit test invalidation"))
                        assertEquals(invalidations + 1, count(firstService, 404))
                    }
                }
                for (selector in 401..406) assertEquals(0L, count(secondService, selector), "No first-context event in second")
                val firstCounts = (401..406).map { count(firstService, it) }
                entered(second) {
                    val program = programs(secondLanguage).first()
                    assertEquals(19L, call(program, 19L))
                    val target = program.entryTarget("identity") as OptimizedCallTarget
                    target.compile(true)
                    assertTrue(target.isValidLastTier)
                    assertEquals(1L, count(secondService, 401))
                    assertEquals(1L, count(secondService, 402))
                }
                assertEquals(firstCounts, (401..406).map { count(firstService, it) }, "No second-context event in first")
                // Host roots without a language are never counted as THC compilation.
                val unrelated = RootNode.createConstantNode(71).callTarget as OptimizedCallTarget
                assertEquals(71, unrelated.call())
                unrelated.compile(true)
                (Truffle.getRuntime() as OptimizedTruffleRuntime).waitForCompilation(unrelated, 30_000)
                assertTrue(unrelated.isValidLastTier)
                assertEquals(firstCounts, (401..406).map { count(firstService, it) })
            } }
        } } }
    }

    @Test fun disabledWindowsAreExcludedAndClosePermanentlyRejectsQueriesAndControls() {
        engine().use { engine -> context(engine).use { context -> entered(context) {
            val service = RuntimeJitServices(language())
            service.use {
                val program = programs(language()).first()
                assertEquals(3L, call(program, 3L))
                val target = program.entryTarget("identity") as OptimizedCallTarget
                target.compile(true)
                assertTrue(target.isValidLastTier)
                assertEquals(0L, service.control(400, 1))
                assertEquals(0L, count(service, 401), "No retrospective fabricated history")
                assertTrue(target.invalidate("first observed invalidation"))
                assertEquals(1L, count(service, 404))
                target.compile(true)
                assertTrue(target.isValidLastTier)
                assertEquals(1L, count(service, 402))
                assertEquals(0L, service.control(400, 0))
                assertEquals(0L, count(service, 400))
                for (selector in 401..406) assertEquals(RuntimeServiceStatus.DISABLED, count(service, selector))
                assertTrue(target.invalidate("disabled invalidation"))
                target.compile(true)
                assertTrue(target.isValidLastTier)
                assertEquals(0L, service.control(400, 1))
                assertEquals(1L, count(service, 401))
                assertEquals(1L, count(service, 402))
                assertEquals(1L, count(service, 404))
                service.close()
                service.close()
                for (selector in 400..406) assertEquals(RuntimeServiceStatus.UNAVAILABLE, count(service, selector))
                assertEquals(RuntimeServiceStatus.UNAVAILABLE, service.control(400, 1))
                assertTrue(target.invalidate("closed observer cannot consume late callbacks"))
            }
        } } }
    }

    @Test fun unavailableProvidersAndMalformedRequestsAreNotMisreportedAsCounters() {
        engine().use { engine -> context(engine).use { context -> entered(context) {
            val owner = language()
            RuntimeJitServices(owner) { null }.use { service ->
                for (selector in 400..406) assertEquals(RuntimeServiceStatus.UNSUPPORTED, count(service, selector))
                assertEquals(RuntimeServiceStatus.UNSUPPORTED, service.control(400, 1))
                assertThrows(RuntimeFault::class.java) { service.query(407, 0, 0) }
                assertThrows(RuntimeFault::class.java) { service.query(400, 1, 0) }
                assertThrows(RuntimeFault::class.java) { service.query(400, 0, -1) }
                assertThrows(RuntimeFault::class.java) { service.control(401, 0) }
                assertThrows(RuntimeFault::class.java) { service.control(400, 2) }
            }
            RuntimeJitServices(owner) { throw SecurityException("test denial") }.use { service ->
                assertEquals(RuntimeServiceStatus.DENIED, count(service, 400))
                assertEquals(RuntimeServiceStatus.DENIED, service.control(400, 1))
            }
            RuntimeJitServices(owner) { throw AssertionError("Do not hide VM failure") }.use { service ->
                assertThrows(AssertionError::class.java) { count(service, 400) }
            }
        } } }
    }

    @Test fun realDeoptimizationAndCompilationFailureHaveTheirOwnCounters() {
        engine("Silent").use { engine -> context(engine).use { context -> entered(context) {
            val owner = language()
            RuntimeJitServices(owner).use { service ->
                assertEquals(0L, service.control(400, 1))
                val deoptimizing = object : RootNode(owner) {
                    override fun execute(frame: VirtualFrame): Any {
                        if (frame.arguments[0] as Boolean) CompilerDirectives.transferToInterpreterAndInvalidate()
                        return if (CompilerDirectives.inCompiledCode()) 17L else 19L
                    }
                }.callTarget as OptimizedCallTarget
                assertEquals(19L, deoptimizing.call(false))
                assertEquals(19L, deoptimizing.call(true))
                deoptimizing.compile(true)
                assertTrue(deoptimizing.isValidLastTier)
                (Truffle.getRuntime() as OptimizedTruffleRuntime).bypassedInstalledCode(deoptimizing)
                assertEquals(17L, deoptimizing.call(false), "First compiled call")
                assertTrue(deoptimizing.isValidLastTier)
                assertEquals(0L, count(service, 405))
                assertEquals(19L, deoptimizing.call(true))
                assertFalse(deoptimizing.isValid)
                // This VM deoptimization does not emit Truffle's separate
                // explicit target-invalidation callback. Do not conflate them.
                assertEquals(0L, count(service, 404))
                assertTrue(count(service, 405) > 0L)

                val rejected = object : RootNode(owner) {
                    override fun execute(frame: VirtualFrame): Any {
                        CompilerAsserts.neverPartOfCompilation("intentional JIT telemetry test bailout")
                        return 23L
                    }
                }.callTarget as OptimizedCallTarget
                assertEquals(23L, rejected.call())
                rejected.compile(true)
                assertFalse(rejected.isValid)
                assertTrue(count(service, 403) > 0L, "Actual compiler failure callback")
                assertTrue(count(service, 406) > 0L, "Actual compilation queue callbacks")
                assertEquals(23L, rejected.call(), "Diagnostics do not prevent interpretation after a bailout")
            }
        } } }
    }

    @Test fun concurrentQueriesAndControlDoNotDuplicateRegistrationOrReviveClosedService() {
        engine().use { engine -> context(engine).use { context -> entered(context) {
            RuntimeJitServices(language()).use { service ->
                val start = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val threads = List(4) {
                    Thread.ofPlatform().unstarted {
                        try {
                            start.await()
                            repeat(100) {
                                assertEquals(0L, service.control(400, 1))
                                assertTrue(count(service, 400) in 0L..1L)
                                assertTrue(count(service, 401) in setOf(0L, RuntimeServiceStatus.DISABLED))
                                assertEquals(0L, service.control(400, 0))
                            }
                        } catch (caught: Throwable) { failure.compareAndSet(null, caught) }
                    }
                }
                threads.forEach { it.start() }
                start.countDown()
                threads.forEach { it.join() }
                failure.get()?.let { throw it }
                assertEquals(0L, service.control(400, 1))
                val program = programs(language()).first()
                assertEquals(5L, call(program, 5L))
                (program.entryTarget("identity") as OptimizedCallTarget).compile(true)
                assertEquals(1L, count(service, 401))
                assertEquals(1L, count(service, 402))
            }
        } } }
    }
}
