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
    @Test void typedTemporaryKeepsPrimitiveAndBoxedLongAfterDescriptorWidening() {
        var builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Long, "temporary", null);
        var descriptor = builder.build();
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        FrameAccess.writeObject(frame, 0, Long.MIN_VALUE);
        assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
        assertEquals(Long.MIN_VALUE, AstSelfLayout.readLongArgument(frame, 0));
        frame.setLong(0, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, AstSelfLayout.readLongArgument(frame, 0));
        FrameAccess.writeObject(frame, 0, "wrong");
        assertEquals("Expected primitive Long argument", assertThrows(RuntimeFault.class,
            () -> AstSelfLayout.readLongArgument(frame, 0)).getMessage());
    }

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

    @Test void firstInstalledTemporaryReadHandlesBothActivationTagsWithoutWarmup() throws Exception {
        try (var context = executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var builder = FrameDescriptor.newBuilder();
                builder.addSlot(FrameSlotKind.Object, "temporary", null);
                class Reader extends RootNode {
                    boolean compiled;
                    Reader() { super(null, builder.build()); }
                    @Override public Object execute(VirtualFrame frame) {
                        compiled = CompilerDirectives.inCompiledCode();
                        long value = (long) frame.getArguments()[0];
                        if ((boolean) frame.getArguments()[1]) frame.setLong(0, value);
                        else frame.setObject(0, value);
                        return AstSelfLayout.readLongArgument(frame, 0);
                    }
                }
                var root = new Reader(); var target = root.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                target.getClass().getMethod("waitForCompilation").invoke(target);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(Long.MIN_VALUE, target.call(Long.MIN_VALUE, false));
                assertTrue(root.compiled, "The first invocation must execute installed code");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                root.compiled = false;
                assertEquals(Long.MAX_VALUE, target.call(Long.MAX_VALUE, true));
                assertTrue(root.compiled);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
}
