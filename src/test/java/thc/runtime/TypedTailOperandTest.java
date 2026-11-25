// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class TypedTailOperandTest {
    @Test void joinEntryCaseReleasesOnlyDeadScrutineeFormals() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean live : List.of(false, true)) for (boolean suspend : List.of(false, true)) {
                    var program = new Program(language, joinCaseModule(live), true);
                    var target = program.entryTarget("entry");
                    var selection = NodeUtil.findFirstNodeInstance(target.getRootNode(), Case.class);
                    var scrutinee = Case.class.getDeclaredField("scrutinee");
                    scrutinee.setAccessible(true);
                    var read = NodeUtil.findFirstNodeInstance((LocalBinding) scrutinee.get(selection), LocalRead.class);
                    var caller = new MaterializedFrame[1];
                    read.replace(new Expr() {
                        @Child private Expr child = read;
                        @Override public Object execute(VirtualFrame frame) {
                            caller[0] = frame.materialize();
                            if (suspend) throw new AstCapture(Unit.INSTANCE, MaskingState.UNMASKED);
                            return child.execute(frame);
                        }
                    }.proven(read.getRepresentation()));
                    var marker = new Object();
                    Object answer = Calls.target(target, new Object[]{0L, marker});
                    var frame = caller[0];
                    var descriptor = frame.getFrameDescriptor();
                    int slot = 0;
                    while (slot < descriptor.getNumberOfSlots() && !"formal".equals(descriptor.getSlotName(slot))) slot++;
                    assertTrue(slot < descriptor.getNumberOfSlots());
                    if (suspend) {
                        assertSame(marker, FrameAccess.read(frame, slot));
                        answer = assertInstanceOf(AstContinuation.class, answer).continueWith(marker);
                    }
                    assertSame(marker, answer);
                    if (live) assertSame(marker, FrameAccess.read(frame, slot));
                    else assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot));
                }
            } finally {
                context.leave();
            }
        }
    }

    private static Map<String, Object> joinCaseModule(boolean live) {
        var reference = Map.of("kind", "object", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
        var closure = Map.of("kind", "closure", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
        var input = List.of("var", "input", Map.of("rep", reference));
        var formal = List.of("var", "formal", Map.of("rep", reference));
        var selection = List.of("case", formal, "result",
            List.of(Arrays.asList("default", null, List.of(), live ? formal : input)),
            Map.of("rep", reference, "binder", Map.of("id", "result", "rep", reference)));
        var metadata = Map.of("rep", closure, "resultRep", reference);
        var join = Map.of("id", "join", "name", "join", "joinValueArity", 1, "joinResultRep", reference,
            "lifted", true, "rep", closure,
            "expr", List.of("lam", List.of(Map.of("id", "formal", "lifted", true, "rep", reference)), selection, metadata));
        var call = List.of("app", List.of("var", "join", Map.of("rep", closure)),
            List.of(input), List.of(true), false, false, Map.of("rep", reference));
        var body = List.of("let", false, List.of(join), call, Map.of("rep", reference));
        return Map.of("instrument", true, "constructors", List.of(), "bindings", List.of(
            Map.of("id", "entry", "name", "entry", "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(Map.of("id", "input", "lifted", true, "rep", reference)), body, metadata))));
    }

    @Test void typedCallReleasesInputOnlyWhenLeavingTheFunction() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean insideScrutinee : List.of(false, true)) {
                    var program = new Program(language, module(insideScrutinee), true);
                    var target = program.entryTarget("entry");
                    var operands = NodeUtil.findFirstNodeInstance(target.getRootNode(), AstInputOperands.class);
                    var field = AstInputOperands.class.getDeclaredField("arguments");
                    field.setAccessible(true);
                    var argument = ((Expr[]) field.get(operands))[0];
                    var caller = new MaterializedFrame[1];
                    argument.replace(new Expr() {
                        @Child private Expr child = argument;
                        @Override public Object execute(VirtualFrame frame) {
                            caller[0] = frame.materialize();
                            return child.execute(frame);
                        }
                    }.proven(argument.getRepresentation()));
                    var marker = new Object();
                    assertSame(marker, Calls.target(target, new Object[]{0L, marker}));
                    var descriptor = caller[0].getFrameDescriptor();
                    int slot = 0;
                    while (slot < descriptor.getNumberOfSlots() && !"input".equals(descriptor.getSlotName(slot))) slot++;
                    assertTrue(slot < descriptor.getNumberOfSlots());
                    if (insideScrutinee) assertSame(marker, FrameAccess.read(caller[0], slot));
                    else assertEquals(FrameSlotKind.Illegal.tag, caller[0].getTag(slot));
                }
            } finally {
                context.leave();
            }
        }
    }

    private static Map<String, Object> module(boolean insideScrutinee) {
        var scalar = Map.of("kind", "long", "evaluated", true, "primReps", List.of("IntRep"));
        var reference = Map.of("kind", "object", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
        var closure = Map.of("kind", "closure", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
        var tuple = Map.of("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
            "primReps", List.of("IntRep"), "components", List.of(scalar));
        var literal = List.of("lit", "int", "42", Map.of("rep", scalar));
        var packed = List.of("app", List.of("con", "T", 1), List.of(literal), List.of(false), false, false, Map.of("rep", tuple));
        var input = List.of("var", "input", Map.of("rep", reference));
        List<?> body = List.of("app", List.of("var", "worker", Map.of("rep", closure)),
            List.of(input, packed), List.of(true, false), false, false, Map.of("rep", reference));
        if (insideScrutinee) {
            var join = Map.of("id", "join", "name", "join", "joinValueArity", 0, "joinResultRep", reference,
                "lifted", true, "rep", reference, "expr", body);
            var nested = List.of("let", false, List.of(join), List.of("var", "join", Map.of("rep", reference)),
                Map.of("rep", reference));
            body = List.of("case", nested, "result", List.of(Arrays.asList("default", null, List.of(), input)),
                Map.of("rep", reference, "binder", Map.of("id", "result", "rep", reference)));
        }
        var workerArguments = List.of(Map.of("id", "argument", "lifted", true, "rep", reference),
            Map.of("id", "tuple", "lifted", false, "rep", tuple));
        return Map.of("instrument", true,
            "constructors", List.of(Map.of("id", "T", "name", "T", "kind", "unboxed-tuple", "arity", 1)),
            "bindings", List.of(
                Map.of("id", "worker", "name", "worker", "lifted", true, "rep", closure,
                    "expr", List.of("lam", workerArguments, List.of("var", "argument", Map.of("rep", reference)),
                        Map.of("rep", closure, "resultRep", reference))),
                Map.of("id", "entry", "name", "entry", "lifted", true, "rep", closure,
                    "expr", List.of("lam", List.of(Map.of("id", "input", "lifted", true, "rep", reference)), body,
                        Map.of("rep", closure, "resultRep", reference)))));
    }
}
