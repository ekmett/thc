// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import thc.Main.executionContext

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExecutionSignature
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

/** Source/lowering counters are independent of guest-entry instrumentation. */
class LazyBindingTest {
    private class ColdCellRoot(private val binding: GlobalBinding, private val compiled: LongArray) : RootNode(null) {
        override fun prepareForAOT(): ExecutionSignature = ExecutionSignature.create(java.lang.Long::class.java, emptyArray())
        override fun execute(frame: VirtualFrame): Any? {
            if (CompilerDirectives.inCompiledCode()) compiled[0]++
            val result = binding.read()
            if (CompilerDirectives.inCompiledCode()) compiled[1]++
            return result
        }
    }
    private val formal = mapOf("id" to "x", "name" to "x", "type" to "Int#",
        "lifted" to false, "coercion" to false)
    private fun lambda(body: () -> List<Any?>): CoreBindingBody = CoreBindingBody(
        CoreBindingBody.Header(3, mapOf(0 to "lam", 1 to listOf(formal)), false)
    ) { listOf("lam", listOf(formal), body()) }
    private fun binding(id: String, body: List<Any?>, arity: Int = 1, lifted: Boolean = true) = mapOf(
        "id" to id, "name" to id, "type" to "Synthetic", "lifted" to lifted,
        "arity" to arity, "expr" to body)
    private fun module(bindings: List<Map<String, Any?>>) = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.LazyBinding",
        "sourceNotesEnabled" to false, "instrument" to true,
        "bindings" to bindings, "constructors" to emptyList<Any>())
    private fun bothBackends(action: (Language, String, Boolean) -> Unit) {
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
            executionContext().use { context ->
                context.initialize("thc")
                context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend, async) }
                finally { context.leave() }
            }
        }
    }
    private fun program(language: Language, backend: String, async: Boolean, data: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, data, async) else BytecodeProgram(language, data, async)
    private fun count(program: ExecutableProgram, name: String): Long =
        (program.diagnostics().getValue(name) as Number).toLong()
    private fun invoke(program: ExecutableProgram, value: Any?, vararg arguments: Any?): Any? =
        Calls.target(program.hostEntryTarget(arguments.size), arrayOf(value, arguments))

    @Test fun firstCompiledColdCellReadDoesNotRetireTheCaller() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val cell = GlobalBinding("cold scalar")
                var preparations = 0
                cell.defer(Any()) { preparations++; 17L }
                val compiled = LongArray(2)
                val target = ColdCellRoot(cell, compiled).callTarget
                val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                // This exact scalar test root declares its real signature. It
                // has never executed, and no guest warm-up/profile is fabricated.
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target))
                type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
                Truffle.getRuntime().let { it.javaClass.getMethod("bypassedInstalledCode", type).invoke(it, target) }
                assertEquals(0, preparations)
                assertEquals(17L, Calls.target(target, emptyArray()))
                assertEquals(1, preparations)
                assertEquals(1L, compiled[0], "the very first read must enter installed code")
                assertEquals(1L, compiled[1], "preparing the cold value must not deoptimize its caller")
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
                repeat(32) { assertEquals(17L, Calls.target(target, emptyArray())) }
                assertEquals(1, preparations)
                assertEquals(33L, compiled[0])
                assertEquals(33L, compiled[1])
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
            } finally { context.leave() }
        }
    }

    @Test fun unusedBodiesStayUndecodedAndOneDemandBuildsOneRootAtBothSizes() = bothBackends { language, backend, async ->
        for (size in listOf(32, 64)) {
            val sources = List(size) { lambda { listOf("var", "x") } }
            val headerReads = LongArray(size)
            val data = module(sources.mapIndexed { index, source ->
                val header = binding("f$index", source)
                object : Map<String, Any?> by header {
                    override fun get(key: String): Any? {
                        headerReads[index]++
                        return header[key]
                    }
                }
            })
            val program = program(language, backend, async, data)
            val beforeReads = headerReads.copyOf()
            assertEquals(0, sources.sumOf { it.decodeAttempts() }, "$backend/$async/$size pre-entry decode")
            assertEquals(0, count(program, "initializedBindingCount"))
            assertEquals(0, count(program, "loweredRootCount"))
            assertEquals(0, count(program, "hostEntryRootCount"))
            val first = program.entryValue("f0")
            assertTrue(first is Closure)
            assertSame(first, program.entryValue("f0"))
            assertEquals(1, sources.sumOf { it.decodeAttempts() })
            assertEquals(1, count(program, "initializedBindingCount"))
            assertEquals(1, count(program, "loweredRootCount"))
            for (index in 1 until size) assertEquals(beforeReads[index], headerReads[index],
                "one demand must not rebuild the full global header index")
            val second = program.entryValue("f${size - 1}")
            assertNotSame(first, second)
            assertEquals(2, sources.sumOf { it.decodeAttempts() })
            assertEquals(2, count(program, "initializedBindingCount"))
            assertEquals(2, count(program, "loweredRootCount"))
            assertEquals(Long.MIN_VALUE, invoke(program, first, Long.MIN_VALUE))
            assertEquals(Long.MAX_VALUE, invoke(program, second, Long.MAX_VALUE))
            assertEquals(2, sources.sumOf { it.decodeAttempts() }, "guest execution must not reload prepared bodies")
        }
    }

    @Test fun demandedInputChecksKeepTheFullGlobalHeaderEnvironment() = bothBackends { language, backend, async ->
        val scalar = mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf("IntRep"))
        val tuple = mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
            "components" to listOf(scalar, scalar), "primReps" to listOf("IntRep", "IntRep"))
        val targetFormals = listOf(formal + ("rep" to tuple))
        val target = CoreBindingBody(CoreBindingBody.Header(3, mapOf(0 to "lam", 1 to targetFormals), false)) {
            error("input validation must not decode the callee implementation")
        }
        val caller = lambda {
            listOf("app", listOf("var", "target"), listOf(listOf("lit", "int", "1")), listOf(false))
        }
        val program = program(language, backend, async, module(listOf(binding("caller", caller), binding("target", target))))
        val failure = assertThrows(RuntimeFault::class.java) { program.entryValue("caller") }
        assertTrue(failure.message.orEmpty().contains("exact tuple argument proof"), failure.message)
        assertEquals(0, target.decodeAttempts())
        assertEquals(0, count(program, "initializedBindingCount"))
    }

    @Test fun reachableMalformedBodyFailsOnceWhileUnusedBodyDoesNotBlockEntry() = bothBackends { language, backend, async ->
        val good = lambda { listOf("var", "x") }
        val broken = lambda { throw IllegalArgumentException("retained malformed body") }
        val invalid = lambda { listOf("var", "missing") }
        val program = program(language, backend, async, module(listOf(
            binding("good", good), binding("broken", broken), binding("invalid", invalid))))
        assertEquals(7L, invoke(program, program.entryValue("good"), 7L))
        assertEquals(0, broken.decodeAttempts())
        val decodeFailure = assertThrows(IllegalArgumentException::class.java) { program.entryValue("broken") }
        assertSame(decodeFailure, assertThrows(IllegalArgumentException::class.java) { program.entryValue("broken") })
        assertEquals(1, broken.decodeAttempts())
        val admissionFailure = assertThrows(RuntimeFault::class.java) { program.entryValue("invalid") }
        assertTrue(admissionFailure.message.orEmpty().contains("Unresolved external binding missing"))
        assertSame(admissionFailure, assertThrows(RuntimeFault::class.java) { program.entryValue("invalid") })
        assertEquals(1, invalid.decodeAttempts())
        assertEquals(1, count(program, "initializedBindingCount"))
    }

    @Test fun bytecodeDeferredFailureKeepsItsIdentityAndAddsOnlyItsExactOwner() {
        for (async in listOf(false, true)) executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val cause = IllegalArgumentException("original cause")
                val previous = IllegalStateException("original suppressed context")
                val original = UnsupportedCore("original deferred failure").also {
                    it.initCause(cause)
                    it.addSuppressed(previous)
                }
                val stack = original.stackTrace.copyOf()
                val broken = lambda { throw original }
                val cold = lambda { error("unrequested body must stay cold") }
                val id = "actual-unit:Original.Module.\$worker"
                val program = BytecodeProgram(language, module(listOf(
                    binding(id, broken), binding("cold-unit:Cold.entry", cold))), async)
                assertEquals(0, broken.decodeAttempts())
                assertEquals(0, cold.decodeAttempts())
                val failure = assertThrows(UnsupportedCore::class.java) { program.entryValue(id) }
                assertSame(original, failure)
                assertEquals("original deferred failure", failure.message)
                assertSame(cause, failure.cause)
                assertArrayEquals(stack, failure.stackTrace)
                assertEquals(2, failure.suppressed.size)
                assertSame(previous, failure.suppressed[0])
                assertEquals("While preparing Core binding $id", failure.suppressed[1].message)
                assertSame(original, assertThrows(UnsupportedCore::class.java) { program.entryValue(id) })
                assertEquals(2, original.suppressed.size, "memoized failure must not acquire duplicate context")
                assertEquals(1, broken.decodeAttempts())
                assertEquals(0, cold.decodeAttempts())
                assertEquals(0, count(program, "initializedBindingCount"))
                assertEquals(0, count(program, "loweredRootCount"))
            } finally { context.leave() }
        }
    }

    @Test fun codePreparationKeepsCafIdentityAndDoesNotEvaluateItsBottom() = bothBackends { language, backend, async ->
        val bottom = CoreBindingBody(CoreBindingBody.Header(2, mapOf(0 to "var", 1 to "bottom"), false)) {
            listOf("var", "bottom")
        }
        val program = program(language, backend, async, module(listOf(binding("bottom", bottom, 0))))
        val thunk = program.entryValue("bottom") as Thunk
        assertSame(thunk, program.entryValue("bottom"))
        assertEquals(0, thunk.state)
        assertEquals(0, count(program, "thunkEvaluations"))
        val failure = assertThrows(RuntimeFault::class.java) { invoke(program, thunk) }
        assertTrue(failure.message.orEmpty().contains("Blackhole"))
        assertSame(failure, assertThrows(RuntimeFault::class.java) { invoke(program, thunk) })
        assertEquals(1, count(program, "thunkEvaluations"))
    }

    @Test fun sourceSharingDoesNotShareExecutableClosuresBetweenPrograms() = bothBackends { language, backend, async ->
        val body = lambda { listOf("var", "x") }
        val data = module(listOf(binding("entry", body)))
        val first = program(language, backend, async, data)
        val second = program(language, backend, async, data)
        val firstClosure = first.entryValue("entry") as Closure
        val secondClosure = second.entryValue("entry") as Closure
        assertNotSame(firstClosure, secondClosure)
        assertNotSame(firstClosure.target, secondClosure.target)
        assertEquals(1, body.decodeAttempts(), "immutable source is shared, runtime roots are not")
        assertEquals(5L, invoke(first, firstClosure, 5L))
        assertEquals(9L, invoke(second, secondClosure, 9L))
    }

    @Test fun ordinaryAndStrictBindingsKeepEagerValidation() = bothBackends { language, backend, async ->
        val ordinary = listOf("lam", listOf(formal), listOf("var", "missing"))
        assertThrows(RuntimeFault::class.java) {
            program(language, backend, async, module(listOf(binding("ordinary", ordinary))))
        }
        val strict = CoreBindingBody(CoreBindingBody.Header(3, mapOf(0 to "lit", 1 to "int", 2 to "11"), false)) {
            listOf("lit", "int", "11")
        }
        val program = program(language, backend, async, module(listOf(binding("strict", strict, 0, false))))
        assertEquals(1, count(program, "initializedBindingCount"))
        assertEquals(11L, program.entryValue("strict"))
    }

    @Test fun concurrentPreparationPublishesOnceAndSharesTheProgramLock() {
        val lock = Any()
        val first = GlobalBinding("first")
        val second = GlobalBinding("second")
        val preparations = java.util.concurrent.atomic.AtomicInteger()
        val result = Any()
        second.defer(lock) { preparations.incrementAndGet(); result }
        first.defer(lock) { preparations.incrementAndGet(); second.read() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val values = pool.invokeAll(List(32) { index -> java.util.concurrent.Callable {
                if (index % 2 == 0) first.read() else second.read()
            } })
            values.forEach { assertSame(result, it.get()) }
        } finally { pool.shutdown() }
        assertEquals(2, preparations.get())
    }
}
