// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.Calls
import thc.runtime.Force
import thc.runtime.Metrics
import thc.runtime.Thunk

class ThunkDiagnosticsTest {
    @Test fun unnamedRootCountsSerializeWithoutExecutingOrDiscardingThem() {
        fun unnamed() = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any = error("Recording must not execute the target")
        }.callTarget
        val first = unnamed()
        val second = unnamed()
        val named = object : RootNode(null) {
            override fun getName() = "unit:Module.named"
            override fun execute(frame: VirtualFrame): Any = error("Recording must not execute the target")
        }.callTarget
        assertNull(first.rootNode.name)
        val metrics = Metrics(true)
        repeat(2) { metrics.recordThunk(first) }
        metrics.recordThunk(second)
        repeat(4) { metrics.recordThunk(named) }
        val snapshot = metrics.thunkCountsSnapshot()
        assertEquals(mapOf("<unnamed>" to 3L, "unit:Module.named" to 4L), snapshot)
        assertEquals(mapOf("thunkEvaluationsByLabel" to snapshot),
            Json.parse(Json.stringify(mapOf("thunkEvaluationsByLabel" to snapshot))))
        metrics.recordThunk(first)
        assertEquals(3L, snapshot["<unnamed>"], "Snapshot must not change after subsequent evaluations")
        assertEquals(4L, metrics.thunkCountsSnapshot()["<unnamed>"])
    }

    @Test fun actualUnnamedThunkRetainsOneEvaluationAndItsMemoizedValue() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val metrics = Metrics(true)
                val answer = Any()
                var evaluated = 0
                val thunk = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any { evaluated++; return answer }
                }.callTarget, null)
                val driver = object : RootNode(null) {
                    @Child private var force = Force(metrics)
                    override fun execute(frame: VirtualFrame): Any? = force.execute(frame, thunk)
                }.callTarget
                repeat(2) { assertSame(answer, Calls.target(driver, emptyArray())) }
                assertEquals(1, evaluated)
                assertEquals(1L, metrics.thunkEvaluations)
                assertEquals(1L, metrics.thunkHits)
                assertNull(thunk.target)
                val diagnostics = mapOf("thunkEvaluations" to metrics.thunkEvaluations,
                    "thunkEvaluationsByLabel" to metrics.thunkCountsSnapshot())
                assertEquals(mapOf("<unnamed>" to 1L), diagnostics["thunkEvaluationsByLabel"])
                assertEquals(diagnostics, Json.parse(Json.stringify(diagnostics)))
            } finally { context.leave() }
        }
    }

    @Test fun nullAndNonStringJsonObjectKeysRemainInvalid() {
        for (key in listOf(null, 1L)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Json.stringify(mapOf("thunkEvaluationsByLabel" to mapOf(key to 2L)))
            }
            assertEquals("JSON object key must be a string", failure.message)
        }
    }
}
