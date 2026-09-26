// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeGCServicesTest {
    private class Collector(
        private val label: String, private val count: Long = 17, private val millis: Long = 23,
        private val valid: Boolean = true
    ) : GarbageCollectorMXBean {
        override fun getName(): String = label
        override fun getCollectionCount(): Long = count
        override fun getCollectionTime(): Long = millis
        override fun isValid(): Boolean = valid
        override fun getMemoryPoolNames(): Array<String> = emptyArray()
        override fun getObjectName(): ObjectName = ObjectName("thc.test:type=GarbageCollector")
    }

    @Test fun namesAreUnicodeCodepointsAndCountersPreserveUnits() {
        val beans = listOf(Collector("young"), Collector("old \uD83D\uDE80", 29, 31))
        fun query(selector: Int, index: Long = 0, detail: Long = 0) =
            RuntimeGCServices.queryJvm(selector, index, detail) { beans }
        assertEquals(2L, query(300))
        assertEquals(5L, query(301, 1, -1))
        assertEquals('o'.code.toLong(), query(301, 1, 0))
        assertEquals(0x1f680L, query(301, 1, 4))
        assertEquals(17L, query(302))
        assertEquals(23L, query(303))
        assertEquals(29L, query(302, 1))
        assertEquals(31L, query(303, 1))
        assertThrows(RuntimeFault::class.java) { query(301, 1, 5) }
    }

    @Test fun missingCountersAndInvalidatedCollectorsAreUnavailable() {
        val missing = listOf(Collector("uncounted", -1, -1), Collector("invalid", valid = false))
        for (selector in listOf(302, 303)) assertEquals(RuntimeServiceStatus.UNAVAILABLE,
            RuntimeGCServices.queryJvm(selector, 0, 0) { missing })
        for (selector in 301..303) assertEquals(RuntimeServiceStatus.UNAVAILABLE,
            RuntimeGCServices.queryJvm(selector, 1, if (selector == 301) -1 else 0) { missing })
        assertEquals(0L, RuntimeGCServices.queryJvm(302, 0, 0) { listOf(Collector("zero", 0, 0)) })
        assertEquals(0L, RuntimeGCServices.queryJvm(303, 0, 0) { listOf(Collector("zero", 0, 0)) })
    }

    @Test fun emptyCollectorInventoryIsAnActualZeroNotUnknown() {
        assertEquals(0L, RuntimeGCServices.queryJvm(300, 0, 0) { emptyList() })
        assertThrows(RuntimeFault::class.java) { RuntimeGCServices.queryJvm(301, 0, -1) { emptyList() } }
    }

    @Test fun deniedAndUnsupportedQueriesDoNotSwallowUnexpectedFailures() {
        assertEquals(RuntimeServiceStatus.DENIED,
            RuntimeGCServices.queryJvm(300, 0, 0) { throw SecurityException() })
        assertEquals(RuntimeServiceStatus.UNSUPPORTED,
            RuntimeGCServices.queryJvm(300, 0, 0) { throw UnsupportedOperationException() })
        val fatal = AssertionError("fatal")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            RuntimeGCServices.queryJvm(300, 0, 0) { throw fatal }
        })
        val unexpected = IllegalStateException("unexpected")
        assertSame(unexpected, assertThrows(IllegalStateException::class.java) {
            RuntimeGCServices.queryJvm(300, 0, 0) { throw unexpected }
        })
    }

    @Test fun malformedIndicesSelectorsAndOffsetsRemainDiagnostics() {
        for ((selector, index, detail) in listOf(Triple(299, 0L, 0L), Triple(304, 0L, 0L),
            Triple(300, 1L, 0L), Triple(300, 0L, 1L), Triple(302, -1L, 0L),
            Triple(303, Long.MAX_VALUE, 0L), Triple(302, 0L, -1L), Triple(301, 0L, -2L))) {
            assertThrows(RuntimeFault::class.java) {
                RuntimeGCServices.queryJvm(selector, index, detail) { fail("Provider must not be called") }
            }
        }
        assertThrows(RuntimeFault::class.java) {
            RuntimeGCServices.queryJvm(302, 1, 0) { listOf(Collector("only")) }
        }
    }

    @Test fun realCollectorsMatchNamesAndOfferNonnegativeOrUnavailableCounters() {
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        assertEquals(beans.size.toLong(), RuntimeGCServices.queryJvm(300, 0, 0))
        for ((index, bean) in beans.withIndex()) {
            val name = bean.name.codePoints().toArray()
            assertEquals(name.size.toLong(), RuntimeGCServices.queryJvm(301, index.toLong(), -1))
            for ((offset, codepoint) in name.withIndex()) assertEquals(codepoint.toLong(),
                RuntimeGCServices.queryJvm(301, index.toLong(), offset.toLong()))
            for (selector in 302..303) {
                val value = RuntimeGCServices.queryJvm(selector, index.toLong(), 0)
                assertTrue(value >= 0 || value == RuntimeServiceStatus.UNAVAILABLE, "$selector: $value")
            }
        }
    }
}
