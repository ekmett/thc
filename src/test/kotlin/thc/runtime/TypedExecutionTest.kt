// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.nodes.UnexpectedResultException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.executionContext

class TypedExecutionTest {
    private class Effects {
        var childExecutions = 0
        var thunkEvaluations = 0
        var compiledEntries = 0
    }

    private class SuppliedValue(private val effects: Effects) : Expr() {
        override fun execute(frame: VirtualFrame): Any? {
            effects.childExecutions++
            return frame.arguments[0]
        }
    }

    private class EvaluationRoot(val effects: Effects, metrics: Metrics) : RootNode(null) {
        @Child private var value = Evaluate(SuppliedValue(effects), metrics)
        override fun execute(frame: VirtualFrame): Any {
            if (CompilerDirectives.inCompiledCode()) effects.compiledEntries++
            return value.executeLong(frame)
        }
    }

    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        assertTrue(type.isInstance(target))
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(validLastTier(target), "Requested guest code must be installed")
    }

    private fun validLastTier(target: RootCallTarget): Boolean =
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            .getMethod("isValidLastTier").invoke(target) == true

    @Test fun typedEvaluationRunsItsChildOnceWhenACompiledLongPathReceivesAThunk() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val effects = Effects()
                val metrics = Metrics(true)
                val root = EvaluationRoot(effects, metrics)
                val target = root.callTarget
                val answer = 3_000_000_017L
                val thunkTarget = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        effects.thunkEvaluations++
                        return answer
                    }
                    override fun getName() = "typed evaluation test thunk"
                }.callTarget
                fun check(value: Any, expected: Long) {
                    val before = effects.childExecutions
                    assertEquals(expected, Calls.target(target, arrayOf(value)))
                    assertEquals(before + 1, effects.childExecutions,
                        "Typed fallback must consume the returned value rather than executing the child again")
                }
                repeat(40) { check(Long.MAX_VALUE, Long.MAX_VALUE) }
                compile(target)
                val compiledBefore = effects.compiledEntries
                check(Long.MIN_VALUE, Long.MIN_VALUE)
                assertTrue(effects.compiledEntries > compiledBefore)

                // The first unexpected representation arrives only after installing the primitive path.
                val firstThunk = Thunk(thunkTarget, null)
                check(firstThunk, answer)
                assertEquals(1, effects.thunkEvaluations)
                assertEquals(1L, metrics.thunkEvaluations)
                assertEquals(2, firstThunk.state)
                check(firstThunk, answer)
                assertEquals(1, effects.thunkEvaluations, "Forcing the same thunk again observes its update")
                assertEquals(1L, metrics.thunkHits)

                // Warm both representations before recompiling. Fresh thunks must not throw a
                // fresh UnexpectedResultException on every entry into the now-general path.
                repeat(40) {
                    check(Thunk(thunkTarget, null), answer)
                    check(Long.MIN_VALUE, Long.MIN_VALUE)
                }
                compile(target)
                val compiledStableBefore = effects.compiledEntries
                repeat(8) {
                    check(Thunk(thunkTarget, null), answer)
                    assertTrue(validLastTier(target), "A stable thunk fallback must retain installed code")
                    check(Long.MAX_VALUE, Long.MAX_VALUE)
                    assertTrue(validLastTier(target), "The generalized path must still accept primitive values")
                }
                assertEquals(compiledStableBefore + 16, effects.compiledEntries,
                    "Every call after recompilation must enter installed code")
            } finally { context.leave() }
        }
    }

    @Test fun runtimeTypesPreserveUnexpectedValuesAndDoNotCoerceDistinctPrimitiveKinds() {
        assertEquals(Long.MIN_VALUE, RuntimeTypesGen.expectLong(Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, RuntimeTypesGen.expectLong(Long.MAX_VALUE))
        assertTrue(RuntimeTypesGen.expectBoolean(true))
        assertSame(Unit, RuntimeTypesGen.expectUnit(Unit))
        val marker = Any()
        for (unexpected in listOf(marker, true, 1)) {
            val failure = assertThrows(UnexpectedResultException::class.java) { RuntimeTypesGen.expectLong(unexpected) }
            assertSame(unexpected, failure.result, "Forwarders need the original value without re-execution")
        }
        val unitFailure = assertThrows(UnexpectedResultException::class.java) { RuntimeTypesGen.expectUnit(marker) }
        assertSame(marker, unitFailure.result)
    }
}
