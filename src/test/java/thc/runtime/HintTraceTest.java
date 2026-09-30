// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(60)
@SuppressWarnings("unchecked")
class HintTraceTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String traceText = "[thc trace event] hint-trace-event\n[thc trace marker] hint-trace-marker\n[thc trace binary] 41004200\n";
    private Context context(ByteArrayOutputStream output) {
        return Context.newBuilder("thc").err(output).allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
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
    private Object call(ExecutableProgram program, String name, Object... args) { var input = new Object[args.length + 1]; input[0] = 0L; System.arraycopy(args, 0, input, 1, args.length); return Calls.target(program.entryTarget("main:HintTraceAudit." + name), input); }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>();
        class Visit {
            void target(RootCallTarget target) {
                if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
                if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments()) {
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
                }
                for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) target(next);
                result.add(target);
            }
        }
        new Visit().target(entry); return result;
    }
    private void valid(RootCallTarget target) throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName()); }
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
            for (String name : List.of("hints", "traces")) {
                var proof = new ArrayCoreEvidence(module, "main:HintTraceAudit." + name); proof.stateLambda(proof.getRoot().get("expr")); assertEquals(2, proof.guestLambdas(proof.getRoot().get("expr")).size(), "Original entry and state lambda");
                assertEquals(1, proof.loweredStateLambdas(proof.getRoot().get("expr")).size(), "Exact runRW redex lowers in-frame");
                // Floating string/bottom CAFs remain supplied; none contains a guest lambda.
                for (var binding : proof.getBindings()) if (binding != proof.getRoot()) assertTrue(proof.guestLambdas(binding.get("expr")).isEmpty());
            }
            var required = new LinkedHashSet<>(PrefetchExpression.ARITIES.keySet()); for (var operation : TraceOp.values()) required.add(operation.getPrimitive()); assertTrue(primitives(module).containsAll(required));
            for (String backend : List.of("ast", "bytecode")) {
                var output = new ByteArrayOutputStream();
                try (var context = context(output)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = new LinkedHashMap<>(CoreModules.reachable(module, List.of("main:HintTraceAudit.hints", "main:HintTraceAudit.traces"), true)); linked.put("instrument", true); var program = program(language, linked, backend);
                        java.util.function.Consumer<List<String>> check = row -> {
                            String name = row.get(0), input = row.get(1), result = row.get(2);
                            assertEquals(Long.parseLong(input) + (name.equals("hints") ? 1L : 19L), Long.parseLong(result)); output.reset();
                            assertEquals(Long.parseLong(result), call(program, name, Long.parseLong(input)), stage + "/" + backend + "/" + name); assertEquals(name.equals("traces") ? traceText : "", output.toString(StandardCharsets.UTF_8));
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        };
                        for (var row : rows) check.accept(row);
                        var distinct = new LinkedHashSet<RootCallTarget>(); for (String name : List.of("hints", "traces")) distinct.addAll(targets(program.entryTarget("main:HintTraceAudit." + name))); var active = new ArrayList<>(distinct);
                        assertEquals(2, active.size(), "One lowered public root for hints and traces"); for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                        for (var row : rows) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.accept(row);
                            // Original functions execute their proven State# body in-frame.
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); for (var target : active) valid(target);
                        }
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void traceBytesBoundsAndIgnoredAddressHintsHaveSensibleTargetSemantics() throws Exception {
        fixture();
        for (String backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = context(output)) {
                context.initialize("thc"); context.enter();
                try {
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
                var allocations = Language.currentState().getNativeAllocations(); address = allocations.malloc(4); address.writeWord8(0, 65); address.writeWord8(1, 0); address.writeWord8(2, 255); address.writeWord8(3, 10);
                RtsDiagnostics.trace(null, TraceOp.EVENT, address, 0); RtsDiagnostics.trace(null, TraceOp.BINARY, address.plus(1), 3);
            } finally { first.leave(); }
            second.enter();
            try { assertThrows(RuntimeFault.class, () -> RtsDiagnostics.trace(null, TraceOp.EVENT, address, 0)); RtsDiagnostics.trace(null, TraceOp.MARKER, ManagedAddress.fromHex("42"), 0); } finally { second.leave(); }
            first.enter();
            try { Language.currentState().getNativeAllocations().free(address); assertThrows(RuntimeFault.class, () -> RtsDiagnostics.trace(null, TraceOp.BINARY, address, 1)); } finally { first.leave(); }
            assertEquals("[thc trace event] A\n[thc trace binary] 00ff0a\n", left.toString(StandardCharsets.UTF_8)); assertEquals("[thc trace marker] B\n", right.toString(StandardCharsets.UTF_8));
        }
    }
    @Test void concurrentRecordsDoNotInterleave() throws Exception {
        var output = new ByteArrayOutputStream(); var workers = Executors.newFixedThreadPool(2);
        try {
            try (var context = context(output)) {
                context.initialize("thc"); var jobs = new ArrayList<Future<?>>();
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
