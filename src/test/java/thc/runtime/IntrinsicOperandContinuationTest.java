// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import jdk.incubator.vector.LongVector;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.CoreModules;
import thc.ForeignExceptionFixtureSupport;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Real MVar cuts in foreign and intrinsic operands retain prefixes and pending calls. */
class IntrinsicOperandContinuationTest {
    private static final String PREFIX = "main:IntrinsicOperands.";
    @SuppressWarnings("unchecked") private static Map<String, Object> module(boolean vector, boolean nested) throws Exception {
        var path = Path.of(System.getProperty("thc.projectRoot"), "build/truffle-strings");
        var manifest = (Map<String, Object>) Json.parse(Files.readString(path.resolve("manifest.json")));
        for (String key : List.of("inputHashes", "artifactHashes"))
            for (var entry : ((Map<String, String>) manifest.get(key)).entrySet()) {
                var bytes = Files.readAllBytes(Path.of(System.getProperty("thc.projectRoot"), entry.getKey()));
                assertEquals(entry.getValue(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
        var source = thc.CoreCbdFixtures.read(path.resolve("core/IntrinsicOperands.cbd"));
        String name = vector ? "vectorOperands" : "stringOperands";
        var selected = CoreModules.reachable(source, List.of(PREFIX + name, PREFIX + "takeRaw", PREFIX + "takeIndex", PREFIX + "takeInt64"), true);
        selected.put("instrument", true);
        if (nested) {
            // GHC currently case-floats these strict operands. Contract exactly
            // three single-use DEFAULT bindings back into their application,
            // preserving evaluation order, actual expressions and genuine ABI.
            // This is an explicit legal-Core control, not an exporter-shape claim.
            var binding = ((List<Map<String, Object>>) selected.get("bindings")).stream()
                .filter(b -> (PREFIX + name).equals(b.get("id"))).findFirst().orElseThrow();
            var lambda = (List<Object>) binding.get("expr");
            var body = (List<Object>) lambda.get(2);
            var values = new ArrayList<Object>(); var ids = new ArrayList<Object>();
            for (int i = 0; i < 3; i++) {
                assertEquals("case", body.get(0)); values.add(body.get(1)); ids.add(body.get(2));
                var alternatives = (List<List<Object>>) body.get(3); assertEquals(1, alternatives.size());
                assertEquals("default", alternatives.getFirst().getFirst());
                body = (List<Object>) alternatives.getFirst().get(3);
            }
            assertEquals("app", body.getFirst());
            var args = (List<List<Object>>) body.get(2); assertEquals(3, args.size());
            for (int i = 0; i < 3; i++) { assertEquals("var", args.get(i).getFirst()); assertEquals(ids.get(i), args.get(i).get(1)); }
            var direct = new ArrayList<>(body); direct.set(2, values); lambda.set(2, direct);
        }
        return selected;
    }
    private static Object partial(ExecutableProgram program, String name, ManagedMVar cell) {
        return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(PREFIX + name), new Object[]{cell}});
    }
    private static void offer(ExecutableProgram program, ManagedMVar cell, Object value) {
        var layout = program.constructorLayout(PREFIX + (value instanceof Long ? "Operand" : "RawOperand"));
        assertTrue(cell.tryPut(value instanceof Long n ? layout.createLong(n) : layout.create(new Object[]{value})));
    }
    private static Object[] arguments(ExecutableProgram program, boolean vector, ManagedMVar[] cells) {
        return new Object[]{0L, partial(program, "takeRaw", cells[0]),
            partial(program, vector ? "takeIndex" : "takeRaw", cells[1]),
            partial(program, vector ? "takeInt64" : "takeIndex", cells[2])};
    }
    private static SavedGuestContinuation cut(Context context, Language.State owner, ManagedMVar cell, Supplier<Object> action) throws Exception {
        var answer = new CompletableFuture<Object>();
        var thread = new Thread(() -> {
            context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                Object result = action.get();
                var saved = SavedGuestContinuations.savedGuestContinuation(result instanceof TailYield tail ? tail.getContinuation() : result);
                if (saved != null && saved.asyncRequest() != null) saved.asyncRequest().acknowledge();
                answer.complete(saved == null ? result : saved);
            } catch (Throwable failure) { answer.completeExceptionally(failure); }
            finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        });
        thread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cell.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
            if (answer.isDone()) fail("Operand suffix completed instead of reaching its real MVar cut: " + answer.get());
            assertEquals(1, cell.pendingCounts().getTakers());
            var request = owner.getThreads().send(Objects.requireNonNull(owner.getThreads().pollState(thread).getCurrent()).getIdentity(), "operand cut");
            var saved = assertInstanceOf(SavedGuestContinuation.class, answer.get(10, TimeUnit.SECONDS));
            assertSame(request, saved.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
            return saved;
        } finally { if (thread.isAlive()) thread.join(5000); if (thread.isAlive()) context.close(true); thread.join(5000); }
    }
    private static void result(boolean vector, Object value) {
        if (vector) {
            var actual = assertInstanceOf(LongVector.class, value);
            assertEquals(7L, actual.lane(0)); assertEquals(42L, actual.lane(1));
        } else assertEquals(0x1f600L, value);
    }

