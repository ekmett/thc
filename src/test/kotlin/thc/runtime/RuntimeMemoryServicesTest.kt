// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.Language

class RuntimeMemoryServicesTest {
    @Test fun selectorsPreserveBytesAndChooseHeapOrNonheap() {
        val heap = MemoryUsage(1024, 2048, 4096, 8192)
        val nonheap = MemoryUsage(64, 128, 256, 512)
        val expected = listOf(2048L, 4096L, 8192L, 1024L, 128L, 256L, 512L, 64L)
        for (selector in 200..207) {
            var reads = 0
            val actual = RuntimeMemoryServices.queryJvm(selector, 0, 0) {
                reads++
                if (it) heap else nonheap
            }
            assertEquals(expected[selector - 200], actual)
            assertEquals(1, reads, "Each query obtains one MemoryUsage")
        }
    }

    @Test fun unknownFieldsAreNotReportedAsZeroOrOtherStatuses() {
        val unknown = MemoryUsage(-1, 0, 0, -1)
        for (selector in listOf(202, 203, 206, 207)) {
            assertEquals(RuntimeServiceStatus.UNAVAILABLE,
                RuntimeMemoryServices.queryJvm(selector, 0, 0) { unknown })
        }
        for (selector in listOf(200, 201, 204, 205)) {
            assertEquals(0L, RuntimeMemoryServices.queryJvm(selector, 0, 0) { unknown })
        }
        assertEquals(RuntimeServiceStatus.UNAVAILABLE,
            RuntimeMemoryServices.queryJvm(200, 0, 0) { null })
    }

    @Test fun optionalProviderFailuresDoNotHideVmFailuresOrRuntimeFaults() {
        assertEquals(RuntimeServiceStatus.DENIED,
            RuntimeMemoryServices.queryJvm(200, 0, 0) { throw SecurityException("read denied") })
        assertEquals(RuntimeServiceStatus.UNSUPPORTED,
            RuntimeMemoryServices.queryJvm(200, 0, 0) { throw UnsupportedOperationException() })
        val fatal = AssertionError("fatal")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            RuntimeMemoryServices.queryJvm(200, 0, 0) { throw fatal }
        })
        val fault = RuntimeFault("cancel/control")
        assertSame(fault, assertThrows(RuntimeFault::class.java) {
            RuntimeMemoryServices.queryJvm(200, 0, 0) { throw fault }
        })
    }

    @Test fun malformedArgumentsAreRejectedBeforeReadingTheProvider() {
        for ((selector, index, detail) in listOf(Triple(199, 0L, 0L), Triple(208, 0L, 0L),
            Triple(200, -1L, 0L), Triple(200, Long.MAX_VALUE, 0L), Triple(200, 0L, -1L))) {
            assertThrows(RuntimeFault::class.java) {
                RuntimeMemoryServices.queryJvm(selector, index, detail) { fail("Provider must not be called") }
            }
        }
    }

    @Test fun realJvmProviderReportsReadOnlyStatistics() {
        val bean = ManagementFactory.getMemoryMXBean()
        val verbose = bean.isVerbose
        for (selector in 200..207) {
            val value = RuntimeMemoryServices.queryJvm(selector, 0, 0)
            assertTrue(value >= 0 || value == RuntimeServiceStatus.UNAVAILABLE, "selector $selector: $value")
        }
        assertEquals(verbose, bean.isVerbose, "Queries do not alter JVM-global GC verbosity")
    }

    @Test fun emptyOwnedNativeStatisticsDoNotRequireNativeAccess(): Unit = context(false) { state ->
        assertEquals(0L, query(state, 208))
        assertEquals(0L, query(state, 209))
        assertThrows(RuntimeFault::class.java) { RuntimeMemoryServices.query(state, 210, 0, 0) }
        assertThrows(RuntimeFault::class.java) { RuntimeMemoryServices.query(state, 208, 1, 0) }
        assertThrows(RuntimeFault::class.java) { RuntimeMemoryServices.query(state, 209, 0, 1) }
    }

    @Test fun ownedBytesFollowMallocReallocFreeAndContextIsolation() {
        nativePlatform()
        context(true) { first ->
            val registry = first.nativeAllocations
            val a = registry.malloc(19)
            val b = registry.malloc(31)
            assertNotSame(ManagedAddress.nullAddress(), a)
            assertNotSame(ManagedAddress.nullAddress(), b)
            assertEquals(50L, query(first, 208))
            assertEquals(2L, query(first, 209))
            context(true) { second ->
                assertEquals(0L, query(second, 208))
                assertEquals(0L, query(second, 209))
                second.nativeAllocations.malloc(7)
                assertEquals(7L, query(second, 208))
                assertEquals(1L, query(second, 209))
            }
            assertEquals(50L, query(first, 208))
            val resized = registry.realloc(a, 67)
            assertNotSame(ManagedAddress.nullAddress(), resized)
            assertEquals(98L, query(first, 208))
            assertEquals(2L, query(first, 209))
            registry.free(b)
            assertEquals(67L, query(first, 208))
            assertEquals(1L, query(first, 209))
            assertSame(ManagedAddress.nullAddress(), registry.realloc(resized, 0))
            assertEquals(0L, query(first, 208))
            assertEquals(0L, query(first, 209))
        }
    }

    @Test fun zeroSizedAllocationsAndFailureHaveHonestOwnershipCounts() {
        nativePlatform()
        context(true) { state ->
            val registry = state.nativeAllocations
            val zero = registry.malloc(0)
            val expectedCount = if (zero === ManagedAddress.nullAddress()) 0L else 1L
            assertEquals(0L, query(state, 208))
            assertEquals(expectedCount, query(state, 209))
            assertSame(ManagedAddress.nullAddress(), registry.malloc(Long.MAX_VALUE))
            assertEquals(0L, query(state, 208))
            assertEquals(expectedCount, query(state, 209))
            registry.free(zero)
            assertEquals(0L, query(state, 209))
        }
    }

    @Test fun contextDisposalReleasesRecordedAllocations() {
        nativePlatform()
        val registry = context(true) { state ->
            state.nativeAllocations.malloc(37)
            assertEquals(37L, query(state, 208))
            state.nativeAllocations
        }
        assertEquals(0L, registry.liveBytes())
        assertEquals(0, registry.liveCount())
    }

    private fun query(state: Language.State, selector: Int) = RuntimeMemoryServices.query(state, selector, 0, 0)
    private fun nativePlatform() = assumeTrue(System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64"))
    private fun <T> context(native: Boolean, action: (Language.State) -> T): T =
        Context.newBuilder("thc").allowNativeAccess(native).build().use { context ->
            context.initialize("thc")
            context.enter()
            try { action(Language.currentState()) } finally { context.leave() }
        }
}
