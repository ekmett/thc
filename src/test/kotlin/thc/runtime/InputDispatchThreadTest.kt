// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InputDispatchThreadTest {
    private class AddRoot(private val amount: Long) : GuestRoot(null, FrameDescriptor.newBuilder().build()) {
        init { configureEntry(booleanArrayOf(false), false) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any = (frame.arguments[1] as Long) + amount
    }

    private class CallerRoot : RootNode(null) {
        @Child private var dispatch = InputDispatch(ScalarArrayInputSource(null), 1, false, Metrics(false))
        override fun execute(frame: VirtualFrame): Any? = dispatch.execute(
            frame, frame.arguments[0] as Closure, arrayOf(frame.arguments[1]))
    }

    @Test fun concurrentColdArmsAndGenericFallbackPreserveAllTargets() {
        val host = CallerRoot().callTarget
        val closures = (0L until 5L).map { Closure(null, arity = 1, target = AddRoot(it * 17L).callTarget) }
        val barrier = CyclicBarrier(5)
        val workers = Executors.newFixedThreadPool(5)
        try {
            val calls = (0 until 5).map { worker -> workers.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(500) { step ->
                    val index = (worker + step) % closures.size
                    val input = (worker * 1000 + step).toLong()
                    assertEquals(input + index * 17L,
                        Calls.target(host, arrayOf(closures[index], input)), "worker=$worker step=$step target=$index")
                }
            }) }
            calls.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Call-site workers did not terminate")
        }
    }
}
