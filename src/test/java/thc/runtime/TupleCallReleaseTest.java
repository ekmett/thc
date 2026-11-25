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

class TupleCallReleaseTest {
    @Test void deadDelayCaptureIsReleasedAfterStagingAndLiveJoinCaptureSurvives() throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var arguments = TupleApplication.class.getDeclaredField("arguments");
                arguments.setAccessible(true);
                for (boolean live : List.of(false, true)) for (boolean suspend : List.of(false, true)) {
                    var program = new Program(language, module(live), true);
                    var target = program.entryTarget("entry");
                    var call = NodeUtil.findFirstNodeInstance(target.getRootNode(), TupleApplication.class);
                    var operands = (Expr[]) arguments.get(call);
                    assertInstanceOf(Delay.class, operands[0]);
                    var second = operands[1];
                    var caller = new MaterializedFrame[1];
                    second.replace(new Expr() {
                        @Child private Expr child = second;
                        @Override public Object execute(VirtualFrame frame) {
                            caller[0] = frame.materialize();
                            if (suspend) throw new AstCapture(Unit.INSTANCE, MaskingState.UNMASKED);
                            return child.execute(frame);
                        }
                    }.proven(second.getRepresentation()));
                    var last = operands[2];
                    last.replace(new Expr() {
                        @Child private Expr child = last;
                        @Override public Object execute(VirtualFrame frame) {
                            caller[0] = frame.materialize();
                            return child.execute(frame);
                        }
                    }.proven(last.getRepresentation()));
                    var marker = new Object();
                    Object result = Calls.target(target, new Object[]{0L, marker});
                    var frame = caller[0];
                    var descriptor = frame.getFrameDescriptor();
                    int slot = 0;
                    while (slot < descriptor.getNumberOfSlots() && !"source".equals(descriptor.getSlotName(slot))) slot++;
                    assertTrue(slot < descriptor.getNumberOfSlots());
                    if (suspend) {
                        assertSame(marker, FrameAccess.read(frame, slot));
                        result = assertInstanceOf(AstContinuation.class, result).continueWith(marker);
                    }
                    assertSame(marker, result);
                    if (live) assertSame(marker, FrameAccess.read(caller[0], slot));
                    else assertEquals(FrameSlotKind.Illegal.tag, caller[0].getTag(slot));
                }
            } finally {
                context.leave();
            }
        }
    }

    private static Map<String, Object> module(boolean live) {
        var ref = Map.of("kind", "object", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
        var closure = Map.of("kind", "closure", "evaluated", true, "primReps", List.of("BoxedRep (Just Lifted)"));
        var tuple = Map.of("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple",
            "primReps", List.of("BoxedRep (Just Lifted)"), "components", List.of(ref));
        var input = List.of("var", "input", Map.of("rep", ref));
        var source = List.of("var", "source", Map.of("rep", ref));
        var payload = List.of("var", "payload", Map.of("rep", ref));
        var id = List.of("var", "id", Map.of("rep", closure));
        var worker = List.of("var", "worker", Map.of("rep", closure));
        var delayed = List.of("app", id, List.of(source), List.of(true), false, false, Map.of("rep", ref));
        var call = List.of("app", worker, List.of(delayed, input, input), List.of(true, true, true), false, false, Map.of("rep", tuple));
        var joinCall = List.of("app", List.of("var", "done", Map.of("rep", closure)),
            List.of(payload), List.of(true), false, false, Map.of("rep", ref));
        var selection = List.of("case", call, "result", List.of(List.of("data", "T", List.of("payload"), joinCall)),
            Map.of("rep", ref, "binder", parameter("result", tuple)));
        var join = Map.of("id", "done", "name", "done", "joinValueArity", 1, "joinResultRep", ref, "lifted", true, "rep", closure,
            "expr", List.of("lam", List.of(parameter("out", ref)), live ? source : List.of("var", "out", Map.of("rep", ref)),
                Map.of("rep", closure, "resultRep", ref)));
        var writer = Map.of("id", "write", "name", "write", "joinValueArity", 0, "joinResultRep", ref,
            "lifted", true, "rep", ref, "expr", selection);
        var invokeWriter = List.of("let", false, List.of(writer), List.of("var", "write", Map.of("rep", ref)), Map.of("rep", ref));
        var sourceBinding = Map.of("id", "source", "name", "source", "lifted", true, "rep", ref, "expr", input);
        var body = List.of("let", false, List.of(sourceBinding), List.of("let", false, List.of(join), invokeWriter, Map.of("rep", ref)), Map.of("rep", ref));
        var packed = List.of("app", List.of("con", "T", 1), List.of(List.of("var", "forced", Map.of("rep", ref))),
            List.of(true), false, false, Map.of("rep", tuple));
        var force = List.of("case", List.of("var", "arg", Map.of("rep", ref)), "forced",
            List.of(Arrays.asList("default", null, List.of(), packed)), Map.of("rep", tuple, "binder", parameter("forced", ref)));
        return Map.of("instrument", true, "constructors", List.of(Map.of("id", "T", "name", "T", "kind", "unboxed-tuple", "arity", 1)),
            "bindings", List.of(
                Map.of("id", "entry", "name", "entry", "lifted", true, "rep", closure,
                    "expr", List.of("lam", List.of(parameter("input", ref)), body, Map.of("rep", closure, "resultRep", ref))),
                Map.of("id", "id", "name", "id", "lifted", true, "rep", closure,
                    "expr", List.of("lam", List.of(parameter("x", ref)), List.of("var", "x", Map.of("rep", ref)), Map.of("rep", closure, "resultRep", ref))),
                Map.of("id", "worker", "name", "worker", "lifted", true, "rep", closure,
                    "expr", List.of("lam", List.of(parameter("arg", ref), parameter("ignored", ref), parameter("last", ref)),
                        force, Map.of("rep", closure, "resultRep", tuple)))));
    }
    private static Map<String, Object> parameter(String id, Map<String, ?> rep) {
        return Map.of("id", id, "lifted", true, "rep", rep);
    }
}
