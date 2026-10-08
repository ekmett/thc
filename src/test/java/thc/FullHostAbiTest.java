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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @org.junit.jupiter.api.AfterEach void releaseIdleFixtureMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    @org.junit.jupiter.api.Test void scalarThunkHandoffIsLazyUntilNumericConversionAndQueriesNeverForce() throws Exception {
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
                assertFalse(interop.isNumber(reference));
                assertFalse(interop.fitsInByte(reference)); assertFalse(interop.fitsInShort(reference));
                assertFalse(interop.fitsInInt(reference)); assertFalse(interop.fitsInLong(reference));
                assertFalse(interop.fitsInBigInteger(reference)); assertFalse(interop.fitsInFloat(reference));
                assertFalse(interop.fitsInDouble(reference));
                var rawProof = thc.runtime.CoreRepresentations.parse(scalar("object", "BoxedRep (Just Unlifted)"))
                    .withHostCarrier(thc.runtime.CoreRepresentation.HostCarrier.OBJECT);
                var raw = new HostReference(Language.currentState(), delayed, rawProof, null);
                assertFalse(interop.isExecutable(raw));
                assertThrows(UnsupportedMessageException.class, () -> interop.asLong(raw));
                assertFalse(interop.hasArrayElements(reference));
                interop.toDisplayString(reference, false);
                assertEquals(0, forces.get());
                var lazy = context.asValue(reference);
                assertTrue(lazy.canExecute()); assertFalse(lazy.isNumber());
                assertEquals(9_007_199_254_740_993L, lazy.asLong());
                assertTrue(lazy.isNumber()); assertFalse(lazy.fitsInDouble());
                assertThrows(ClassCastException.class, lazy::asDouble);
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
                    assertEquals(state, key.getState());
                }
                assertEquals(0, forces.get());
                var delayed = new thc.runtime.Thunk(target, null);
                var reference = new HostReference(Language.currentState(), delayed, proof, null);
                assertSame(failure, assertThrows(thc.runtime.RuntimeFault.class, () -> interop.asLong(reference)));
                assertSame(failure, assertThrows(thc.runtime.RuntimeFault.class, () -> interop.execute(reference)));
                assertSame(failure, assertThrows(thc.runtime.RuntimeFault.class, () -> interop.asDouble(reference)));
                assertEquals(1, forces.get()); assertFalse(interop.isNumber(reference));
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
                assertThrows(ClassCastException.class, lazy::asLong);
                assertEquals(1, forces.get()); assertFalse(lazy.isNumber());
                assertEquals(73L, lazy.execute(73L).asLong());
                assertEquals(91L, lazy.execute(91L).asLong()); assertEquals(1, forces.get());
                var applications = new java.util.concurrent.atomic.AtomicInteger();
                var zeroArgument = new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { applications.incrementAndGet(); return 37L; }
                };
                zeroArgument.configureInputProofs(List.of()); zeroArgument.configureScalarResult(integer);
                var zeroFunction = new thc.runtime.Closure(null, 0, zeroArgument.getCallTarget());
                var zeroThunk = delayed(language, () -> zeroFunction);
                var zeroView = context.asValue(new HostReference(Language.currentState(), zeroThunk, closure, null));
                assertThrows(ClassCastException.class, zeroView::asLong);
                assertThrows(ClassCastException.class, zeroView::asDouble);
                assertEquals(0, applications.get(), "numeric demand must not apply even a zero-argument closure");
                assertEquals(37L, zeroView.execute().asLong()); assertEquals(1, applications.get());
            } finally { context.leave(); }
        }
    }

    @org.junit.jupiter.api.Test void storageViewsStopAtContextLifetimeAndDoNotPromoteAddresses() {
        var context = context();
        context.initialize("thc"); context.enter();
        final Value view, lazy;
        var forces = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
            lazy = context.asValue(new HostReference(Language.currentState(),
                delayed(language, () -> { forces.incrementAndGet(); return 73L; }),
                thc.runtime.CoreRepresentations.parse(scalar("object", "BoxedRep (Just Lifted)")), null));
            view = context.asValue(HostReference.storage(Language.currentState(), new byte[8], true));
            var address = context.asValue(new HostReference(Language.currentState(),
                thc.runtime.ManagedAddress.nullAddress(),
                thc.runtime.CoreRepresentations.parse(scalar("address", "AddrRep")), null));
            assertFalse(address.hasBufferElements());
            assertTrue(view.isBufferWritable());
        } finally { context.leave(); }
        context.close();
        assertThrows(IllegalStateException.class, view::getBufferSize);
        assertThrows(IllegalStateException.class, lazy::asLong); assertEquals(0, forces.get());
    }

    @ParameterizedTest @ValueSource(strings = {"byte", "short", "int", "long", "bigInteger", "float", "double"})
    void everyNumericConversionDemandsWhnfAndPreservesExactScalars(String conversion) {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                String constructor = switch (conversion) { case "bigInteger" -> "W#"; case "float" -> "F#"; case "double" -> "D#"; default -> "I#"; };
                String rep = switch (conversion) { case "bigInteger" -> "WordRep"; case "float" -> "FloatRep"; case "double" -> "DoubleRep"; default -> "IntRep"; };
                Object payload = switch (conversion) { case "bigInteger" -> -1L; case "float" -> -0f; case "double" -> Double.POSITIVE_INFINITY; default -> 42L; };
                var answer = new thc.runtime.DataLayout(language, "ghc-internal:GHC.Internal.Types." + constructor, constructor, new String[]{rep}).create(new Object[]{payload});
                var forces = new java.util.concurrent.atomic.AtomicInteger();
                var thunk = delayed(language, () -> { forces.incrementAndGet(); return answer; });
                var lazy = context.asValue(new HostReference(Language.currentState(), thunk,
                    thc.runtime.CoreRepresentations.parse(scalar("object", "BoxedRep (Just Lifted)")), null));
                java.util.function.Function<Value, Object> convert = switch (conversion) {
                    case "byte" -> Value::asByte; case "short" -> Value::asShort; case "int" -> Value::asInt;
                    case "long" -> Value::asLong; case "bigInteger" -> Value::asBigInteger;
                    case "float" -> Value::asFloat; case "double" -> Value::asDouble;
                    default -> throw new AssertionError(conversion);
                };
                Object expected = switch (conversion) {
                    case "byte" -> (byte) 42; case "short" -> (short) 42; case "int" -> 42; case "long" -> 42L;
                    case "bigInteger" -> java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE);
                    case "float" -> -0f; case "double" -> Double.POSITIVE_INFINITY;
                    default -> throw new AssertionError(conversion);
                };
                assertFalse(lazy.isNumber()); assertEquals(0, forces.get());
                assertEquals(expected, convert.apply(lazy)); assertTrue(lazy.isNumber());
                assertEquals(expected, convert.apply(lazy)); assertEquals(1, forces.get());
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void publicNumericDemandSharesCoreEffectsAndGuestFailures(String backend) throws Exception {
        var integer = scalar("long", "IntRep");
        var data = with(scalar("object", "BoxedRep (Just Lifted)"), "evaluated", false);
        var state = map("kind", "void", "primReps", list(), "evaluated", true);
        var result = map("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true,
            "primReps", list("IntRep", "BoxedRep (Just Lifted)"), "components", list(integer, data));
        String constructor = "ghc-internal:GHC.Internal.Types.I#";
        var box = list("app", list("con", constructor, 1, map()),
            list(list("lit", "int", "73", map("rep", integer))), list(false), true, true, map("rep", with(data, "evaluated", true)));
        var bindings = new ArrayList<Map<String,Object>>();
        for (String name : List.of("value", "failure")) {
            var body = name.equals("value") ? box : list("app", list("prim", "raise#", map()), list(box), list(true), false, false, map("rep", data));
            var trace = list("app", list("prim", "traceEvent#", map()),
                list(list("lit", "string-bytes", HexFormat.of().formatHex(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), map()), list("void", map("rep", state))),
                list(false, false), false, false, map("rep", state));
            var traced = list("case", trace, "traced", list(list("default", null, list(), body, map("binders", list()))),
                map("rep", data, "binder", map("id", "traced", "lifted", false, "rep", state)));
            bindings.add(map("id", "host:Host." + name, "name", name, "arity", 0, "lifted", true, "rep", data, "expr", traced));
            var pair = list("app", list("con", "host:Host.Pair", 2, map()),
                list(list("var", "x", map("rep", integer)), list("var", "host:Host." + name, map("rep", data))),
                list(false, true), false, false, map("rep", result));
            var getter = list("lam", list(map("id", "x", "name", "x", "rep", integer, "lifted", false)),
                pair, map("rep", CLOSURE, "resultRep", result));
            bindings.add(map("id", "host:Host." + name + "View", "name", name + "View", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", getter));
        }
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Host", "boundary", "optimized-Core-after-Tidy-before-CorePrep", "bindings", bindings,
            "constructors", list(map("id", "host:Host.Pair", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1,
                "strictFields", list(false, false), "fieldLifted", list(false, true),
                "fieldReps", list(list("IntRep"), list("BoxedRep (Just Lifted)")), "fieldTypes", list(integer, data)),
                map("id", constructor, "name", "I#", "kind", "boxed", "arity", 1, "tag", 1,
                "fieldReps", list(list("IntRep")), "fieldTypes", list(integer), "strictFields", list(false), "fieldLifted", list(false))));
        var path = CoreCbdFixtures.write(directory.resolve("Host.cbd"), module);
        var output = new java.io.ByteArrayOutputStream();
        try (var context = Context.newBuilder("thc").err(output).build()) {
            var program = Main.loadProgram(context, List.of(path.toString()), false, backend, false, false);
            context.enter();
            try { Language.currentState().getRuntimeTrace().control(500, 1); }
            finally { context.leave(); }
            var success = Main.loadEntry(program, "host:Host.valueView");
            var failure = Main.loadEntry(program, "host:Host.failureView").execute(0).getArrayElement(1);
            var first = success.execute(0).getArrayElement(1); var second = success.execute(0).getArrayElement(1);
            assertFalse(first.isNumber()); assertFalse(second.fitsInLong()); assertFalse(failure.isNumber());
            assertEquals("", output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(73L, first.asLong()); assertEquals(73L, second.asLong());
            for (int attempt = 0; attempt < 2; attempt++) {
                var thrown = assertThrows(PolyglotException.class, failure::asLong);
                assertTrue(thrown.isGuestException()); assertFalse(thrown.isHostException());
                assertEquals("Haskell exception (payload retained lazily)", thrown.getMessage());
            }
            assertTrue(assertThrows(PolyglotException.class, failure::execute).isGuestException());
            assertEquals(73L, success.execute(0).getArrayElement(1).asLong());
            assertEquals("[thc trace event] value\n[thc trace event] failure\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static thc.runtime.Thunk delayed(Language language, java.util.function.Supplier<Object> body) {
        return new thc.runtime.Thunk(new thc.runtime.GuestRoot(language, new thc.runtime.FrameLayout().build()) {
            @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
            @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { return body.get(); }
        }.getCallTarget(), null);
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
