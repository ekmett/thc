// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Timeout(30)
class RuntimeTraceServicesTest {
    private class Jfr(var availability: Long = 0L, var emission: Long = 0L) : RuntimeTraceJfr {
        val events = ArrayList<List<Any>>()
        override fun support() = availability
        override fun emit(contextId: Long, phase: String, token: Long, name: String, elapsedNanos: Long): Long {
            if (emission == 0L) events.add(listOf(contextId, phase, token, name, elapsedNanos))
            return emission
        }
    }

    private fun emit(service: RuntimeTraceServices, operation: Int, text: String = "", token: Long = 0L): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return service.emit(operation, token, ManagedAddress.fromByteArray(bytes), bytes.size.toLong())
    }

    private fun records(output: ByteArrayOutputStream): List<Map<*, *>> =
        output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map { Json.parse(it) as Map<*, *> }.toList()

    @Test fun defaultDisabledAndQueriesDoNotEmitOrEnableAnything() {
        val output = ByteArrayOutputStream()
        val provider = Jfr()
        RuntimeTraceServices(output, provider).use { trace ->
            assertEquals(0L, trace.query(500, 0, 0))
            assertEquals(3L, trace.query(501, 0, 0))
            assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 0, "invisible"))
            assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 1, "invisible"))
            assertEquals(0, output.size())
            assertTrue(provider.events.isEmpty())
        }
    }

    @Test fun exactUtf8IsEscapedWithoutCreatingFakeRecords() {
        val output = ByteArrayOutputStream()
        RuntimeTraceServices(output, Jfr()).use { trace ->
            assertEquals(0L, trace.control(500, 1))
            val text = "quote\" slash\\ LF\nCR\r nul\u0000 del\u007f paragraph\u2029 雪 🎉"
            assertEquals(0L, emit(trace, 0, text))
            val record = records(output).single()
            assertEquals("trace", record["thc"])
            assertEquals("event", record["phase"])
            assertEquals(text, record["name"])
            assertEquals(0L, record["span"])
            assertEquals(0L, record["elapsedNanos"])
            assertEquals(Thread.currentThread().threadId(), record["thread"])
            assertTrue(output.toString(Charsets.UTF_8).contains("\\u0000"))
        }
    }

    @Test fun completePayloadAndAbiAreValidatedBeforeEffectsEvenWhenDisabled() {
        val output = ByteArrayOutputStream()
        RuntimeTraceServices(output, Jfr()).use { trace ->
            for (sink in listOf(0L, 1L)) {
                trace.control(500, sink)
                for (bad in listOf(byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0x61, 0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
                    assertThrows(RuntimeFault::class.java) {
                        trace.emit(0, 0, ManagedAddress.fromByteArray(bad), bad.size.toLong())
                    }
                }
                assertThrows(RuntimeFault::class.java) { trace.emit(0, 0, ManagedAddress.fromByteArray(byteArrayOf(0x61, 0x62)), 3) }
                assertThrows(RuntimeFault::class.java) { trace.emit(0, 0, ManagedAddress.nullAddress(), -1) }
                assertThrows(RuntimeFault::class.java) {
                    trace.emit(0, 0, ManagedAddress.nullAddress(), RuntimeTraceServices.MAX_INPUT_BYTES + 1)
                }
                assertThrows(RuntimeFault::class.java) { emit(trace, 4) }
                assertThrows(RuntimeFault::class.java) { emit(trace, 0, token = 1) }
                assertThrows(RuntimeFault::class.java) { emit(trace, 2) }
            }
            assertEquals(0, output.size())
        }
    }

    @Test fun spanLifecycleNamesNormalAndExceptionalCompletion() {
        val output = ByteArrayOutputStream()
        RuntimeTraceServices(output, Jfr()).use { trace ->
            trace.control(500, 1)
            val normal = emit(trace, 1, "normal")
            val failed = emit(trace, 1, "failed")
            assertTrue(normal > 0)
            assertTrue(failed > normal)
            assertThrows(RuntimeFault::class.java) { emit(trace, 2, "invalid end payload", normal) }
            assertEquals(0L, emit(trace, 2, token = normal))
            assertEquals(0L, emit(trace, 3, token = failed))
            assertThrows(RuntimeFault::class.java) { emit(trace, 2, token = normal) }
            val rows = records(output)
            assertEquals(listOf("begin", "begin", "end", "exception"), rows.map { it["phase"] })
            assertEquals(listOf(normal, failed, normal, failed), rows.map { it["span"] })
            assertEquals(listOf("normal", "failed", "normal", "failed"), rows.map { it["name"] })
            assertTrue(rows.all { (it["elapsedNanos"] as Long) >= 0 })
        }
    }

    @Test fun contextsDoNotShareConfigurationOrSpanOwnership() {
        val firstOutput = ByteArrayOutputStream()
        val secondOutput = ByteArrayOutputStream()
        RuntimeTraceServices(firstOutput, Jfr()).use { first ->
            RuntimeTraceServices(secondOutput, Jfr()).use { second ->
                first.control(500, 1)
                val firstSpan = emit(first, 1, "first")
                assertEquals(0L, second.query(500, 0, 0))
                assertThrows(RuntimeFault::class.java) { emit(second, 2, token = firstSpan) }
                second.control(500, 1)
                val secondSpan = emit(second, 1, "second")
                assertNotEquals(firstSpan, secondSpan)
                assertThrows(RuntimeFault::class.java) { emit(first, 2, token = secondSpan) }
                assertEquals(0L, emit(first, 2, token = firstSpan))
                assertEquals(0L, emit(second, 2, token = secondSpan))
                assertNotEquals(records(firstOutput).first()["context"], records(secondOutput).first()["context"])
                assertTrue(records(firstOutput).all { it["name"] == "first" })
                assertTrue(records(secondOutput).all { it["name"] == "second" })
            }
        }
    }

    @Test fun disablingRetiresLiveSpansWithoutOutputAndDisposalClosesTheService() {
        val output = ByteArrayOutputStream()
        val trace = RuntimeTraceServices(output, Jfr())
        trace.control(500, 1)
        val token = emit(trace, 1, "silent end")
        trace.control(500, 0)
        assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 2, token = token))
        assertThrows(RuntimeFault::class.java) { emit(trace, 2, token = token) }
        trace.control(500, 1)
        val abandoned = emit(trace, 1, "abandoned")
        trace.close()
        trace.close()
        assertEquals(RuntimeServiceStatus.UNAVAILABLE, trace.query(500, 0, 0))
        assertEquals(RuntimeServiceStatus.UNAVAILABLE, trace.control(500, 1))
        assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 2, token = abandoned))
        assertEquals(listOf("begin", "begin"), records(output).map { it["phase"] })
    }

    @Test fun activeSpansAndRetainedNamesAreBoundedAndEndReleasesCapacity() {
        val output = ByteArrayOutputStream()
        RuntimeTraceServices(output, Jfr(), maxActiveSpans = 2, maxRetainedNameBytes = 5).use { trace ->
            trace.control(500, 1)
            val first = emit(trace, 1, "1234")
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 1, "12"))
            val second = emit(trace, 1, "1")
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 1))
            assertEquals(0L, emit(trace, 2, token = first))
            assertTrue(emit(trace, 1, "1234") > 0)
            assertEquals(0L, emit(trace, 2, token = second))
            assertEquals(5, records(output).size)
        }
    }

    @Test fun controlsRejectBadRequestsWithoutChangingExistingSink() {
        RuntimeTraceServices(ByteArrayOutputStream(), Jfr()).use { trace ->
            trace.control(500, 1)
            assertThrows(RuntimeFault::class.java) { trace.control(501, 2) }
            assertThrows(RuntimeFault::class.java) { trace.control(500, 4) }
            assertThrows(RuntimeFault::class.java) { trace.control(500, -1) }
            assertThrows(RuntimeFault::class.java) { trace.query(502, 0, 0) }
            assertThrows(RuntimeFault::class.java) { trace.query(500, 1, 0) }
            assertThrows(RuntimeFault::class.java) { trace.query(500, 0, 1) }
            assertEquals(1L, trace.query(500, 0, 0))
        }
    }

    @Test fun optionalJfrStatusesAreExplicitAndBothSinksCanUseStderrAlone() {
        for (status in listOf(RuntimeServiceStatus.UNSUPPORTED, RuntimeServiceStatus.DENIED, RuntimeServiceStatus.UNAVAILABLE)) {
            RuntimeTraceServices(ByteArrayOutputStream(), Jfr(availability = status)).use { trace ->
                assertEquals(1L, trace.query(501, 0, 0))
                assertEquals(status, trace.control(500, 2))
                assertEquals(0L, trace.query(500, 0, 0))
            }
        }
        val output = ByteArrayOutputStream()
        val provider = Jfr(emission = RuntimeServiceStatus.DISABLED)
        RuntimeTraceServices(output, provider).use { trace ->
            assertEquals(0L, trace.control(500, 2))
            assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 1, "not recorded"))
            assertEquals(0L, trace.control(500, 3))
            val token = emit(trace, 1, "stderr only")
            assertTrue(token > 0)
            assertEquals(0L, emit(trace, 2, token = token))
            assertEquals(2, records(output).size)
            assertTrue(provider.events.isEmpty())
        }
    }

    @Test fun ioAndPermissionFailureAreNotReportedAsSuccessfulEmission() {
        val broken = object : OutputStream() {
            override fun write(value: Int) { throw IOException("closed test stream") }
        }
        RuntimeTraceServices(broken, Jfr()).use { trace ->
            trace.control(500, 1)
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 0, "write"))
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 1, "begin"))
        }
        RuntimeTraceServices(ByteArrayOutputStream(), Jfr(emission = RuntimeServiceStatus.DENIED)).use { trace ->
            trace.control(500, 2)
            assertEquals(RuntimeServiceStatus.DENIED, emit(trace, 0, "write"))
        }
    }

    @Test fun concurrentWritersProduceWholeRecordsAndUniqueOwnedTokens() {
        val output = ByteArrayOutputStream()
        val pool = Executors.newFixedThreadPool(4)
        try {
            RuntimeTraceServices(output, Jfr()).use { trace ->
                trace.control(500, 1)
                val jobs = (0..3).map { worker -> pool.submit {
                    repeat(40) { iteration ->
                        val token = emit(trace, 1, "worker-$worker-$iteration")
                        assertTrue(token > 0)
                        assertEquals(0L, emit(trace, 2, token = token))
                    }
                } }
                jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                val rows = records(output)
                assertEquals(320, rows.size)
                val pairs = rows.groupBy { it["span"] }
                assertEquals(160, pairs.size)
                assertTrue(pairs.values.all { pair ->
                    pair.map { it["phase"] } == listOf("begin", "end") && pair[0]["name"] == pair[1]["name"]
                })
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun actualJfrRecordsStructuredFieldsWithoutCreatingOrReconfiguringRecordings() {
        assumeTrue(FlightRecorder.isAvailable())
        val recorder = FlightRecorder.getFlightRecorder()
        val baseline = recorder.recordings.map { it.id }.toSet()
        val destination = Files.createTempFile("thc-trace-", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable("thc.RuntimeTrace").withThreshold(Duration.ZERO).withoutStackTrace()
                recording.start()
                val expectedRecordings = baseline + recording.id
                RuntimeTraceServices(ByteArrayOutputStream()).use { trace ->
                    assertEquals(3L, trace.query(501, 0, 0))
                    assertEquals(0L, trace.control(500, 2))
                    val settings = recording.settings.toMap()
                    assertEquals(0L, emit(trace, 0, "actual λ"))
                    val token = emit(trace, 1, "actual span")
                    assertTrue(token > 0)
                    assertEquals(0L, emit(trace, 3, token = token))
                    assertEquals(expectedRecordings, recorder.recordings.map { it.id }.toSet())
                    assertEquals(settings, recording.settings)
                }
                recording.stop()
                recording.dump(destination)
            }
            val events = RecordingFile.readAllEvents(destination).filter { it.eventType.name == "thc.RuntimeTrace" }
            assertEquals(listOf("event", "begin", "exception"), events.map { it.getString("phase") })
            assertEquals(listOf("actual λ", "actual span", "actual span"), events.map { it.getString("message") })
            assertEquals(events[1].getLong("spanId"), events[2].getLong("spanId"))
            assertTrue(events.all { it.getLong("contextId") > 0 && it.getLong("elapsedNanos") >= 0 })
            assertTrue(events.all { it.thread.javaThreadId == Thread.currentThread().threadId() })
            assertEquals(baseline, recorder.recordings.map { it.id }.toSet())
        } finally { Files.deleteIfExists(destination) }
    }

    @Test fun actualJfrDisabledEventStaysDisabled() {
        assumeTrue(FlightRecorder.isAvailable())
        val recorder = FlightRecorder.getFlightRecorder()
        val baseline = recorder.recordings.map { it.id }.toSet()
        Recording().use { recording ->
            recording.disable("thc.RuntimeTrace")
            recording.start()
            val settings = recording.settings.toMap()
            RuntimeTraceServices(ByteArrayOutputStream()).use { trace ->
                assertEquals(0L, trace.control(500, 2))
                assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 0, "not recorded"))
                assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 1, "not recorded"))
                assertEquals(settings, recording.settings)
                assertEquals(baseline + recording.id, recorder.recordings.map { it.id }.toSet())
            }
        }
        assertEquals(baseline, recorder.recordings.map { it.id }.toSet())
    }
}
