// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Thunk ownership is tested structurally; collection timing is irrelevant. */
class ThunkRetentionTest {
    private static final class ForceDriver extends RootNode {
        @Child private Force force;
        ForceDriver(Metrics metrics) { super(null); force = new Force(metrics); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object apply(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
    }
    @FunctionalInterface private interface Action { void run(Language language, CapturedFrame environment) throws Exception; }
    private void withRuntime(Action action) throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var layout = new FrameLayout();
                int slot = layout.bind("captured"); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
                FrameAccess.write(frame, slot, 3_000_000_000L);
                action.run(language, new CaptureLayout(language, new boolean[]{true}).capture(frame, new int[]{slot}));
            } finally { context.leave(); }
        }
    }
    private void assertReleased(Thunk thunk) {
        assertNull(thunk.getTarget(), "An updated thunk must release its body target");
        assertNull(thunk.getEnvironment(), "An updated thunk must release its captured environment");
    }
    @Test void successfulUpdatesReleaseSuspensionsAndRemainShared() throws Exception { withRuntime((language, environment) -> {
        int[] evaluations = {0};
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) {
                evaluations[0]++; assertSame(environment, frame.getArguments()[1]); return ((CapturedFrame) frame.getArguments()[1]).getLong(0) + 17L;
            }
        }.getCallTarget();
        var metrics = new Metrics(true); var driver = new ForceDriver(metrics); var thunk = new Thunk(target, environment);
        for (int i = 0; i < 4; i++) {
            assertEquals(3_000_000_017L, driver.apply(thunk)); assertEquals(2, thunk.getState());
            assertEquals(3_000_000_017L, thunk.getValue()); assertReleased(thunk);
        }
        assertEquals(1, evaluations[0]); assertEquals(1L, metrics.getThunkEvaluations()); assertEquals(3L, metrics.getThunkHits());
    }); }
    @Test void memoizedGuestAndRuntimeFailuresReleaseSuspensionsWithoutRepeatingEffects() throws Exception { withRuntime((language, environment) -> {
        var failures = list(new RuntimeFault("deliberate runtime failure"), new GuestException("retained lazy exception payload", new Node() {}));
        for (var failure : failures) {
            int[] evaluations = {0};
            var target = new RootNode(null) {
                @Override public Object execute(VirtualFrame frame) { evaluations[0]++; assertSame(environment, frame.getArguments()[1]); throw failure; }
            }.getCallTarget();
            var driver = new ForceDriver(new Metrics(true)); var thunk = new Thunk(target, environment);
            for (int attempt = 0; attempt < 3; attempt++) {
                var observed = assertThrows(failure.getClass(), () -> driver.apply(thunk));
                if (failure instanceof GuestException guest) {
                    assertSame(guest.getPayload(), ((GuestException) observed).getPayload());
                    if (attempt > 0) assertNotSame(failure, observed);
                } else assertSame(failure, observed);
                assertEquals(3, thunk.getState());
                if (failure instanceof GuestException) assertFalse(thunk.getValue() instanceof GuestException);
                else assertSame(failure, thunk.getValue());
                assertReleased(thunk);
            }
            assertEquals(1, evaluations[0]);
        }
    }); }
    @Test void unexpectedFailuresRetainTheSuspensionWithoutReplayingItsEffects() throws Exception { withRuntime((language, environment) -> {
        var interrupted = new IllegalStateException("host failure after an effect"); int[] evaluations = {0};
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { evaluations[0]++; assertSame(environment, frame.getArguments()[1]); throw interrupted; }
        }.getCallTarget();
        var driver = new ForceDriver(new Metrics(true)); var thunk = new Thunk(target, environment);
        assertSame(interrupted, assertThrows(IllegalStateException.class, () -> driver.apply(thunk)));
        assertEquals(4, thunk.getState()); assertNull(thunk.getValue()); assertSame(target, thunk.getTarget()); assertSame(environment, thunk.getEnvironment());
        var unsupported = assertThrows(RuntimeFault.class, () -> driver.apply(thunk));
        assertTrue(unsupported.getMessage().contains("no resumable continuation"));
        assertEquals(1, evaluations[0], "Escaping after an effect must not execute the body again");
    }); }
    @Test void aThunkReturningAnotherThunkFailsBeforeEitherCanPublishAnIndirection() throws Exception { withRuntime((language, environment) -> {
        int[] innerEvaluations = {0}, outerEvaluations = {0};
        var innerTarget = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { innerEvaluations[0]++; return 3_000_000_000L; } }.getCallTarget();
        var inner = new Thunk(innerTarget, environment);
        var outer = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { outerEvaluations[0]++; return inner; } }.getCallTarget(), environment);
        var driver = new ForceDriver(new Metrics(true)); var failure = assertThrows(RuntimeFault.class, () -> driver.apply(outer));
        assertEquals("Thunk target violated WHNF convention", failure.getMessage()); assertSame(failure, assertThrows(RuntimeFault.class, () -> driver.apply(outer)));
        assertEquals(1, outerEvaluations[0]); assertEquals(0, innerEvaluations[0], "Rejecting an invalid thunk result must not force it");
        assertEquals(3, outer.getState()); assertReleased(outer); assertEquals(0, inner.getState()); assertSame(innerTarget, inner.getTarget()); assertSame(environment, inner.getEnvironment());
    }); }
    @Test void nestedForcingThroughTheSameForceNodePublishesSeparateSharedAnswers() throws Exception { withRuntime((language, environment) -> {
        int[] innerEvaluations = {0}, outerEvaluations = {0}; var metrics = new Metrics(true); var driver = new ForceDriver(metrics);
        var inner = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { innerEvaluations[0]++; return 3_000_000_000L; } }.getCallTarget(), environment);
        var outer = new Thunk(new RootNode(null) { @Override public Object execute(VirtualFrame frame) { outerEvaluations[0]++; return (Long) driver.apply(inner) + 17L; } }.getCallTarget(), environment);
        for (int i = 0; i < 3; i++) { assertEquals(3_000_000_017L, driver.apply(outer)); assertEquals(3_000_000_000L, driver.apply(inner)); }
        assertEquals(1, innerEvaluations[0]); assertEquals(1, outerEvaluations[0]); assertEquals(2L, metrics.getThunkEvaluations()); assertEquals(5L, metrics.getThunkHits());
        for (var thunk : list(inner, outer)) { assertEquals(2, thunk.getState()); assertFalse(thunk.getValue() instanceof Thunk); assertReleased(thunk); }
    }); }
    private Map<String, Object> binding(String id, List<Object> expression) { return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "arity", 0, "expr", expression); }
    private List<Object> delayed(List<Object> expression) { return list("case", list("lit", "int", "0"), "ignored", list(list("default", null, list(), expression))); }
    private Object invokeEntry(ExecutableProgram program, Thunk thunk) { return Calls.target(program.hostEntryTarget(0), new Object[]{thunk, new Object[0]}); }
    private void assertUpdatedValueInstalled(String name, String backend, ExecutableProgram program, RootCallTarget target) throws Exception {
        if (!name.equals("value")) return;
        assertSame(target, program.entryTarget(name), backend + " updated value target identity");
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), backend + " updated value retains its installed code");
    }
    @Test void cafEntryTargetsRemainCompilableAfterValuesFunctionsAndFailuresAreMemoized() throws Exception { withRuntime((language, environment) -> {
        var data = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ThunkRetention", "instrument", true,
            "constructors", list(map("id", "Done", "name", "Done", "arity", 0, "tag", 1, "kind", "boxed", "strictFields", list(), "fieldLifted", list(), "fieldReps", list())),
            "bindings", list(binding("value", delayed(list("con", "Done", 0))),
                binding("function", delayed(list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false)), list("var", "x")))),
                binding("failure", list("var", "failure"))));
        for (String backend : list("ast", "bytecode")) {
            ExecutableProgram program = backend.equals("ast") ? new Program(language, data, false, false) : new BytecodeProgram(language, data, null, false);
            for (String name : list("value", "function", "failure")) {
                var thunk = (Thunk) program.entryValue(name); assertSame(thunk.getTarget(), program.entryTarget(name), backend + " unentered " + name);
                Object result = name.equals("failure") ? assertThrows(RuntimeFault.class, () -> invokeEntry(program, thunk)) : invokeEntry(program, thunk);
                assertReleased(thunk);
                RootCallTarget expectedTarget = result instanceof Closure closure ? closure.target : program.hostEntryTarget(0);
                assertSame(expectedTarget, program.entryTarget(name), backend + " updated " + name);
                // Preserve the original initialized-entry compilation path, including all eight calls.
                for (int i = 0; i < 8; i++) {
                    if (name.equals("failure")) assertSame(result, assertThrows(RuntimeFault.class, () -> invokeEntry(program, thunk)));
                    else assertSame(result, invokeEntry(program, thunk), backend + " retains the already evaluated answer");
                    if (result instanceof Closure) assertEquals(3_000_000_000L, Calls.target(program.hostEntryTarget(1), new Object[]{thunk, new Object[]{3_000_000_000L}}));
                }
                var entry = new EntryValue(program, name, result instanceof Closure ? 1 : 0, null, null, null, null, null, false, null);
                assertEquals(true, assertDoesNotThrow(() -> InteropLibrary.getUncached().invokeMember(entry, "compile"), backend + " updated " + name + " must install guest code"));
                assertUpdatedValueInstalled(name, backend, program, expectedTarget);
                if (name.equals("failure")) assertSame(result, assertThrows(RuntimeFault.class, () -> invokeEntry(program, thunk)));
                else assertSame(result, invokeEntry(program, thunk), backend + " retains the already evaluated answer");
                assertUpdatedValueInstalled(name, backend, program, expectedTarget);
                if (result instanceof Closure) assertEquals(Long.MIN_VALUE, Calls.target(program.hostEntryTarget(1), new Object[]{thunk, new Object[]{Long.MIN_VALUE}}));
            }
        }
    }); }
}
