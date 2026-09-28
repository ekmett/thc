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
    private static final Map<String,Object> CLOSURE = scalar("closure", "BoxedRep (Just Lifted)");
    private static Map<String,Object> scalar(String kind, String rep) {
        return map("kind", kind, "primReps", list(rep), "evaluated", true);
    }
    private static Map<String,Object> tuple(Map<String,Object>... components) {
        var reps = new ArrayList<Object>();
        for (var component : components) reps.addAll((List<?>) component.get("primReps"));
        return map("kind", "unknown", "aggregate", "unboxed-tuple", "components", Arrays.asList(components),
            "primReps", reps, "evaluated", true);
    }
    private static String identity(String backend, Map<String,Object> proof) {
        var parameter = map("id", "x", "name", "x", "rep", proof, "lifted", false);
        var body = list("lam", list(parameter), list("var", "x", map("rep", proof)), map("rep", CLOSURE, "resultRep", proof));
        var binding = map("id", "host:Host.identity", "name", "identity", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", body);
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Host", "bindings", list(binding), "constructors", list());
        return Json.stringify(map("backend", backend, "entry", "host:Host.identity", "asyncExceptions", false, "modules", list(module)));
    }
    private static String unary(String backend, Map<String,Object> input, Map<String,Object> result, List<Object> expression) {
        var body = list("lam", list(map("id", "x", "name", "x", "rep", input, "lifted", false)), expression,
            map("rep", CLOSURE, "resultRep", result));
        var binding = map("id", "host:Host.unary", "name", "unary", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", body);
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Host", "bindings", list(binding), "constructors", list());
        return Json.stringify(map("backend", backend, "entry", "host:Host.unary", "asyncExceptions", false, "modules", list(module)));
    }
    private static void released(Context context) {
        context.enter();
        try {
            var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var state = language.getHandoffState().get();
            assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
            assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
        } finally { context.leave(); }
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
    private static void compiled(Value entry) {
        assertTrue(entry.invokeMember("compile").asBoolean());
    }
    private static void firstEntry(Value entry) {
        var diagnostic = (Map<?,?>) Json.parse(entry.getMember("diagnostics").asString());
        assertEquals(1L, ((Number) diagnostic.get("compiledEntries")).longValue());
        var code = (Map<?,?>) diagnostic.get("explicitCompilation");
        assertEquals(true, code.get("sameTargets")); assertEquals(true, code.get("validLastTier"));
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void nullaryLambdaRunsWhileScalarValueEntryRemainsAValue(String backend) {
        var proof = scalar("double", "DoubleRep");
        for (boolean lambda : new boolean[]{true, false}) try (var context = context()) {
            var literal = list("lit", "double", "2.5", map("rep", proof));
            var expression = lambda ? list("lam", list(), literal, map("rep", CLOSURE, "resultRep", proof)) : literal;
            var binding = map("id", "host:Host.zero", "name", "zero", "arity", 0, "lifted", lambda,
                "rep", lambda ? CLOSURE : proof, "expr", expression);
            var module = map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Host",
                "bindings", list(binding), "constructors", list());
            var entry = context.eval("thc", Json.stringify(map("backend", backend, "entry", "host:Host.zero",
                "asyncExceptions", false, "modules", list(module))));
            compiled(entry);
            assertEquals(2.5d, entry.execute().asDouble());
            if (lambda) firstEntry(entry);
            assertEquals(2.5d, entry.execute().asDouble());
            released(context);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void exactBigIntegerInputsHonorSignedAndNarrowRanges(String backend) {
        try (var context = context()) {
            for (int bits : new int[]{8, 16, 32, 64}) {
                var signed = context.eval("thc", identity(backend, scalar("long", "Int" + bits + "Rep")));
                var maximum = java.math.BigInteger.ONE.shiftLeft(bits - 1).subtract(java.math.BigInteger.ONE);
                var minimum = java.math.BigInteger.ONE.shiftLeft(bits - 1).negate();
                compiled(signed);
                assertEquals(minimum, signed.execute(minimum).asBigInteger()); firstEntry(signed);
                assertEquals(maximum, signed.execute(maximum).asBigInteger());
                assertThrows(PolyglotException.class, () -> signed.execute(minimum.subtract(java.math.BigInteger.ONE)));
                assertThrows(PolyglotException.class, () -> signed.execute(maximum.add(java.math.BigInteger.ONE)));
                var unsigned = context.eval("thc", identity(backend, scalar("long", "Word" + bits + "Rep")));
                var wordMaximum = java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE);
                compiled(unsigned);
                assertEquals(wordMaximum, unsigned.execute(wordMaximum).asBigInteger()); firstEntry(unsigned);
                assertThrows(PolyglotException.class, () -> unsigned.execute(java.math.BigInteger.ONE.negate()));
                assertThrows(PolyglotException.class, () -> unsigned.execute(wordMaximum.add(java.math.BigInteger.ONE)));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void floatingInputsPreserveNegativeZeroAndRejectLossyConversionBeforeGuestEntry(String backend) {
        try (var context = context()) {
            var floats = context.eval("thc", identity(backend, scalar("float", "FloatRep")));
            compiled(floats);
            assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(floats.execute(-0.0f).asFloat()));
            firstEntry(floats);
            assertThrows(PolyglotException.class, () -> floats.execute(0.1d));
            var doubles = context.eval("thc", identity(backend, scalar("double", "DoubleRep")));
            compiled(doubles);
            assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(doubles.execute(-0.0d).asDouble()));
            firstEntry(doubles);
            assertTrue(Double.isNaN(doubles.execute(Double.NaN).asDouble()));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void logicalNestedTuplesRetainEmptyFieldsAndExactNarrowCarriers(String backend) {
        var proof = tuple(tuple(), scalar("long", "Word32Rep"), tuple(scalar("double", "DoubleRep"), scalar("long", "Int16Rep")));
        try (var context = context()) {
            var entry = context.eval("thc", identity(backend, proof)); compiled(entry);
            var value = entry.execute((Object) new Object[]{new Object[0], 4294967295L, new Object[]{-0.0d, -32768L}});
            assertEquals(3, value.getArraySize()); assertEquals(0, value.getArrayElement(0).getArraySize());
            assertEquals(4294967295L, value.getArrayElement(1).asLong());
            assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(value.getArrayElement(2).getArrayElement(0).asDouble()));
            assertEquals(-32768L, value.getArrayElement(2).getArrayElement(1).asLong()); firstEntry(entry);
            assertThrows(PolyglotException.class, () -> entry.execute((Object) new Object[]{new Object[0], -1L, new Object[]{1d, 2L}}));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void rawJdkVectorsRetainIdentitySpeciesAndBits(String backend) {
        var proof = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 2 Int64ElemRep"),
            "vector", map("lanes", 2, "element", "Int64ElemRep"));
        var original = LongVector.fromArray(LongVector.SPECIES_128, new long[]{Long.MIN_VALUE, 0x123456789abcdefL}, 0);
        try (var context = context()) {
            var entry = context.eval("thc", identity(backend, proof)); compiled(entry);
            assertSame(original, entry.execute(original).asHostObject()); firstEntry(entry);
            assertThrows(PolyglotException.class, () -> entry.execute(LongVector.zero(LongVector.SPECIES_256)));
            assertThrows(PolyglotException.class, () -> entry.execute(IntVector.zero(IntVector.SPECIES_128)));
            assertThrows(PolyglotException.class, () -> entry.execute((Object) new long[]{1, 2}));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void sumsExposeTheirTagAndOnlyTheSelectedPayload(String backend) {
        var proof = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum", "primReps", list("WordRep", "WordRep", "DoubleRep"),
            "alternatives", list(scalar("long", "Int8Rep"), scalar("double", "DoubleRep")), "tagSlot", 0, "alternativeSlots", list(list(1), list(2)));
        try (var context = context()) {
            var entry = context.eval("thc", identity(backend, proof)); compiled(entry);
            var first = entry.execute((Object) new Object[]{1L, -128L});
            assertEquals(2, first.getArraySize()); assertEquals(1L, first.getArrayElement(0).asLong());
            assertEquals(-128L, first.getArrayElement(1).asLong()); firstEntry(entry);
            var second = entry.execute((Object) new Object[]{2L, Math.PI});
            assertEquals(2L, second.getArrayElement(0).asLong()); assertEquals(Math.PI, second.getArrayElement(1).asDouble());
            assertThrows(PolyglotException.class, () -> entry.execute((Object) new Object[]{0L, 1L}));
            assertThrows(PolyglotException.class, () -> entry.execute((Object) new Object[]{1L, 128L}));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void returnedFunctionsKeepTheirScalarSignatureAndReferenceIdentity(String backend) {
        var doubleProof = scalar("double", "DoubleRep");
        var inner = list("lam", list(map("id", "y", "name", "y", "rep", doubleProof, "lifted", false)),
            list("var", "y", map("rep", doubleProof)), map("rep", CLOSURE, "resultRep", doubleProof));
        var outer = list("lam", list(map("id", "ignored", "name", "ignored", "rep", scalar("long", "IntRep"), "lifted", false)),
            inner, map("rep", CLOSURE, "resultRep", CLOSURE));
        var binding = map("id", "host:Host.function", "name", "function", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", outer);
        var module = map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Host", "bindings", list(binding), "constructors", list());
        var source = Json.stringify(map("backend", backend, "entry", "host:Host.function", "asyncExceptions", false, "modules", list(module)));
        for (boolean hosted : new boolean[]{false, true}) try (var context = context(hosted); var other = context()) {
            var entry = context.eval("thc", source);
            var function = entry.execute(0L);
            assertTrue(function.canExecute());
            assertEquals(Math.PI, function.execute(Math.PI).asDouble());
            assertEquals(Double.doubleToRawLongBits(-0d), Double.doubleToRawLongBits(function.execute(-0d).asDouble()));
            var identity = context.eval("thc", identity(backend, CLOSURE));
            var same = identity.execute(function);
            assertEquals(function, same);
            assertEquals(1.25, same.execute(1.25).asDouble());
            var foreign = other.eval("thc", identity(backend, CLOSURE));
            assertThrows(RuntimeException.class, () -> foreign.execute(function));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void signedUnsignedAndManagedNullAddressBoundaries(String backend) {
        try (var context = context()) {
            for (int bits : new int[]{8, 16, 32, 64}) {
                var signed = context.eval("thc", identity(backend, scalar("long", "Int" + bits + "Rep")));
                compiled(signed);
                long minimum = bits == 64 ? Long.MIN_VALUE : -(1L << (bits - 1));
                long maximum = bits == 64 ? Long.MAX_VALUE : (1L << (bits - 1)) - 1;
                assertEquals(minimum, signed.execute(minimum).asLong()); firstEntry(signed);
                assertEquals(maximum, signed.execute(maximum).asLong());
                if (bits != 64) { assertThrows(PolyglotException.class, () -> signed.execute(minimum - 1)); assertThrows(PolyglotException.class, () -> signed.execute(maximum + 1)); }
                var unsigned = context.eval("thc", identity(backend, scalar("long", "Word" + bits + "Rep")));
                compiled(unsigned);
                var max = java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE);
                Object input = bits == 64 ? max : max.longValueExact();
                assertEquals(max, unsigned.execute(input).asBigInteger());
                firstEntry(unsigned);
                assertThrows(PolyglotException.class, () -> unsigned.execute(-1L));
                assertThrows(PolyglotException.class, () -> unsigned.execute(max.add(java.math.BigInteger.ONE)));
            }
            var address = context.eval("thc", identity(backend, scalar("address", "AddrRep")));
            var nil = address.execute((Object) null);
            assertTrue(nil.isNull()); assertTrue(address.execute(nil).isNull());
            assertThrows(PolyglotException.class, () -> address.execute(1234L));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void allSupportedRawSpeciesAndNestedVectorSumsRetainExactObjects(String backend) {
        String[] elements = {"Int8ElemRep", "Word8ElemRep", "Int16ElemRep", "Word16ElemRep", "Int32ElemRep", "Word32ElemRep", "Int64ElemRep", "Word64ElemRep", "FloatElemRep", "DoubleElemRep"};
        int[] laneBits = {8, 8, 16, 16, 32, 32, 64, 64, 32, 64};
        try (var context = context()) {
            for (int i = 0; i < elements.length; i++) for (int bits : new int[]{128, 256, 512}) {
                int count = bits / laneBits[i]; String element = elements[i];
                var proof = map("kind", "vector", "evaluated", true, "primReps", list("VecRep " + count + " " + element),
                    "vector", map("lanes", count, "element", element));
                var raw = thc.runtime.RawVectorTestValues.rawVectorTestValue(thc.runtime.CoreRepresentations.parse(proof));
                var entry = context.eval("thc", identity(backend, proof));
                assertSame(raw, entry.execute(raw).asHostObject(), element + "/" + bits);
                released(context);
                var sum = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum", "primReps", list("WordRep", "VecRep " + count + " " + element),
                    "alternatives", list(proof, tuple()), "tagSlot", 0, "alternativeSlots", list(list(1), list()));
                var nested = context.eval("thc", identity(backend, tuple(sum, scalar("long", "Word8Rep"))));
                var result = nested.execute((Object) new Object[]{new Object[]{1L, raw}, 255L});
                assertSame(raw, result.getArrayElement(0).getArrayElement(1).asHostObject());
                assertEquals(255L, result.getArrayElement(1).asLong());
                var empty = nested.execute((Object) new Object[]{new Object[]{2L, new Object[0]}, 0L});
                assertEquals(2L, empty.getArrayElement(0).getArrayElement(0).asLong());
                assertEquals(0, empty.getArrayElement(0).getArrayElement(1).getArraySize());
                released(context);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void managedLiteralAddressRoundTripRetainsOwnerAndBounds(String backend) {
        var address = scalar("address", "AddrRep"); var integer = scalar("long", "IntRep");
        var literal = list("lit", "string-bytes", "410042", map("rep", address));
        var variable = list("var", "x", map("rep", address));
        var read = list("app", list("prim", "indexCharOffAddr#"), list(variable, list("lit", "int", "2")), list(false, false), false, false, map("rep", integer));
        Value retained;
        try (var context = context(); var other = context()) {
            retained = context.eval("thc", unary(backend, integer, address, literal)).execute(0L);
            assertFalse(retained.isNull()); assertFalse(retained.isHostObject());
            var identity = context.eval("thc", identity(backend, address));
            assertEquals(retained, identity.execute(retained));
            assertEquals(66L, context.eval("thc", unary(backend, address, integer, read)).execute(retained).asLong());
            var foreign = other.eval("thc", identity(backend, address));
            assertThrows(RuntimeException.class, () -> foreign.execute(retained));
            released(context);
        }
        assertThrows(IllegalStateException.class, retained::isNull);
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void typedPartialApplicationsAndCapturedFunctionsKeepLogicalPrefixes(String backend) {
        var vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 2 Int64ElemRep"), "vector", map("lanes", 2, "element", "Int64ElemRep"));
        var doubleProof = scalar("double", "DoubleRep");
        var vectorParameter = map("id", "v", "name", "v", "lifted", false, "rep", vector);
        var doubleParameter = map("id", "y", "name", "y", "lifted", false, "rep", doubleProof);
        var function = list("lam", list(vectorParameter, doubleParameter), list("var", "y", map("rep", doubleProof)), map("rep", CLOSURE, "resultRep", doubleProof));
        var pap = list("app", function, list(list("var", "x", map("rep", vector))), list(false), false, false, map("rep", CLOSURE));
        try (var context = context()) {
            var original = LongVector.fromArray(LongVector.SPECIES_128, new long[]{1, Long.MIN_VALUE}, 0);
            var partial = context.eval("thc", unary(backend, vector, CLOSURE, pap)).execute(original);
            assertTrue(partial.canExecute()); assertEquals(3.5d, partial.execute(3.5d).asDouble());
            assertThrows(PolyglotException.class, () -> partial.execute(original, 3.5d));
            var add = list("app", list("prim", "+##"), list(list("var", "x", map("rep", doubleProof)), list("var", "y", map("rep", doubleProof))), list(false, false), false, false, map("rep", doubleProof));
            var captured = list("lam", list(doubleParameter), add, map("rep", CLOSURE, "resultRep", doubleProof));
            var closure = context.eval("thc", unary(backend, doubleProof, CLOSURE, captured)).execute(1.25d);
            assertEquals(3.75d, closure.execute(2.5d).asDouble()); released(context);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void returnedNullaryFunctionsInvokeTheirBody(String backend) {
        var doubleProof = scalar("double", "DoubleRep");
        var emptyFunction = list("lam", list(), list("lit", "double", "1.25", map("rep", doubleProof)), map("rep", CLOSURE, "resultRep", doubleProof));
        try (var context = context()) {
            var function = context.eval("thc", unary(backend, scalar("long", "IntRep"), CLOSURE, emptyFunction)).execute(0L);
            assertTrue(function.canExecute()); assertEquals(1.25, function.execute().asDouble()); released(context);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void independentlyLoadedNominalDataRoundTripsOnlyWithinMatchingContextAndContract(String backend) {
        var integer = scalar("long", "IntRep"); var data = scalar("data", "BoxedRep (Just Lifted)");
        var constructor = map("id", "host:Host.Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1,
            "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false), "fieldTypes", list(integer));
        var box = list("app", list("con", "host:Host.Box", 1), list(list("var", "x", map("rep", integer))), list(false), true, true, map("rep", data));
        var boxedRequest = object(Json.parse(unary(backend, integer, data, box)));
        object(((List<?>) boxedRequest.get("modules")).getFirst()).put("constructors", list(constructor));
        var extract = list("case", list("var", "x", map("rep", data)), "box", list(list("data", "host:Host.Box", list("payload"),
            list("var", "payload", map("rep", integer)), map("binders", list(map("id", "payload", "name", "payload", "lifted", false, "rep", integer))))),
            map("rep", integer, "binder", map("id", "box", "lifted", true, "rep", data)));
        var unboxRequest = object(Json.parse(unary(backend, data, integer, extract)));
        object(((List<?>) unboxRequest.get("modules")).getFirst()).put("constructors", list(constructor));
        try (var context = context(); var other = context()) {
            var boxed = context.eval("thc", Json.stringify(boxedRequest)).execute(Long.MIN_VALUE);
            assertFalse(boxed.isNumber()); assertFalse(boxed.isHostObject());
            var unbox = context.eval("thc", Json.stringify(unboxRequest));
            assertEquals(Long.MIN_VALUE, unbox.execute(boxed).asLong());
            String opposite = backend.equals("ast") ? "bytecode" : "ast";
            unboxRequest.put("backend", opposite);
            assertEquals(Long.MIN_VALUE, context.eval("thc", Json.stringify(unboxRequest)).execute(boxed).asLong());
            var wrongContract = context.eval("thc", Json.stringify(unboxRequest).replace("IntRep", "WordRep"));
            var failure = assertThrows(PolyglotException.class, () -> wrongContract.execute(boxed));
            assertTrue(failure.getMessage().contains("Non-exhaustive Core case"));
            var foreign = other.eval("thc", Json.stringify(unboxRequest));
            assertThrows(RuntimeException.class, () -> foreign.execute(boxed));
            assertThrows(PolyglotException.class, () -> unbox.execute(Long.MIN_VALUE));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void sameProgramDataAndFunctionBundlesPreserveIdentityAndVoidFields(String backend) {
        var integer = scalar("long", "IntRep"); var data = scalar("data", "BoxedRep (Just Lifted)");
        var bundle = tuple(data, CLOSURE);
        var boxConstructor = map("id", "host:Host.Box", "name", "Box", "kind", "boxed", "arity", 1, "tag", 1,
            "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false), "fieldTypes", list(integer));
        var box = list("app", list("con", "host:Host.Box", 1), list(list("var", "x", map("rep", integer))), list(false), true, true, map("rep", data));
        var extract = list("case", list("var", "d", map("rep", data)), "box", list(list("data", "host:Host.Box", list("payload"),
            list("var", "payload", map("rep", integer)), map("binders", list(map("id", "payload", "name", "payload", "lifted", false, "rep", integer))))),
            map("rep", integer, "binder", map("id", "box", "lifted", true, "rep", data)));
        var consume = list("lam", list(map("id", "d", "name", "d", "lifted", true, "rep", data)), extract, map("rep", CLOSURE, "resultRep", integer));
        var pair = list("app", list("con", "host:Host.Pair", 2), list(box, consume), list(true, true), true, true, map("rep", bundle));
        var request = object(Json.parse(unary(backend, integer, bundle, pair)));
        object(((List<?>) request.get("modules")).getFirst()).put("constructors", list(boxConstructor,
            map("id", "host:Host.Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)));
        try (var context = context()) {
            var produced = context.eval("thc", Json.stringify(request)).execute(Long.MIN_VALUE);
            var boxed = produced.getArrayElement(0); var consumeBox = produced.getArrayElement(1);
            assertFalse(boxed.isNumber()); assertFalse(boxed.isHostObject()); assertTrue(consumeBox.canExecute());
            assertEquals(Long.MIN_VALUE, consumeBox.execute(boxed).asLong());
            var identity = context.eval("thc", identity(backend, data));
            var roundTrip = identity.execute(boxed); assertEquals(boxed, roundTrip);
            assertEquals(Long.MIN_VALUE, consumeBox.execute(roundTrip).asLong());
            var voidProof = map("kind", "void", "evaluated", true, "primReps", list());
            var state = context.eval("thc", identity(backend, voidProof)); assertTrue(state.execute((Object) null).isNull());
            var nested = context.eval("thc", identity(backend, tuple(voidProof, integer, voidProof)));
            var result = nested.execute((Object) new Object[]{null, 42L, null});
            assertEquals(3, result.getArraySize()); assertTrue(result.getArrayElement(0).isNull());
            assertEquals(42L, result.getArrayElement(1).asLong()); assertTrue(result.getArrayElement(2).isNull()); released(context);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void directEntryConstructionUsesTheRetainedSignatureWithoutGuessing(String backend) {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var vector = map("kind", "vector", "evaluated", true, "primReps", list("VecRep 2 Int64ElemRep"), "vector", map("lanes", 2, "element", "Int64ElemRep"));
                for (var proof : list(scalar("float", "FloatRep"), vector)) {
                    var request = object(Json.parse(identity(backend, proof)));
                    var module = object(((List<?>) request.get("modules")).getFirst());
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
