// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class BloomHeaderTest {
    private static final class Probe extends Expr {
        @Child private TailCheck tailCheck;
        RootCallTarget target;
        final Object untouchedHeader = new Object();
        Probe(Metrics metrics) { tailCheck = new TailCheck(metrics); }
        @Override public Object execute(VirtualFrame frame) {
            Object[] packet = {untouchedHeader};
            try { tailCheck.check(frame, target, packet); return packet[0]; }
            catch (TailCall tail) { return tail; }
        }
    }
    private FunctionRoot root(Expr body, Metrics metrics) {
        return new FunctionRoot(null, new FrameLayout().build(), "bloom test", null, new int[0], new int[0], new int[0],
            body, metrics, new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(),
            new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
    }
    private Object run(FunctionRoot source, long incoming) { return Calls.target(source.getCallTarget(), new Object[]{incoming}); }
    private FunctionRoot nonCollidingTarget(FunctionRoot source, Metrics metrics) {
        // Select identity-derived masks without changing production or assuming an identity hash.
        for (int i = 0; i < 128; i++) {
            var target = root(new Expr() { @Override public Object execute(VirtualFrame frame) { return thc.runtime.Unit.INSTANCE; } }, metrics);
            if ((source.mask & target.mask) != target.mask) return target;
        }
        throw new IllegalStateException("Could not construct a non-colliding test target");
    }
    @Test void compiledDynamicAncestryPreservesEveryBitAndStillDetectsBloomHits() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var metrics = new Metrics(true); var probe = new Probe(metrics); var source = root(probe, metrics);
                var target = nonCollidingTarget(source, metrics); probe.target = target.getCallTarget();
                long available = ~(source.mask | target.mask), bitA = Long.lowestOneBit(available), bitB = Long.lowestOneBit(available ^ bitA);
                long[] ancestry = {0L, bitA, bitB, bitA | bitB};
                for (int i = 0; i < 40; i++) { long incoming = ancestry[i % ancestry.length]; assertEquals(source.mask | incoming, run(source, incoming)); }
                var optimized = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                optimized.getMethod("compile", boolean.class).invoke(source.getCallTarget(), true);
                assertEquals(true, optimized.getMethod("isValidLastTier").invoke(source.getCallTarget()));
                long before = metrics.getCompiledEntries();
                for (int i = ancestry.length - 1; i >= 0; i--) assertEquals(source.mask | ancestry[i], run(source, ancestry[i]));
                assertTrue(metrics.getCompiledEntries() > before);
                var collision = (TailCall) run(source, target.mask | bitA);
                assertSame(target.getCallTarget(), collision.getTarget()); assertSame(probe.untouchedHeader, collision.getArgs()[0]);
                assertEquals(1L, metrics.getTailBounces());
            } finally { context.leave(); }
        }
    }
    @Test void selfAndConservativeBloomHitsStillBounceBeforeWritingAnyHeader() {
        var metrics = new Metrics(true); var probe = new Probe(metrics); var source = root(probe, metrics);
        probe.target = source.getCallTarget(); var self = (TailCall) run(source, 0L);
        assertSame(source.getCallTarget(), self.getTarget()); assertSame(probe.untouchedHeader, self.getArgs()[0]);
        var other = nonCollidingTarget(source, metrics); probe.target = other.getCallTarget();
        var collision = (TailCall) run(source, other.mask);
        assertSame(other.getCallTarget(), collision.getTarget()); assertSame(probe.untouchedHeader, collision.getArgs()[0]);
        assertEquals(2L, metrics.getTailBounces());
    }
}
