// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(30)
class RuntimeTraceServicesTest {
    private static final class Jfr implements RuntimeTraceJfr {
        long availability;
        long emission;
        final List<List<Object>> events = new ArrayList<>();
        Jfr() { this(0L, 0L); }
        Jfr(long availability, long emission) { this.availability = availability; this.emission = emission; }
        @Override public long support() { return availability; }
        @Override public long emit(long contextId, String phase, long token, String name, long elapsedNanos, String payloadHex) {
            if (emission == 0L) events.add(List.of(contextId, phase, token, name, elapsedNanos, payloadHex));
            return emission;
        }
    }

    private static long emit(RuntimeTraceServices service, int operation, String text, long token) {
        byte[] bytes = text.getBytes(UTF_8);
        return service.emit(operation, token, ManagedAddress.fromByteArray(bytes), bytes.length);
    }

    private static List<Map<?, ?>> records(ByteArrayOutputStream output) {
        return output.toString(UTF_8).lines().filter(line -> !line.isEmpty())
            .map(line -> (Map<?, ?>) Json.parse(line)).collect(Collectors.toList());
    }

    @Test void defaultDisabledAndQueriesDoNotEmitOrEnableAnything() {
        var output = new ByteArrayOutputStream();
        var provider = new Jfr();
        try (var trace = new RuntimeTraceServices(output, provider)) {
            assertEquals(0L, trace.query(500, 0, 0));
            assertTrue(trace.isPrimopDisabled());
            trace.emitPrimop(TraceOp.EVENT, ManagedAddress.nullAddress(), 0L);
            trace.emitPrimop(TraceOp.BINARY, ManagedAddress.nullAddress(), -1L);
            assertEquals(3L, trace.query(501, 0, 0));
            assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 0, "invisible", 0L));
            assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 1, "invisible", 0L));
            assertEquals(0, output.size());
            assertTrue(provider.events.isEmpty());
        }
    }

    @Test void originalPrimopsShareSinkAndPreserveExactBytes() {
        var output = new ByteArrayOutputStream();
        var provider = new Jfr();
        try (var trace = new RuntimeTraceServices(output, provider)) {
            assertEquals(0L, trace.control(500, 3));
            assertFalse(trace.isPrimopDisabled());
            trace.emitPrimop(TraceOp.EVENT, ManagedAddress.fromByteArray(new byte[]{(byte) 0xff, 10, 0, 65}), 0L);
            trace.emitPrimop(TraceOp.MARKER, ManagedAddress.fromByteArray(new byte[]{66, 0}), 0L);
            trace.emitPrimop(TraceOp.BINARY, ManagedAddress.fromByteArray(new byte[]{0, (byte) 0xff, 10, 88}), 3L);
            trace.emitPrimop(TraceOp.BINARY, ManagedAddress.nullAddress(), 0L);
            assertEquals(List.of("event", "marker", "binary", "binary"), provider.events.stream().map(row -> row.get(1)).toList());
            assertEquals(List.of("\ufffd\n", "B", "00ff0a", ""), provider.events.stream().map(row -> row.get(3)).toList());
            assertEquals(List.of("ff0a", "42", "00ff0a", ""), provider.events.stream().map(row -> row.get(5)).toList());
            assertEquals(1, provider.events.stream().map(row -> row.getFirst()).distinct().count());
            assertArrayEquals("[thc trace event] \u00ff\\x0a\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                Arrays.copyOf(output.toByteArray(), 24));
            assertTrue(output.toString(UTF_8).endsWith("[thc trace marker] B\n[thc trace binary] 00ff0a\n[thc trace binary] \n"));
            trace.control(500, 0);
            assertTrue(trace.isPrimopDisabled());
            trace.emitPrimop(TraceOp.EVENT, ManagedAddress.nullAddress(), 0L);
            assertEquals(4, provider.events.size());
            trace.control(500, 2);
            trace.emitPrimop(TraceOp.MARKER, ManagedAddress.fromByteArray(new byte[]{67, 0}), 0L);
            assertEquals(5, provider.events.size());
        }
    }

    @Test void exactUtf8IsEscapedWithoutCreatingFakeRecords() {
        var output = new ByteArrayOutputStream();
        try (var trace = new RuntimeTraceServices(output, new Jfr())) {
            assertEquals(0L, trace.control(500, 1));
            String text = "quote\" slash\\ LF\nCR\r nul\u0000 del\u007f paragraph\u2029 雪 🎉";
            assertEquals(0L, emit(trace, 0, text, 0L));
            var rows = records(output);
            assertEquals(1, rows.size());
            var record = rows.getFirst();
            assertEquals("trace", record.get("thc"));
            assertEquals("event", record.get("phase"));
            assertEquals(text, record.get("name"));
            assertEquals(0L, record.get("span"));
            assertEquals(0L, record.get("elapsedNanos"));
            assertEquals(Thread.currentThread().threadId(), record.get("thread"));
            assertTrue(output.toString(UTF_8).contains("\\u0000"));
        }
    }

    @Test void completePayloadAndAbiAreValidatedBeforeEffectsEvenWhenDisabled() {
        var output = new ByteArrayOutputStream();
        try (var trace = new RuntimeTraceServices(output, new Jfr())) {
            for (long sink : new long[]{0L, 1L}) {
                trace.control(500, sink);
                for (byte[] bad : List.of(new byte[]{(byte) 0xc0, (byte) 0x80},
                        new byte[]{0x61, (byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
                    assertThrows(RuntimeFault.class, () ->
                        trace.emit(0, 0, ManagedAddress.fromByteArray(bad), bad.length));
                }
                assertThrows(RuntimeFault.class, () -> trace.emit(0, 0,
                    ManagedAddress.fromByteArray(new byte[]{0x61, 0x62}), 3));
                assertThrows(RuntimeFault.class, () -> trace.emit(0, 0, ManagedAddress.nullAddress(), -1));
                assertThrows(RuntimeFault.class, () -> trace.emit(0, 0,
                    ManagedAddress.nullAddress(), RuntimeTraceServices.MAX_INPUT_BYTES + 1));
                assertThrows(RuntimeFault.class, () -> emit(trace, 4, "", 0L));
                assertThrows(RuntimeFault.class, () -> emit(trace, 0, "", 1L));
                assertThrows(RuntimeFault.class, () -> emit(trace, 2, "", 0L));
            }
            assertEquals(0, output.size());
        }
    }

    @Test void spanLifecycleNamesNormalAndExceptionalCompletion() {
        var output = new ByteArrayOutputStream();
        try (var trace = new RuntimeTraceServices(output, new Jfr())) {
            trace.control(500, 1);
            long normal = emit(trace, 1, "normal", 0L);
            long failed = emit(trace, 1, "failed", 0L);
            assertTrue(normal > 0);
            assertTrue(failed > normal);
            assertThrows(RuntimeFault.class, () -> emit(trace, 2, "invalid end payload", normal));
            assertEquals(0L, emit(trace, 2, "", normal));
            assertEquals(0L, emit(trace, 3, "", failed));
            assertThrows(RuntimeFault.class, () -> emit(trace, 2, "", normal));
            var rows = records(output);
            assertEquals(List.of("begin", "begin", "end", "exception"), rows.stream().map(row -> row.get("phase")).toList());
            assertEquals(List.of(normal, failed, normal, failed), rows.stream().map(row -> row.get("span")).toList());
            assertEquals(List.of("normal", "failed", "normal", "failed"), rows.stream().map(row -> row.get("name")).toList());
            assertTrue(rows.stream().allMatch(row -> (Long) row.get("elapsedNanos") >= 0));
        }
    }

    @Test void contextsDoNotShareConfigurationOrSpanOwnership() {
        var firstOutput = new ByteArrayOutputStream();
        var secondOutput = new ByteArrayOutputStream();
        try (var first = new RuntimeTraceServices(firstOutput, new Jfr());
             var second = new RuntimeTraceServices(secondOutput, new Jfr())) {
            first.control(500, 1);
            long firstSpan = emit(first, 1, "first", 0L);
            assertEquals(0L, second.query(500, 0, 0));
            assertThrows(RuntimeFault.class, () -> emit(second, 2, "", firstSpan));
            second.control(500, 1);
            long secondSpan = emit(second, 1, "second", 0L);
            assertNotEquals(firstSpan, secondSpan);
            assertThrows(RuntimeFault.class, () -> emit(first, 2, "", secondSpan));
            assertEquals(0L, emit(first, 2, "", firstSpan));
            assertEquals(0L, emit(second, 2, "", secondSpan));
            assertNotEquals(records(firstOutput).getFirst().get("context"), records(secondOutput).getFirst().get("context"));
            assertTrue(records(firstOutput).stream().allMatch(row -> "first".equals(row.get("name"))));
            assertTrue(records(secondOutput).stream().allMatch(row -> "second".equals(row.get("name"))));
        }
    }

    @Test void disablingRetiresLiveSpansWithoutOutputAndDisposalClosesTheService() {
        var output = new ByteArrayOutputStream();
        var trace = new RuntimeTraceServices(output, new Jfr());
        trace.control(500, 1);
        long token = emit(trace, 1, "silent end", 0L);
        trace.control(500, 0);
        assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 2, "", token));
        assertThrows(RuntimeFault.class, () -> emit(trace, 2, "", token));
        trace.control(500, 1);
        long abandoned = emit(trace, 1, "abandoned", 0L);
        trace.close();
        trace.close();
        assertEquals(RuntimeServiceStatus.UNAVAILABLE, trace.query(500, 0, 0));
        assertEquals(RuntimeServiceStatus.UNAVAILABLE, trace.control(500, 1));
        assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 2, "", abandoned));
        assertEquals(List.of("begin", "begin"), records(output).stream().map(row -> row.get("phase")).toList());
    }

    @Test void activeSpansAndRetainedNamesAreBoundedAndEndReleasesCapacity() {
        var output = new ByteArrayOutputStream();
        try (var trace = new RuntimeTraceServices(output, new Jfr(), 2, 5)) {
            trace.control(500, 1);
            long first = emit(trace, 1, "1234", 0L);
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 1, "12", 0L));
            long second = emit(trace, 1, "1", 0L);
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 1, "", 0L));
            assertEquals(0L, emit(trace, 2, "", first));
            assertTrue(emit(trace, 1, "1234", 0L) > 0);
            assertEquals(0L, emit(trace, 2, "", second));
            assertEquals(5, records(output).size());
        }
    }

    @Test void controlsRejectBadRequestsWithoutChangingExistingSink() {
        try (var trace = new RuntimeTraceServices(new ByteArrayOutputStream(), new Jfr())) {
            trace.control(500, 1);
            assertThrows(RuntimeFault.class, () -> trace.control(501, 2));
            assertThrows(RuntimeFault.class, () -> trace.control(500, 4));
            assertThrows(RuntimeFault.class, () -> trace.control(500, -1));
            assertThrows(RuntimeFault.class, () -> trace.query(502, 0, 0));
            assertThrows(RuntimeFault.class, () -> trace.query(500, 1, 0));
            assertThrows(RuntimeFault.class, () -> trace.query(500, 0, 1));
            assertEquals(1L, trace.query(500, 0, 0));
        }
    }

    @Test void optionalJfrStatusesAreExplicitAndBothSinksCanUseStderrAlone() {
        for (long status : new long[]{RuntimeServiceStatus.UNSUPPORTED, RuntimeServiceStatus.DENIED, RuntimeServiceStatus.UNAVAILABLE}) {
            try (var trace = new RuntimeTraceServices(new ByteArrayOutputStream(), new Jfr(status, 0L))) {
                assertEquals(1L, trace.query(501, 0, 0));
                assertEquals(status, trace.control(500, 2));
                assertEquals(0L, trace.query(500, 0, 0));
            }
        }
        var output = new ByteArrayOutputStream();
        var provider = new Jfr(0L, RuntimeServiceStatus.DISABLED);
        try (var trace = new RuntimeTraceServices(output, provider)) {
            assertEquals(0L, trace.control(500, 2));
            assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 1, "not recorded", 0L));
            assertEquals(0L, trace.control(500, 3));
            long token = emit(trace, 1, "stderr only", 0L);
            assertTrue(token > 0);
            assertEquals(0L, emit(trace, 2, "", token));
            assertEquals(2, records(output).size());
            assertTrue(provider.events.isEmpty());
        }
    }

    @Test void ioAndPermissionFailureAreNotReportedAsSuccessfulEmission() {
        var broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("closed test stream"); }
        };
        try (var trace = new RuntimeTraceServices(broken, new Jfr())) {
            trace.control(500, 1);
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 0, "write", 0L));
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, emit(trace, 1, "begin", 0L));
        }
        try (var trace = new RuntimeTraceServices(new ByteArrayOutputStream(), new Jfr(0L, RuntimeServiceStatus.DENIED))) {
            trace.control(500, 2);
            assertEquals(RuntimeServiceStatus.DENIED, emit(trace, 0, "write", 0L));
        }
    }

    @Test void concurrentWritersProduceWholeRecordsAndUniqueOwnedTokens() throws Exception {
        var output = new ByteArrayOutputStream();
        var pool = Executors.newFixedThreadPool(4);
        try {
            try (var trace = new RuntimeTraceServices(output, new Jfr())) {
                trace.control(500, 1);
                var jobs = new ArrayList<Future<?>>();
                for (int worker = 0; worker <= 3; worker++) {
                    int workerId = worker;
                    jobs.add(pool.submit(() -> {
                        for (int iteration = 0; iteration < 40; iteration++) {
                            long token = emit(trace, 1, "worker-" + workerId + "-" + iteration, 0L);
                            assertTrue(token > 0);
                            assertEquals(0L, emit(trace, 2, "", token));
                        }
                    }));
                }
                for (var job : jobs) job.get(10, TimeUnit.SECONDS);
                var rows = records(output);
                assertEquals(320, rows.size());
                var pairs = rows.stream().collect(Collectors.groupingBy(row -> row.get("span")));
                assertEquals(160, pairs.size());
                assertTrue(pairs.values().stream().allMatch(pair ->
                    pair.stream().map(row -> row.get("phase")).toList().equals(List.of("begin", "end")) &&
                    Objects.equals(pair.get(0).get("name"), pair.get(1).get("name"))));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void actualJfrRecordsStructuredFieldsWithoutCreatingOrReconfiguringRecordings() throws Exception {
        assumeTrue(FlightRecorder.isAvailable());
        var recorder = FlightRecorder.getFlightRecorder();
        var baseline = recorder.getRecordings().stream().map(Recording::getId).collect(Collectors.toSet());
        var destination = Files.createTempFile("thc-trace-", ".jfr");
        try {
            try (var recording = new Recording()) {
                recording.enable("thc.RuntimeTrace").withThreshold(Duration.ZERO).withoutStackTrace();
                recording.start();
                var expectedRecordings = new HashSet<>(baseline);
                expectedRecordings.add(recording.getId());
                try (var trace = new RuntimeTraceServices(new ByteArrayOutputStream())) {
                    assertEquals(3L, trace.query(501, 0, 0));
                    assertEquals(0L, trace.control(500, 2));
                    var settings = new HashMap<>(recording.getSettings());
                    assertEquals(0L, emit(trace, 0, "actual λ", 0L));
                    long token = emit(trace, 1, "actual span", 0L);
                    assertTrue(token > 0);
                    assertEquals(0L, emit(trace, 3, "", token));
                    assertEquals(expectedRecordings, recorder.getRecordings().stream().map(Recording::getId).collect(Collectors.toSet()));
                    assertEquals(settings, recording.getSettings());
                }
                recording.stop();
                recording.dump(destination);
            }
            var events = RecordingFile.readAllEvents(destination).stream()
                .filter(event -> event.getEventType().getName().equals("thc.RuntimeTrace")).toList();
            assertEquals(List.of("event", "begin", "exception"), events.stream().map(event -> event.getString("phase")).toList());
            assertEquals(List.of("actual λ", "actual span", "actual span"), events.stream().map(event -> event.getString("message")).toList());
            assertEquals(events.get(1).getLong("spanId"), events.get(2).getLong("spanId"));
            assertTrue(events.stream().allMatch(event -> event.getLong("contextId") > 0 && event.getLong("elapsedNanos") >= 0));
            assertTrue(events.stream().allMatch(event -> event.getThread().getJavaThreadId() == Thread.currentThread().threadId()));
            assertEquals(baseline, recorder.getRecordings().stream().map(Recording::getId).collect(Collectors.toSet()));
        } finally { Files.deleteIfExists(destination); }
    }

    @Test void actualJfrDisabledEventStaysDisabled() {
        assumeTrue(FlightRecorder.isAvailable());
        var recorder = FlightRecorder.getFlightRecorder();
        var baseline = recorder.getRecordings().stream().map(Recording::getId).collect(Collectors.toSet());
        try (var recording = new Recording()) {
            recording.disable("thc.RuntimeTrace");
            recording.start();
            var settings = new HashMap<>(recording.getSettings());
            try (var trace = new RuntimeTraceServices(new ByteArrayOutputStream())) {
                assertEquals(0L, trace.control(500, 2));
                assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 0, "not recorded", 0L));
                assertEquals(RuntimeServiceStatus.DISABLED, emit(trace, 1, "not recorded", 0L));
                trace.emitPrimop(TraceOp.EVENT, ManagedAddress.fromByteArray(new byte[]{65, 0}), 0L);
                trace.emitPrimop(TraceOp.BINARY, ManagedAddress.nullAddress(), 0L);
                assertEquals(settings, recording.getSettings());
                var expectedRecordings = new HashSet<>(baseline);
                expectedRecordings.add(recording.getId());
                assertEquals(expectedRecordings, recorder.getRecordings().stream().map(Recording::getId).collect(Collectors.toSet()));
            }
        }
        assertEquals(baseline, recorder.getRecordings().stream().map(Recording::getId).collect(Collectors.toSet()));
    }
}
