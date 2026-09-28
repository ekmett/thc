// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Core protocol tests, not native GHC snapshot or IPE equivalence. */
public class ManagedStackSnapshotTest {
    private static final class Capture extends Expr {
        ManagedStackSnapshot snapshot; Node differentNode; int compiledEntries; boolean throwAfterCapture; List<String> beforeUnwind;
        @Override public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) compiledEntries++; snapshot = ManagedStackSnapshot.capture(differentNode == null ? this : differentNode);
            if (throwAfterCapture) { beforeUnwind = snapshot.renderLines(); throw new GuestException(snapshot, this); } return 7L;
        }
    }
    private static final class Probe extends GuestRoot {
        @Child Capture body; String label = "capture-probe"; private final SourceSection rootSection;
        Probe(Language language, CoreSourceLocation location) { this(language, location, null); }
        Probe(Language language, CoreSourceLocation location, SourceSection rootSection) { super(language, new FrameLayout().build()); this.rootSection = rootSection; body = new Capture(); body.located(location); }
        @Override public Object execute(VirtualFrame frame) { return body.execute(frame); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public String getName() { return label; }
        @Override public SourceSection getSourceSection() { return rootSection; }
    }
    private CoreSourceLocation location() throws Exception { return location(List.of()); }
    private CoreSourceLocation location(List<CoreSourceNote> notes) throws Exception { var source = Source.newBuilder("thc", "", "Capture.hs").content(Source.CONTENT_NONE).build(); return new CoreSourceLocation(source.createSection(91, 7, 91, 11), notes); }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private final ManagedStackFunctionIdentity entryIdentity = new ManagedStackFunctionIdentity("app-unit:Entry.Owner.$wentry_r17", "app-unit", "Entry.Owner", "$wentry_r17");
    private final ManagedStackFunctionIdentity workerIdentity = new ManagedStackFunctionIdentity("library-unit:Worker.Owner.worker", "library-unit", "Worker.Owner", "worker");
    private String bindingId(String id, boolean named) { if (!named) return id; return switch (id) { case "entry" -> entryIdentity.getBindingId(); case "worker" -> workerIdentity.getBindingId(); default -> id; }; }
    private Map<String, Object> metadata(String id, String outer) { return Map.of("source", id, "sourceNotes", outer.equals(id) ? List.of(outer) : List.of(outer, id)); }
    private Map<String, Object> span(String text, String id, int line, int length) { int index = 0; var lines = text.split("\n", -1); for (int i = 0; i < line - 1; i++) index += lines[i].length() + 1; return Map.of("id", id, "file", "fixture", "startLine", line, "startColumn", 1, "endLine", line, "endColumn", length + 1, "charIndex", index, "charLength", length, "label", id); }
    private Map<String, Object> function(boolean named, String id, String formal, String target, List<Object> arg, boolean lifted) {
        var outer = id + "-root"; var site = id + "-call"; var call = List.of("app", List.of("var", bindingId(target, named)), List.of(arg), List.of(lifted), false, false, metadata(site, outer));
        var add = List.of("app", List.of("prim", "+#"), List.of(List.of("var", id + "-result"), List.of("lit", "int", "1")), List.of(false, false), false, false, metadata(outer, outer));
        // Keep both calls non-tail so both guest frames are live during capture.
        var body = List.of("case", call, id + "-result", List.of(Arrays.asList("default", null, List.of(), add)), metadata(outer, outer));
        return Map.of("id", bindingId(id, named), "name", id, "lifted", true, "source", outer, "expr", List.of("lam", List.of(Map.of("id", formal, "name", formal, "lifted", true)), body, metadata(outer, outer)));
    }
    private Map<String, Object> module() { return module(true, false); }
    private Map<String, Object> module(boolean enabled, boolean named) {
        var text = "entry outerCallback =\n  worker outerCallback + 1\nworker innerCallback =\n  innerCallback 0 + 1\n"; var origins = new LinkedHashMap<String, Object>();
        if (named) for (var identity : List.of(entryIdentity, workerIdentity)) origins.put(identity.getBindingId(), Map.of("unit", identity.getUnitId(), "module", identity.getModuleName()));
        return Map.of("sourceNotesEnabled", enabled, "instrument", true, "bindingOrigins", origins, "sourceFiles", List.of(Map.of("id", "fixture", "path", "Snapshot.hs", "content", text)),
            "sourceSpans", List.of(span(text, "entry-root", 1, 21), span(text, "entry-call", 2, 24), span(text, "worker-root", 3, 22), span(text, "worker-call", 4, 21)),
            "bindings", List.of(function(named, "entry", "outerCallback", "worker", List.of("var", "outerCallback"), true), function(named, "worker", "innerCallback", "innerCallback", List.of("lit", "int", "0"), false)));
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) { var targets = new ArrayList<RootCallTarget>(); Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); visit(entry, targets, seen); return targets; }
    private void visit(RootCallTarget target, List<RootCallTarget> targets, Set<RootCallTarget> seen) {
        if (!seen.add(target)) return; var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, targets, seen); targets.add(target);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
    private void detached(ManagedStackSnapshot snapshot) throws Exception {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>()); detachedVisit(snapshot, snapshot, seen);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getFrames().clear()); assertThrows(UnsupportedOperationException.class, () -> snapshot.getFrames().getFirst().getSections().clear()); assertThrows(UnsupportedOperationException.class, () -> snapshot.getFrames().getFirst().getNotes().clear());
    }
    private void detachedVisit(Object value, ManagedStackSnapshot snapshot, Set<Object> seen) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return; if (!seen.add(value)) return;
        if (value == snapshot.getOwnerToken()) { assertEquals(Object.class, value.getClass(), "Opaque ownership token must not retain context state"); assertEquals(0, value.getClass().getDeclaredFields().length); return; }
        if (value instanceof List<?> list) { for (var child : list) detachedVisit(child, snapshot, seen); return; }
        assertTrue(value instanceof ManagedStackSnapshot || value instanceof ManagedStackFrame || value instanceof ManagedStackSource || value instanceof ManagedStackNote || value instanceof ManagedStackFunctionIdentity, "Snapshot must not retain " + value.getClass().getName());
        for (var field : value.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) { assertTrue(Modifier.isFinal(field.getModifiers()), field.getName()); field.setAccessible(true); detachedVisit(field.get(value), snapshot, seen); }
    }
    private <T> List<T> frames(ManagedStackSnapshot snapshot, Function<ManagedStackFrame, T> read) { var result = new ArrayList<T>(); for (var frame : snapshot.getFrames()) result.add(read.apply(frame)); return result; }
    private List<Integer> lines(ManagedStackSnapshot snapshot) { return frames(snapshot, frame -> frame.getLocation() == null ? null : frame.getLocation().getStartLine()); }
    private void released(Language language) { var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); }
    @Test public void realAstAndBytecodeFramesKeepCurrentAndCallerLocationsAfterReturnAndCompilation() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                var probe = new Probe(language, location()); var callback = new Closure(null, 1, probe.getCallTarget()); var entry = program.entryTarget("entry");
                class Runner {
                    ManagedStackSnapshot invoke() { assertEquals(9L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{callback}})); return Objects.requireNonNull(probe.body.snapshot); }
                    void check(ManagedStackSnapshot snapshot) throws Exception {
                        assertEquals(List.of("capture-probe", "lambda innerCallback", "lambda outerCallback"), frames(snapshot, ManagedStackFrame::getFunctionName), backend);
                        assertEquals(List.of(91, 4, 2), lines(snapshot), backend + "/" + inlining + " callsite ownership");
                        for (var frame : snapshot.getFrames()) assertNull(frame.getCoreIdentity(), "Source/debug labels must not invent binding owners");
                        assertEquals(7, snapshot.getFrames().get(0).getLocation() == null ? null : snapshot.getFrames().get(0).getLocation().getStartColumn()); assertEquals(ManagedStackLocationKind.CURRENT_NODE, snapshot.getFrames().get(0).getLocationKind());
                        for (var frame : snapshot.getFrames().subList(1, snapshot.getFrames().size())) {
                            assertEquals(backend.equals("ast") ? ManagedStackLocationKind.CALL_NODE : ManagedStackLocationKind.BYTECODE, frame.getLocationKind());
                            if (backend.equals("ast")) { boolean found = false; for (var note : frame.getNotes()) if (note.getId().endsWith("-call")) { found = true; break; } assertTrue(found); }
                            else { assertTrue(frame.getSections().size() >= 2); assertTrue(frame.getNotes().isEmpty()); }
                        }
                        assertTrue(snapshot.renderLines().getFirst().contains("Capture.hs:91:7")); detached(snapshot);
                    }
                }
                var runner = new Runner(); var first = runner.invoke(); runner.check(first); var rendered = first.renderLines(); for (int i = 0; i < 5; i++) runner.check(runner.invoke());
                var targets = activeTargets(entry); assertEquals(3, targets.size()); for (var target : targets) compile(target); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); int probeBefore = probe.body.compiledEntries;
                runner.check(runner.invoke()); assertEquals(2L, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before); assertEquals(probeBefore + 1, probe.body.compiledEntries); for (var target : targets) valid(target);
                assertEquals(rendered, first.renderLines(), "Later capture must not mutate the returned snapshot"); for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue()); released(language);
            } finally { context.leave(); }
        }
    }
    @Test public void exactBindingIdentitiesSurviveCompilationInliningAndContextCloseWithoutChangingDebugNames() throws Exception {
        var expected = Arrays.asList(null, workerIdentity, entryIdentity);
        for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) {
            var retained = new ArrayList<ManagedStackSnapshot>(); var rendered = new ArrayList<List<String>>();
            try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, module(true, true)) : new BytecodeProgram(language, module(true, true));
                    // Even a plausible qualified debug name is not binding provenance.
                    var probe = new Probe(language, location()); probe.label = "invented-unit:Fake.Module.capture"; var callback = new Closure(null, 1, probe.getCallTarget()); var entry = program.entryTarget(entryIdentity.getBindingId());
                    class Runner {
                        ManagedStackSnapshot invoke() { assertEquals(9L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(entryIdentity.getBindingId()), new Object[]{callback}})); return Objects.requireNonNull(probe.body.snapshot); }
                        void check(ManagedStackSnapshot snapshot) throws Exception {
                            assertEquals(expected, frames(snapshot, ManagedStackFrame::getCoreIdentity), backend + "/" + inlining);
                            assertEquals(List.of(probe.label, "lambda innerCallback", "lambda outerCallback"), frames(snapshot, ManagedStackFrame::getFunctionName)); assertEquals(List.of(91, 4, 2), lines(snapshot));
                            assertEquals(List.of("Capture.hs", "Snapshot.hs", "Snapshot.hs"), frames(snapshot, frame -> frame.getLocation() == null ? null : frame.getLocation().getName())); assertTrue(snapshot.renderLines().get(1).startsWith("lambda innerCallback (")); detached(snapshot);
                        }
                    }
                    var runner = new Runner(); var first = runner.invoke(); runner.check(first); retained.add(first); assertEquals(0L, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertEquals(0, probe.body.compiledEntries);
                    for (int i = 0; i < 5; i++) runner.check(runner.invoke()); var targets = activeTargets(entry); assertEquals(3, targets.size()); for (var target : targets) compile(target);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); int probeBefore = probe.body.compiledEntries; var installed = runner.invoke(); runner.check(installed); retained.add(installed);
                    assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertEquals(probeBefore + 1, probe.body.compiledEntries); for (var target : targets) valid(target); for (var snapshot : retained) rendered.add(snapshot.renderLines()); released(language);
                } finally { context.leave(); }
            }
            for (int i = 0; i < retained.size(); i++) { var snapshot = retained.get(i); assertEquals(expected, frames(snapshot, ManagedStackFrame::getCoreIdentity)); assertEquals(rendered.get(i), snapshot.renderLines(), backend + "/" + inlining + " after context close"); detached(snapshot); }
        }
    }
    @Test public void missingMetadataRetainsRealFramesWithoutInventingLocations() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, module(false, false)) : new BytecodeProgram(language, module(false, false)); var probe = new Probe(language, null);
                assertEquals(9L, Calls.target(program.entryTarget("entry"), new Object[]{0L, new Closure(null, 1, probe.getCallTarget())})); var snapshot = Objects.requireNonNull(probe.body.snapshot); assertEquals(3, snapshot.getFrames().size());
                for (var frame : snapshot.getFrames()) assertTrue(frame.getLocation() == null && frame.getSections().isEmpty() && frame.getNotes().isEmpty()); for (var frame : snapshot.getFrames()) assertEquals(ManagedStackLocationKind.UNAVAILABLE, frame.getLocationKind());
                for (var line : snapshot.renderLines()) assertTrue(line.endsWith("(<source unavailable>)")); detached(snapshot);
            } finally { context.leave(); }
        }
    }
    @Test public void copiedNotesRetainExclusiveEndsAndDoNotKeepMutableNodesOrLists() throws Exception {
        var section = Objects.requireNonNull(location().getSection()); var notes = new ArrayList<>(List.of(new CoreSourceNote("original", section, "source label", 91, 7, 92, 1))); var probe = new Probe(null, location(notes));
        assertEquals(7L, Calls.target(probe.getCallTarget(), new Object[]{0L})); var snapshot = Objects.requireNonNull(probe.body.snapshot); notes.clear(); probe.label = "changed"; probe.body.setCoreSourceLocation(null);
        if (snapshot.getFrames().size() != 1) throw new IllegalArgumentException("Expected one frame"); var frame = snapshot.getFrames().getFirst(); assertEquals("capture-probe", frame.getFunctionName()); if (frame.getNotes().size() != 1) throw new IllegalArgumentException("Expected one note"); var note = frame.getNotes().getFirst();
        assertEquals("original", note.getId()); assertEquals("source label", note.getLabel()); assertEquals(92, note.getEndLine()); assertEquals(1, note.getEndColumn()); assertEquals(91, note.getSource().getEndLine()); assertEquals(11, note.getSource().getEndColumn()); detached(snapshot);
    }
    @Test public void rootFallbackIsNotMislabelledAsCurrentOrCallerLocation() throws Exception {
        var section = location().getSection(); var probe = new Probe(null, null, section);
        var caller = new GuestRoot(null, new FrameLayout().build()) {
            @Child DirectCallNode call = DirectCallNode.create(probe.getCallTarget());
            @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, new Object[]{0L}); }
            @Override public long bloom(VirtualFrame frame) { return 0L; }
            @Override public String getName() { return "root-only-caller"; }
            @Override public SourceSection getSourceSection() { return section; }
        };
        assertEquals(7L, Calls.target(caller.getCallTarget(), new Object[]{0L})); var snapshot = Objects.requireNonNull(probe.body.snapshot); assertEquals(List.of("capture-probe", "root-only-caller"), frames(snapshot, ManagedStackFrame::getFunctionName));
        assertEquals(List.of(ManagedStackLocationKind.ROOT, ManagedStackLocationKind.ROOT), frames(snapshot, ManagedStackFrame::getLocationKind)); for (var frame : snapshot.getFrames()) assertEquals(91, frame.getLocation() == null ? null : frame.getLocation().getStartLine());
        probe.body.differentNode = probe; assertEquals(7L, Calls.target(probe.getCallTarget(), new Object[]{0L})); if (probe.body.snapshot.getFrames().size() != 1) throw new IllegalArgumentException("Expected one frame"); assertEquals(ManagedStackLocationKind.ROOT, probe.body.snapshot.getFrames().getFirst().getLocationKind());
    }
    @Test public void captureBeforeSynchronousUnwindRemainsStableAfterGuestFramesAndContextReturn() throws Exception {
        for (var backend : List.of("ast", "bytecode")) {
            ManagedStackSnapshot retained; List<String> rendered;
            try (var context = context(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module()); var probe = new Probe(language, location()); probe.body.throwAfterCapture = true;
                    var failure = assertThrows(GuestException.class, () -> Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{new Closure(null, 1, probe.getCallTarget())}}));
                    retained = Objects.requireNonNull(probe.body.snapshot); assertSame(retained, failure.getPayload()); rendered = Objects.requireNonNull(probe.body.beforeUnwind);
                    assertEquals(List.of("capture-probe", "lambda innerCallback", "lambda outerCallback"), frames(retained, ManagedStackFrame::getFunctionName)); assertEquals(List.of(91, 4, 2), lines(retained)); assertEquals(rendered, retained.renderLines());
                    probe.label = "changed-after-unwind"; probe.body.setCoreSourceLocation(null); released(language);
                } finally { context.leave(); }
            }
            assertEquals(rendered, retained.renderLines(), backend + " snapshot survives unwinding and context close"); detached(retained);
        }
    }
    @Test public void absentInactiveAndWrongCurrentGuestNodesFailInsteadOfReturningEmptySnapshots() throws Exception {
        assertThrows(RuntimeFault.class, () -> ManagedStackSnapshot.capture(new Node() {})); var inactive = new Probe(null, location()); inactive.getCallTarget(); assertThrows(RuntimeFault.class, () -> ManagedStackSnapshot.capture(inactive.body));
        var active = new Probe(null, location()); active.body.differentNode = inactive.body; assertThrows(RuntimeFault.class, () -> Calls.target(active.getCallTarget(), new Object[]{0L})); assertNull(active.body.snapshot);
    }
}
