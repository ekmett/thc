// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Local aggregate bindings reuse their typed slots without re-entering the RHS. */
class AggregateLetTest {
    private static final Map<String, Object> INTEGER = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String, Object> REFERENCE = map("kind", "object", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private static final Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String, Object> PAIR = tuple(REFERENCE, INTEGER);
    private static final Map<String, Object> EMPTY = tuple();
    private static final Map<String, Object> SUM = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum",
        "primReps", list("WordRep", "BoxedRep (Just Lifted)", "WordRep"), "alternatives", list(PAIR, INTEGER),
        "tagSlot", 0, "alternativeSlots", list(list(1, 2), list(2)));

    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        var reps = new ArrayList<Object>();
        for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
        return map("kind", "unknown", "primReps", reps, "evaluated", true,
            "aggregate", "unboxed-tuple", "components", Arrays.asList(fields));
    }
    private static Map<String, Object> binder(String id, Map<String, Object> proof) {
        return map("id", id, "name", id, "rep", proof, "lifted", proof == REFERENCE || proof == CLOSURE);
    }
    private static List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> number(long n) { return list("lit", "int", Long.toString(n), map("rep", INTEGER)); }
    private static List<Object> call(List<Object> function, Map<String, Object> result, List<List<Object>> args, Object... lifted) {
        return list("app", function, args, Arrays.asList(lifted), false, false, map("rep", result));
    }
    private static List<Object> lambda(List<Map<String, Object>> args, List<Object> body) {
        return list("lam", args, body, map("rep", CLOSURE, "resultRep", INTEGER, "entryStrict", Collections.nCopies(args.size(), false)));
    }
    private static List<Object> pairProjection(List<Object> value, String prefix) {
        return list("case", value, prefix + "whole", list(list("data", "Pair", list(prefix + "x", prefix + "n"), variable(prefix + "n", INTEGER),
            map("binders", list(binder(prefix + "x", REFERENCE), binder(prefix + "n", INTEGER))))),
            map("rep", INTEGER, "binder", binder(prefix + "whole", PAIR)));
    }
    private static List<Object> projection(boolean sum, String prefix) {
        if (!sum) return pairProjection(variable("value", PAIR), prefix);
        return list("case", variable("value", SUM), prefix + "whole", list(
            list("data", "Left", list(prefix + "pair"), pairProjection(variable(prefix + "pair", PAIR), prefix + "p"),
                map("binders", list(binder(prefix + "pair", PAIR)))),
            list("data", "Right", list(prefix + "n"), variable(prefix + "n", INTEGER), map("binders", list(binder(prefix + "n", INTEGER))))),
            map("rep", INTEGER, "binder", binder(prefix + "whole", SUM)));
    }
    private static Map<String, Object> module(boolean sum, boolean right, String usage, boolean recursive) {
        var tick = call(variable("tick", CLOSURE), INTEGER, list(number(42)), false);
        var pair = call(list("con", "Pair", 2), PAIR, list(variable("x", REFERENCE), tick), true, false);
        var proof = usage.equals("empty") ? EMPTY : sum ? SUM : PAIR;
        var emptyBody = list("let", false, list(with(binder("ignored", INTEGER), "expr", tick)),
            list("con", "Empty", 0, map("rep", EMPTY)), map("rep", EMPTY));
        var emptyCall = call(list("lam", list(binder("unused", INTEGER)), emptyBody,
            map("rep", CLOSURE, "resultRep", EMPTY)), EMPTY, list(number(0)), false);
        var rhs = usage.equals("empty") ? emptyCall : !sum ? pair :
            call(list("con", right ? "Right" : "Left", 1), SUM, list(right ? tick : pair), false);
        var body = switch (usage) {
            case "unused", "empty" -> number(7);
            case "capture" -> call(lambda(list(binder("unused", INTEGER)), projection(sum, "c")), INTEGER, list(number(0)), false);
            default -> call(list("prim", "+#"), INTEGER, list(projection(sum, "a"), projection(sum, "b")), false, false);
        };
        var local = with(binder("value", proof), "expr", rhs);
        var let = list("let", recursive, list(local), body, map("rep", INTEGER));
        var entry = map("id", "entry", "name", "entry", "arity", 2, "lifted", true, "rep", CLOSURE,
            "expr", lambda(list(binder("tick", CLOSURE), binder("x", REFERENCE)), let));
        return map("schema", 1, "ghc", "9.14.1", "module", "AggregateLet", "instrument", true, "bindings", list(entry),
            "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2),
                map("id", "Empty", "name", "Empty", "kind", "unboxed-tuple", "arity", 0),
                map("id", "Left", "name", "Left", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 1),
                map("id", "Right", "name", "Right", "kind", "unboxed-sum", "arity", 1, "sumArity", 2, "tag", 2)));
    }
    @Test void sumAndTupleLetsEvaluateOnceKeepLazyFieldsAndCaptureTypedSlots() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var entered = new AtomicInteger();
                var callback = new Closure(null, 1, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { entered.incrementAndGet(); return 42L; }
                }.getCallTarget());
                var poison = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Aggregate let entered its lifted payload"); }
                }.getCallTarget(), null);
                for (boolean sum : new boolean[]{false, true}) for (boolean right : new boolean[]{false, true})
                    for (var usage : list("twice", "unused", "capture", "empty")) {
                        var input = module(sum, right, usage, false);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                        var target = program.entryTarget("entry");
                        for (int phase = 0; phase < 2; phase++) {
                            if (phase == 1) target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            entered.set(0);
                            long expected = usage.equals("twice") ? 84 : usage.equals("capture") ? 42 : 7;
                            assertEquals(expected, Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("entry"), new Object[]{callback, poison}}), backend + "/" + sum + "/" + right + "/" + usage);
                            assertEquals(1, entered.get(), "The RHS must execute exactly once, even when unused or zero-width");
                            assertEquals(0, poison.getState());
                        }
                    }
                for (boolean sum : new boolean[]{false, true}) {
                    var input = module(sum, false, "twice", true);
                    assertThrows(UnsupportedCore.class, () -> {
                        if (backend.equals("ast")) new Program(language, input); else new BytecodeProgram(language, input);
                    }, "Recursive unlifted lets are not valid Core bindings");
                }
                var state = language.getHandoffState().get();
                assertNull(state.getPending()); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
                assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }
}