    private static Map<String, Object> foreignModule() throws Exception {
        var word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var cell = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var ref = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("IntRep"),
            "components", List.of(state, word), "evaluated", true);
        var read = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("BoxedRep (Just Lifted)"),
            "components", List.of(state, ref), "evaluated", true);
        var take = List.of("app", List.of("prim", "takeMVar#"), List.of(
            List.of("var", "cell", Map.of("rep", cell)), List.of("void", Map.of("rep", state))),
            List.of(false, false), false, false, Map.of("rep", read));
        var unbox = List.of("case", List.of("var", "boxed", Map.of("rep", ref)), "box", List.of(List.of("data", PREFIX + "Operand", List.of("value"),
            List.of("var", "value", Map.of("rep", word)), Map.of("binders", List.of(Map.of("id", "value", "lifted", false, "rep", word))))),
            Map.of("rep", word, "binder", Map.of("id", "box", "lifted", true, "rep", ref)));
        var takeBody = List.of("case", take, "read", List.of(List.of("data", "read-pair", List.of("state", "boxed"), unbox,
            Map.of("binders", List.of(Map.of("id", "state", "lifted", false, "rep", state), Map.of("id", "boxed", "lifted", true, "rep", ref))))),
            Map.of("rep", word, "binder", Map.of("id", "read", "lifted", false, "rep", read)));
        var takeBinding = Map.of("id", PREFIX + "takeIndex", "name", "takeIndex", "arity", 1, "lifted", true, "rep", closure,
            "expr", List.of("lam", List.of(Map.of("id", "cell", "lifted", false, "rep", cell)), takeBody, Map.of("rep", closure, "resultRep", word)));
        var arguments = new ArrayList<Object>();
        var parameters = new ArrayList<Object>();
        for (String name : List.of("left", "right")) {
            parameters.add(Map.of("id", name, "lifted", false, "rep", cell));
            arguments.add(List.of("app", List.of("var", PREFIX + "takeIndex", Map.of("rep", closure)),
                List.of(List.of("var", name, Map.of("rep", cell))), List.of(false), false, false, Map.of("rep", word)));
        }
        arguments.add(List.of("void", Map.of("rep", state)));
        String script = "(a,b) => { globalThis.thcForeignOperandCalls++; return a+b; }";
        var declaration = Map.ofEntries(Map.entry("schema", 1), Map.entry("target", Map.of("kind", "static", "isFunction", true,
                "symbol", "thc_javascript_v1_" + HexFormat.of().formatHex(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
            Map.entry("convention", "ccall"), Map.entry("safety", "safe"), Map.entry("arity", 3), Map.entry("suppliedArity", 3),
            Map.entry("argumentReps", List.of(word, word, state)), Map.entry("resultRep", tuple),
            Map.entry("intrinsic", "javascript-v1"), Map.entry("javascriptSource", script));
        var call = List.of("app", List.of("var", "foreign-add", Map.of("rep", closure)), arguments,
            List.of(false, false, false), false, false, Map.of("rep", tuple, "foreignCall", declaration));
        var body = List.of("case", call, "pair", List.of(List.of("data", "foreign-pair", List.of("s", "n"),
            List.of("var", "n", Map.of("rep", word)), Map.of("binders", List.of(
                Map.of("id", "s", "lifted", false, "rep", state), Map.of("id", "n", "lifted", false, "rep", word))))),
            Map.of("rep", word, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        var source = new LinkedHashMap<String, Object>();
        source.put("schema", 1); source.put("ghc", "9.14.1"); source.put("unit", "main"); source.put("module", "IntrinsicOperands");
        source.put("instrument", true);
        source.put("bindings", List.of(takeBinding, Map.of("id", "foreignOperands", "name", "foreignOperands", "arity", 2, "lifted", true, "rep", closure,
            "expr", List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", word)))));
        source.put("constructors", List.of(
            Map.of("id", PREFIX + "Operand", "name", "Operand", "arity", 1, "fieldReps", List.of(List.of("IntRep")),
                "fieldLifted", List.of(false), "strictFields", List.of(false)),
            Map.of("id", "read-pair", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1),
            Map.of("id", "foreign-pair", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
        // Legal non-ANF Core uses real MVar operations; exception dictionaries
        // come from the genuine GHC-produced runtime fixture.
        return ForeignExceptionFixtureSupport.link(source, "foreignOperands");
    }

    @Tag("foreign-exceptions-full-core")
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void foreignOperandsRetainThePendingCallAfterSuspension(String backend, boolean compiled) throws Exception {
        try (var context = Context.newBuilder().allowAllAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.initialize("js"); context.enter();
            ExecutableProgram program; Language.State owner; RootCallTarget target, resume;
            var cells = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar()};
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                var source = foreignModule();
                program = backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                target = program.entryTarget("foreignOperands");
                context.eval("js", "globalThis.thcForeignOperandCalls = 0");
                for (int i = 0; i < (compiled ? 4 : 0); i++) {
                    var left = new ManagedMVar(); var right = new ManagedMVar();
                    offer(program, left, 19L); offer(program, right, 23L);
                    assertEquals(42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, left, right}));
                    assertTrue(left.isEmpty()); assertTrue(right.isEmpty());
                }
                assertEquals(compiled ? 4 : 0, context.eval("js", "globalThis.thcForeignOperandCalls").asInt());
                if (compiled) {
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                }
                context.eval("js", "globalThis.thcForeignOperandCalls = 0");
                offer(program, cells[0], 19L);
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var saved = cut(context, owner, cells[1], () -> ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, cells[0], cells[1]}));
            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
            assertTrue(cells[0].isEmpty(), "The first operand's effect completed before the cut");
            context.enter();
            try {
                assertEquals(0, context.eval("js", "globalThis.thcForeignOperandCalls").asInt());
                offer(program, cells[1], 23L);
                assertEquals(42L, Calls.target(resume, new Object[]{saved}));
                assertEquals(1, context.eval("js", "globalThis.thcForeignOperandCalls").asInt());
                assertTrue(cells[0].isEmpty()); assertTrue(cells[1].isEmpty());
                assertThrows(RuntimeException.class, () -> Calls.target(resume, new Object[]{saved}));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()));
                var handoff = TruffleLanguage.LanguageReference.create(Language.class).get(null).getHandoffState().get();
                assertNull(handoff.getPending()); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @CsvSource({"ast,false,false", "ast,false,true", "ast,true,false", "ast,true,true",
        "bytecode,false,false", "bytecode,false,true", "bytecode,true,false", "bytecode,true,true"})
    void genuineGuestOperandsResumeInOrderWithoutReplay(String backend, boolean vector, boolean nested) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            ExecutableProgram program; Language.State owner; RootCallTarget target, resume; Object[] args;
            var cells = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar(), new ManagedMVar()};
            Object first = vector ? LongVector.broadcast(LongVector.SPECIES_128, 7L) : TruffleString.Encoding.UTF_8;
            Object second = vector ? 1L : TruffleString.fromCodePointUncached(0x1f600, TruffleString.Encoding.UTF_8);
            Object third = vector ? 42L : 0L;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                var source = module(vector, nested);
                program = backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                target = program.entryTarget(PREFIX + (vector ? "vectorOperands" : "stringOperands"));
                // Ordinary completed calls establish profiles; no prior cut or suffix replay.
                for (int i = 0; i < 4; i++) {
                    var ready = new ManagedMVar[]{new ManagedMVar(), new ManagedMVar(), new ManagedMVar()};
                    offer(program, ready[0], first); offer(program, ready[1], second); offer(program, ready[2], third);
                    result(vector, ScalarTestCalls.callScalarTestTarget(target, arguments(program, vector, ready)));
                    for (var cell : ready) assertTrue(cell.isEmpty());
                }
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                args = arguments(program, vector, cells); offer(program, cells[0], first);
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
                }.getCallTarget();
            } finally { context.leave(); }
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var saved = cut(context, owner, cells[1], () -> ScalarTestCalls.callScalarTestTarget(target, args));
            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "Original target entered compiled code before first cut");
            assertSame(target.getRootNode(), saved.getSourceRoot()); assertTrue(cells[0].isEmpty());
            context.enter(); try { offer(program, cells[1], second); } finally { context.leave(); }
            var recut = cut(context, owner, cells[2], () -> Calls.target(resume, new Object[]{saved}));
            assertTrue(cells[0].isEmpty()); assertTrue(cells[1].isEmpty());
            context.enter();
            try {
                offer(program, cells[2], third);
                result(vector, Calls.target(resume, new Object[]{recut}));
                for (var cell : cells) assertTrue(cell.isEmpty(), "Each operand consumes its one offered value exactly once");
                assertThrows(RuntimeException.class, () -> Calls.target(resume, new Object[]{recut}));
                assertSame(target, program.entryTarget(PREFIX + (vector ? "vectorOperands" : "stringOperands")));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()));
                var state = TruffleLanguage.LanguageReference.create(Language.class).get(null).getHandoffState().get();
                assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }
}
