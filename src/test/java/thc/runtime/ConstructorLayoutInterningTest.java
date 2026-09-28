// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Constructor identity spans loads within one context, never unrelated names or contexts. */
class ConstructorLayoutInterningTest {
    private static final String BOX = "test:Shared.Box";
    private static final Map<String, Object> INTEGER = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
    private static final Map<String, Object> DATA = map("kind", "data", "evaluated", false, "primReps", list("BoxedRep (Just Lifted)"));
    private static List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> number(long value) { return list("lit", "int", Long.toString(value), map("rep", INTEGER)); }
    private static Map<String, Object> binder(String id, Map<String, Object> proof) {
        return map("id", id, "name", id, "lifted", proof == DATA, "rep", proof);
    }
    private static Map<String, Object> constructor(String id, List<Map<String, Object>> fields) {
        return map("id", id, "name", "Box", "arity", fields.size(), "kind", "boxed", "tag", 1,
            "fieldReps", fields.stream().map(field -> field.get("primReps")).toList(), "fieldTypes", fields,
            "fieldLifted", Collections.nCopies(fields.size(), false), "strictFields", Collections.nCopies(fields.size(), false));
    }
    private static Map<String, Object> module(String id, boolean producer) {
        var body = producer
            ? list("app", list("con", id, 1), list(variable("x", INTEGER)), list(false), false, false, map("rep", DATA))
            : list("case", variable("x", DATA), "whole", list(
                list("data", id, list("n"), variable("n", INTEGER), map("binders", list(binder("n", INTEGER)))),
                list("default", null, list(), number(-1))), map("rep", INTEGER, "binder", binder("whole", DATA)));
        var lambda = list("lam", list(binder("x", producer ? INTEGER : DATA)), body,
            map("resultRep", producer ? DATA : INTEGER));
        return map("schema", 1, "ghc", "9.14.1", "unit", "test", "module", producer ? "Producer" : "Consumer",
            "bindings", list(map("id", "entry", "name", "entry", "lifted", true, "expr", lambda)),
            "constructors", list(with(constructor(id, list(INTEGER)), "type", producer ? "producer prose" : "consumer prose")));
    }
    private static ExecutableProgram program(Language language, String backend, Map<String, Object> input) {
        return backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
    }
    private static Object call(ExecutableProgram program, Object value) {
        return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{value}});
    }
    @Test void independentLoadsShareQualifiedConstructorsAcrossBackends() throws Exception {
        for (var strategy : list("field-based", "array-based")) try (var context = Context.newBuilder("thc")
            .allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var id : list(BOX, "test:Shared.:."))
                for (var source : list("ast", "bytecode")) for (var target : list("ast", "bytecode")) {
                    var producer = program(language, source, module(id, true));
                    var consumer = program(language, target, module(id, false));
                    assertSame(producer.constructorLayout(id), consumer.constructorLayout(id));
                    assertEquals(42L, call(consumer, call(producer, 42L)), source + " -> " + target);
                    consumer.entryTarget("entry").getClass().getMethod("compile", boolean.class).invoke(consumer.entryTarget("entry"), true);
                    assertEquals(Long.MIN_VALUE, call(consumer, call(producer, Long.MIN_VALUE)));
                    var unrelated = program(language, target, module("other:Shared.Box", false));
                    assertEquals(-1L, call(unrelated, call(producer, 42L)), "The display name is not constructor identity");
                    var localProducer = program(language, source, module("Box", true));
                    var localConsumer = program(language, target, module("Box", false));
                    assertEquals(-1L, call(localConsumer, call(localProducer, 42L)), "Unqualified fixture identities remain program-scoped");
                }
            } finally { context.leave(); }
        }
    }
    @Test void equalPhysicalSlotsDoNotEraseLogicalAggregateContracts() {
        try (var context = Context.create("thc")) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var pair = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
                    "components", list(INTEGER, INTEGER), "primReps", list("IntRep", "IntRep"));
                var grouped = map("bindings", list(), "constructors", list(constructor(BOX, list(pair))));
                var separate = map("bindings", list(), "constructors", list(constructor(BOX, list(INTEGER, INTEGER))));
                var a = program(language, "ast", grouped).constructorLayout(BOX);
                var b = program(language, "bytecode", separate).constructorLayout(BOX);
                assertEquals(2, a.getArity()); assertEquals(2, b.getArity());
                assertEquals(1, a.getLogicalArity()); assertEquals(2, b.getLogicalArity());
                assertNotSame(a, b); assertFalse(a.matches(b.create(new Object[]{7L, 9L})));
                assertSame(a, program(language, "bytecode", grouped).constructorLayout(BOX));
                var word = with(INTEGER, "primReps", list("WordRep"));
                var sum = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-sum",
                    "primReps", list("WordRep", "WordRep"), "alternatives", list(INTEGER, word),
                    "tagSlot", 0, "alternativeSlots", list(list(1), list(1)));
                var reversed = with(sum, "alternatives", list(word, INTEGER));
                var left = program(language, "ast", map("bindings", list(), "constructors", list(constructor(BOX, list(sum))))).constructorLayout(BOX);
                var right = program(language, "bytecode", map("bindings", list(), "constructors", list(constructor(BOX, list(reversed))))).constructorLayout(BOX);
                assertEquals(left.getArity(), right.getArity()); assertEquals(left.getLogicalArity(), right.getLogicalArity());
                assertNotSame(left, right, "Sum alternative contracts remain distinct despite identical physical slots");
            } finally { context.leave(); }
        }
    }
    @Test void sparkFallbackUsesTheLoadedFalseConstructor() {
        try (var context = Context.create("thc")) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = map("kind", "void", "evaluated", true, "primReps", list());
                var result = map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
                    "primReps", list("IntRep", "BoxedRep (Just Lifted)"), "components", list(state, INTEGER, DATA));
                var spark = list("app", list("prim", "getSpark#"), list(variable("s", state)), list(false), false, false, map("rep", result));
                var body = list("case", spark, "whole", list(list("data", "Triple", list("s2", "count", "empty"), variable("empty", DATA),
                    map("binders", list(binder("s2", state), binder("count", INTEGER), binder("empty", DATA))))),
                    map("rep", DATA, "binder", binder("whole", result)));
                var input = map("bindings", list(map("id", "entry", "name", "entry", "lifted", true,
                    "expr", list("lam", list(binder("s", state)), body, map("resultRep", DATA)))),
                    "constructors", list(map("id", "Triple", "kind", "unboxed-tuple", "arity", 3)));
                for (var backend : list("ast", "bytecode")) {
                    var producer = program(language, backend, input);
                    var value = (DataValue) call(producer, Unit.INSTANCE);
                    var consumer = program(language, backend.equals("ast") ? "bytecode" : "ast",
                        map("bindings", list(), "constructors", list(with(constructor(CoreThreadScheduling.FALSE, list()), "name", "False"))));
                    assertTrue(consumer.constructorLayout(CoreThreadScheduling.FALSE).matches(value));
                    assertSame(value, consumer.constructorLayout(CoreThreadScheduling.FALSE).allocate());
                }
            } finally { context.leave(); }
        }
    }
    @Test void sharedEngineDoesNotShareConstructorOwnershipAcrossContexts() {
        try (var engine = Engine.create(); var first = Context.newBuilder("thc").engine(engine).build();
             var second = Context.newBuilder("thc").engine(engine).build()) {
            DataValue saved; DataLayout layout;
            first.initialize("thc"); first.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var producer = program(language, "ast", module(BOX, true));
                saved = (DataValue) call(producer, 42L); layout = producer.constructorLayout(BOX);
            } finally { first.leave(); }
            second.initialize("thc"); second.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var consumer = program(language, "bytecode", module(BOX, false));
                assertNotSame(layout, consumer.constructorLayout(BOX));
                assertFalse(consumer.constructorLayout(BOX).matches(saved)); assertEquals(-1L, call(consumer, saved));
            } finally { second.leave(); }
        }
    }
    @Test void interningDoesNotReadUndemandedConstructorMetadata() {
        try (var context = Context.create("thc")) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : list("ast", "bytecode")) {
                    var reads = new AtomicInteger();
                    var demand = new CoreDemandBindings(id -> false, id -> { throw new AssertionError("Unexpected binding read"); },
                        id -> { reads.incrementAndGet(); assertEquals(BOX, id); return constructor(id, list(INTEGER)); },
                        (id, binding) -> { throw new AssertionError("Unexpected binding compilation"); }, false, id -> false);
                    var input = map("bindings", list(), "constructors", list(), "demandBindings", demand);
                    var selected = program(language, backend, input);
                    assertEquals(0, reads.get());
                    var layout = selected.constructorLayout(BOX); assertEquals(1, reads.get());
                    assertSame(layout, selected.constructorLayout(BOX)); assertEquals(1, reads.get());
                }
            } finally { context.leave(); }
        }
    }
}
