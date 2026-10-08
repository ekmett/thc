// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.EntryValue;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class ExecutableLifecycleTest {
    private final CoreRepresentation state = new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null);
    private final CoreRepresentation unit = new CoreRepresentation(CoreKind.DATA, true, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
    private final CoreRepresentation result = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("BoxedRep (Just Lifted)"), List.of(state, unit), null, null, null, null);
    private static class ActionRoot extends GuestRoot {
        private final TupleShape shape;
        private final DataValue unitValue;
        private final Runnable effect;
        ActionRoot(Language language, TupleShape shape, DataValue unitValue, Runnable effect) {
            super(language, new FrameLayout().build());
            this.shape = shape; this.unitValue = unitValue; this.effect = effect;
            configureInputProofs(List.of(shape.getProof().getComponents().getFirst()));
            configureTupleResult(shape);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            TupleResults.requireVoidCarrier(frame.getArguments()[1]);
            effect.run();
            var storage = shape.getLayout().create();
            shape.getLayout().setObject(storage, 0, unitValue);
            return storage;
        }
    }
    private record Actions(RootCallTarget main, RootCallTarget shutdown) implements ExecutableProgram {
        @Override public boolean getAsynchronousExceptions() { return true; }
        @Override public RootCallTarget hostEntryTarget(int arity) { return main; }
        @Override public Object entryValue(String name) { return new Closure(null, 1, entryTarget(name)); }
        @Override public RootCallTarget entryTarget(String name) {
            return switch (name) { case "main" -> main; case "shutdown" -> shutdown; default -> throw new IllegalStateException(name); };
        }
        @Override public DataLayout constructorLayout(String id) { throw new IllegalStateException("Unexpected constructor layout request in lifecycle test: " + id); }
        @Override public Map<String, Object> diagnostics() { return Map.of(); }
    }
    @Test void successfulExecutableSharesStateAndRunsShutdownOnce() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var shape = new TupleShape(result, language);
                var boxedUnit = new DataLayout(language, "ghc-internal:GHC.Internal.Tuple.()", "()", new String[0], new Class<?>[0]).create(new Object[0]);
                int[] state = {0};
                var events = new ArrayList<String>();
                var main = new ActionRoot(language, shape, boxedUnit, () -> { events.add("main"); state[0]++; }).getCallTarget();
                var shutdown = new ActionRoot(language, shape, boxedUnit, () -> { events.add("shutdown:" + state[0]); state[0]++; }).getCallTarget();
                var action = context.asValue(new EntryValue(new Actions(main, shutdown), "main", 0, null, result, language, "shutdown", result, false, null, null));
                assertTrue(action.invokeMember("runIO").asBoolean());
                assertEquals(List.of("main", "shutdown:1"), events);
                assertEquals(2, state[0]);
                var second = assertThrows(PolyglotException.class, () -> action.invokeMember("runIO"));
                assertTrue(second.getMessage().contains("already started"));
                assertEquals(2, state[0]);
            } finally { context.leave(); }
        }
    }
    @Test void failedMainDoesNotRunShutdownOrRetryEffects() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var shape = new TupleShape(result, language);
                var boxedUnit = new DataLayout(language, "ghc-internal:GHC.Internal.Tuple.()", "()", new String[0], new Class<?>[0]).create(new Object[0]);
                int[] mainCalls = {0}, shutdownCalls = {0};
                var main = new ActionRoot(language, shape, boxedUnit, () -> { mainCalls[0]++; throw new RuntimeFault("main failed"); }).getCallTarget();
                var shutdown = new ActionRoot(language, shape, boxedUnit, () -> shutdownCalls[0]++).getCallTarget();
                var action = context.asValue(new EntryValue(new Actions(main, shutdown), "main", 0, null, result, language, "shutdown", result, false, null, null));
                assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO")).getMessage().contains("main failed"));
                assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO")).getMessage().contains("already started"));
                assertEquals(1, mainCalls[0]); assertEquals(0, shutdownCalls[0]);
            } finally { context.leave(); }
        }
    }
    @Test void failedShutdownDoesNotRepeatTheCompletedMain() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var shape = new TupleShape(result, language);
                var boxedUnit = new DataLayout(language, "ghc-internal:GHC.Internal.Tuple.()", "()", new String[0], new Class<?>[0]).create(new Object[0]);
                int[] mainCalls = {0}, shutdownCalls = {0};
                var main = new ActionRoot(language, shape, boxedUnit, () -> mainCalls[0]++).getCallTarget();
                var shutdown = new ActionRoot(language, shape, boxedUnit, () -> { shutdownCalls[0]++; throw new RuntimeFault("shutdown failed"); }).getCallTarget();
                var action = context.asValue(new EntryValue(new Actions(main, shutdown), "main", 0, null, result, language, "shutdown", result, false, null, null));
                assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO")).getMessage().contains("shutdown failed"));
                assertTrue(assertThrows(PolyglotException.class, () -> action.invokeMember("runIO")).getMessage().contains("already started"));
                assertEquals(1, mainCalls[0]); assertEquals(1, shutdownCalls[0]);
            } finally { context.leave(); }
        }
    }
}
