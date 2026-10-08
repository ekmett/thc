// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Independent public transport models; native GHC arithmetic is checked separately. */
class FullHostAbiTest {
    @org.junit.jupiter.api.Test void scalarThunkHandoffIsLazyUntilInvocationAndNumericQueriesNeverForce() throws Exception {
        for (boolean hosted : new boolean[]{false, true}) try (var context = context(hosted)) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = new thc.runtime.DataLayout(language, "ghc-internal:GHC.Internal.Types.I#", "I#", new String[]{"IntRep"});
                var answer = layout.createLong(9_007_199_254_740_993L);
                var forces = new java.util.concurrent.atomic.AtomicInteger();
                var delayed = new thc.runtime.Thunk(new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { forces.incrementAndGet(); return answer; }
                }.getCallTarget(), null);
                var proof = thc.runtime.CoreRepresentations.parse(scalar("object", "BoxedRep (Just Lifted)"));
                var reference = new HostReference(Language.currentState(), delayed, proof, null);
                var interop = InteropLibrary.getUncached();
                assertTrue(interop.isExecutable(reference));
                assertFalse(interop.isNumber(reference)); assertFalse(interop.fitsInLong(reference));
                assertThrows(UnsupportedMessageException.class, () -> interop.asLong(reference));
                assertFalse(interop.hasArrayElements(reference));
                interop.toDisplayString(reference, false);
                assertEquals(0, forces.get());
                var lazy = context.asValue(reference);
                assertTrue(lazy.canExecute()); assertFalse(lazy.isNumber());
                var demanded = lazy.execute();
                assertTrue(demanded.isNumber()); assertEquals(9_007_199_254_740_993L, demanded.asLong());
                assertFalse(demanded.fitsInDouble());
                assertEquals(9_007_199_254_740_993L, lazy.asLong());
                assertEquals(9_007_199_254_740_993L, lazy.execute().asLong());
                assertSame(answer, delayed.getValue()); assertEquals(1, forces.get());
            } finally { context.leave(); }
        }
    }

    @org.junit.jupiter.api.Test void terminalNumericViewsUseNominalBuiltinConstructorsAndExactConversions() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var proof = thc.runtime.CoreRepresentations.parse(scalar("object", "BoxedRep (Just Lifted)"));
                var interop = InteropLibrary.getUncached();
                Object[][] cases = {
                    {"GHC.Internal.Int.I8#", "I8#", "Int8Rep", -128, (byte) -128},
                    {"GHC.Internal.Int.I16#", "I16#", "Int16Rep", -32768, (short) -32768},
                    {"GHC.Internal.Int.I32#", "I32#", "Int32Rep", Integer.MIN_VALUE, Integer.MIN_VALUE},
                    {"GHC.Internal.Int.I64#", "I64#", "Int64Rep", Long.MIN_VALUE, Long.MIN_VALUE},
                    {"GHC.Internal.Word.W8#", "W8#", "Word8Rep", 255, 255},
                    {"GHC.Internal.Word.W16#", "W16#", "Word16Rep", 65535, 65535},
                    {"GHC.Internal.Word.W32#", "W32#", "Word32Rep", -1, 4_294_967_295L},
                    {"GHC.Internal.Word.W64#", "W64#", "Word64Rep", -1L, new thc.runtime.UnsignedWord64(-1L)},
                    {"GHC.Internal.Types.I#", "I#", "IntRep", Long.MAX_VALUE, Long.MAX_VALUE},
                    {"GHC.Internal.Types.W#", "W#", "WordRep", -1L, new thc.runtime.UnsignedWord64(-1L)},
                    {"GHC.Internal.Types.F#", "F#", "FloatRep", -0f, -0f},
                    {"GHC.Internal.Types.D#", "D#", "DoubleRep", Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}
                };
                for (var item : cases) {
                    var layout = new thc.runtime.DataLayout(language, "ghc-internal:" + item[0], (String) item[1], new String[]{(String) item[2]});
                    var data = layout.create(new Object[]{item[3]});
                    var reference = new HostReference(Language.currentState(), data, proof, null);
                    Object expected = item[4];
                    assertTrue(interop.isNumber(reference), item[0].toString());
                    assertEquals(interop.fitsInByte(expected), interop.fitsInByte(reference));
                    assertEquals(interop.fitsInShort(expected), interop.fitsInShort(reference));
                    assertEquals(interop.fitsInInt(expected), interop.fitsInInt(reference));
                    assertEquals(interop.fitsInLong(expected), interop.fitsInLong(reference));
                    assertEquals(interop.fitsInBigInteger(expected), interop.fitsInBigInteger(reference));
                    assertEquals(interop.fitsInFloat(expected), interop.fitsInFloat(reference));
                    assertEquals(interop.fitsInDouble(expected), interop.fitsInDouble(reference));
                    if (interop.fitsInByte(expected)) assertEquals(interop.asByte(expected), interop.asByte(reference));
                    else assertThrows(UnsupportedMessageException.class, () -> interop.asByte(reference));
                    if (interop.fitsInShort(expected)) assertEquals(interop.asShort(expected), interop.asShort(reference));
                    else assertThrows(UnsupportedMessageException.class, () -> interop.asShort(reference));
                    if (interop.fitsInInt(expected)) assertEquals(interop.asInt(expected), interop.asInt(reference));
                    else assertThrows(UnsupportedMessageException.class, () -> interop.asInt(reference));
                    if (interop.fitsInLong(expected)) assertEquals(interop.asLong(expected), interop.asLong(reference));
                    else assertThrows(UnsupportedMessageException.class, () -> interop.asLong(reference));
                    if (interop.fitsInBigInteger(expected)) assertEquals(interop.asBigInteger(expected), interop.asBigInteger(reference));
                    if (interop.fitsInFloat(expected)) assertEquals(Float.floatToRawIntBits(interop.asFloat(expected)), Float.floatToRawIntBits(interop.asFloat(reference)));
                    if (interop.fitsInDouble(expected)) assertEquals(Double.doubleToRawLongBits(interop.asDouble(expected)), Double.doubleToRawLongBits(interop.asDouble(reference)));
                }
                var ordinary = new thc.runtime.DataLayout(language, "user:GHC.Internal.Types.I#", "I#", new String[]{"IntRep"}).createLong(73);
                var reference = new HostReference(Language.currentState(), ordinary, proof, null);
                assertFalse(interop.isNumber(reference));
                assertThrows(UnsupportedMessageException.class, () -> interop.asLong(reference));
            } finally { context.leave(); }
        }
    }

    @org.junit.jupiter.api.Test void lazyNumericObservationKeepsOpaqueStatesAndMemoizedFailures() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var proof = thc.runtime.CoreRepresentations.parse(scalar("object", "BoxedRep (Just Lifted)"));
                var failure = new thc.runtime.RuntimeFault("failure after a lazy effect");
                var forces = new java.util.concurrent.atomic.AtomicInteger();
                var target = new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { forces.incrementAndGet(); throw failure; }
                }.getCallTarget();
                var interop = InteropLibrary.getUncached();
                for (int state : new int[]{0, 1, 3, 4, 5}) {
                    var key = new thc.runtime.Thunk(target, null);
                    key.setValue(73L); key.setState(state);
                    var reference = new HostReference(Language.currentState(), key, proof, null);
                    assertFalse(interop.isNumber(reference));
                    assertFalse(interop.fitsInLong(reference));
                    assertThrows(UnsupportedMessageException.class, () -> interop.asLong(reference));
                    assertEquals(state, key.getState());
                }
                assertEquals(0, forces.get());
                var delayed = new thc.runtime.Thunk(target, null);
                var reference = new HostReference(Language.currentState(), delayed, proof, null);
                for (int attempt = 0; attempt < 2; attempt++)
                    assertSame(failure, assertThrows(thc.runtime.RuntimeFault.class, () -> interop.execute(reference)));
                assertEquals(1, forces.get()); assertFalse(interop.isNumber(reference));
                assertThrows(UnsupportedMessageException.class, () -> interop.asLong(reference));
            } finally { context.leave(); }
        }
    }

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
