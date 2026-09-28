// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

@SuppressWarnings("unchecked")
class LeadingCaseReturnTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> map(Object... fields) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private final Map<String, Object> longRep = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private List<Object> variable(String id) { return variable(id, null); }
    private List<Object> variable(String id, String source) { return source == null ? list("var", id) : list("var", id, map("source", source)); }
    private List<Object> integer(long n) { return list("lit", "int", Long.toString(n)); }
    private Map<String, Object> parameter(String id) { return parameter(id, longRep); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) { return map("id", id, "name", id, "lifted", rep != longRep, "coercion", false, "rep", rep); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body) { return lambda(args, body, Collections.nCopies(args.size(), false), longRep); }
    private List<Object> lambda(List<Map<String, Object>> args, List<Object> body, List<Boolean> strict, Map<String, Object> result) { return list("lam", args, body, map("rep", closure, "resultRep", result, "entryStrict", strict)); }
    private Map<String, Object> binding(String id, List<Object> rhs) { return map("id", id, "name", id, "lifted", true, "expr", rhs); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return apply(fn, args, Collections.nCopies(args.size(), false)); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false); }
    private List<Object> plus(List<Object> a, List<Object> b) { return apply(list("prim", "+#"), List.of(a, b)); }
    private Map<String, Object> constructor(String id) { return constructor(id, List.of()); }
    private Map<String, Object> constructor(String id, List<List<String>> reps) { return map("id", id, "name", id, "arity", reps.size(), "kind", "boxed", "fieldReps", reps, "strictFields", Collections.nCopies(reps.size(), false), "fieldLifted", Collections.nCopies(reps.size(), false)); }
    private List<Object> worker() { return worker(false, false, false, null); }
    private List<Object> worker(boolean extra, boolean strictTree, boolean captured, List<Object> coldBody) {
        var payload = captured ? plus(variable("payload"), variable("captured")) : variable("payload");
        var body = list("case", variable("tree"), "treeCase", list(list("data", "Stop", List.of(), variable("acc", "arm")), list("data", "Next", list("payload"), coldBody == null ? plus(variable("acc"), payload) : coldBody, map("binders", list(parameter("payload")))), list("default", null, List.of(), integer(-1))), map("rep", longRep, "binder", parameter("treeCase", data), "source", "case", "sourceNotes", list("case")));
        var args = new ArrayList<>(List.of(parameter("acc"), parameter("tree", data))); if (extra) args.add(parameter("unused", data));
        var strict = new ArrayList<>(List.of(false, strictTree)); if (extra) strict.add(true); return lambda(args, body, strict, longRep);
    }
    private List<Object> driver() { return driver(false, false); }
    private List<Object> driver(boolean extra, boolean tail) {
        var callArgs = new ArrayList<>(List.of(variable("acc"), variable("tree"))); if (extra) callArgs.add(variable("unused")); var lifted = new ArrayList<>(List.of(false, true)); if (extra) lifted.add(true);
        var call = apply(variable("fn"), callArgs, lifted); var args = new ArrayList<>(List.of(parameter("fn", closure), parameter("acc"), parameter("tree", data))); if (extra) args.add(parameter("unused", data)); return lambda(args, tail ? call : plus(call, integer(1)));
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings, boolean notes, boolean diagnostic) {
        var all = new ArrayList<>(List.of(binding("stop", list("con", "Stop", 0)), binding("other", list("con", "Other", 0)), binding("next", apply(list("con", "Next", 1), List.of(integer(7)))))); all.addAll(bindings);
        return map("bindings", all, "constructors", list(constructor("Stop"), constructor("Other"), constructor("Next", List.of(List.of("IntRep"))), map("id", "Unsupported", "name", "Unsupported", "arity", 0, "kind", "unboxed-tuple", "fieldReps", List.of())),
            "instrument", true, "diagnosticUnsupported", diagnostic, "sourceNotesEnabled", notes,
            "sourceFiles", list(map("id", "Case.hs", "path", "Case.hs", "content", "case tree of Stop -> acc \n")),
            "sourceSpans", list(map("id", "case", "file", "Case.hs", "startLine", 1, "startColumn", 1, "endLine", 1, "endColumn", 25, "charIndex", 0, "charLength", 24), map("id", "arm", "file", "Case.hs", "startLine", 1, "startColumn", 22, "endLine", 1, "endColumn", 25, "charIndex", 21, "charLength", 3)));
    }
    @FunctionalInterface private interface Action { void run(String backend, ExecutableProgram program) throws Exception; }
    private void each(List<Map<String, Object>> bindings, Action action) throws Exception { each(true, true, false, bindings, action); }
    private void each(boolean enabled, boolean notes, boolean diagnostic, List<Map<String, Object>> bindings, Action action) throws Exception {
        String previous = System.getProperty(LeadingCaseReturn.LEADING_CASE_RETURN_PROPERTY); System.setProperty(LeadingCaseReturn.LEADING_CASE_RETURN_PROPERTY, Boolean.toString(enabled));
        try { try (var context = executionContext()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); for (String backend : List.of("ast", "bytecode")) { var m = module(bindings, notes, diagnostic); action.run(backend, backend.equals("ast") ? new Program(language, m) : new BytecodeProgram(language, m)); }
        } finally { context.leave(); } } } finally { if (previous == null) System.clearProperty(LeadingCaseReturn.LEADING_CASE_RETURN_PROPERTY); else System.setProperty(LeadingCaseReturn.LEADING_CASE_RETURN_PROPERTY, previous); }
    }
    private Object run(ExecutableProgram p, String name, Object... args) { return Calls.target(p.hostEntryTarget(args.length), new Object[]{p.entryValue(name), args.clone()}); }
    private long count(ExecutableProgram p) { return count(p, "leadingCaseReturns"); }
    private long count(ExecutableProgram p, String name) { return ((Number) p.diagnostics().get(name)).longValue(); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private static class CloneCaller extends RootNode {
        @Child private DirectCallNode call;
        CloneCaller(RootCallTarget target) { super(null); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, frame.getArguments()); }
        RootCallTarget cloneTarget() { getCallTarget(); assertTrue(call.cloneCallTarget()); return (RootCallTarget) call.getClonedCallTarget(); }
    }
    private List<LeadingCaseReturnNode> leadingNodes(RootNode root) {
        if (!(root instanceof BytecodeRoot bytecode)) return NodeUtil.findAllNodeInstances(root, LeadingCaseReturnNode.class);
        // Bytecode DSL cached operation nodes are not ordinary @Children entries.
        var nodes = new LinkedHashSet<Node>(); for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments()) if (argument.getDescriptor().getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var node = argument.asCachedNode(); if (node != null) nodes.add(node); }
        var result = new ArrayList<LeadingCaseReturnNode>(); for (var node : nodes) result.addAll(NodeUtil.findAllNodeInstances(node, LeadingCaseReturnNode.class)); return result;
    }
    private Thunk thunk(Object value) { return thunk(value, false); }
    private Thunk thunk(Object value, boolean failure) { return new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { if (failure) throw new RuntimeFault("forced unused argument"); return value; } }.getCallTarget(), null); }
    private <T> T single(List<T> values) { if (values.isEmpty()) throw new NoSuchElementException("List is empty."); if (values.size() != 1) throw new IllegalArgumentException("List has more than one element."); return values.getFirst(); }

    @Test void onlyCompiledDirectCallsShortcutAndPreservePendingWorkAndFullWidthResults() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) each(enabled, true, false, List.of(binding("worker", worker()), binding("driver", driver()), binding("tailDriver", driver(false, true))), (backend, p) -> {
            var fn = p.entryValue("worker"); var stop = p.entryValue("stop"); var next = p.entryValue("next"); assertEquals(enabled, ((GuestRoot) p.entryTarget("worker").getRootNode()).getLeadingCaseReturn() != null);
            for (int i = 0; i < 25; i++) { assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(19L, run(p, "driver", fn, 11L, next)); }
            for (int i = 0; i < 10; i++) assertEquals(11L, run(p, "tailDriver", fn, 11L, stop)); assertEquals(0L, count(p), backend + " interpreter must retain ordinary calls");
            compile(p.entryTarget("driver")); compile(p.entryTarget("tailDriver")); long before = count(p);
            for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_017L}) { assertEquals(value + 1, run(p, "driver", fn, value, stop), backend); assertEquals(value, run(p, "tailDriver", fn, value, stop), backend); }
            assertEquals(enabled ? before + 6 : before, count(p), backend); long hits = count(p); assertEquals(19L, run(p, "driver", fn, 11L, next), backend); assertEquals(0L, run(p, "driver", fn, 11L, p.entryValue("other")), backend); assertEquals(hits, count(p), backend + " nonmatching layouts must execute the original case");
        });
    }
    @Test void unusedStrictFormalsAreForcedBeforeShortcutAndPapPrefixesOnlyAtSaturation() throws Exception {
        each(List.of(binding("worker", worker(true, true, false, null)), binding("driver", driver(true, false)), binding("saturate", lambda(List.of(parameter("fn", closure), parameter("unused", data)), plus(apply(variable("fn"), List.of(variable("unused")), List.of(true)), integer(1))))), (backend, p) -> {
            var fn = (Closure) p.entryValue("worker"); var stop = p.entryValue("stop");
            for (int i = 0; i < 25; i++) { assertEquals(12L, run(p, "driver", fn, 11L, stop, stop)); assertEquals(12L, run(p, "driver", fn, 11L, stop, thunk(stop))); }
            compile(p.entryTarget("driver")); var delayed = thunk(stop); long before = count(p, "thunkEvaluations"); assertEquals(12L, run(p, "driver", fn, 11L, stop, delayed), backend); assertEquals(before + 1, count(p, "thunkEvaluations")); assertEquals(2, delayed.getState()); long hits = count(p);
            assertThrows(RuntimeFault.class, () -> run(p, "driver", fn, 11L, stop, thunk(null, true))); assertEquals(hits, count(p), "A throwing strict argument prevents the shortcut");
            var prefix = thunk(stop); var pap = fn.pap(new Object[]{31L, prefix}); assertEquals(0, prefix.getState(), "PAP allocation must remain lazy");
            for (int i = 0; i < 25; i++) { assertEquals(32L, run(p, "saturate", pap, stop)); assertEquals(32L, run(p, "saturate", fn.pap(new Object[]{31L, thunk(stop)}), thunk(stop))); }
            compile(p.entryTarget("saturate")); var fresh = thunk(stop); var freshPap = fn.pap(new Object[]{47L, fresh}); var freshUnused = thunk(stop); long papHits = count(p);
            assertEquals(48L, run(p, "saturate", freshPap, freshUnused), backend); assertEquals(papHits + 1, count(p), "Both prefix and suffix are forced before a saturated PAP hit"); assertEquals(2, fresh.getState()); assertEquals(2, freshUnused.getState());
        });
    }
    @Test void unmarkedThunkScrutineeFallsBackWhileMarkedScrutineeCanReturnAfterForcing() throws Exception {
        for (boolean strict : new boolean[]{false, true}) each(List.of(binding("worker", worker(false, strict, false, null)), binding("driver", driver())), (backend, p) -> {
            var fn = p.entryValue("worker"); var stop = p.entryValue("stop"); for (int i = 0; i < 25; i++) { assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(12L, run(p, "driver", fn, 11L, thunk(stop))); }
            compile(p.entryTarget("driver")); var delayed = thunk(stop); long before = count(p), evaluations = count(p, "thunkEvaluations"); assertEquals(12L, run(p, "driver", fn, 11L, delayed), backend); assertEquals(2, delayed.getState()); assertEquals(evaluations + 1, count(p, "thunkEvaluations")); assertEquals(before + (strict ? 1 : 0), count(p), backend);
        });
    }
    @Test void capturedEnvironmentOffsetAndOverapplicationRemainCorrect() throws Exception {
        each(List.of(binding("factory", lambda(List.of(parameter("captured")), worker(false, false, true, null), List.of(false), closure)), binding("worker", worker()), binding("driver", driver()), binding("over", lambda(List.of(parameter("fn", closure), parameter("acc"), parameter("tree", data)), apply(variable("fn"), List.of(variable("acc"), variable("tree"), integer(1)), List.of(false, true, false))))), (backend, p) -> {
            var fn = (Closure) run(p, "factory", 100L); var stop = p.entryValue("stop"); var next = p.entryValue("next"); assertNotNull(fn.environment); assertEquals(2, ((GuestRoot) fn.target.getRootNode()).getEntryArgumentOffset()); assertNull(((GuestRoot) fn.target.getRootNode()).getLeadingCaseReturn(), "Captured entry validation stays on the normal path");
            for (int i = 0; i < 25; i++) { assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(119L, run(p, "driver", fn, 11L, next)); }
            compile(p.entryTarget("driver")); long before = count(p); assertEquals(Long.MAX_VALUE, run(p, "driver", fn, Long.MAX_VALUE - 1, stop), backend); assertEquals(before, count(p));
            // Returning an Int# does not make overapplication valid, even on the trivial arm.
            var eligible = p.entryValue("worker"); for (int i = 0; i < 20; i++) assertThrows(RuntimeFault.class, () -> run(p, "over", eligible, 11L, stop)); compile(p.entryTarget("over"));
            // Throwing code may transfer to the interpreter before this compiled-only optimization.
            // The continuation must still reject overapplication in either execution tier.
            assertThrows(RuntimeFault.class, () -> run(p, "over", eligible, 11L, stop));
        });
    }
    @Test void diagnosticReplacementCannotAcquireARecipeButAnUnsupportedColdBodyCan() throws Exception {
        var unsupportedCase = list("case", variable("tree"), "treeCase", list(list("data", "Stop", List.of(), variable("acc")), list("data", "Unsupported", List.of(), integer(0))), map("rep", longRep));
        each(true, true, true, List.of(binding("broken", lambda(List.of(parameter("acc"), parameter("tree", data)), unsupportedCase)), binding("cold", worker(false, false, false, list("unsupported", "cold"))), binding("driver", driver())), (backend, p) -> {
            assertNull(((GuestRoot) p.entryTarget("broken").getRootNode()).getLeadingCaseReturn(), backend); assertThrows(RuntimeFault.class, () -> run(p, "driver", p.entryValue("broken"), 11L, p.entryValue("stop")));
            var fn = p.entryValue("cold"); var stop = p.entryValue("stop"); assertNotNull(((GuestRoot) p.entryTarget("cold").getRootNode()).getLeadingCaseReturn()); for (int i = 0; i < 25; i++) assertEquals(12L, run(p, "driver", fn, 11L, stop));
            compile(p.entryTarget("driver")); long before = count(p); assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(before + 1, count(p)); assertThrows(RuntimeFault.class, () -> run(p, "driver", fn, 11L, p.entryValue("next")));
        });
    }
    @Test void otherUsedFormalValidationAndUnprovenResultsCannotBeSkipped() throws Exception {
        var base = worker(true, false, false, null); var arms = new ArrayList<>((List<List<Object>>) ((List<Object>) base.get(2)).get(3)); var arm = new ArrayList<>(arms.get(1)); arm.set(3, plus(variable("acc"), variable("unused"))); arms.set(1, arm);
        var body = new ArrayList<>((List<Object>) base.get(2)); body.set(3, arms); var used = lambda(List.of(parameter("acc"), parameter("tree", data), parameter("unused")), body); var legacyParameter = new LinkedHashMap<>(parameter("acc")); legacyParameter.remove("rep"); var legacy = lambda(List.of(legacyParameter, parameter("tree", data)), (List<Object>) worker().get(2));
        each(List.of(binding("used", used), binding("legacy", legacy), binding("driver", driver(true, false))), (backend, p) -> {
            assertNull(((GuestRoot) p.entryTarget("used").getRootNode()).getLeadingCaseReturn(), backend); assertNull(((GuestRoot) p.entryTarget("legacy").getRootNode()).getLeadingCaseReturn(), backend);
            var fn = p.entryValue("used"); var stop = p.entryValue("stop"); for (int i = 0; i < 25; i++) assertEquals(12L, run(p, "driver", fn, 11L, stop, 3L)); compile(p.entryTarget("driver")); var failure = assertThrows(RuntimeFault.class, () -> run(p, "driver", fn, 11L, stop, "invalid primitive carrier"));
            String expectedMessage = backend.equals("ast") && Boolean.getBoolean(HandoffKt.HANDOFF_PROPERTY) ? "Invalid Long handoff field" : backend.equals("ast") ? "Expected primitive Long argument" : "Expected primitive Long";
            assertEquals(expectedMessage, failure.getMessage()); assertEquals(0L, count(p));
        });
    }
    @Test void sourceNotesAndClonedRecipesPreserveAttributionWithoutAddingRootEntries() throws Exception {
        for (boolean notes : new boolean[]{false, true}) each(true, notes, false, List.of(binding("worker", worker()), binding("driver", driver())), (backend, p) -> {
            var fn = (Closure) p.entryValue("worker"); var stop = p.entryValue("stop"); var root = (GuestRoot) fn.target.getRootNode(); var recipe = Objects.requireNonNull(root.getLeadingCaseReturn()); var source = recipe.getSource();
            assertEquals(notes ? "acc" : null, source == null || source.getSection() == null ? null : source.getSection().getCharacters().toString(), backend);
            if (notes) { var ids = new ArrayList<String>(); for (var note : Objects.requireNonNull(recipe.getSource()).getNotes()) ids.add(note.getId()); assertEquals(list("case", "arm"), ids); }
            var workerCaller = new CloneCaller(fn.target); var clonedWorker = workerCaller.cloneTarget(); assertSame(recipe, ((GuestRoot) clonedWorker.getRootNode()).getLeadingCaseReturn()); assertEquals(11L, Calls.target(workerCaller.getCallTarget(), new Object[]{0L, 11L, stop}));
            for (int i = 0; i < 25; i++) assertEquals(12L, run(p, "driver", fn, 11L, stop));
            // Truffle clones belong to their originating DirectCallNode; do not create
            // a new DirectCallNode targeting an already-cloned target.
            var driverCaller = new CloneCaller(p.entryTarget("driver")); var clonedDriver = driverCaller.cloneTarget(); for (int i = 0; i < 25; i++) assertEquals(12L, Calls.target(driverCaller.getCallTarget(), new Object[]{0L, fn, 11L, stop}));
            var originalChildren = leadingNodes(p.entryTarget("driver").getRootNode()); var children = leadingNodes(clonedDriver.getRootNode()); assertTrue(!children.isEmpty(), backend); assertNotSame(single(originalChildren), single(children));
            var section = single(children).getSourceSection(); assertEquals(notes ? "acc" : null, section == null ? null : section.getCharacters().toString()); assertEquals(single(originalChildren).getCoreSourceNotes(), single(children).getCoreSourceNotes());
            compile(clonedDriver); long hits = count(p), entries = count(p, "compiledEntries"); assertEquals(12L, Calls.target(driverCaller.getCallTarget(), new Object[]{0L, fn, 11L, stop})); assertEquals(hits + 1, count(p)); assertEquals(entries + 1, count(p, "compiledEntries"), "Only the caller actually enters a guest root");
        });
    }
}
