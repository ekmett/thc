// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class EmptyJoinInputTest {
    private static String id(String name) { return "main:EmptyJoinInputAudit." + name; }
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... values) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result;
    }
    private static Map<String, Object> with(Map<String, Object> original, Object... values) { var result = new LinkedHashMap<>(original); result.putAll(map(values)); return result; }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> state = map("kind", "void", "primReps", list(), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    @SafeVarargs private final Map<String, Object> tuple(Map<String, Object>... children) {
        var reps = new ArrayList<String>(); for (var child : children) reps.addAll((List<String>) child.get("primReps"));
        return map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", reps, "components", Arrays.asList(children), "evaluated", true);
    }
    private final Map<String, Object> empty = tuple();
    private Map<String, Object> meta(Map<String, Object> proof) { return map("rep", proof); }
    private List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, meta(proof)); }
    private Map<String, Object> parameter(String id, Map<String, Object> proof) { return map("id", id, "name", id, "lifted", false, "rep", proof); }
    private List<Object> lambda(List<Map<String, Object>> params, List<Object> body) { return list("lam", params, body, map("rep", closure, "resultRep", integer)); }
    private Map<String, Object> binding(String id, List<Object> body) { return map("id", id, "name", id, "lifted", true, "rep", closure, "expr", body); }
    private Map<String, Object> fixture() { return fixture(empty, list("con", "Empty", 0, meta(empty)), List.of(false, false)); }
    private Map<String, Object> fixture(Map<String, Object> formal, List<Object> actual, List<Boolean> flags) {
        var join = with(binding("finish", lambda(List.of(parameter("unit", formal), parameter("n", integer)), variable("n", integer))), "joinValueArity", 2, "joinResultRep", integer);
        var call = list("app", variable("finish", closure), list(actual, variable("x", integer)), flags, false, false, map("rep", integer, "callStrict", list(true, true)));
        var body = list("let", false, list(join), call, meta(integer));
        return map("bindings", list(binding("entry", lambda(List.of(parameter("x", integer)), body))), "instrument", true,
            "constructors", list(map("id", "Empty", "kind", "unboxed-tuple", "arity", 0, "fieldReps", list(), "fieldLifted", list(), "strictFields", list())));
    }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build(); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private void valid(RootCallTarget target, String label) throws ReflectiveOperationException { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed"); }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>();
        class Visit {
            void target(RootCallTarget target) {
                if (!seen.add(target)) return; var node = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(node);
                if (node instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments()) {
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
                }
                for (var part : nodes) for (var call : NodeUtil.findAllNodeInstances(part, DirectCallNode.class)) {
                    if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot) target(active);
                }
                result.add(target);
            }
        }
        new Visit().target(entry); return result;
    }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private Map<String, Object> module(String stage) throws Exception { return CoreCbdFixtures.read(new File(root, "build/empty-join-input/" + stage + "-core/EmptyJoinInputAudit.cbd").toPath()); }
    @Test void genuineEmptyJoinInputsWithInlining() throws Exception { nativeEvidence(true); }
    @Test void genuineEmptyJoinInputsAcrossResidualCalls() throws Exception { nativeEvidence(false); }
    private void nativeEvidence(boolean inlining) throws Exception {
        var provenance = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/empty-join-input/provenance.json").toPath()));
        for (String kind : List.of("inputs", "artifacts")) for (var row : (List<Map<String, String>>) provenance.get(kind)) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, row.get("path")).toPath())));
            assertEquals(row.get("sha256"), hash, "Stale empty-join evidence " + row.get("path"));
        }
        for (String stage : List.of("pre", "post")) {
            var audit = (Map<?, ?>) Json.parse(Files.readString(new File(root, "build/empty-join-input/" + stage + "-audit.json").toPath())); assertEquals(true, audit.get("accepted"), stage + " strict native audit");
        }
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(new File(root, "build/empty-join-input/oracle.tsv").toPath())) { var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); }
        int count = 0; for (var cases : rows.values()) count += cases.size(); assertEquals(58, count); assertEquals(9, rows.size());
        for (String stage : List.of("pre", "post")) for (var group : rows.entrySet()) for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                String name = group.getKey(); var cases = group.getValue(); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = program(language, with(CoreModules.reachable(module(stage), id(name)), "instrument", true), backend);
                var function = context.asValue(new EntryValue(program, id(name), 1)); var original = program.entryTarget(id(name)); var host = program.hostEntryTarget(1); String label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                java.util.function.Consumer<List<String>> check = row -> { assertEquals(Long.parseLong(row.get(2)), function.execute(Long.parseLong(row.get(1))).asLong(), label + "/" + row.get(1)); released(language); };
                for (var row : cases) check.accept(row);
                var active = activeTargets(host); for (var target : active) if (target != host) compile(target); assertTrue(function.invokeMember("compile").asBoolean());
                boolean firstCompiledCall = true;
                for (var row : cases.reversed()) {
                    long before = (Long) program.diagnostics().get("compiledEntries"); check.accept(row);
                    if (firstCompiledCall) {
                        assertTrue((Long) program.diagnostics().get("compiledEntries") > before, label + " first installed call enters compiled guest code");
                        valid(original, label); for (var target : active) valid(target, label);
                        firstCompiledCall = false;
                    }
                }
                for (String counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, program.diagnostics().get(counter), label + "/" + counter);
                System.out.println("EmptyJoin PASS " + label + " rows=" + cases.size());
            } finally { context.leave(); }
        }
    }
    @Test void emptyOperandRunsInLogicalOrderBeforeParallelMovesAndFailureTransfersNothing() {
        try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var layout = new FrameLayout(); int a = layout.bind("a"), b = layout.bind("b"), first = layout.bind("first"), last = layout.bind("last");
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build()); var scalar = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
                var zero = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of(), List.of(), null, null, null, null); int[][] typed = {null, new int[0], null};
                var target = new LocalJoinTarget(new Object(), 1, new int[]{a, -1, b}, new CoreRepresentation[]{scalar, zero, scalar}, new boolean[3], CoreRepresentation.UNKNOWN, typed); var events = new ArrayList<String>(); var metrics = new Metrics(true);
                class Read extends Expr {
                    private final String label; private final int slot;
                    Read(String label, int slot) { this.label = label; this.slot = slot; }
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) { events.add(label); return frame.getLong(slot); }
                }
                for (boolean fails : new boolean[]{false, true}) {
                    events.clear(); FrameAccess.writeLong(frame, a, 11); FrameAccess.writeLong(frame, b, 29);
                    var zeroExpr = new Expr() {
                        @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Empty tuple must not use a scalar carrier"); }
                        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
                            assertEquals(0, slots.length); assertEquals(0, offset); events.add("empty"); assertEquals(11L, frame.getLong(a)); assertEquals(29L, frame.getLong(b));
                            if (fails) throw new RuntimeFault("empty operand failure"); return null;
                        }
                    };
                    var call = new LocalJoinCall(language, target, new Expr[]{new Read("first", b), zeroExpr, new Read("last", a)}, new int[]{first, -1, last}, metrics, typed);
                    if (fails) {
                        assertThrows(RuntimeFault.class, () -> call.execute(frame)); assertEquals(List.of("first", "empty"), events); assertEquals(11L, frame.getLong(a)); assertEquals(29L, frame.getLong(b));
                    } else {
                        assertSame(target.getJump(), assertThrows(LocalJoinJump.class, () -> call.execute(frame))); assertEquals(List.of("first", "empty", "last"), events); assertEquals(29L, frame.getLong(a)); assertEquals(11L, frame.getLong(b));
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void emptyInputKeepsLogicalArityOnEitherBackend() {
        for (String backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var p = program(language, fixture(), backend);
                assertEquals(37L, Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("entry"), new Object[]{37L}}));
                released(language); var prototype = fixture();
                for (int count : new int[]{0, 1, 3}) {
                    var m = (Map<String, Object>) Json.parse(Json.stringify(prototype)); var body = (List<Object>) ((List<?>) ((List<Map<String, Object>>) m.get("bindings")).getFirst().get("expr")).get(2); var call = (List<Object>) body.get(3);
                    if (count == 0) body.set(3, variable("finish", closure));
                    else { var args = (List<Object>) call.get(2); var flags = (List<Object>) call.get(3); if (count == 1) { args.removeLast(); flags.removeLast(); } else { args.add(variable("x", integer)); flags.add(false); } }
                    assertThrows(RuntimeFault.class, () -> program(language, m, backend));
                }
            } finally { context.leave(); }
        }
    }
    @Test void shapeLevityAndUnknownProofsRejectButSameFrameEmptyJoinCaptureRuns() throws ReflectiveOperationException {
        for (String backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var formal : List.of(tuple(state), tuple(empty), tuple(integer), with(empty, "components", null))) assertThrows(RuntimeFault.class, () -> program(language, fixture(formal, list("con", "Empty", 0, meta(empty)), List.of(false, false)), backend));
                for (var actual : List.of(list("void", meta(state)), list("void"), list("lit", "int", "1", meta(integer)))) assertThrows(RuntimeFault.class, () -> program(language, fixture(empty, actual, List.of(false, false)), backend));
                assertThrows(RuntimeFault.class, () -> program(language, fixture(state, list("con", "Empty", 0, meta(empty)), List.of(false, false)), backend));
                for (var flags : List.of(List.of(true, false), List.<Boolean>of())) assertThrows(RuntimeFault.class, () -> program(language, fixture(empty, list("con", "Empty", 0, meta(empty)), flags), backend));
                var bad = (Map<String, Object>) Json.parse(Json.stringify(fixture())); var let = (List<?>) ((List<?>) ((List<Map<String, Object>>) bad.get("bindings")).getFirst().get("expr")).get(2);
                var join = (List<?>) ((List<Map<String, Object>>) let.get(2)).getFirst().get("expr"); ((List<Map<String, Object>>) join.get(1)).getFirst().put("lifted", true); assertThrows(RuntimeFault.class, () -> program(language, bad, backend));
                var capturing = fixture(); var capturedJoin = with(binding("capture", lambda(List.of(parameter("n", integer)), list("case", variable("held", empty), "forced", list(list("default", null, list(), variable("n", integer))), map("rep", integer, "binder", parameter("forced", empty))))), "joinValueArity", 1, "joinResultRep", integer);
                var captureCall = list("app", variable("capture", closure), list(variable("x", integer)), list(false), false, false, meta(integer)); var region = list("let", false, list(capturedJoin), captureCall, meta(integer));
                var body = list("case", list("con", "Empty", 0, meta(empty)), "held", list(list("default", null, list(), region)), map("rep", integer, "binder", parameter("held", empty)));
                // Same activation, zero physical capture leaves; no closure environment owns a tuple.
                var p = program(language, with(capturing, "bindings", list(binding("entry", lambda(List.of(parameter("x", integer)), body)))), backend);
                java.util.function.Supplier<Object> call = () -> Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue("entry"), new Object[]{37L}});
                assertEquals(37L, call.get()); released(language); var target = p.entryTarget("entry"); compile(target); long before = (Long) p.diagnostics().get("compiledEntries");
                assertEquals(37L, call.get()); assertTrue((Long) p.diagnostics().get("compiledEntries") > before); valid(target, backend + " empty join capture"); released(language);
            } finally { context.leave(); }
        }
    }
    @Test void genuineThrowingEmptyOperandIsNotErasedAndRecoveryReleasesResults() throws Exception {
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var p = program(language, with(CoreModules.reachable(module(stage), id("throwCase")), "instrument", true), backend);
                java.util.function.LongFunction<Object> call = x -> Calls.target(p.hostEntryTarget(1), new Object[]{p.entryValue(id("throwCase")), new Object[]{x}});
                assertEquals(108L, call.apply(7)); assertThrows(GuestException.class, () -> call.apply(-1)); released(language);
                assertEquals(108L, call.apply(7)); released(language);
            } finally { context.leave(); }
        }
    }
}
