// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.runtime.AbstractCompilationTask
import com.oracle.truffle.runtime.OptimizedCallTarget
import com.oracle.truffle.runtime.OptimizedTruffleRuntime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CompilerCpuAffinityTest {
    @Test fun synchronousCallerAndRenamedOrdinaryThreadAreUntouched() {
        Truffle.getRuntime()
        val calls = AtomicInteger()
        val listener = CompilerAffinityListener { calls.incrementAndGet(); null }
        val target = RootNode.createConstantNode(42).callTarget as OptimizedCallTarget
        val task = object : AbstractCompilationTask() {
            override fun isCancelled() = false
            override fun isLastTier() = true
            override fun hasNextTier() = false
        }
        listener.onCompilationStarted(target, task)
        val thread = Thread.ofPlatform().name("TruffleCompilerThread-999").start(object : Runnable {
            override fun run() { listener.onCompilationStarted(target, task) }
        })
        thread.join()
        assertEquals(0, calls.get())
    }

    @Test fun compilerWorkerResetsWithoutClosingTokenOrChangingCaller() {
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        val runtime = Truffle.getRuntime() as OptimizedTruffleRuntime
        val listener = CompilerAffinityListener {
            worker.set(Thread.currentThread())
            calls.incrementAndGet()
            AutoCloseable { closes.incrementAndGet() }
        }
        runtime.addListener(listener)
        try {
            val target = RootNode.createConstantNode(42).callTarget as OptimizedCallTarget
            assertEquals(42, target.call())
            target.compile(true)
            runtime.waitForCompilation(target, 30_000)
            assertTrue(target.isValidLastTier)
            assertEquals(42, target.call())
            assertTrue(target.isValidLastTier)
        } finally { runtime.removeListener(listener) }
        assertTrue(calls.get() > 0)
        assertNotSame(caller, worker.get())
        assertEquals(0, closes.get())
    }

    @Test fun unavailableNativeResetDoesNotFailCompilation() {
        val calls = AtomicInteger()
        val runtime = Truffle.getRuntime() as OptimizedTruffleRuntime
        val listener = CompilerAffinityListener {
            calls.incrementAndGet()
            throw UnsupportedOperationException("affinity unavailable")
        }
        runtime.addListener(listener)
        try {
            val target = RootNode.createConstantNode(17).callTarget as OptimizedCallTarget
            assertEquals(17, target.call())
            target.compile(true)
            runtime.waitForCompilation(target, 30_000)
            assertTrue(target.isValidLastTier)
            assertEquals(17, target.call())
        } finally { runtime.removeListener(listener) }
        assertTrue(calls.get() > 0)
    }
}
