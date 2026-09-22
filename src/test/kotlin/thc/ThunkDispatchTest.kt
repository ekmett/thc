package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

class ThunkDispatchTest {
    private class ForceDriver(val metrics: Metrics) : RootNode(null) {
        @Child private var force = Force(metrics)
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun apply(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
    }

    private fun withCapture(action: (CapturedFrame) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val layout = FrameLayout()
                val slot = layout.bind("captured")
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
                FrameAccess.write(frame, slot, 3_000_000_000L)
                val capture = CaptureLayout(language, booleanArrayOf(true)).capture(frame, intArrayOf(slot))
                action(capture)
            } finally { context.leave() }
        }
    }

    @Test fun closedAndCapturedThunksKeepTheirArgumentsAfterTargetCacheSaturates() = withCapture { environment ->
        var evaluations = 0
        fun target(captured: Boolean, increment: Long): RootCallTarget = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                evaluations++
                assertEquals(0L, frame.arguments[0])
                assertEquals(if (captured) 2 else 1, frame.arguments.size)
                val base = if (captured) {
                    assertSame(environment, frame.arguments[1])
                    (frame.arguments[1] as CapturedFrame).getLong(0)
                } else 0L
                return base + increment
            }
        }.callTarget
        val cases = listOf(false to 11L, true to 22L, false to 33L, true to 44L, false to 55L)
        val targets = cases.map { (captured, increment) -> target(captured, increment) }
        val metrics = Metrics(true)
        val driver = ForceDriver(metrics)
        // Exercise both direct shapes, overflow the bounded three-target cache,
        // then revisit every shape with fresh thunks through its indirect path.
        for (index in cases.indices + cases.indices.reversed()) {
            val (captured, increment) = cases[index]
            val thunk = Thunk(targets[index], if (captured) environment else null)
            val expected = (if (captured) 3_000_000_000L else 0L) + increment
            assertEquals(expected, driver.apply(thunk))
            assertEquals(2, thunk.state)
            assertNull(thunk.environment, "Successful update releases the captured environment")
            assertEquals(expected, driver.apply(thunk))
        }
        assertEquals(10, evaluations, "Each thunk target is entered exactly once")
        assertEquals(10L, metrics.thunkEvaluations)
        assertEquals(10L, metrics.thunkHits)
        assertEquals(3L, metrics.directCacheMisses)
        assertTrue(metrics.indirectCalls > 0L)
    }

    @Test fun capturedThunkTailBounceStillUpdatesTheOriginalThunk() = withCapture { environment ->
        var completions = 0
        val finalTarget = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                completions++
                assertEquals(0L, frame.arguments[0])
                assertSame(environment, frame.arguments[1])
                return (frame.arguments[1] as CapturedFrame).getLong(0)
            }
        }.callTarget
        val bouncingTarget = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any {
                throw TailCall(finalTarget, arrayOf(null, frame.arguments[1]))
            }
        }.callTarget
        val driver = ForceDriver(Metrics(true))
        val thunk = Thunk(bouncingTarget, environment)
        assertEquals(3_000_000_000L, driver.apply(thunk))
        assertEquals(2, thunk.state)
        assertNull(thunk.environment)
        assertEquals(3_000_000_000L, driver.apply(thunk))
        assertEquals(1, completions)
    }
}
