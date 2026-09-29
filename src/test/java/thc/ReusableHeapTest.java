// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import java.util.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Constructor values use ordinary typed calls, with code-owned storage and fresh heap owners. */
class ReusableHeapTest {
    private static final Map<String,Object> DATA = map("kind", "data", "evaluated", false, "primReps", list("BoxedRep (Just Lifted)"));
    private static final Map<String,Object> CLOSURE = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
    private static final Map<String,Object> NARROW = map("kind", "long", "evaluated", true, "primReps", list("Int16Rep"));
    private static final Map<String,Object> DOUBLE = map("kind", "double", "evaluated", true, "primReps", list("DoubleRep"));
    private static final Map<String,Object> EMPTY = tuple();
    private static final Map<String,Object> COUNTERS = tuple(NARROW, DOUBLE, EMPTY);
    private static final Map<String,Object> LONGS = vector(2, "Int64ElemRep");
    private static final Map<String,Object> FLOATS = vector(4, "FloatElemRep");
    private static final Map<String,Object> SUM = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum",
        "primReps", list("WordRep", "VecRep 2 Int64ElemRep", "VecRep 4 FloatElemRep"),
        "alternatives", list(LONGS, FLOATS, EMPTY), "tagSlot", 0, "alternativeSlots", list(list(1), list(2), list()));
    @SafeVarargs private static Map<String,Object> tuple(Map<String,Object>... fields) {
        var reps = new ArrayList<Object>(); for (var field : fields) reps.addAll((List<?>)field.get("primReps"));
        return map("kind", "unknown", "evaluated", true, "primReps", reps, "aggregate", "unboxed-tuple", "components", Arrays.asList(fields));
    }
    private static Map<String,Object> vector(int lanes, String element) {
        return map("kind", "vector", "evaluated", true, "primReps", list("VecRep " + lanes + " " + element),
            "vector", map("lanes", lanes, "element", element));
    }
    private static Map<String,Object> parameter(String id, Map<String,Object> proof) {
        return map("id", id, "name", id, "lifted", proof == DATA || proof == CLOSURE, "rep", proof);
    }
    private static List<Object> variable(String id, Map<String,Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> call(List<Object> f, List<List<Object>> args, List<Boolean> lifted, Map<String,Object> result) {
        return list("app", f, args, lifted, false, false, map("rep", result));
    }
    private static Map<String,Object> function(String id, List<Map<String,Object>> args, List<Object> body, Map<String,Object> result) {
        return map("id", id, "name", id, "lifted", true, "arity", args.size(), "rep", CLOSURE,
            "expr", list("lam", args, body, map("resultRep", result)));
    }
    private static Map<String,Object> module() {
        var fields = list(COUNTERS, SUM, DATA, DATA);
        var box = map("id", "Box", "name", "Box", "kind", "boxed", "arity", 4, "tag", 1,
            "strictFields", list(false, false, false, false), "fieldLifted", list(false, false, true, true),
            "fieldReps", fields.stream().map(f -> f.get("primReps")).toList(), "fieldTypes", fields);
        var con = list("con", "Box", 4, map("rep", CLOSURE));
        var args = list(variable("counters", COUNTERS), variable("payload", SUM), variable("rest", DATA), variable("unused", DATA));
        var parameters = list(parameter("counters", COUNTERS), parameter("payload", SUM), parameter("rest", DATA), parameter("unused", DATA));
        var projection = call(list("con", "Pair", 2), list(variable("counters", COUNTERS), variable("payload", SUM)), list(false, false), tuple(COUNTERS, SUM));
        var unpack = list("case", variable("box", DATA), "whole", list(list("data", "Box", list("counters", "payload", "rest", "unused"),
            projection, map("binders", parameters))), map("rep", tuple(COUNTERS, SUM), "binder", parameter("whole", DATA)));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableHeap", "instrument", true,
            "constructors", list(box, map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", list(
                map("id", "Box", "name", "Box", "lifted", true, "arity", 4, "rep", CLOSURE, "expr", con),
                function("make", parameters, call(con, args, list(false, false, true, true), DATA), DATA),
                function("partial", parameters.subList(0, 2), call(con, args.subList(0, 2), list(false, false), CLOSURE), CLOSURE),
                function("finish", list(parameter("make", CLOSURE), parameter("rest", DATA), parameter("unused", DATA)),
                    call(variable("make", CLOSURE), args.subList(2, 4), list(true, true), DATA), DATA),
                function("unpack", list(parameter("box", DATA)), unpack, tuple(COUNTERS, SUM)),
                map("id", "never", "name", "never", "lifted", true, "rep", DATA, "expr", variable("never", DATA))));
    }
    private static Object entry(Program program, String name, Object... args) {
        return new EntryValue(program, name, args.length).execute(args, HostDispatch.create());
    }
    private static void clean(Language language) {
        var state = language.getHandoffState().get();
        assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    @SuppressWarnings("unchecked") private void checks(boolean compiled, boolean reusable) throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", Boolean.toString(compiled))
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code = null;
            if (reusable) try (var preparation = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module(), List.of("Box", "make", "partial", "finish", "unpack", "never")); }
                finally { preparation.leave(); }
            }
            if (compiled) {
                var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                for (var target : (List<RootCallTarget>)field.get(code)) {
                    assertEquals(false, targetClass.getMethod("wasExecuted").invoke(target));
                    assertEquals(true, targetClass.getMethod("prepareForAOT").invoke(target));
                    targetClass.getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(false, targetClass.getMethod("wasExecuted").invoke(target));
                }
                code.requireInstalledCode();
            }
            String previous = System.getProperty("thc.requireCompiledCode");
            if (compiled) System.setProperty("thc.requireCompiledCode", "true");
            try {
                DataValue earlier = null;
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).allowAllAccess(true).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = reusable ? code.newInstance(language) : new Program(language, module());
                        var other = reusable ? code.newInstance(language) : new Program(language, module());
                        var never = (Thunk)program.entryValue("never");
                        var lazy = new HostReference(Language.currentState(), never, CoreRepresentations.parse(DATA), program);
                        var owner = program.constructorLayout("Box");
                        assertNotSame(owner, other.constructorLayout("Box"));
                        if (earlier != null) assertFalse(owner.matches(earlier));
                        for (int tag = 1; tag <= 3; tag++) {
                            double weight = Double.longBitsToDouble(0x8000000000000000L);
                            Object[] counters = {-32768L + load, weight, new Object[0]};
                            Object payload = tag == 1 ? LongVector.fromArray(LongVector.SPECIES_128, new long[]{Long.MIN_VALUE + load, 7}, 0) :
                                tag == 2 ? FloatVector.fromArray(FloatVector.SPECIES_128, new float[]{-0.0f, 2, 3, 4}, 0) : new Object[0];
                            Object[] sum = {(long)tag, payload};
                            for (String constructor : List.of("make", "Box", "partial")) {
                                HostReference result = (HostReference)(constructor.equals("partial") ?
                                    entry(program, "finish", entry(program, "partial", counters, sum), lazy, lazy) :
                                    entry(program, constructor, counters, sum, lazy, lazy));
                                var value = assertInstanceOf(DataValue.class, result.value);
                                assertTrue(owner.matches(value)); assertFalse(other.constructorLayout("Box").matches(value));
                                assertEquals(-32768 + load, owner.readInt(value, 0));
                                assertEquals(Double.doubleToRawLongBits(weight), Double.doubleToRawLongBits(owner.readDouble(value, 1)));
                                assertSame(never, owner.read(value, 5)); assertSame(never, owner.read(value, 6));
                                var scratch = new FrameLayout(); int slot = scratch.bind("restored vector", FrameSlotKind.Object);
                                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], scratch.build());
                                owner.restoreVector(value, 3, frame, new int[]{slot}, 0);
                                assertEquals(tag == 1 ? payload : LongVector.zero(LongVector.SPECIES_128), frame.getObject(slot));
                                owner.restoreVector(value, 4, frame, new int[]{slot}, 0);
                                assertArrayEquals((tag == 2 ? (FloatVector)payload : FloatVector.zero(FloatVector.SPECIES_128)).reinterpretAsBytes().toArray(),
                                    ((FloatVector)frame.getObject(slot)).reinterpretAsBytes().toArray());
                                frame.clear(slot);
                                assertThrows(RuntimeFault.class, () -> other.constructorLayout("Box").readInt(value, 0));
                                var roundTrip = context.asValue(entry(program, "unpack", result));
                                assertEquals(-32768L + load, roundTrip.getArrayElement(0).getArrayElement(0).asLong());
                                assertEquals((long)tag, roundTrip.getArrayElement(1).getArrayElement(0).asLong());
                                var restored = roundTrip.getArrayElement(1).getArrayElement(1);
                                if (tag == 1) assertEquals(payload, restored.asHostObject());
                                else if (tag == 2) assertArrayEquals(((FloatVector)payload).reinterpretAsBytes().toArray(), ((FloatVector)restored.asHostObject()).reinterpretAsBytes().toArray());
                                else assertEquals(0, restored.getArraySize());
                                assertEquals(0, never.getState()); assertEquals(0, ((Thunk)other.entryValue("never")).getState());
                                if (compiled) code.requireInstalledCode();
                                clean(language); earlier = value;
                            }
                        }
                        if (reusable) assertEquals(0, program.diagnostics().get("loweredRootCount"));
                    } finally { context.leave(); }
                }
            } finally { if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous); }
        }
    }
    @Test void ordinaryTypedConstructorValuesAndPaps() throws Exception { checks(false, false); }
    @Test void reusableTypedConstructorValuesAndPapsKeepFreshOwners() throws Exception { checks(false, true); }
    @Test void firstCompiledHeapFieldsAndConstructorPapsNeedNoTraining() throws Exception { checks(true, true); }
    @Test void reusableFieldsStillRequireExactLogicalProofs() {
        var unknown = map("kind", "unknown", "evaluated", true, "primReps", list("IntRep"));
        var input = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.UnknownHeapField",
            "constructors", list(map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed",
                "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list("IntRep")), "fieldTypes", list(unknown))),
            "bindings", list(map("id", "Box", "name", "Box", "lifted", true, "arity", 1, "rep", CLOSURE,
                "expr", list("con", "Box", 1, map("rep", CLOSURE)))));
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                assertTrue(assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, input, List.of("Box")))
                    .getMessage().contains("exact representation proofs"));
            } finally { context.leave(); }
        }
    }
}
