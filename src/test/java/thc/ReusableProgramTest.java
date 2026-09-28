// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Real lowerer ownership checks; auxiliary-cache persistence remains a separate acceptance. */
class ReusableProgramTest {
    private Map<String, Object> binding(String id, List<Object> body, boolean lifted) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "expr", body);
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long n) { return list("lit", "int", Long.toString(n)); }
    private List<Object> plus(List<Object> a, List<Object> b) {
        return list("app", list("prim", "+#"), list(a, b), list(false, false));
    }
    private List<Object> lambda(List<Object> body) {
        return list("lam", list(wordParameter("x")), body);
    }
    private Map<String, Object> wordParameter(String id) {
        return map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false,
            "rep", map("kind", "long", "evaluated", true, "primReps", list("IntRep")));
    }
    private Object call(ExecutableProgram program, Object value, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{value, arguments});
    }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Reusable", "instrument", true,
            "constructors", list(), "bindings", bindings);
    }

    @Test void cachedPublicSourcesCreateFreshProgramsWithinAndAcrossContexts() {
        var longRep = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closureRep = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var caf = binding("shared", plus(literal(17), literal(25)), true);
        caf.put("arity", 0);
        var reader = binding("read", list("lam", list(wordParameter("x")), plus(variable("shared"), variable("x")),
            map("rep", closureRep, "resultRep", longRep)), true);
        reader.put("arity", 1); reader.put("rep", closureRep);
        for (String backend : List.of("ast", "bytecode")) {
            var request = Json.stringify(map("modules", list(module(list(caf, reader))), "entry", "read",
                "backend", backend, "asyncExceptions", false));
            var source = org.graalvm.polyglot.Source.newBuilder("thc", request, "owned-load-" + backend).cached(true).buildLiteral();
            try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build();
                 var first = Context.newBuilder("thc").engine(engine).build();
                 var second = Context.newBuilder("thc").engine(engine).build()) {
                var firstReader = first.eval(source);
                var sameContextReader = first.eval(source);
                var secondReader = second.eval(source);
                assertEquals(0L, publicCount(firstReader, "thunkEvaluations"));
                assertEquals(0L, publicCount(sameContextReader, "thunkEvaluations"));
                assertEquals(0L, publicCount(secondReader, "thunkEvaluations"));
                assertEquals(47L, firstReader.execute(5L).asLong());
                assertEquals(1L, publicCount(firstReader, "thunkEvaluations"));
                assertEquals(0L, publicCount(sameContextReader, "thunkEvaluations"));
                assertEquals(0L, publicCount(secondReader, "thunkEvaluations"));
                assertEquals(49L, sameContextReader.execute(7L).asLong());
                assertEquals(1L, publicCount(sameContextReader, "thunkEvaluations"));
                first.close();
                assertEquals(51L, secondReader.execute(9L).asLong());
                assertEquals(1L, publicCount(secondReader, "thunkEvaluations"));
            }
        }
    }
    private long publicCount(org.graalvm.polyglot.Value entry, String key) {
        return ((Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get(key)).longValue();
    }

    @Test void untouchedRealRootsPrepareOutsideContextAndRetainTheirFirstCompiledEntry() throws Exception {
        var untouched = new AtomicInteger();
        var lazy = new CoreBindingBody(new CoreBindingBody.Header(2, Map.of(0, "var", 1, "unused"), false), null,
            () -> { untouched.incrementAndGet(); throw new AssertionError("untouched definition was decoded"); });
        var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
            binding("read", lambda(plus(variable("shared"), variable("x"))), true),
            binding("bottom", variable("bottom"), true), binding("unused", lazy, true)));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            Program preparation;
            com.oracle.truffle.api.RootCallTarget[] targets;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    code = Program.prepareCode(language, data, List.of("read", "bottom"));
                    preparation = code.newInstance(language);
                    targets = new com.oracle.truffle.api.RootCallTarget[]{
                        ((Closure) preparation.entryValue("read")).target,
                        ((Thunk) preparation.entryValue("shared")).getTarget(),
                        ((Thunk) preparation.entryValue("bottom")).getTarget()};
                } finally { context.leave(); }
            }
            // No entered context, guest execution or profile-training call; the preparation context is gone.
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var target : targets) {
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target), target.getRootNode().getName());
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                assertEquals(false, type.getMethod("wasExecuted").invoke(target), "preparation/compilation must not execute a guest body");
            }
            assertEquals(0L, count(preparation, "thunkEvaluations"));
            assertEquals(0L, count(preparation, "compiledEntries"));
            assertEquals(0, untouched.get());
            RuntimeFault previousFailure = null;
            for (int owner = 0; owner < 2; owner++) {
                try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = code.newInstance(language);
                        var reader = (Closure) program.entryValue("read");
                        assertSame(targets[0], reader.target);
                        assertEquals(0, ((Thunk) program.entryValue("shared")).getState());
                        var runtime = Truffle.getRuntime();
                        runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, targets[0]);
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[0]), "original reader immediately before its first call");
                        assertEquals(47L + owner, Calls.target(reader.target, new Object[]{0L, reader.environment, 5L + owner}));
                        assertEquals(2L, count(program, "compiledEntries"), "first reader and shared CAF must both enter compiled code");
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[0]), "original reader after its first call");
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[1]), "original CAF after its first call");
                        assertEquals(1L, count(program, "thunkEvaluations"));
                        var sibling = code.newInstance(language);
                        var siblingReader = (Closure) sibling.entryValue("read");
                        assertSame(reader.target, siblingReader.target);
                        assertEquals(0, ((Thunk) sibling.entryValue("shared")).getState());
                        assertEquals(53L, Calls.target(siblingReader.target, new Object[]{0L, siblingReader.environment, 11L}));
                        assertEquals(2L, count(sibling, "compiledEntries"));
                        assertEquals(1L, count(sibling, "thunkEvaluations"));
                        assertEquals(2L, count(program, "compiledEntries"));
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[0]));
                        var bottom = program.entryValue("bottom");
                        var failure = assertThrows(RuntimeFault.class, () -> call(program, bottom));
                        assertSame(failure, assertThrows(RuntimeFault.class, () -> call(program, bottom)));
                        if (previousFailure != null) assertNotSame(previousFailure, failure);
                        previousFailure = failure;
                        assertEquals(2L, count(program, "thunkEvaluations"));
                        assertEquals(0L, count(program, "loweredRootCount"));
                        assertEquals(0, untouched.get());
                    } finally { context.leave(); }
                }
            }
            assertEquals(0L, count(preparation, "thunkEvaluations"));
        }
    }

    @Test void ordinaryRootsDoNotClaimReusableAotPreparation() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var ordinary = new Program(language, module(list(binding("read", lambda(variable("x")), true))));
                var target = ((Closure) ordinary.entryValue("read")).target;
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                assertEquals(false, type.getMethod("prepareForAOT").invoke(target));
                assertEquals(0L, count(ordinary, "compiledEntries"));
            } finally { context.leave(); }
        }
    }

    @Test void preparationRejectsStrictGuestBodiesAndMissingInputProofs() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var arithmetic = module(list(binding("strict", plus(literal(17), literal(25)), false)));
                var force = module(list(binding("caf", plus(literal(17), literal(25)), true),
                    binding("strict", variable("caf"), false)));
                var unknown = wordParameter("x"); unknown.remove("rep");
                var missingProof = module(list(binding("read", list("lam", list(unknown), plus(variable("x"), literal(1))), true)));
                assertAll(
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, arithmetic, List.of("strict"))),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, force, List.of("strict"))),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, missingProof, List.of("read"))));
                var literalCode = Program.prepareCode(language, module(list(binding("strict", literal(42), false))), List.of("strict"));
                assertEquals(42L, literalCode.newInstance(language).entryValue("strict"));
            } finally { context.leave(); }
        }
    }

    @Test void admittedInputsRejectNonLongAndThunkBeforeEffects() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = Program.prepareCode(language, module(list(binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true))), List.of("read")).newInstance(language);
                var effects = new AtomicInteger();
                var external = new Thunk(new GuestRoot(language, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { effects.incrementAndGet(); return 9L; }
                }.getCallTarget(), null);
                var reader = (Closure) program.entryValue("read");
                for (Object invalid : List.of("9", Integer.valueOf(9), external)) {
                    assertThrows(RuntimeFault.class, () -> Calls.target(reader.target, new Object[]{0L, reader.environment, invalid}));
                    assertEquals(0, effects.get());
                    assertEquals(0, external.getState());
                    assertEquals(0, ((Thunk) program.entryValue("shared")).getState());
                    assertEquals(0L, count(program, "thunkEvaluations"));
                }
            } finally { context.leave(); }
        }
    }

    @Test void preparedCodeRejectsForeignLanguageBeforeInstanceCreation() {
        Program.PreparedCode code;
        Language preparedLanguage;
        var data = module(list(binding("read", lambda(variable("x")), true)));
        try (var first = Main.executionContext(false)) {
            first.initialize("thc"); first.enter();
            try {
                preparedLanguage = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                code = Program.prepareCode(preparedLanguage, data, List.of("read"));
            } finally { first.leave(); }
        }
        try (var second = Main.executionContext(false)) {
            second.initialize("thc"); second.enter();
            try {
                var currentLanguage = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                assertNotSame(preparedLanguage, currentLanguage);
                assertAll(
                    () -> assertThrows(UnsupportedCore.class, () -> code.newInstance(currentLanguage)),
                    () -> assertThrows(UnsupportedCore.class, () -> code.newInstance(preparedLanguage)),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(preparedLanguage, data, List.of("read"))));
            } finally { second.leave(); }
        }
    }

    @Test void reusableRootsOutlivePreparationContextButInstancesDoNotCrossContexts() {
        var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
            binding("read", lambda(plus(variable("shared"), variable("x"))), true),
            binding("bottom", variable("bottom"), true)));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            Program.PreparedCode code;
            Program first;
            Closure firstReader;
            RuntimeFault firstFailure;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    code = Program.prepareCode(language, data, List.of("read", "bottom"));
                    first = code.newInstance(language);
                    firstReader = (Closure) first.entryValue("read");
                    assertEquals(47L, call(first, firstReader, 5L));
                    firstFailure = assertThrows(RuntimeFault.class, () -> call(first, first.entryValue("bottom")));
                } finally { context.leave(); }
            }
            // The preparation context is closed. Only code may be reused; its values remain owned.
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var second = code.newInstance(language);
                    var secondReader = (Closure) second.entryValue("read");
                    assertSame(firstReader.target, secondReader.target);
                    assertEquals(0, ((Thunk) second.entryValue("shared")).getState());
                    assertEquals(51L, call(second, secondReader, 9L));
                    assertEquals(1L, count(second, "thunkEvaluations"));
                    long secondEntries = count(second, "compiledEntries");
                    assertThrows(RuntimeFault.class, () -> Calls.target(secondReader.target,
                        new Object[]{0L, firstReader.environment, 1L}), "a closed context's instance cannot enter shared code elsewhere");
                    assertEquals(secondEntries, count(second, "compiledEntries"));
                    var failure = assertThrows(RuntimeFault.class, () -> call(second, second.entryValue("bottom")));
                    assertNotSame(firstFailure, failure);
                    assertSame(failure, assertThrows(RuntimeFault.class, () -> call(second, second.entryValue("bottom"))));
                    assertEquals(2L, count(second, "thunkEvaluations"));
                    assertEquals(2L, count(first, "thunkEvaluations"));
                    assertEquals(0L, count(second, "loweredRootCount"));
                } finally { context.leave(); }
            }
        }
    }

    @Test void admissionRejectsUnconvertedOwnershipAndConflictingCarrierProofs() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var foreign = module(list(binding("read", lambda(variable("x")), true)));
                foreign.put("selectedForeignExceptionBridge", map("unit", "u", "box", "b", "project", "p"));
                var narrow = map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false,
                    "rep", map("kind", "long", "evaluated", true, "primReps", list("Int32Rep")));
                var conflicting = module(list(binding("read", list("lam", list(narrow), variable("x")), true)));
                var nested = module(list(binding("read", lambda(lambda(variable("x"))), true)));
                assertAll(
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, foreign, List.of("read"))),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, conflicting, List.of("read"))),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, nested, List.of("read"))));
            } finally { context.leave(); }
        }
    }

    @Test void installedSharedRootUsesFreshOwnersCafAndMetricsOnItsFirstCall() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true))), List.of("read"));
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                var reader = (Closure) first.entryValue("read");
                var secondReader = (Closure) second.entryValue("read");
                var secondCaf = (Thunk) second.entryValue("shared");
                assertSame(reader.target, secondReader.target);
                // One declared call establishes the ordinary JIT profiles using the FIRST owner only.
                // The second owner and its CAF have never executed when shared code is installed.
                assertEquals(47L, Calls.target(reader.target, new Object[]{0L, reader.environment, 5L}));
                assertEquals(0, secondCaf.getState());
                var target = reader.target;
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
                long firstEntries = count(first, "compiledEntries");
                long secondEntries = count(second, "compiledEntries");
                assertEquals(51L, Calls.target(target, new Object[]{0L, secondReader.environment, 9L}));
                assertSame(target, secondReader.target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target), "the first second-owner call must retain installed code");
                assertTrue(count(second, "compiledEntries") > secondEntries);
                assertEquals(firstEntries, count(first, "compiledEntries"));
                assertEquals(2, secondCaf.getState());
                assertEquals(1L, count(first, "thunkEvaluations"));
                assertEquals(1L, count(second, "thunkEvaluations"));
            } finally { context.leave(); }
        }
    }

    @Test void realLoweredPapRetainsItsOwnerAcrossOtherInstanceCalls() {
        var x = wordParameter("x");
        var y = wordParameter("y");
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", list("lam", list(x, y), plus(variable("shared"), plus(variable("x"), variable("y")))), true))), List.of("read"));
                var first = code.newInstance(language); var second = code.newInstance(language);
                var firstPap = (Closure) call(first, first.entryValue("read"), 3L);
                var secondPap = (Closure) call(second, second.entryValue("read"), 11L);
                assertSame(first, firstPap.environment.getProgram()); assertSame(second, secondPap.environment.getProgram());
                assertSame(firstPap.target, secondPap.target);
                assertEquals(55L, call(second, secondPap, 2L));
                assertEquals(0, ((Thunk) first.entryValue("shared")).getState());
                assertEquals(50L, call(first, firstPap, 5L));
                assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(1L, count(second, "thunkEvaluations"));
            } finally { context.leave(); }
        }
    }

    @Test void wrongCodeAndMissingOwnerAreRejectedBeforeCafEffects() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true)));
                var first = Program.prepareCode(language, data, List.of("read")).newInstance(language);
                var other = Program.prepareCode(language, data, List.of("read")).newInstance(language);
                var reader = (Closure) first.entryValue("read");
                var layout = reader.environment.getLayout();
                for (var environment : List.of(layout.captureValues(new Object[0]), layout.captureValues(new Object[0], other))) {
                    assertThrows(RuntimeFault.class, () -> Calls.target(reader.target, new Object[]{0L, environment, 5L}));
                    assertEquals(0, ((Thunk) first.entryValue("shared")).getState());
                    assertEquals(0, ((Thunk) other.entryValue("shared")).getState());
                    assertEquals(0L, count(first, "thunkEvaluations")); assertEquals(0L, count(other, "thunkEvaluations"));
                }
            } finally { context.leave(); }
        }
    }

    @Test void realLoweredRootsShareCodeButNotCafUpdatesFailuresOrMetrics() {
        var untouched = new AtomicInteger();
        var lazy = new CoreBindingBody(new CoreBindingBody.Header(2, Map.of(0, "var", 1, "unused"), false), null,
            () -> { untouched.incrementAndGet(); throw new AssertionError("untouched definition was decoded"); });
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Reusable",
            "instrument", true, "constructors", list(), "bindings", list(
                binding("shared", plus(literal(17), literal(25)), true),
                binding("read", lambda(plus(variable("shared"), variable("x"))), true),
                binding("readAgain", lambda(plus(variable("shared"), variable("x"))), true),
                binding("bottom", variable("bottom"), true), binding("unused", lazy, true)));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module, List.of("read", "readAgain", "bottom"));
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                assertEquals(0, untouched.get());
                assertEquals(0L, count(first, "thunkEvaluations"));
                assertEquals(0L, count(second, "thunkEvaluations"));
                var firstReader = (Closure) first.entryValue("read");
                var secondReader = (Closure) second.entryValue("read");
                assertSame(firstReader.target, secondReader.target, "the actual lowered root must be reused");
                assertNotSame(firstReader, secondReader);
                assertSame(first, firstReader.environment.getProgram());
                assertSame(second, secondReader.environment.getProgram());
                var firstCaf = (Thunk) first.entryValue("shared");
                var secondCaf = (Thunk) second.entryValue("shared");
                assertNotSame(firstCaf, secondCaf);
                assertSame(firstCaf.getTarget(), secondCaf.getTarget());
                assertEquals(0, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                assertEquals(47L, call(first, firstReader, 5L));
                assertEquals(2, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(0L, count(second, "thunkEvaluations"));
                assertEquals(49L, call(first, first.entryValue("readAgain"), 7L));
                assertEquals(1L, count(first, "thunkEvaluations"));
                assertEquals(51L, call(second, secondReader, 9L));
                assertEquals(1L, count(second, "thunkEvaluations"));
                var firstBottom = (Thunk) first.entryValue("bottom");
                var secondBottom = (Thunk) second.entryValue("bottom");
                assertEquals(0, firstBottom.getState()); assertEquals(0, secondBottom.getState());
                var firstFailure = assertThrows(RuntimeFault.class, () -> call(first, firstBottom));
                assertSame(firstFailure, assertThrows(RuntimeFault.class, () -> call(first, firstBottom)));
                assertEquals(0, secondBottom.getState());
                var secondFailure = assertThrows(RuntimeFault.class, () -> call(second, secondBottom));
                assertSame(secondFailure, assertThrows(RuntimeFault.class, () -> call(second, secondBottom)));
                assertNotSame(firstFailure, secondFailure);
                assertEquals(2L, count(first, "thunkEvaluations")); assertEquals(2L, count(second, "thunkEvaluations"));
                assertEquals(0L, count(first, "loweredRootCount")); assertEquals(0L, count(second, "loweredRootCount"));
                assertEquals(0, untouched.get());
            } finally { context.leave(); }
        }
    }
}
