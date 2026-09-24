// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

/** Boxing caches must preserve all mask bits and never change tail-call decisions. */
class BloomValueTest {
    private class BoxRoot(language: Language) : RootNode(language) {
        @Child private var value: BloomValue = BloomValueNodeGen.create()
        override fun execute(frame: VirtualFrame): Any = value.execute(frame.arguments[0] as Long)
        override fun isCloningAllowed(): Boolean = true
    }

    private class CloneCaller(target: RootCallTarget) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, frame.arguments)
        fun cloneTarget(): RootCallTarget {
            callTarget
            assertTrue(call.isCallTargetCloningAllowed)
            assertTrue(call.cloneCallTarget())
            return call.clonedCallTarget as RootCallTarget
        }
    }

    private class Leaf(language: Language) : GuestRoot(language, FrameLayout().build()) {
        override fun bloom(frame: VirtualFrame): Long = (frame.arguments[0] as Long) or mask
        override fun execute(frame: VirtualFrame): Any? = frame.arguments[0]
    }

    private class Forward(language: Language, target: RootCallTarget, metrics: Metrics) :
        GuestRoot(language, FrameLayout().build()) {
        @Child private var caller = DirectCallerNode(target, metrics)
        override fun bloom(frame: VirtualFrame): Long = (frame.arguments[0] as Long) or mask
        override fun execute(frame: VirtualFrame): Any? = caller.call(frame, arrayOf(null), true)
        override fun isCloningAllowed(): Boolean = true
    }

    private class NonTail(language: Language, target: RootCallTarget, metrics: Metrics) :
        GuestRoot(language, FrameLayout().build()) {
        @Child private var caller = DirectCallerNode(target, metrics)
        override fun bloom(frame: VirtualFrame): Long = (frame.arguments[0] as Long) or mask
        override fun execute(frame: VirtualFrame): Any =
            (caller.call(frame, arrayOf(null), false) as Long) + (frame.arguments[1] as Long)
    }

    private class CatchBounce(language: Language, target: RootCallTarget, metrics: Metrics) : RootNode(language) {
        @Child private var call = DirectCallNode.create(target)
        @Child private var loop = TailCallLoop(metrics)
        override fun execute(frame: VirtualFrame): Any {
            // Deliberately saturated bloom: a legitimate false positive must be safe.
            val result = try { Calls.direct(call, arrayOf(-1L)) }
            catch (tail: TailCall) { loop.execute(tail) }
            return (result as Long) + (frame.arguments[0] as Long)
        }
    }

    private data class Chain(val a: Forward, val b: Forward, val c: Forward, val leaf: Leaf,
                             val metrics: Metrics) {
        val ancestry: Long get() = a.mask or b.mask or c.mask
    }

    private fun chain(language: Language): Chain {
        repeat(64) {
            val metrics = Metrics(true)
            val leaf = Leaf(language)
            val c = Forward(language, leaf.callTarget, metrics)
            val b = Forward(language, c.callTarget, metrics)
            val a = Forward(language, b.callTarget, metrics)
            var seen = 0L
            val fresh = listOf(a, b, c, leaf).all { root ->
                val new = seen and root.mask != root.mask
                seen = seen or root.mask
                new
            }
            if (fresh) return Chain(a, b, c, leaf, metrics)
        }
        error("Could not construct a collision-free test chain")
    }

    private fun entered(action: (Language) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }

    private fun invoke(root: RootNode, mask: Long): Any? = Calls.target(root.callTarget, arrayOf(mask))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun threeWideMasksReuseBoxesAndMegamorphicFallbackPreservesEveryBit() = entered { language ->
        val root = BoxRoot(language)
        val masks = listOf(Long.MIN_VALUE + 3, Long.MAX_VALUE - 17, 0x2000_0000_0000_0042L)
        val boxes = masks.map { invoke(root, it) }
        repeat(20) { masks.forEachIndexed { i, mask ->
            assertEquals(mask, invoke(root, mask))
            assertSame(boxes[i], invoke(root, mask), "A hot mask must return its existing Object box")
        } }
        compile(root.callTarget)
        masks.forEachIndexed { i, mask -> assertSame(boxes[i], invoke(root, mask)) }
        // The fourth key replaces the bounded cache. Thereafter every value,
        // including old keys, still arrives unchanged at the Object ABI.
        val extra = listOf(0x4000_0000_0000_0081L, -1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (mask in extra + masks) assertEquals(mask, invoke(root, mask))
        compile(root.callTarget)
        for (mask in masks + extra.reversed()) assertEquals(mask, invoke(root, mask))
    }

    @Test fun specializingAnActualCloneDoesNotWidenTheOriginalCache() = entered { language ->
        val root = BoxRoot(language)
        val first = Long.MIN_VALUE + 19
        val second = Long.MAX_VALUE - 23
        val firstBox = invoke(root, first)
        val secondBox = invoke(root, second)
        val caller = CloneCaller(root.callTarget)
        val clone = caller.cloneTarget()
        assertNotSame(root.callTarget, clone)
        assertNotSame(root, clone.rootNode)
        val cloneFirst = invoke(caller, first)
        assertEquals(first, cloneFirst)
        assertSame(cloneFirst, invoke(caller, first), "The clone must use its own valid cached specialization")
        assertEquals(second, invoke(caller, second))
        for (mask in listOf(0x4000_0000_0000_0011L, 0x2000_0000_0000_0022L, 0L, -1L))
            assertEquals(mask, invoke(caller, mask))
        assertSame(firstBox, invoke(root, first), "The clone's generic transition must not change the original node")
        assertSame(secondBox, invoke(root, second))
        compile(root.callTarget); compile(clone)
        assertSame(firstBox, invoke(root, first))
        assertEquals(second, invoke(caller, second))
    }

    @Test fun actualTailChainsReuseTheirHeaderAndNonTailCallsResetAncestry() = entered { language ->
        val chain = chain(language)
        val first = invoke(chain.a, 0L)
        assertEquals(chain.ancestry, first)
        repeat(30) { assertSame(first, invoke(chain.a, 0L)) }
        assertEquals(0L, chain.metrics.tailBounces)
        compile(chain.a.callTarget)
        assertSame(first, invoke(chain.a, 0L))
        val nonTail = NonTail(language, chain.a.callTarget, chain.metrics)
        repeat(20) {
            assertEquals(chain.ancestry + it, Calls.target(nonTail.callTarget, arrayOf(-1L, it.toLong())))
        }
        compile(nonTail.callTarget)
        assertEquals(chain.ancestry + Long.MAX_VALUE,
            Calls.target(nonTail.callTarget, arrayOf(-1L, Long.MAX_VALUE)))
        assertEquals(0L, chain.metrics.tailBounces, "A non-tail call must clear the supplied saturated ancestry")
    }

    @Test fun saturatedBloomCollisionStillBouncesAndPreservesPendingWork() = entered { language ->
        val chain = chain(language)
        val caller = CatchBounce(language, chain.a.callTarget, chain.metrics)
        // A bounces to B. The trampoline restarts B with zero ancestry.
        val expected = chain.b.mask or chain.c.mask
        repeat(30) { assertEquals(expected + it, invoke(caller, it.toLong())) }
        assertEquals(30L, chain.metrics.tailBounces)
        assertEquals(30L, chain.metrics.trampolineIterations)
        compile(caller.callTarget)
        for (offset in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_019L))
            assertEquals(expected + offset, invoke(caller, offset))
        assertEquals(33L, chain.metrics.tailBounces)
        assertEquals(33L, chain.metrics.trampolineIterations)
    }
}
