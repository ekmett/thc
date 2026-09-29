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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine exported callbacks suspend in takeMVar#, including valid non-ANF Core operands. */
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
        var source = (Map<String, Object>) Json.parse(Files.readString(path.resolve("core/IntrinsicOperands.json")));
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
