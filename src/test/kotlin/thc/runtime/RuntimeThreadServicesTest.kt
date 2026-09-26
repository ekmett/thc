// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.lang.reflect.Proxy
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(30)
class RuntimeThreadServicesTest {
    private fun threads(affinity: CpuAffinity = CpuAffinity(null, 4)) =
        GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }, affinity) { }

    private fun query(threads: GuestThreads, selector: Int, index: Long = 0, detail: Long = 0,
                      native: Boolean = true) = RuntimeThreadServices.query(threads, native, selector, index, detail)

    private fun bean(extended: Boolean = true, answer: (String) -> Any): ThreadMXBean =
        Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(if (extended) com.sun.management.ThreadMXBean::class.java else ThreadMXBean::class.java)
        ) { _, method, _ ->
            assertFalse(method.name.startsWith("set"), "Observation must not enable JVM accounting")
            answer(method.name)
        } as ThreadMXBean

    @Test fun supportedAccountingReadsExactValuesWithoutMutatingOrEnumeratingThreads() {
        val calls = ArrayList<String>()
        val management = bean { name ->
            calls.add(name)
            when (name) {
                "isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled",
                "isThreadAllocatedMemorySupported", "isThreadAllocatedMemoryEnabled" -> true
                "getCurrentThreadCpuTime" -> 17L
                "getCurrentThreadUserTime" -> 11L
                "getCurrentThreadAllocatedBytes" -> 4096L
                else -> error("Unexpected bean operation: $name")
            }
        }
        assertEquals(17L, RuntimeThreadServices.accounting(105, false) { management })
        assertEquals(11L, RuntimeThreadServices.accounting(106, false) { management })
        assertEquals(4096L, RuntimeThreadServices.accounting(107, false) { management })
        assertEquals(listOf("isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled", "getCurrentThreadCpuTime",
            "isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled", "getCurrentThreadUserTime",
            "isThreadAllocatedMemorySupported", "isThreadAllocatedMemoryEnabled", "getCurrentThreadAllocatedBytes"), calls)
    }

    @Test fun disabledUnsupportedDeniedAndUnavailableAreDistinct() {
        for (selector in 105..107) {
            val disabled = bean { name ->
                when {
                    name.endsWith("Supported") -> true
                    name.endsWith("Enabled") -> false
                    else -> error("Disabled measurement must not be read: $name")
                }
            }
            assertEquals(RuntimeServiceStatus.DISABLED, RuntimeThreadServices.accounting(selector, false) { disabled })
            val unsupported = bean { name ->
                if (name.endsWith("Supported")) false else error("Unsupported measurement queried: $name")
            }
            assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(selector, false) { unsupported })
            val unavailable = bean { name -> if (name.startsWith("is")) true else -1L }
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, RuntimeThreadServices.accounting(selector, false) { unavailable })
            assertEquals(RuntimeServiceStatus.DENIED, RuntimeThreadServices.accounting(selector, false) {
                throw SecurityException("management denied")
            })
            assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(selector, false) {
                throw UnsupportedOperationException("no provider")
            })
            val deniedRead = bean { name -> if (name.startsWith("is")) true else throw SecurityException("read denied") }
            assertEquals(RuntimeServiceStatus.DENIED, RuntimeThreadServices.accounting(selector, false) { deniedRead })
        }
        val standard = bean(extended = false) { error("Allocation requires the optional extension") }
        assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(107, false) { standard })
    }

    @Test fun optionalAccountingDoesNotHideProgrammingOrVmFailures() {
        assertThrows(IllegalStateException::class.java) {
            RuntimeThreadServices.accounting(105, false) { error("unexpected provider failure") }
        }
        assertThrows(AssertionError::class.java) {
            RuntimeThreadServices.accounting(105, false) { throw AssertionError("VM control") }
        }
        assertThrows(RuntimeFault::class.java) { RuntimeThreadServices.accounting(100) }
    }

    @Test fun realPlatformThreadAccountingPreservesInstrumentationSettings() {
        val management = ManagementFactory.getThreadMXBean()
        val allocation = management as? com.sun.management.ThreadMXBean
        val cpuEnabled = if (management.isCurrentThreadCpuTimeSupported) management.isThreadCpuTimeEnabled else null
        val allocationEnabled = if (allocation?.isThreadAllocatedMemorySupported == true)
            allocation.isThreadAllocatedMemoryEnabled else null
        assertEquals(1L, query(threads(), 100))
        for (selector in 105..107) {
            val enabled = if (selector == 107) allocationEnabled else cpuEnabled
            val before = RuntimeThreadServices.accounting(selector)
            val after = RuntimeThreadServices.accounting(selector)
            when (enabled) {
                null -> assertEquals(RuntimeServiceStatus.UNSUPPORTED, before)
                false -> assertEquals(RuntimeServiceStatus.DISABLED, before)
                true -> {
                    assertTrue(before >= 0, "Pinned JVM supports this platform-thread measurement")
                    assertTrue(after >= before)
                }
            }
        }
        if (cpuEnabled != null) assertEquals(cpuEnabled, management.isThreadCpuTimeEnabled)
        if (allocationEnabled != null) assertEquals(allocationEnabled, allocation!!.isThreadAllocatedMemoryEnabled)
    }

    @Test fun actualVirtualThreadNeverReportsCarrierAccountingOrAffinity() {
        val task = FutureTask {
            val current = threads()
            assertEquals(2L, query(current, 100))
            assertEquals(RuntimeServiceStatus.UNSUPPORTED, query(current, 103))
            for (selector in 105..107) {
                assertEquals(RuntimeServiceStatus.UNSUPPORTED, query(current, selector))
                assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(selector) {
                    error("Virtual thread must not ask a bean for carrier accounting")
                })
            }
        }
        Thread.ofVirtual().start(task)
        task.get(10, TimeUnit.SECONDS)
    }

    @Test fun logicalLockAndRecordedAcceptanceAreIndependentOfNativeSupport() {
        val current = threads()
        current.enterCurrent(capability = -1)
        try {
            assertEquals(3L, query(current, 101))
            assertEquals(1L, query(current, 102))
            assertEquals(0L, query(current, 104))
            assertEquals(RuntimeServiceStatus.DENIED, query(current, 103, native = false))
            current.currentIdentity().affinityApplied = true
            assertEquals(1L, query(current, 104), "Reports accepted-at-fork state, not a fresh OS guarantee")
            val separate = threads()
            separate.enterCurrent()
            try {
                assertEquals(0L, query(separate, 102))
                assertEquals(0L, query(separate, 104), "Context identities do not share acceptance state")
            } finally { separate.leaveCurrent() }
        } finally { current.leaveCurrent() }
        assertThrows(RuntimeFault::class.java) { query(current, 101) }
    }

    @Test fun eligibilityUsesQuotaBoundedDenseMappingAndNeverChangesAffinity() {
        val coordinates = listOf(CpuCoordinate(4, 1), CpuCoordinate(4, 17), CpuCoordinate(4, 63))
        val affinity = CpuAffinity(object : NativeCpuAffinity {
            override val count = coordinates.size
            override val mode = CpuAffinityMode.ADVISORY
            override fun coordinate(index: Int) = coordinates.getOrNull(index)
            override fun bindCurrent(index: Int): AutoCloseable? = error("Read-only query tried to bind")
            override fun resetCurrent(): AutoCloseable? = error("Read-only query tried to reset")
        }, 2)
        val current = threads(affinity)
        assertEquals(1L, query(current, 103))
        assertEquals(2L, query(current, 108), "JVM quota bounds the capability mapping")
        assertEquals(4L, query(current, 109, 1))
        assertEquals(17L, query(current, 110, 1))
        assertNull(affinity.coordinate(2))
        assertNull(affinity.coordinate(-1))
        assertThrows(RuntimeFault::class.java) { query(current, 110, 2) }
        for (selector in listOf(103, 108, 109, 110))
            assertEquals(RuntimeServiceStatus.DENIED, query(current, selector, native = false))
    }

    @Test fun realLinuxCoordinatesMatchOriginalSparseMaskWithoutPinning() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val provider = LinuxCpuAffinity.discover()
        assertNotNull(provider, "Native access is enabled in the test JVM")
        provider!!
        val original = provider.currentMask()!!
        val cpus = (0 until original.size * 8).filter { original[it / 8].toInt() and (1 shl (it % 8)) != 0 }
        val affinity = CpuAffinity(provider, Runtime.getRuntime().availableProcessors())
        val current = threads(affinity)
        assertEquals(2L, query(current, 103))
        assertEquals(affinity.count.toLong(), query(current, 108))
        for (index in 0 until affinity.count) {
            assertEquals(0L, query(current, 109, index.toLong()))
            assertEquals(cpus[index].toLong(), query(current, 110, index.toLong()))
        }
        assertArrayEquals(original, provider.currentMask())
    }

    @Test fun malformedQueryIndicesAndUnknownSelectorsFailExplicitly() {
        val current = threads()
        for (selector in 100..110) {
            assertThrows(RuntimeFault::class.java) { query(current, selector, detail = 1) }
            assertThrows(RuntimeFault::class.java) { query(current, selector, index = -1) }
            if (selector !in 109..110) assertThrows(RuntimeFault::class.java) { query(current, selector, index = 1) }
        }
        for (selector in listOf(99, 111, 199)) assertThrows(RuntimeFault::class.java) { query(current, selector) }
        assertThrows(RuntimeFault::class.java) { query(current, 110, Long.MAX_VALUE) }
    }
}
