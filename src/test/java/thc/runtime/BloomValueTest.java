// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.MainKt;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

/** Boxing caches preserve every mask bit and the original tail-call decisions. */
class BloomValueTest {
    private static final class BoxRoot extends RootNode {
        @Child private BloomValue value = BloomValueNodeGen.create();
        BoxRoot(Language language) { super(language); }
        @Override public Object execute(VirtualFrame frame) { return value.execute((Long) frame.getArguments()[0]); }
        @Override public boolean isCloningAllowed() { return true; }
    }
    private static final class CloneCaller extends RootNode {
        @Child private DirectCallNode call;
        CloneCaller(RootCallTarget target) { super(null); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, frame.getArguments()); }
        RootCallTarget cloneTarget() {
            getCallTarget(); assertTrue(call.isCallTargetCloningAllowed()); assertTrue(call.cloneCallTarget());
            return (RootCallTarget) call.getClonedCallTarget();
        }
    }
    private static final class Leaf extends GuestRoot {
        Leaf(Language language) { super(language, new FrameLayout().build()); }
        @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0] | mask; }
        @Override public Object execute(VirtualFrame frame) { return frame.getArguments()[0]; }
    }
    private static final class Forward extends GuestRoot {
        @Child private DirectCallerNode caller;
        Forward(Language language, RootCallTarget target, Metrics metrics) { super(language, new FrameLayout().build()); caller = new DirectCallerNode(target, metrics); }
        @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0] | mask; }
        @Override public Object execute(VirtualFrame frame) { return caller.call(frame, new Object[]{null}, true); }
        @Override public boolean isCloningAllowed() { return true; }
    }
    private static final class NonTail extends GuestRoot {
        @Child private DirectCallerNode caller;
        NonTail(Language language, RootCallTarget target, Metrics metrics) { super(language, new FrameLayout().build()); caller = new DirectCallerNode(target, metrics); }
        @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0] | mask; }
        @Override public Object execute(VirtualFrame frame) { return (Long) caller.call(frame, new Object[]{null}, false) + (Long) frame.getArguments()[1]; }
    }
    private static final class CatchBounce extends RootNode {
        @Child private DirectCallNode call;
        @Child private TailCallLoop loop;
        CatchBounce(Language language, RootCallTarget target, Metrics metrics) { super(language); call = DirectCallNode.create(target); loop = new TailCallLoop(metrics); }
        @Override public Object execute(VirtualFrame frame) {
            // Deliberately saturated bloom: a legitimate false positive must be safe.
            Object result;
            try { result = Calls.direct(call, new Object[]{-1L}); }
            catch (TailCall tail) { result = loop.execute(tail); }
            return (Long) result + (Long) frame.getArguments()[0];
        }
    }
    private record Chain(Forward a, Forward b, Forward c, Leaf leaf, Metrics metrics) {
        long ancestry() { return a.mask | b.mask | c.mask; }
    }
    private Chain chain(Language language) {
        for (int i = 0; i < 64; i++) {
            var metrics = new Metrics(true); var leaf = new Leaf(language);
            var c = new Forward(language, leaf.getCallTarget(), metrics); var b = new Forward(language, c.getCallTarget(), metrics); var a = new Forward(language, b.getCallTarget(), metrics);
            long seen = 0L; boolean fresh = true;
            for (var root : new GuestRoot[]{a, b, c, leaf}) {
                boolean next = (seen & root.mask) != root.mask; seen |= root.mask;
                if (!next) { fresh = false; break; }
            }
            if (fresh) return new Chain(a, b, c, leaf, metrics);
        }
        throw new IllegalStateException("Could not construct a collision-free test chain");
    }
    private void entered(CheckedConsumer<Language> action) throws Exception {
        try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private Object invoke(RootNode root, long mask) { return Calls.target(root.getCallTarget(), new Object[]{mask}); }
    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    @Test void threeWideMasksReuseBoxesAndMegamorphicFallbackPreservesEveryBit() throws Exception {
        entered(language -> {
            var root = new BoxRoot(language); long[] masks = {Long.MIN_VALUE + 3, Long.MAX_VALUE - 17, 0x2000_0000_0000_0042L};
            Object[] boxes = {invoke(root, masks[0]), invoke(root, masks[1]), invoke(root, masks[2])};
            for (int repeat = 0; repeat < 20; repeat++) for (int i = 0; i < masks.length; i++) {
                assertEquals(masks[i], invoke(root, masks[i])); assertSame(boxes[i], invoke(root, masks[i]), "A hot mask must return its existing Object box");
            }
            compile(root.getCallTarget());
            for (int i = 0; i < masks.length; i++) assertSame(boxes[i], invoke(root, masks[i]));
            // The fourth key replaces the bounded cache without changing the Object ABI.
            long[] extra = {0x4000_0000_0000_0081L, -1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE};
            for (long mask : extra) assertEquals(mask, invoke(root, mask));
            for (long mask : masks) assertEquals(mask, invoke(root, mask));
            compile(root.getCallTarget());
            for (long mask : masks) assertEquals(mask, invoke(root, mask));
            for (int i = extra.length - 1; i >= 0; i--) assertEquals(extra[i], invoke(root, extra[i]));
        });
    }
    @Test void specializingAnActualCloneDoesNotWidenTheOriginalCache() throws Exception {
        entered(language -> {
            var root = new BoxRoot(language); long first = Long.MIN_VALUE + 19, second = Long.MAX_VALUE - 23;
            var firstBox = invoke(root, first); var secondBox = invoke(root, second); var caller = new CloneCaller(root.getCallTarget()); var clone = caller.cloneTarget();
            assertNotSame(root.getCallTarget(), clone); assertNotSame(root, clone.getRootNode());
            var cloneFirst = invoke(caller, first); assertEquals(first, cloneFirst);
            assertSame(cloneFirst, invoke(caller, first), "The clone must use its own valid cached specialization"); assertEquals(second, invoke(caller, second));
            for (long mask : new long[]{0x4000_0000_0000_0011L, 0x2000_0000_0000_0022L, 0L, -1L}) assertEquals(mask, invoke(caller, mask));
            assertSame(firstBox, invoke(root, first), "The clone's generic transition must not change the original node"); assertSame(secondBox, invoke(root, second));
            compile(root.getCallTarget()); compile(clone); assertSame(firstBox, invoke(root, first)); assertEquals(second, invoke(caller, second));
        });
    }
    @Test void actualTailChainsReuseTheirHeaderAndNonTailCallsResetAncestry() throws Exception {
        entered(language -> {
            var chain = chain(language); var first = invoke(chain.a(), 0L); assertEquals(chain.ancestry(), first);
            for (int i = 0; i < 30; i++) assertSame(first, invoke(chain.a(), 0L));
            assertEquals(0L, chain.metrics().getTailBounces()); compile(chain.a().getCallTarget()); assertSame(first, invoke(chain.a(), 0L));
            var nonTail = new NonTail(language, chain.a().getCallTarget(), chain.metrics());
            for (int i = 0; i < 20; i++) assertEquals(chain.ancestry() + i, Calls.target(nonTail.getCallTarget(), new Object[]{-1L, (long) i}));
            compile(nonTail.getCallTarget()); assertEquals(chain.ancestry() + Long.MAX_VALUE, Calls.target(nonTail.getCallTarget(), new Object[]{-1L, Long.MAX_VALUE}));
            assertEquals(0L, chain.metrics().getTailBounces(), "A non-tail call must clear the supplied saturated ancestry");
        });
    }
    @Test void saturatedBloomCollisionStillBouncesAndPreservesPendingWork() throws Exception {
        entered(language -> {
            var chain = chain(language); var caller = new CatchBounce(language, chain.a().getCallTarget(), chain.metrics());
            // A bounces to B; the trampoline restarts B with zero ancestry.
            long expected = chain.b().mask | chain.c().mask;
            for (int i = 0; i < 30; i++) assertEquals(expected + i, invoke(caller, i));
            assertEquals(30L, chain.metrics().getTailBounces()); assertEquals(30L, chain.metrics().getTrampolineIterations()); compile(caller.getCallTarget());
            for (long offset : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_019L}) assertEquals(expected + offset, invoke(caller, offset));
            assertEquals(33L, chain.metrics().getTailBounces()); assertEquals(33L, chain.metrics().getTrampolineIterations());
        });
    }
}
