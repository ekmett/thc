// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

class ScalarTupleTemporaryTest {
    @Test void genericSelfTransferRetainsItsWrongPrimitiveCarrierCheck() {
        var builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Long, "formal", null);
        builder.addSlot(FrameSlotKind.Int, "temporary", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        frame.setInt(1, 7);
        var proof = new CoreRepresentation(CoreKind.LONG, true, true, java.util.List.of("IntRep"));
        var layout = new AstSelfLayout(null, new int[0], new int[]{0}, new CoreRepresentation[]{proof}, new boolean[]{false});
        assertEquals("Expected primitive Long argument", assertThrows(RuntimeFault.class,
            () -> layout.transfer(frame, null, new int[]{1})).getMessage());
        assertThrows(UnexpectedResultException.class, () -> new LocalRead(1, false).proven(proof).executeLong(frame));
    }

    @Test void firstInstalledTransferKeepsScalarValuesWithAWidenedScratchDescriptor() throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var builder = FrameDescriptor.newBuilder();
                int temporary = builder.addSlot(FrameSlotKind.Object, "temporary", null);
                int formal = builder.addSlot(FrameSlotKind.Object, "formal", null);
                var proof = new CoreRepresentation(CoreKind.LONG, true, true, java.util.List.of("IntRep"));
                var layout = new AstSelfLayout(null, new int[0], new int[]{formal}, new CoreRepresentation[]{proof}, new boolean[]{false});
                class Transfer extends RootNode {
                    boolean compiled;
                    final int[] temporaries = {temporary};
                    Transfer() { super(null, builder.build()); }
                    @Override public Object execute(VirtualFrame frame) {
                        long value = (long) frame.getArguments()[0];
                        frame.setLong(temporary, value);
                        try { throw layout.transferLongArguments(frame, null, temporaries); }
                        catch (AstSelfCall transfer) {
                            compiled = CompilerDirectives.inCompiledCode();
                            return frame.getObject(formal);
                        }
                    }
                }
                var root = new Transfer(); var target = root.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(Long.MIN_VALUE, target.call(Long.MIN_VALUE));
                assertTrue(root.compiled, "The first transfer must execute installed code");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
}
