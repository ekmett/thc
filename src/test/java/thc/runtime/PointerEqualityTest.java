// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY;

/** Raw object identity is intentionally tested separately from the native value oracle. */
class PointerEqualityTest {
    private final Map<String, Object> longRep =
        Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> reference =
        Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private List<Object> variable(String id) {
        return List.of("var", id);
    }
    private List<Object> integer(long value) {
        return List.of("lit", "int", Long.toString(value));
    }
    @SafeVarargs
    private final List<Object> pointer(List<Object>... args) {
        return List.of("app", List.of("prim", "reallyUnsafePtrEquality#"), Arrays.asList(args),
            Collections.nCopies(args.length, true), false, false,
            Map.of("rep", longRep, "callDemand",
                Map.of("arity", 2, "strictArgs", Collections.nCopies(args.length, false))));
    }
    @SafeVarargs
    private final List<Object> arithmetic(String name, List<Object>... args) {
        return List.of("app", List.of("prim", name), Arrays.asList(args), Collections.nCopies(args.length, false));
    }
    private List<Object> demand(List<Object> value, String name, List<Object> body) {
        return List.of("case", value, name, List.of(Arrays.asList("default", null, List.of(), body)));
    }
    private Map<String, Object> module(List<Object> body, boolean diagnostic) {
        var parameters = new ArrayList<Map<String, Object>>();
        for (var id : List.of("left", "right"))
            parameters.add(
                Map.of("id", id, "name", id, "type", "a", "lifted", true, "coercion", false, "rep", reference));
        var lambda =
            List.of("lam", parameters, body, Map.of("resultRep", longRep, "entryStrict", List.of(false, false)));
        return Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.PointerEquality", "instrument", true,
            "diagnosticUnsupported", diagnostic, "constructors", List.of(), "bindings",
            List.of(Map.of(
                "id", "entry", "name", "entry", "type", "a -> b -> Int#", "lifted", true, "arity", 2, "expr", lambda)));
    }
    private long count(ExecutableProgram program, String key) {
        return ((Number) program.diagnostics().get(key)).longValue();
    }
    private Object invoke(ExecutableProgram program, Object left, Object right) {
        return Calls.target(
            program.hostEntryTarget(2), new Object[] {program.entryValue("entry"), new Object[] {left, right}});
    }
    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        type.getMethod("waitForCompilation").invoke(target);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    @FunctionalInterface
    private interface Action {
        void accept(String backend, ExecutableProgram program, Language language) throws Exception;
    }
    private void eachBackend(List<Object> body, Action action) throws Exception {
        var previous = System.getProperty(CALL_DEMANDS_PROPERTY);
        System.setProperty(CALL_DEMANDS_PROPERTY, "true");
        try {
            try (var context = Main.executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var backend : List.of("ast", "bytecode")) {
                        ExecutableProgram program = backend.equals("ast")
                            ? new Program(language, module(body, false))
                            : new BytecodeProgram(language, module(body, false));
                        action.accept(backend, program, language);
                        assertEquals(0L, count(program, "unsupportedTraps"), backend);
                        assertEquals(0L, count(program, "blackholes"), backend);
                    }
                } finally {
                    context.leave();
                }
            }
        } finally {
            if (previous == null)
                System.clearProperty(CALL_DEMANDS_PROPERTY);
            else
                System.setProperty(CALL_DEMANDS_PROPERTY, previous);
        }
    }
    private Thunk bottom() {
        return new Thunk(new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                throw new RuntimeFault("pointer test bottom entered");
            }
        }.getCallTarget(), null);
    }
    private record EqualPayload(long value) {}
    private record IdentityCase(Object left, Object right, long expected) {}
    private void checkIdentity(
        String backend, ExecutableProgram program, List<IdentityCase> cases, int index, boolean compiled) {
        var row = cases.get(index);
        long before = count(program, "compiledEntries");
        var result = invoke(program, row.left(), row.right());
        assertInstanceOf(Long.class, result, backend + " Int# uses a full-width long");
        assertEquals(row.expected(), result, backend + " case " + index);
        if (compiled)
            assertTrue(
                count(program, "compiledEntries") > before, backend + " case " + index + " enters installed code");
    }
    @Test
    void sharedAndDistinctReferencesIncludeUnevaluatedAndUpdatedThunks() throws Exception {
        eachBackend(pointer(variable("left"), variable("right")), (backend, program, language) -> {
            var layout = new DataLayout(language, "PointerBox", "PointerBox", new String[] {"IntRep"});
            var first = layout.create(new Object[] {3_000_000_017L});
            var equal = layout.create(new Object[] {3_000_000_017L});
            var leftBottom = bottom();
            var rightBottom = bottom();
            var updated = bottom();
            updated.setState(2);
            updated.setValue(first);
            updated.setTarget(null);
            var updatedAlias = bottom();
            updatedAlias.setState(2);
            updatedAlias.setValue(first);
            updatedAlias.setTarget(null);
            var equalObject = new EqualPayload(7);
            var otherEqualObject = new EqualPayload(7);
            assertEquals(equalObject, otherEqualObject);
            var cases = List.of(new IdentityCase(first, first, 1L), new IdentityCase(first, equal, 0L),
                new IdentityCase(equalObject, otherEqualObject, 0L), new IdentityCase(equalObject, equalObject, 1L),
                new IdentityCase(leftBottom, leftBottom, 1L), new IdentityCase(leftBottom, rightBottom, 0L),
                new IdentityCase(leftBottom, first, 0L), new IdentityCase(first, rightBottom, 0L),
                new IdentityCase(updated, first, 0L), new IdentityCase(updated, updated, 1L),
                new IdentityCase(updated, updatedAlias, 0L));
            for (int i = 0; i < 44; i++) checkIdentity(backend, program, cases, i % cases.size(), false);
            compile(program.entryTarget("entry"));
            for (int i = cases.size() - 1; i >= 0; i--) checkIdentity(backend, program, cases, i, true);
            assertEquals(0L, count(program, "thunkEvaluations"), backend);
            assertEquals(0L, count(program, "thunkHits"), backend + " must not follow an updated thunk either");
            assertEquals(0, leftBottom.getState());
            assertEquals(0, rightBottom.getState());
        });
    }
    private void checkArithmetic(
        String backend, ExecutableProgram program, Object left, Object right, boolean equal, boolean compiled) {
        long before = count(program, "compiledEntries");
        assertEquals(equal ? 7_294_967_318L : 3_000_000_007L, invoke(program, left, equal ? left : right), backend);
        if (compiled)
            assertTrue(count(program, "compiledEntries") > before, backend);
    }
    @Test
    void resultFeedsFullWidthArithmeticOnBothIdentityBranches() throws Exception {
        eachBackend(
            arithmetic("+#", arithmetic("*#", pointer(variable("left"), variable("right")), integer(4_294_967_311L)),
                integer(3_000_000_007L)),
            (backend, program, language) -> {
                var left = new Object();
                var right = new Object();
                for (int i = 0; i < 40; i++) checkArithmetic(backend, program, left, right, i % 2 == 0, false);
                compile(program.entryTarget("entry"));
                checkArithmetic(backend, program, left, right, false, true);
                checkArithmetic(backend, program, left, right, true, true);
            });
    }
    private void checkDemand(String backend, ExecutableProgram program, RootCallTarget producer, Object answer,
        boolean shared, boolean compiled) {
        var left = new Thunk(producer, null);
        var right = shared ? left : new Thunk(producer, null);
        long evaluations = count(program, "thunkEvaluations"), hits = count(program, "thunkHits"),
             entered = count(program, "compiledEntries");
        assertEquals(shared ? 10L : 2L, invoke(program, left, right), backend);
        assertEquals(evaluations + (shared ? 1 : 2), count(program, "thunkEvaluations"), backend);
        assertEquals(hits + (shared ? 1 : 0), count(program, "thunkHits"), backend);
        assertSame(answer, left.getValue());
        assertSame(answer, right.getValue());
        if (compiled)
            assertTrue(count(program, "compiledEntries") > entered, backend);
    }
    @Test
    void forcingOneLocalDoesNotFollowASeparateAliasUntilThatAliasIsDemanded() throws Exception {
        var pair = pointer(variable("left"), variable("right"));
        var encoded = arithmetic("+#", arithmetic("*#", variable("before"), integer(8)),
            arithmetic("+#", arithmetic("*#", variable("afterLeft"), integer(4)), arithmetic("*#", pair, integer(2))));
        var body = demand(pair, "before",
            demand(variable("left"), "leftValue",
                demand(pair, "afterLeft", demand(variable("right"), "rightValue", encoded))));
        eachBackend(body, (backend, program, language) -> {
            var answer = new Object();
            var producer = new RootNode(null) {
                @Override
                public Object execute(VirtualFrame frame) {
                    return answer;
                }
            }.getCallTarget();
            for (int i = 0; i < 40; i++) checkDemand(backend, program, producer, answer, i % 2 == 0, false);
            compile(program.entryTarget("entry"));
            checkDemand(backend, program, producer, answer, false, true);
            checkDemand(backend, program, producer, answer, true, true);
        });
    }
    @Test
    void onlyTheChosenFallbackBranchMayEnterABottom() throws Exception {
        List<Object> body = List.of("case", pointer(variable("left"), variable("right")), "same",
            List.of(List.of("lit", List.of("int", "1"), List.of(), integer(3_000_000_007L)),
                Arrays.asList("default", null, List.of(), demand(variable("right"), "value", integer(7)))));
        eachBackend(body, (backend, program, language) -> {
            var untouched = bottom();
            var answer = new Object();
            for (int i = 0; i < 20; i++) {
                assertEquals(3_000_000_007L, invoke(program, untouched, untouched), backend);
                assertEquals(7L, invoke(program, untouched, answer), backend);
            }
            compile(program.entryTarget("entry"));
            long entered = count(program, "compiledEntries");
            assertEquals(3_000_000_007L, invoke(program, untouched, untouched), backend);
            assertEquals(7L, invoke(program, untouched, answer), backend);
            assertTrue(count(program, "compiledEntries") > entered, backend);
            assertEquals(0L, count(program, "thunkEvaluations"), backend);
            assertEquals(0, untouched.getState());
            var demanded = bottom();
            var failure = assertThrows(RuntimeFault.class, () -> invoke(program, answer, demanded));
            assertTrue(
                (failure.getMessage() == null ? "" : failure.getMessage()).contains("pointer test bottom entered"),
                backend);
            assertEquals(1L, count(program, "thunkEvaluations"), backend);
            assertEquals(3, demanded.getState(), backend + " only the selected fallback enters the bottom");
        });
    }
    @Test
    void malformedAritiesRemainLoadErrorsEvenInDiagnosticMode() {
        try (var context = Main.executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var backend : List.of("ast", "bytecode"))
                    for (boolean diagnostic : new boolean[] {false, true})
                        for (int arity : new int[] {0, 1, 3}) {
                            @SuppressWarnings("unchecked") var args = (List<Object>[]) new List<?>[arity];
                            for (int i = 0; i < arity; i++) args[i] = variable("left");
                            var body = pointer(args);
                            var failure = assertThrows(RuntimeFault.class, () -> {
                                if (backend.equals("ast"))
                                    new Program(language, module(body, diagnostic));
                                else
                                    new BytecodeProgram(language, module(body, diagnostic));
                            });
                            assertTrue((failure.getMessage() == null ? "" : failure.getMessage())
                                           .contains("Primitive arity mismatch: reallyUnsafePtrEquality#"),
                                backend + " diagnostic=" + diagnostic + " arity=" + arity + ": "
                                    + failure.getMessage());
                        }
            } finally {
                context.leave();
            }
        }
    }
}
