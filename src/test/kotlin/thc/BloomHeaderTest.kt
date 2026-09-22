package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

class BloomHeaderTest {
    private class Probe(val metrics: Metrics) : Expr() {
        @Child private var tailCheck = TailCheck(metrics)
        lateinit var target: RootCallTarget
        val untouchedHeader = Any()
        override fun execute(frame: VirtualFrame): Any? {
            val packet = arrayOf<Any?>(untouchedHeader)
            return try {
                tailCheck.check(frame, target, packet)
                packet[0]
            } catch (tail: TailCall) {
                // Observe the unchanged bounce payload before FunctionRoot's
                // existing self-tail handler consumes it.
                tail
            }
        }
    }

    private fun root(body: Expr, metrics: Metrics): FunctionRoot = FunctionRoot(
        null, FrameLayout().build(), "bloom test", null,
        intArrayOf(), intArrayOf(), intArrayOf(), body, metrics)

    private fun run(source: FunctionRoot, incoming: Long): Any? =
        Calls.target(source.callTarget, arrayOf(incoming))

    private fun nonCollidingTarget(source: FunctionRoot, metrics: Metrics): FunctionRoot {
        // Root masks are deliberately identity-derived. Select a fixture whose
        // bits are not already covered by the source; do not change production
        // masks or depend on any particular JVM identity hash.
        repeat(128) {
            val target = root(object : Expr() {
                override fun execute(frame: VirtualFrame): Any = Unit
            }, metrics)
            if (source.mask and target.mask != target.mask) return target
        }
        error("Could not construct a non-colliding test target")
    }

    @Test fun ownMaskReusesAnImmutableHeaderAcrossFreshCallPackets() {
        val metrics = Metrics(true)
        val probe = Probe(metrics)
        val source = root(probe, metrics)
        probe.target = nonCollidingTarget(source, metrics).callTarget
        val first = run(source, 0L)
        assertEquals(source.mask, first)
        assertSame(source.boxedMask, first)
        repeat(8) { assertSame(first, run(source, 0L)) }
        assertEquals(0L, metrics.tailBounces)
    }

    @Test fun extraAncestryUsesTheExactUncachedMaskThenOwnMaskStillReusesItsHeader() {
        val metrics = Metrics(true)
        val probe = Probe(metrics)
        val source = root(probe, metrics)
        val target = nonCollidingTarget(source, metrics)
        probe.target = target.callTarget
        val available = (source.mask or target.mask).inv()
        val bitA = java.lang.Long.lowestOneBit(available)
        val bitB = java.lang.Long.lowestOneBit(available xor bitA)
        assertNotEquals(0L, bitA)
        assertNotEquals(0L, bitB)
        for (extra in listOf(bitA, bitB, bitA or bitB, bitA)) {
            val header = run(source, extra)
            assertEquals(source.mask or extra, header)
            assertNotSame(source.boxedMask, header)
        }
        assertSame(source.boxedMask, run(source, 0L))
        assertEquals(0L, metrics.tailBounces)
    }

    @Test fun selfAndConservativeBloomHitsStillBounceBeforeWritingAnyHeader() {
        val metrics = Metrics(true)
        val probe = Probe(metrics)
        val source = root(probe, metrics)
        probe.target = source.callTarget
        val self = run(source, 0L) as TailCall
        assertSame(source.callTarget, self.target)
        assertSame(probe.untouchedHeader, self.args[0])

        val other = nonCollidingTarget(source, metrics)
        probe.target = other.callTarget
        // An ancestry bitset covering another target must conservatively bounce
        // even though target identity differs from the executing root.
        val collision = run(source, other.mask) as TailCall
        assertSame(other.callTarget, collision.target)
        assertSame(probe.untouchedHeader, collision.args[0])
        assertEquals(2L, metrics.tailBounces)
    }
}
