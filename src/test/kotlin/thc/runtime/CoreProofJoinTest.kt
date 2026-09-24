// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.LoopNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias Core = List<Any?>

class CoreProofJoinTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun meta(rep: Map<String, Any?> = long) = mapOf("rep" to rep)
    private fun v(id: String): Core = listOf("var", id, meta())
    private fun n(value: Long): Core = listOf("lit", "int", value.toString(), meta())
    private fun arg(id: String): Map<String, Any?> = mapOf("id" to id, "name" to id, "lifted" to false, "rep" to long)
    private fun app(fn: Core, vararg args: Core): Core = listOf("app", fn, args.toList(), List(args.size) { false }, false, false, meta())
    private fun call(id: String, vararg args: Core) = app(listOf("var", id, meta(closure)), *args)
    private fun prim(id: String, vararg args: Core) = app(listOf("prim", id), *args)
    private fun lam(args: List<String>, body: Core): Core = listOf("lam", args.map(::arg), body,
        mapOf("rep" to closure, "resultRep" to long))
    private fun bind(id: String, rhs: Core, join: Int? = null): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id, "name" to id, "lifted" to true, "rep" to closure, "expr" to rhs).also {
        if (join != null) { it["joinValueArity"] = join; it["joinResultRep"] = long }
    }
    private fun let(recursive: Boolean, bindings: List<Map<String, Any?>>, body: Core): Core =
        listOf("let", recursive, bindings, body, meta())
    private fun choose(condition: Core, yes: Core, no: Core): Core = listOf("case", condition, "condition", listOf(
        listOf("lit", listOf("int", "1"), emptyList<String>(), yes),
        listOf("default", null, emptyList<String>(), no)), meta() + ("binder" to arg("condition")))
    private fun program(body: Core, action: (Program) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                action(Program(language, mapOf("bindings" to listOf(bind("entry", lam(listOf("input"), body))), "instrument" to true)))
            } finally { context.leave() }
        }
    }
    private fun run(program: Program, n: Long): Long = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf<Any?>(n))) as Long
    private fun count(program: Program, name: String) = (program.diagnostics().getValue(name) as Number).toLong()
    private fun compile(program: Program) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        val target = program.entryTarget("entry")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }

    private class CloneCaller(target: RootCallTarget) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, frame.arguments)
        fun cloneTarget(): RootCallTarget {
            callTarget
            assertTrue(call.cloneCallTarget())
            return call.clonedCallTarget as RootCallTarget
        }
    }

    private fun assertBranchRegions(root: RootNode, expected: Int) {
        val regions = NodeUtil.findAllNodeInstances(root, LocalJoinRegion::class.java)
        assertEquals(expected, regions.size)
        // Roots and generic forcing nodes retain their own tail-call loops;
        // the acyclic join regions themselves must not own any LoopNode.
        assertTrue(regions.all { region -> region.children.none { it is LoopNode } })
    }

    @Test fun deeplyNestedNonrecursiveJoinsAreBranchesIncludingInClonedCompiledTargets() {
        var region = let(false, listOf(
            bind("positive", lam(listOf("value"), prim("+#", v("value"), n(17))), 1),
            bind("negative", lam(listOf("value"), prim("-#", n(0), v("value"))), 1)),
            choose(prim("<#", v("input"), n(0)), call("negative", v("input")),
                choose(prim("==#", v("input"), n(0)), n(4096), call("positive", v("input")))))
        // Acyclic joins must not multiply Graal loop nesting. Cover zero-arity
        // transfers, several targets, a direct return, and a non-tail continuation.
        repeat(24) { index ->
            region = let(false, listOf(bind("finish$index", region, 0)), v("finish$index"))
        }
        program(prim("+#", region, n(19))) { p ->
            val original = p.entryTarget("entry")
            assertBranchRegions(original.rootNode, 25)
            val caller = CloneCaller(original)
            val cloned = caller.cloneTarget()
            assertBranchRegions(cloned.rootNode, 25)
            fun check(input: Long) {
                val expected = (if (input < 0) -input else if (input == 0L) 4096L else input + 17) + 19
                assertEquals(expected, run(p, input))
                assertEquals(expected, Calls.target(caller.callTarget, arrayOf(0L, input)))
            }
            repeat(30) { check(it.toLong() - 15) }
            compile(p)
            val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(cloned, true)
            assertEquals(true, type.getMethod("isValidLastTier").invoke(cloned))
            val before = count(p, "compiledEntries")
            for (input in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, 3_000_000_001L, -3_000_000_001L)) check(input)
            assertTrue(count(p, "compiledEntries") > before)
            assertTrue(count(p, "localJoinTransfers") > 0)
        }
    }

    @Test fun nonrecursiveShadowedJoinRhsTransfersToItsLexicalAncestor() {
        val inner = let(false, listOf(bind("finish", lam(listOf("inner"),
            call("finish", prim("+#", v("inner"), n(5)))), 1)), call("finish", v("input")))
        val outer = let(false, listOf(bind("finish", lam(listOf("outer"),
            prim("+#", v("outer"), n(7))), 1)), inner)
        program(prim("+#", outer, n(11))) { p ->
            assertBranchRegions(p.entryTarget("entry").rootNode, 2)
            repeat(30) { assertEquals(it.toLong() + 23, run(p, it.toLong())) }
            compile(p)
            for (input in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L)) assertEquals(input + 23, run(p, input))
            assertEquals(66L, count(p, "localJoinTransfers"))
            assertEquals(0L, count(p, "tailBounces"))
        }
    }

    @Test fun recursivePrimitiveJoinUsesParallelMovesAndReturnsToNonTailContinuation() {
        val step = choose(prim("<=#", v("n"), n(0)), prim("-#", v("a"), v("b")),
            call("loop", prim("-#", v("n"), n(1)), v("b"), v("a")))
        val region = let(true, listOf(bind("loop", lam(listOf("n", "a", "b"), step), 3)),
            call("loop", v("input"), n(4_000_000_001), n(9_000_000_002)))
        program(prim("+#", region, n(17))) { p ->
            fun check(n: Long) = assertEquals((if (n % 2 == 0L) -5_000_000_001 else 5_000_000_001) + 17, run(p, n))
            check(100_000); check(100_001); compile(p); check(100_002); check(100_003)
            assertTrue(count(p, "localJoinTransfers") > 400_000)
            assertEquals(0L, count(p, "tailBounces")); assertEquals(0L, count(p, "papAllocations"))
        }
    }

    @Test fun mutualJoinsAndNestedOuterTransfersStayInTheirLexicalRegion() {
        val even = choose(prim("<=#", v("n"), n(0)), n(20), call("odd", prim("-#", v("n"), n(1))))
        val odd = choose(prim("<=#", v("n"), n(0)), n(30),
            let(false, listOf(bind("inner", call("even", prim("-#", v("n"), n(1))), 0)), listOf("var", "inner", meta())))
        program(let(true, listOf(bind("even", lam(listOf("n"), even), 1), bind("odd", lam(listOf("n"), odd), 1)), call("even", v("input")))) { p ->
            assertEquals(20L, run(p, 100_000)); assertEquals(30L, run(p, 100_001)); compile(p)
            assertEquals(30L, run(p, 100_003)); assertEquals(0L, count(p, "tailBounces"))
            assertTrue(count(p, "localJoinTransfers") > 300_000)
        }
    }

    @Test fun invalidJoinUsesRejectAtLoadRatherThanEscapingIntoClosures() {
        val definition = bind("join", lam(listOf("x"), v("x")), 1)
        for (body in listOf(v("join"), call("join"), call("join", n(1), n(2)),
            prim("+#", call("join", n(1)), n(2)), lam(emptyList(), call("join", n(1))))) {
            assertThrows(RuntimeFault::class.java) { program(let(false, listOf(definition), body)) {} }
        }
    }

    @Test fun exportedEvaluatedCafAndRecursiveAliasesStillForceIntroducedThunks() {
        val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val box = mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed",
            "fieldReps" to listOf(listOf("IntRep")), "fieldLifted" to listOf(false), "strictFields" to listOf(false))
        fun dataVar(id: String): Core = listOf("var", id, meta(dataRep))
        fun dataBinding(id: String, rhs: Core) = mapOf("id" to id, "name" to id, "lifted" to true, "rep" to dataRep, "expr" to rhs)
        fun boxed(value: Core): Core = listOf("app", listOf("con", "Box", 1, meta(closure)), listOf(value), listOf(false), true, true, meta(dataRep))
        fun extract(value: Core): Core = listOf("case", value, "box", listOf(
            listOf("data", "Box", listOf("payload"), v("payload"), mapOf("binders" to listOf(arg("payload"))))),
            meta() + ("binder" to mapOf("id" to "box", "lifted" to true, "rep" to dataRep)))
        val caf = dataBinding("caf", boxed(n(4_000_000_003)))
        val recursive = listOf("let", true, listOf(dataBinding("value", boxed(v("input"))), dataBinding("alias", dataVar("value"))),
            extract(dataVar("alias")), meta())
        val body = prim("+#", extract(dataVar("caf")), recursive)
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p = Program(language, mapOf("constructors" to listOf(box), "bindings" to listOf(caf, bind("entry", lam(listOf("input"), body)))))
                assertEquals(7_000_000_004L, run(p, 3_000_000_001))
                assertTrue(count(p, "thunkEvaluations") >= 3, "CAF and recursive aliases must retain actual storage forcing")
                compile(p)
                assertEquals(4_000_000_003L + Long.MAX_VALUE, run(p, Long.MAX_VALUE))
            } finally { context.leave() }
        }
    }

    @Test fun provenLongCapturesPreserveFullWidthValuesWithoutAnObjectArm() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val layout = FrameLayout(); val slot = layout.bind("captured")
                val descriptor = layout.build()
                val captures = CaptureLayout(language, booleanArrayOf(true), booleanArrayOf(true))
                for (value in listOf(3_000_000_001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val frame = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                    FrameAccess.writeLong(frame, slot, value)
                    val environment = captures.capture(frame, intArrayOf(slot))
                    assertTrue(environment.isLong(0)); assertFalse(environment.isObject(0))
                    assertEquals(value, environment.getLong(0))
                    val restored = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                    captures.restore(environment, 0, restored, slot)
                    assertTrue(restored.isLong(slot)); assertEquals(value, restored.getLong(slot))
                }
                assertThrows(RuntimeFault::class.java) { captures.captureValues(arrayOf(Unit)) }
            } finally { context.leave() }
        }
    }

    @Test fun representationMetadataRequiresExactCarriersAndPreservesLegacyUnknown() {
        assertFalse(CoreRepresentations.expression(listOf("var", "x")).present)
        assertTrue(CoreRepresentations.parse(long).isLong)
        for (bad in listOf(long - "primReps", long + ("primReps" to listOf("AddrRep")),
            closure + ("primReps" to listOf("IntRep")), closure + ("primReps" to emptyList<String>()))) {
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(bad) }
        }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(long).refine(CoreRepresentations.parse(closure)) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.joinArity(mapOf("joinValueArity" to 1.5)) }
        assertNull(CoreRepresentations.joinArity(mapOf("info" to mapOf("joinArity" to 1))))
    }
}
