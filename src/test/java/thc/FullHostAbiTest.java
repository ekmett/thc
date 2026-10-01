// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Independent public transport models; native GHC arithmetic is checked separately. */
class FullHostAbiTest {
    @org.junit.jupiter.api.Test void arrayCallableCanReturnAndForceAFunctionThunkWithoutAProgram() {
        for (boolean hosted : new boolean[]{false, true}) try (var context = context(hosted)) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var integer = thc.runtime.CoreRepresentations.parse(scalar("long", "IntRep"));
                var closure = thc.runtime.CoreRepresentations.parse(CLOSURE);
                var identity = new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { return frame.getArguments()[1]; }
                };
                identity.configureInputProofs(List.of(integer)); identity.configureScalarResult(integer);
                var function = new thc.runtime.Closure(null, 1, identity.getCallTarget());
                var forces = new java.util.concurrent.atomic.AtomicInteger();
                var delayed = new thc.runtime.Thunk(new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { forces.incrementAndGet(); return function; }
                }.getCallTarget(), null);
                var factory = new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { return delayed; }
                };
                factory.configureInputProofs(List.of(integer)); factory.configureScalarResult(closure);
                var view = context.asValue(HostReference.storage(Language.currentState(),
                    new Object[]{new thc.runtime.Closure(null, 1, factory.getCallTarget())}, false));
                var lazy = view.getArrayElement(0).execute(0L);
                assertEquals(0, forces.get()); assertTrue(lazy.canExecute());
                assertEquals(73L, lazy.execute(73L).asLong());
                assertEquals(91L, lazy.execute(91L).asLong()); assertEquals(1, forces.get());
            } finally { context.leave(); }
        }
    }

    @org.junit.jupiter.api.Test void storageViewsStopAtContextLifetimeAndDoNotPromoteAddresses() {
        var context = context();
        context.initialize("thc"); context.enter();
        final Value view;
        try {
            view = context.asValue(HostReference.storage(Language.currentState(), new byte[8], true));
            var address = context.asValue(new HostReference(Language.currentState(),
                thc.runtime.ManagedAddress.nullAddress(),
                thc.runtime.CoreRepresentations.parse(scalar("address", "AddrRep")), null));
            assertFalse(address.hasBufferElements());
            assertTrue(view.isBufferWritable());
        } finally { context.leave(); }
        context.close();
        assertThrows(IllegalStateException.class, view::getBufferSize);
    }
    private static final Map<String,Object> CLOSURE = scalar("closure", "BoxedRep (Just Lifted)");
    private static Map<String,Object> scalar(String kind, String rep) {
        return map("kind", kind, "primReps", list(rep), "evaluated", true);
    }

    private static Map<String,Object> identity(Map<String,Object> proof) {
        var parameter = map("id", "x", "name", "x", "rep", proof, "lifted", false);
        var body = list("lam", list(parameter), list("var", "x", map("rep", proof)), map("rep", CLOSURE, "resultRep", proof));
        var binding = map("id", "host:Host.identity", "name", "identity", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", body);
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Host", "bindings", list(binding), "constructors", list());
        return module;
    }

    private static Context context() {
        return context(false);
    }
    private static Context context(boolean hosted) {
        var builder = Context.newBuilder("thc").allowExperimentalOptions(true).allowHostAccess(HostAccess.ALL)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw");
        if (hosted) builder.allowCreateThread(true).option("thc.ThreadHosting", "loom");
        return builder.build();
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void directEntryConstructionUsesTheRetainedSignatureWithoutGuessing(String backend) {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 2 Int64ElemRep"), "vector", map("lanes", 2, "element", "Int64ElemRep"));
                for (var proof : list(scalar("float", "FloatRep"), vector)) {
                    var module = identity(proof);
                    thc.runtime.ExecutableProgram program = backend.equals("ast") ? new thc.runtime.Program(language, module) : new thc.runtime.BytecodeProgram(language, module);
                    var entry = context.asValue(new EntryValue(program, "host:Host.identity", 1));
                    if (proof == vector) {
                        var raw = LongVector.fromArray(LongVector.SPECIES_128, new long[]{Long.MIN_VALUE, 7L}, 0);
                        assertSame(raw, entry.execute(raw).asHostObject());
                    } else assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits(entry.execute(-0f).asFloat()));
                }
            } finally { context.leave(); }
        }
    }
}
