// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ThreadInventoryCoreEvidence.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(60)
@SuppressWarnings("unchecked")
class HintTraceTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String traceText = "[thc trace event] hint-trace-event\n[thc trace marker] hint-trace-marker\n[thc trace binary] 41004200\n";
    private Context context(ByteArrayOutputStream output) { return context(output, null); }
    private Context context(ByteArrayOutputStream output, Engine engine) {
        var builder = Context.newBuilder("thc").err(output).allowNativeAccess(true).allowExperimentalOptions(true);
        if (engine != null) builder.engine(engine);
        else builder.option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000");
        return builder.build();
    }
    private Map<String, Object> fixture() throws Exception {
        var receipt = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/hint-trace/manifest.json").toPath())); assertEquals("9.14.1", receipt.get("ghc")); assertEquals(10L, receipt.get("nativeRows"));
        for (String kind : List.of("inputHashes", "artifactHashes")) for (var row : ((Map<String, String>) receipt.get(kind)).entrySet()) {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, row.getKey()).toPath()))); assertEquals(row.getValue(), digest, "Stale fixture: " + row.getKey());
        }
        return receipt;
    }
    private Map<String, Object> module(String stage) throws Exception { return thc.CoreCbdFixtures.read(new File(root, "build/hint-trace/" + stage + "/core/HintTraceAudit.cbd").toPath()); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private Object call(ExecutableProgram program, String name, Object... args) { var input = new Object[args.length + 1]; input[0] = 0L; System.arraycopy(args, 0, input, 1, args.length); return ScalarTestCalls.callScalarTestTarget(program.entryTarget("main:HintTraceAudit." + name), input); }
    private Set<String> primitives(Object value) {
        var result = new LinkedHashSet<String>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(primitives(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "prim".equals(list.getFirst())) result.add((String) list.get(1)); else for (var child : list) result.addAll(primitives(child));
        }
        return result;
    }
    @Test void allNineteenOriginalPrimopsMatchNativeResultsAndEmitTargetRecords() throws Exception {
        var receipt = fixture(); var rows = new ArrayList<List<String>>(); for (var line : Files.readAllLines(new File(root, "build/hint-trace/oracle.tsv").toPath())) rows.add(Arrays.asList(line.split("\t", -1))); assertEquals(10, rows.size());
        for (String stage : (List<String>) receipt.get("stages")) {
            var module = module(stage);
            var required = new LinkedHashSet<>(PrefetchExpression.ARITIES.keySet()); for (var operation : TraceOp.values()) required.add(operation.getPrimitive()); assertTrue(primitives(module).containsAll(required));
            for (String backend : List.of("ast", "bytecode")) {
                var output = new ByteArrayOutputStream();
                try (var context = context(output)) {
                    context.initialize("thc"); context.enter();
                    try {
                        Language.currentState().getRuntimeTrace().control(500, 1);
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = new LinkedHashMap<>(CoreModules.reachable(module, List.of("main:HintTraceAudit.hints", "main:HintTraceAudit.traces"), true)); linked.put("instrument", true); var program = program(language, linked, backend);
                        java.util.function.Consumer<List<String>> check = row -> {
                            String name = row.get(0), input = row.get(1), result = row.get(2);
                            assertEquals(Long.parseLong(input) + (name.equals("hints") ? 1L : 19L), Long.parseLong(result)); output.reset();
                            assertEquals(Long.parseLong(result), call(program, name, Long.parseLong(input)), stage + "/" + backend + "/" + name); assertEquals(name.equals("traces") ? traceText : "", output.toString(StandardCharsets.UTF_8));
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        };
                        for (var row : rows) check.accept(row);
                        var distinct = new LinkedHashSet<RootCallTarget>(); for (String name : List.of("hints", "traces")) distinct.addAll(targets(program.entryTarget("main:HintTraceAudit." + name))); var active = new ArrayList<>(distinct);
                        var interpreted = interpretedCalls(active);
                        install(active);
                        for (var row : rows) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.accept(row);
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "First installed call enters compiled code");
                            assertEquals(interpreted, interpretedCalls(active), "No interpreted settling call");
                            for (var target : active) assertTrue(valid(target), "Installed trace target remains valid");
                        }
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void originalPrimopsEnableAfterCompilationAndKeepJfrPayloadsContextLocal() throws Exception {
        assumeTrue(FlightRecorder.isAvailable());
        fixture();
        var destination = Files.createTempFile("thc-original-trace-", ".jfr");
        var recorder = FlightRecorder.getFlightRecorder();
        var baseline = new HashSet<>(recorder.getRecordings().stream().map(Recording::getId).toList());
        try {
            try (var recording = new Recording()) {
                recording.enable("thc.RuntimeTrace").withoutStackTrace();
                recording.start();
                var settings = new HashMap<>(recording.getSettings());
                for (String backend : List.of("ast", "bytecode")) {
                    var firstOutput = new ByteArrayOutputStream();
                    var secondOutput = new ByteArrayOutputStream();
                    try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
                         var first = context(firstOutput, engine)) {
                        first.initialize("thc"); first.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = new LinkedHashMap<>(CoreModules.reachable(module("pre"), List.of("main:HintTraceAudit.traces", "main:HintTraceAudit.event", "main:HintTraceAudit.binary"), true));
                            linked.put("instrument", true);
                            var program = program(language, linked, backend);
                            assertEquals(20L, call(program, "traces", 1L));
                            // Off ignores invalid payload regions and lengths, including after re-disabling.
                            assertEquals(7L, call(program, "event", ManagedAddress.nullAddress(), 7L));
                            assertEquals(-1L, call(program, "binary", ManagedAddress.nullAddress(), -1L));
                            var active = targets(program.entryTarget("main:HintTraceAudit.traces"));
                            install(active);
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            var interpreted = interpretedCalls(active);
                            assertEquals(21L, call(program, "traces", 2L));
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "First disabled installed call executes compiled code");
                            assertEquals(interpreted, interpretedCalls(active));
                            assertEquals(0, firstOutput.size(), "Default-off interpreter and compiled calls stay silent");
                            var trace = Language.currentState().getRuntimeTrace();
                            assertEquals(0L, trace.control(500, 2));
                            assertEquals(22L, call(program, "traces", 3L), "First call after enabling must emit all three events");
                            assertEquals(9L, call(program, "event", ManagedAddress.fromByteArray(new byte[]{(byte) 0xff, 10, 0, 65}), 9L));
                            assertEquals(0L, trace.control(500, 0));
                            assertEquals(23L, call(program, "traces", 4L));
                            assertEquals(-1L, call(program, "binary", ManagedAddress.nullAddress(), -1L));
                            assertEquals(0L, trace.control(500, 2));
                            assertEquals(24L, call(program, "traces", 5L));
                            assertEquals(0, firstOutput.size(), "JFR-only calls never leak stderr");
                        } finally { first.leave(); }
                        try (var second = context(secondOutput, engine)) {
                            second.initialize("thc"); second.enter();
                            try {
                                assertEquals(0L, Language.currentState().getRuntimeTrace().query(500, 0, 0));
                                RtsDiagnostics.trace(null, TraceOp.EVENT, ManagedAddress.nullAddress(), 0L);
                                assertEquals(0L, Language.currentState().getRuntimeTrace().control(500, 2));
                                RtsDiagnostics.trace(null, TraceOp.MARKER, ManagedAddress.fromByteArray(new byte[]{66, 0}), 0L);
                                assertEquals(0, secondOutput.size(), "Other context also stays silent on stderr");
                            } finally { second.leave(); }
                        }
                    }
                }
                assertEquals(settings, recording.getSettings());
                var expectedRecordings = new HashSet<>(baseline); expectedRecordings.add(recording.getId());
                assertEquals(expectedRecordings, new HashSet<>(recorder.getRecordings().stream().map(Recording::getId).toList()));
                recording.stop(); recording.dump(destination);
            }
            var events = RecordingFile.readAllEvents(destination).stream().filter(event -> event.getEventType().getName().equals("thc.RuntimeTrace")).toList();
            assertEquals(16, events.size());
            for (int offset : new int[]{0, 8}) {
                var group = events.subList(offset, offset + 8);
                assertEquals(List.of("event", "marker", "binary", "event", "event", "marker", "binary", "marker"), group.stream().map(event -> event.getString("phase")).toList());
                assertEquals(List.of("hint-trace-event", "hint-trace-marker", "41004200", "\ufffd\n", "hint-trace-event", "hint-trace-marker", "41004200", "B"), group.stream().map(event -> event.getString("message")).toList());
                assertEquals(List.of("68696e742d74726163652d6576656e74", "68696e742d74726163652d6d61726b6572", "41004200", "ff0a", "68696e742d74726163652d6576656e74", "68696e742d74726163652d6d61726b6572", "41004200", "42"), group.stream().map(event -> event.getString("payloadHex")).toList());
                long contextId = group.getFirst().getLong("contextId");
                assertTrue(group.subList(0, 7).stream().allMatch(event -> event.getLong("contextId") == contextId));
                assertNotEquals(contextId, group.getLast().getLong("contextId"));
            }
            assertEquals(baseline, new HashSet<>(recorder.getRecordings().stream().map(Recording::getId).toList()));
        } finally { Files.deleteIfExists(destination); }
    }
    @Test void traceBytesBoundsAndIgnoredAddressHintsHaveSensibleTargetSemantics() throws Exception {
        fixture();
        for (String backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = context(output)) {
                context.initialize("thc"); context.enter();
                try {
                    Language.currentState().getRuntimeTrace().control(500, 1);
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = program(language, CoreModules.reachable(module("pre"), List.of("main:HintTraceAudit.event", "main:HintTraceAudit.marker", "main:HintTraceAudit.binary", "main:HintTraceAudit.addressHints"), true), backend);
                    var address = ManagedAddress.fromByteArray("λ\n\\\u0000ignored".getBytes(StandardCharsets.UTF_8));
                    assertEquals(23L, call(program, "event", address, 23L)); assertEquals("[thc trace event] λ\\x0a\\\\\n", output.toString(StandardCharsets.UTF_8)); output.reset();
                    assertEquals(3L, call(program, "binary", ManagedAddress.fromByteArray(new byte[]{0, -1, 10, 88}), 3L)); assertEquals("[thc trace binary] 00ff0a\n", output.toString(StandardCharsets.UTF_8)); output.reset();
                    assertEquals(0L, call(program, "binary", ManagedAddress.nullAddress(), 0L)); assertEquals(7L, call(program, "marker", ManagedAddress.fromByteArray(new byte[]{0}), 7L));
                    assertEquals("[thc trace binary] \n[thc trace marker] \n", output.toString(StandardCharsets.UTF_8)); output.reset(); var bytes = ManagedAddress.fromByteArray(new byte[]{65, 0});
                    record Invalid(String name, ManagedAddress location, long count) {}
                    for (var invalid : List.of(new Invalid("event", ManagedAddress.fromByteArray(new byte[]{65}), 0L), new Invalid("event", ManagedAddress.unownedNumeric(1234), 0L), new Invalid("binary", bytes, -1L), new Invalid("binary", bytes, 3L), new Invalid("binary", bytes, Long.MAX_VALUE)))
                        assertThrows(RuntimeFault.class, () -> call(program, invalid.name(), invalid.location(), invalid.count()));
                    assertEquals(0, output.size(), "Invalid trace must not publish a partial record"); var pointerCell = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8)); pointerCell.writeAddressElementIndex(0, bytes);
                    assertThrows(RuntimeFault.class, () -> call(program, "binary", pointerCell, 8L));
                    for (var location : List.of(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(1234), bytes.plus(2))) for (long offset : new long[]{Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE}) assertEquals(offset, call(program, "addressHints", location, offset));
                    assertEquals(0, output.size(), "Prefetch emits no diagnostic and dereferences nothing");
                } finally { context.leave(); }
            }
        }
    }
    @Test void nativeTraceLifetimesAndContextOutputIsolation() {
        assumeTrue("Linux".equals(System.getProperty("os.name")) && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")), "Existing native malloc provider ABI");
        var left = new ByteArrayOutputStream(); var right = new ByteArrayOutputStream();
        try (var first = context(left); var second = context(right)) {
            first.initialize("thc"); second.initialize("thc"); first.enter(); final ManagedAddress address;
            try {
                Language.currentState().getRuntimeTrace().control(500, 1);
                var allocations = Language.currentState().getNativeAllocations(); address = allocations.malloc(4); address.writeWord8(0, 65); address.writeWord8(1, 0); address.writeWord8(2, 255); address.writeWord8(3, 10);
                RtsDiagnostics.trace(null, TraceOp.EVENT, address, 0); RtsDiagnostics.trace(null, TraceOp.BINARY, address.plus(1), 3);
            } finally { first.leave(); }
            second.enter();
            try { Language.currentState().getRuntimeTrace().control(500, 1); assertThrows(RuntimeFault.class, () -> RtsDiagnostics.trace(null, TraceOp.EVENT, address, 0)); RtsDiagnostics.trace(null, TraceOp.MARKER, ManagedAddress.fromHex("42"), 0); } finally { second.leave(); }
            first.enter();
            try { Language.currentState().getNativeAllocations().free(address); assertThrows(RuntimeFault.class, () -> RtsDiagnostics.trace(null, TraceOp.BINARY, address, 1)); } finally { first.leave(); }
            assertEquals("[thc trace event] A\n[thc trace binary] 00ff0a\n", left.toString(StandardCharsets.UTF_8)); assertEquals("[thc trace marker] B\n", right.toString(StandardCharsets.UTF_8));
        }
    }
    @Test void concurrentRecordsDoNotInterleave() throws Exception {
        var output = new ByteArrayOutputStream(); var workers = Executors.newFixedThreadPool(2);
        try {
            try (var context = context(output)) {
                context.initialize("thc"); context.enter();
                try { Language.currentState().getRuntimeTrace().control(500, 1); } finally { context.leave(); }
                var jobs = new ArrayList<Future<?>>();
                for (int worker = 0; worker <= 1; worker++) {
                    int id = worker; jobs.add(workers.submit(() -> {
                        context.enter();
                        try { var text = ManagedAddress.fromByteArray(("worker-" + id + '\u0000').getBytes(StandardCharsets.UTF_8)); for (int i = 0; i < 32; i++) RtsDiagnostics.trace(null, TraceOp.EVENT, text, 0); }
                        finally { context.leave(); }
                    }));
                }
                for (var job : jobs) job.get(5, TimeUnit.SECONDS); var lines = new ArrayList<String>(); for (String line : output.toString(StandardCharsets.UTF_8).split("\\r\\n|\\n|\\r", -1)) if (!line.isEmpty()) lines.add(line); assertEquals(64, lines.size());
                for (int worker = 0; worker <= 1; worker++) { int count = 0; for (String line : lines) if (line.equals("[thc trace event] worker-" + worker)) count++; assertEquals(32, count); }
            }
        } finally { workers.shutdownNow(); }
    }
}
