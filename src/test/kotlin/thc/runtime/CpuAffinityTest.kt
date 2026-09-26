// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicReference

@Timeout(30)
class CpuAffinityTest {
    @Test fun unavailableNativeAccessStillReportsCpuCapacityAndForkSelection() {
        val affinity = CpuAffinity.discover(false)
        assertEquals(CpuAffinityMode.UNAVAILABLE, affinity.mode)
        assertEquals(Runtime.getRuntime().availableProcessors(), affinity.count)
        assertNull(affinity.bindCurrent(Long.MIN_VALUE))
        assertNull(affinity.resetCurrent())
        val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }, CpuAffinity(null, 3)) { }
        for (requested in listOf(Long.MIN_VALUE, -1L, 0L, 4L, Long.MAX_VALUE)) inThread {
            threads.enterCurrent(forked = true, capability = requested)
            try {
                assertEquals(Math.floorMod(requested, 3L), threads.currentIdentity().capability)
                assertTrue(threads.currentIdentity().capabilityLocked)
                assertFalse(threads.currentIdentity().affinityApplied)
                assertEquals(3L, threads.capabilityCount())
            } finally { threads.leaveCurrent() }
        }
    }

    @Test fun logicalCountRespectsQuotaAndRejectedRequestIsOptional() {
        val requests = ArrayList<Int>()
        val provider = object : NativeCpuAffinity {
            override val count = 8
            override val mode = CpuAffinityMode.PINNED
            override fun bindCurrent(index: Int): AutoCloseable? { requests.add(index); return null }
            override fun resetCurrent(): AutoCloseable? = null
        }
        val affinity = CpuAffinity(provider, 3)
        assertEquals(3, affinity.count)
        assertNull(affinity.bindCurrent(-1))
        assertEquals(listOf(2), requests)
        assertEquals(8, CpuAffinity(provider, 32).count)
        assertEquals(1, CpuAffinity(null, 0).count)
    }

    @Test fun linuxPinAndNestedResetRestoreMasksEvenOnExceptions() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val provider = LinuxCpuAffinity.discover()
        assertNotNull(provider, "Linux test JVM enables native access")
        provider!!
        val original = provider.currentMask()!!
        val expectedCount = minOf(provider.count, Runtime.getRuntime().availableProcessors())
        val affinity = CpuAffinity(provider, expectedCount)
        inThread {
            val pin = provider.bindCurrent(0)
            assertNotNull(pin, "At least the current eligible CPU must be selectable")
            assertThrows(IllegalStateException::class.java) {
                pin.use {
                    val singleton = provider.currentMask()!!
                    assertEquals(1, bits(singleton))
                    assertEquals(expectedCount, affinity.count, "Snapshot does not query a pinned carrier")
                    inThread {
                        assertArrayEquals(singleton, provider.currentMask(), "Linux pthread inheritance")
                        provider.resetCurrent().use {
                            assertArrayEquals(original, provider.currentMask())
                            System.gc()
                            repeat(128) { ByteArray(8192).also { assertEquals(8192, it.size) } }
                        }
                        assertArrayEquals(singleton, provider.currentMask())
                    }
                    assertArrayEquals(singleton, provider.currentMask())
                    // This is the production start() pattern: reset the creator,
                    // create a child with broad eligibility, then restore its pin.
                    provider.resetCurrent().use { inThread { assertArrayEquals(original, provider.currentMask()) } }
                    assertArrayEquals(singleton, provider.currentMask())
                    error("guest unwind")
                }
            }
            assertArrayEquals(original, provider.currentMask())
            assertNull(provider.bindCurrent(-1))
            assertNull(provider.bindCurrent(provider.count))
        }
        assertArrayEquals(original, provider.currentMask(), "Never changes the unrelated host carrier")
        val virtualFailure = AtomicReference<Throwable?>()
        val virtual = Thread.ofVirtual().start {
            try { assertNull(provider.bindCurrent(0)); assertNull(provider.resetCurrent()) }
            catch (failure: Throwable) { virtualFailure.set(failure) }
        }
        virtual.join()
        virtualFailure.get()?.let { throw AssertionError("virtual guard", it) }
    }

    private fun bits(mask: ByteArray) = mask.sumOf { Integer.bitCount(it.toInt() and 255) }
    private fun inThread(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread { try { action() } catch (error: Throwable) { failure.set(error) } }
        thread.start(); thread.join(10000)
        assertFalse(thread.isAlive)
        failure.get()?.let { throw AssertionError("platform child", it) }
    }
}
