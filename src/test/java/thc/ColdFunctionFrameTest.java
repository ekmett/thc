// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class ColdFunctionFrameTest {
    private record Fixture(FunctionRoot root, int slot) {}
    private CoreRepresentation proof(CoreKind kind, boolean evaluated, boolean present) {
        return new CoreRepresentation(kind, evaluated, present, null, null, null, null, null, null);
    }
    private Fixture root(CoreRepresentation proof) { return root(proof, false, FrameSlotKind.Illegal); }
    private Fixture root(CoreRepresentation proof, boolean asyncStrict, FrameSlotKind initial) {
        var layout = new FrameLayout(); int argument = layout.bind("argument"); var descriptor = layout.build();
        descriptor.setSlotKind(argument, initial);
        var body = new Expr() { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Preparation must not execute the body"); } };
        return new Fixture(new FunctionRoot(null, descriptor, "cold frame", null, new int[0], new int[]{argument},
            new int[]{0}, body, new Metrics(true), new CoreRepresentation[]{proof}, body.getRepresentation(),
            body.getCoreSourceLocation(), new boolean[]{asyncStrict}, null, null, new int[0], null, asyncStrict,
            new int[0][], false, FunctionRootRole.FUNCTION, false), argument);
    }
    @Test void exactArgumentCarriersAreEstablishedBeforeTargetPublicationWithoutExecutingCode() {
        var kinds = new CoreKind[]{CoreKind.LONG, CoreKind.FLOAT, CoreKind.DOUBLE, CoreKind.DATA, CoreKind.CLOSURE, CoreKind.ADDRESS};
        var slots = new FrameSlotKind[]{FrameSlotKind.Long, FrameSlotKind.Float, FrameSlotKind.Double, FrameSlotKind.Object, FrameSlotKind.Object, FrameSlotKind.Object};
        for (int i = 0; i < kinds.length; i++) {
            var fixture = root(proof(kinds[i], true, true));
            assertEquals(slots[i], fixture.root.getFrameDescriptor().getSlotKind(fixture.slot));
            var target = fixture.root.getCallTarget(); assertSame(fixture.root, target.getRootNode());
            assertEquals(slots[i], target.getRootNode().getFrameDescriptor().getSlotKind(fixture.slot));
        }
    }
    @Test void unknownLazyAndAsynchronouslyForcedInputsRetainDynamicStorage() {
        for (var proof : List.of(CoreRepresentation.Companion.getUNKNOWN(), proof(CoreKind.DATA, false, false), proof(CoreKind.OBJECT, false, false))) {
            var fixture = root(proof); assertEquals(FrameSlotKind.Illegal, fixture.root.getFrameDescriptor().getSlotKind(fixture.slot));
        }
        var fixture = root(proof(CoreKind.LONG, true, false), true, FrameSlotKind.Illegal);
        assertEquals(FrameSlotKind.Illegal, fixture.root.getFrameDescriptor().getSlotKind(fixture.slot));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L, new Object()}, fixture.root.getFrameDescriptor());
        fixture.root.buildFrame(frame.getArguments(), frame);
        assertSame(frame.getArguments()[1], FrameAccess.read(frame, fixture.slot));
    }
    @Test void preparedScalarSlotsStillWidenMonotonicallyAndDoNotNarrowExistingObjectSlots() {
        var proof = proof(CoreKind.LONG, true, true); var fixture = root(proof);
        var first = Truffle.getRuntime().createVirtualFrame(new Object[]{0L, Long.MIN_VALUE}, fixture.root.getFrameDescriptor());
        fixture.root.buildFrame(first.getArguments(), first);
        var other = Truffle.getRuntime().createVirtualFrame(new Object[0], fixture.root.getFrameDescriptor());
        var marker = new Object(); FrameAccess.writeObject(other, fixture.slot, marker);
        assertEquals(Long.MIN_VALUE, FrameAccess.read(first, fixture.slot));
        fixture.root.buildFrame(new Object[]{0L, Long.MAX_VALUE}, first);
        assertEquals(FrameSlotKind.Object, fixture.root.getFrameDescriptor().getSlotKind(fixture.slot));
        assertEquals(Long.MAX_VALUE, FrameAccess.read(first, fixture.slot)); assertSame(marker, FrameAccess.read(other, fixture.slot));
        var wide = root(proof, false, FrameSlotKind.Object);
        assertEquals(FrameSlotKind.Object, wide.root.getFrameDescriptor().getSlotKind(wide.slot));
    }
    @Test void denseSnapshotKindsComeFromTheActualPacketLayoutBeforeAnyLoan() {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout = new FrameLayout(); int[] slots = {layout.bind("snapshot 0"), layout.bind("snapshot 1")};
                int destination = layout.bind("destination");
                var packet = new HandoffLayout(language, 0, List.of("long", "reference"));
                var entry = new HandoffEntry(language, packet, true, slots, destination);
                var body = new Expr() { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("No guest entry during preparation"); } };
                var root = new FunctionRoot(language, layout.build(), "dense cold frame", null, new int[0], new int[0], new int[0],
                    body, new Metrics(true), new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(),
                    new boolean[0], entry, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
                assertEquals(FrameSlotKind.Long, root.getFrameDescriptor().getSlotKind(slots[0]));
                assertEquals(FrameSlotKind.Object, root.getFrameDescriptor().getSlotKind(slots[1]));
                assertEquals(FrameSlotKind.Long, root.getFrameDescriptor().getSlotKind(destination));
                assertEquals(0L, entry.state().getCalls()); assertEquals(0, entry.state().getArguments().getDepth());
            } finally { context.leave(); }
        }
    }
}
