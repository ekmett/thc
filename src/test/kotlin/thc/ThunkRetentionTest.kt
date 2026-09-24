package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

/** Thunk ownership is tested structurally; collection timing is irrelevant. */
class ThunkRetentionTest {
    private class ForceDriver(metrics: Metrics) : RootNode(null) {
        @Child private var force = Force(metrics)
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun apply(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
    }

    private fun withRuntime(action: (Language, CapturedFrame) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val layout = FrameLayout()
                val slot = layout.bind("captured")
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
                FrameAccess.write(frame, slot, 3_000_000_000L)
                action(language, CaptureLayout(language, booleanArrayOf(true)).capture(frame, intArrayOf(slot)))
            } finally { context.leave() }
        }
    }

    private fun assertReleased(thunk: Thunk) {
        assertNull(thunk.target, "An updated thunk must release its body target")
        assertNull(thunk.environment, "An updated thunk must release its captured environment")
    }

    @Test fun successfulUpdatesReleaseSuspensionsAndRemainShared() = withRuntime { _, environment ->
        var evaluations = 0
        val target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                evaluations++
                assertSame(environment, frame.arguments[1])
                return (frame.arguments[1] as CapturedFrame).getLong(0) + 17L
            }
        }.callTarget
        val metrics = Metrics(true)
        val driver = ForceDriver(metrics)
        val thunk = Thunk(target, environment)
        repeat(4) {
            assertEquals(3_000_000_017L, driver.apply(thunk))
            assertEquals(2, thunk.state)
            assertEquals(3_000_000_017L, thunk.value)
            assertReleased(thunk)
        }
        assertEquals(1, evaluations)
        assertEquals(1L, metrics.thunkEvaluations)
        assertEquals(3L, metrics.thunkHits)
    }

    @Test fun memoizedGuestAndRuntimeFailuresReleaseSuspensionsWithoutRepeatingEffects() = withRuntime { _, environment ->
        val failures = listOf(RuntimeFault("deliberate runtime failure"),
            GuestException("retained lazy exception payload", object : Node() {}))
        for (failure in failures) {
            var evaluations = 0
            val target = object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any {
                    evaluations++
                    assertSame(environment, frame.arguments[1])
                    throw failure
                }
            }.callTarget
            val driver = ForceDriver(Metrics(true))
            val thunk = Thunk(target, environment)
            repeat(3) {
                assertSame(failure, assertThrows(failure.javaClass) { driver.apply(thunk) })
                assertEquals(3, thunk.state)
                assertSame(failure, thunk.value)
                assertReleased(thunk)
            }
            assertEquals(1, evaluations)
        }
    }

    @Test fun unexpectedFailuresKeepTheSuspensionForAnActualRetry() = withRuntime { _, environment ->
        val interrupted = IllegalStateException("retryable host interruption")
        var evaluations = 0
        val target = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                evaluations++
                assertSame(environment, frame.arguments[1])
                if (evaluations == 1) throw interrupted
                return (frame.arguments[1] as CapturedFrame).getLong(0)
            }
        }.callTarget
        val driver = ForceDriver(Metrics(true))
        val thunk = Thunk(target, environment)
        assertSame(interrupted, assertThrows(IllegalStateException::class.java) { driver.apply(thunk) })
        assertEquals(0, thunk.state)
        assertNull(thunk.value)
        assertSame(target, thunk.target)
        assertSame(environment, thunk.environment)
        assertEquals(3_000_000_000L, driver.apply(thunk))
        assertReleased(thunk)
        assertEquals(3_000_000_000L, driver.apply(thunk))
        assertEquals(2, evaluations, "The unexpected failure retries once; the completed value remains shared")
    }

    @Test fun aThunkReturningAnotherThunkFailsBeforeEitherCanPublishAnIndirection() = withRuntime { _, environment ->
        var innerEvaluations = 0
        val innerTarget = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                innerEvaluations++
                return 3_000_000_000L
            }
        }.callTarget
        val inner = Thunk(innerTarget, environment)
        var outerEvaluations = 0
        val outer = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                outerEvaluations++
                return inner
            }
        }.callTarget, environment)
        val driver = ForceDriver(Metrics(true))
        val failure = assertThrows(RuntimeFault::class.java) { driver.apply(outer) }
        assertEquals("Thunk target violated WHNF convention", failure.message)
        assertSame(failure, assertThrows(RuntimeFault::class.java) { driver.apply(outer) })
        assertEquals(1, outerEvaluations)
        assertEquals(0, innerEvaluations, "Rejecting an invalid thunk result must not force it")
        assertEquals(3, outer.state)
        assertReleased(outer)
        assertEquals(0, inner.state)
        assertSame(innerTarget, inner.target)
        assertSame(environment, inner.environment)
    }

    @Test fun nestedForcingThroughTheSameForceNodePublishesSeparateSharedAnswers() = withRuntime { _, environment ->
        var innerEvaluations = 0
        var outerEvaluations = 0
        val metrics = Metrics(true)
        val driver = ForceDriver(metrics)
        val inner = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                innerEvaluations++
                return 3_000_000_000L
            }
        }.callTarget, environment)
        val outer = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                outerEvaluations++
                return (driver.apply(inner) as Long) + 17L
            }
        }.callTarget, environment)
        repeat(3) {
            assertEquals(3_000_000_017L, driver.apply(outer))
            assertEquals(3_000_000_000L, driver.apply(inner))
        }
        assertEquals(1, innerEvaluations)
        assertEquals(1, outerEvaluations)
        assertEquals(2L, metrics.thunkEvaluations)
        assertEquals(5L, metrics.thunkHits)
        for (thunk in listOf(inner, outer)) {
            assertEquals(2, thunk.state)
            assertFalse(thunk.value is Thunk)
            assertReleased(thunk)
        }
    }

    @Test fun cafEntryTargetsRemainCompilableAfterValuesFunctionsAndFailuresAreMemoized() = withRuntime { language, _ ->
        fun binding(id: String, expression: List<Any?>) = mapOf(
            "id" to id, "name" to id, "type" to "Synthetic", "lifted" to true, "arity" to 0, "expr" to expression)
        fun delayed(expression: List<Any?>): List<Any?> = listOf("case", listOf("lit", "int", "0"), "ignored",
            listOf(listOf("default", null, emptyList<String>(), expression)))
        val data = mapOf(
            "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.ThunkRetention", "instrument" to true,
            "constructors" to listOf(mapOf("id" to "Done", "name" to "Done", "arity" to 0, "tag" to 1,
                "kind" to "boxed", "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>(),
                "fieldReps" to emptyList<Any>())),
            "bindings" to listOf(
                binding("value", delayed(listOf("con", "Done", 0))),
                binding("function", delayed(listOf("lam", listOf(mapOf("id" to "x", "name" to "x",
                    "type" to "Int#", "lifted" to false, "coercion" to false)), listOf("var", "x")))),
                binding("failure", listOf("var", "failure"))))
        for (backend in listOf("ast", "bytecode")) {
            val program: ExecutableProgram = if (backend == "ast") Program(language, data) else BytecodeProgram(language, data)
            for (name in listOf("value", "function", "failure")) {
                val thunk = program.entryValue(name) as Thunk
                assertSame(thunk.target, program.entryTarget(name), "$backend unentered $name")
                fun invokeEntry(): Any? = Calls.target(program.hostEntryTarget(0), arrayOf(thunk, emptyArray<Any?>()))
                val result = if (name == "failure") assertThrows(RuntimeFault::class.java) { invokeEntry() }
                    else invokeEntry()
                assertReleased(thunk)
                val expectedTarget: RootCallTarget = if (result is Closure) result.target else program.hostEntryTarget(0)
                assertSame(expectedTarget, program.entryTarget(name), "$backend updated $name")
                // OptimizedCallTarget.compile requires an initialized target. Exercise the same
                // host execution path used by clients before compiling the updated entry.
                repeat(8) {
                    if (name == "failure") assertSame(result, assertThrows(RuntimeFault::class.java) { invokeEntry() })
                    else assertSame(result, invokeEntry(), "$backend retains the already evaluated answer")
                    if (result is Closure) assertEquals(3_000_000_000L,
                        Calls.target(program.hostEntryTarget(1), arrayOf(thunk, arrayOf<Any?>(3_000_000_000L))))
                }
                val entry = EntryValue(program, name, if (result is Closure) 1 else 0)
                assertEquals(true, assertDoesNotThrow<Any>({ InteropLibrary.getUncached().invokeMember(entry, "compile") },
                    "$backend updated $name must install guest code"))
                if (name == "failure") assertSame(result, assertThrows(RuntimeFault::class.java) { invokeEntry() })
                else assertSame(result, invokeEntry(), "$backend retains the already evaluated answer")
                if (result is Closure) assertEquals(Long.MIN_VALUE,
                    Calls.target(program.hostEntryTarget(1), arrayOf(thunk, arrayOf<Any?>(Long.MIN_VALUE))))
            }
        }
    }
}
