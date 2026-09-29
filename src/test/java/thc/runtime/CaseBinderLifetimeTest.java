// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** Observe actual Core case lowering, including its first compiled arm entry. */
class CaseBinderLifetimeTest {
    private static final Map<String, Object> LONG = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private static List<Object> node(Object... values) { return Arrays.asList(values); }
    private static Map<String, Object> binder(String id) { return Map.of("id", id, "name", id, "lifted", false, "rep", LONG); }
    private static List<Object> variable(String id) { return node("var", id, Map.of("rep", LONG)); }
    private static Map<String, Object> module(boolean used, boolean defaults) {
        var body = used ? variable("seen") : node("lit", "int", "42", Map.of("rep", LONG));
        var fallback = node("default", null, List.of(), body);
        var alternatives = defaults ? List.of(fallback) : List.of(node("lit", node("int", "0"), List.of(), body));
        var expression = node("case", variable("input"), "seen", alternatives,
                Map.of("rep", LONG, "binder", binder("seen")));
        return Map.of("bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true,
                "rep", CLOSURE, "expr", node("lam", List.of(binder("input")), expression,
                        Map.of("rep", CLOSURE, "resultRep", LONG)))));
    }
    private static final class Observe extends Expr {
        @Child private Expr body;
        private final int slot;
        private final int[] observations;
        Observe(Expr body, int slot, int[] observations) {
            this.body = body; this.slot = slot; this.observations = observations;
            setRepresentation(body.getRepresentation());
        }
        @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
        @Override public long executeLong(VirtualFrame frame) {
            observations[0]++;
            if (frame.getTag(slot) == FrameSlotKind.Illegal.tag) observations[1]++;
            if (CompilerDirectives.inCompiledCode()) observations[2]++;
            return body.executeRequiredLong(frame);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unusedBinderIsReleasedBeforeTheSelectedCompiledArm(boolean defaults) throws Exception { check(false, defaults); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void usedBinderRemainsAvailableToTheSelectedCompiledArm(boolean defaults) throws Exception { check(true, defaults); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resumedScrutineeCompletesItsWriteAndSelectionWithoutReplay(boolean used) {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(used, true), true);
                var target = program.entryTarget("entry");
                var selection = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class).getFirst();
                int[] entries = new int[1], observations = new int[3];
                var scrutinee = NodeUtil.findAllNodeInstances(selection, Evaluate.class).getFirst();
                Expr interrupted = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        entries[0]++;
                        throw new AstCapture(Unit.INSTANCE, MaskingState.MASKED_INTERRUPTIBLE);
                    }
                };
                interrupted.setRepresentation(scrutinee.getRepresentation());
                scrutinee.replace(interrupted);
                Expr body = selection.alternatives[0].getBody();
                body.replace(new Observe(body, selection.binderSlot, observations));
                var saved = assertInstanceOf(AstContinuation.class,
                        ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, 7L}));
                assertEquals(1, entries[0]);
                assertEquals(0, observations[0]);
                assertEquals(used ? 7L : 42L, saved.continueWith(7L));
                assertEquals(1, entries[0], "The interrupted scrutinee cannot replay");
                assertEquals(1, observations[0]);
                assertEquals(used ? 0 : 1, observations[1]);
                assertSame(target, program.entryTarget("entry"));
                assertThrows(RuntimeFault.class, () -> saved.continueWith(7L));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()));
            } finally { context.leave(); }
        }
    }

    private static void check(boolean used, boolean defaults) throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, module(used, defaults), false);
                var target = program.entryTarget("entry");
                var cases = NodeUtil.findAllNodeInstances(target.getRootNode(), Case.class);
                assertEquals(1, cases.size());
                var selection = cases.getFirst();
                int[] observations = new int[3];
                for (Alternative arm : selection.alternatives) {
                    Expr body = arm.getBody();
                    body.replace(new Observe(body, selection.binderSlot, observations));
                }
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                for (long input : new long[]{0L, defaults ? 7L : 0L})
                    assertEquals(used ? input : 42L, ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, input}));
                assertEquals(2, observations[0]);
                assertEquals(used ? 0 : 2, observations[1], "Only a binder unused by every arm may be released");
                assertEquals(2, observations[2], "Both original entries must execute installed code without training");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertSame(target, program.entryTarget("entry"));
            } finally { context.leave(); }
        }
    }
}
