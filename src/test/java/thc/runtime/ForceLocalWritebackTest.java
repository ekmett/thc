// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class ForceLocalWritebackTest {
    private static <T> T single(List<T> values) {
        if (values.isEmpty()) throw new java.util.NoSuchElementException("List is empty.");
        if (values.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    private void withLanguage(Consumer<Language> action) {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private RootCallTarget target(Language language, Object binding, boolean resumed) {
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            var local = b.createLocal("forced binding", null);
            b.beginBlock();
            b.beginStoreLocal(local); b.emitLoadConstant(binding); b.endStoreLocal();
            b.beginReturn(); b.beginBlock();
            if (resumed) {
                var child = (Thunk) binding;
                b.beginResumeForcedLocal(local, false);
                b.emitLoadConstant(new ThunkSuspended(child));
                b.emitLoadArgument(1);
                b.endResumeForcedLocal();
            } else {
                b.beginForceLocal(new Metrics(true), local, false, false);
                b.emitLoadLocal(local); b.endForceLocal();
            }
            b.emitLoadLocal(local); b.endBlock(); b.endReturn(); b.endBlock(); b.endRoot();
        }).getNode(0).getCallTarget();
    }
    private void checkIdentity(boolean resumed) {
        withLanguage(language -> {
            for (Object value : List.of(1_000_000L, 1.25f, 2.5, true, new Object())) {
                int[] evaluations = {0};
                var body = new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { evaluations[0]++; return value; }
                }.getCallTarget();
                var child = new Thunk(body, null);
                if (resumed) { child.setValue(value); child.setState(2); }
                var caller = target(language, child, resumed);
                for (int repeat = 0; repeat < 20; repeat++) {
                    // Each caller consumes its own completion carrier; the thunk's
                    // memoized result remains shared across all these invocations.
                    Object completion = resumed ? new ChildResume(value, null) : Unit.INSTANCE;
                    assertSame(value, Calls.target(caller, new Object[]{0L, completion}), "A thunk's object local must reuse its published result");
                    assertSame(value, child.getValue()); assertEquals(2, child.getState());
                }
                assertEquals(resumed ? 0 : 1, evaluations[0], "Writeback must not replay the thunk");
                var root = (BytecodeRootNode) caller.getRootNode();
                assertEquals(FrameSlotKind.Object, single(root.getBytecodeNode().getLocals()).getTypeProfile());
            }
        });
    }
    @Test void ordinaryForceReusesTheBoxedThunkResult() { checkIdentity(false); }
    @Test void resumedForceReusesTheBoxedThunkResult() { checkIdentity(true); }
    @Test void alreadyScalarBindingsKeepTheirPrimitiveSlots() {
        withLanguage(language -> {
            record Row(Object value, FrameSlotKind tag) {}
            for (var row : List.of(new Row(1_000_000L, FrameSlotKind.Long), new Row(1.25f, FrameSlotKind.Float),
                new Row(2.5, FrameSlotKind.Double), new Row(true, FrameSlotKind.Boolean))) {
                var caller = target(language, row.value, false);
                for (int repeat = 0; repeat < 20; repeat++) assertEquals(row.value, Calls.target(caller, new Object[]{0L}));
                var root = (BytecodeRootNode) caller.getRootNode();
                assertEquals(row.tag, single(root.getBytecodeNode().getLocals()).getTypeProfile());
            }
        });
    }
}
