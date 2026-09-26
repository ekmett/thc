// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import jdk.incubator.vector.ShortVector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias TypedSelfCore = List<Any?>
private typealias TypedSelfRep = Map<String, Any?>

/** Exact-Core controls. Native loopCase and compiler graphs remain separate evidence. */
class TypedSelfCallTest {
    private val integer: TypedSelfRep = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val lane: TypedSelfRep = mapOf("kind" to "long", "primReps" to listOf("Int16Rep"), "evaluated" to true)
    private val closure: TypedSelfRep = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val vector: TypedSelfRep = mapOf("kind" to "vector", "primReps" to listOf("VecRep 8 Int16ElemRep"),
        "vector" to mapOf("lanes" to 8, "element" to "Int16ElemRep"), "evaluated" to true)
    private val unpacked: TypedSelfRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to List(8) { "Int16Rep" }, "components" to List(8) { lane }, "evaluated" to true)
    private fun variable(id: String, rep: TypedSelfRep = integer): TypedSelfCore = listOf("var", id, mapOf("rep" to rep))
    private fun literal(value: Long): TypedSelfCore = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun formal(id: String, rep: TypedSelfRep = integer) = mapOf("id" to id, "name" to id, "lifted" to false, "rep" to rep)
    private fun application(fn: TypedSelfCore, args: List<TypedSelfCore>, rep: TypedSelfRep = integer): TypedSelfCore =
        listOf("app", fn, args, List(args.size) { false }, false, false, mapOf("rep" to rep))
    private fun primitive(name: String, args: List<TypedSelfCore>, rep: TypedSelfRep = integer) =
        application(listOf("prim", name), args, rep)
    private fun broadcast(value: TypedSelfCore) = primitive("broadcastInt16X8#",
        listOf(primitive("intToInt16#", listOf(value), lane)), vector)
    private fun checksum(value: TypedSelfCore): TypedSelfCore {
        val ids = (0 until 8).map { "lane$it" }
        val sum = ids.fold(literal(0)) { total, id -> primitive("+#", listOf(total,
            primitive("int16ToInt#", listOf(variable(id, lane))))) }
        return listOf("case", primitive("unpackInt16X8#", listOf(value), unpacked), "whole", listOf(
            listOf("data", "T8", ids, sum, mapOf("binders" to ids.map { formal(it, lane) }))),
            mapOf("rep" to integer, "binder" to formal("whole", unpacked)))
    }
    private fun module(vectorResult: Boolean): Map<String, Any?> {
        val result = if (vectorResult) vector else integer
        val left = variable("left", vector); val right = variable("right", vector)
        val remaining = variable("remaining")
        val finish = if (vectorResult) left else primitive("+#", listOf(
            checksum(primitive("minusInt16X8#", listOf(left, right), vector)),
            primitive("-#", listOf(variable("x"), variable("y")))))
        val recur = application(variable("worker", closure), listOf(
            primitive("plusInt16X8#", listOf(right, broadcast(remaining)), vector),
            primitive("timesInt16X8#", listOf(left, broadcast(literal(3))), vector),
            primitive("-#", listOf(remaining, literal(1))), variable("y"), variable("x")), result)
        val body = listOf("case", remaining, "condition", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), finish),
            listOf("default", null, emptyList<String>(), recur)),
            mapOf("rep" to result, "binder" to formal("condition")))
        val args = listOf(formal("left", vector), formal("right", vector), formal("remaining"), formal("x"), formal("y"))
        val rhs = listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result,
            "entryStrict" to List(args.size) { false }))
        return mapOf("instrument" to true, "constructors" to listOf(
            mapOf("id" to "T8", "name" to "T8", "kind" to "unboxed-tuple", "arity" to 8)),
            "bindings" to listOf(mapOf("id" to "worker", "name" to "worker", "lifted" to true, "rep" to closure, "expr" to rhs)))
    }
    private fun count(program: ExecutableProgram, name: String) = (program.diagnostics().getValue(name) as Number).toLong()
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }
    private fun eachBackend(vectorResult: Boolean, action: (String, Language, ExecutableProgram) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                action("ast", language, Program(language, module(vectorResult)))
                action("bytecode", language, BytecodeProgram(language, module(vectorResult)))
            } finally { context.leave() }
        }
    }
    private fun check(backend: String, language: Language, program: ExecutableProgram, depth: Long, vectorResult: Boolean) {
        val target = program.entryTarget("worker")
        val root = target.rootNode as GuestRoot
        val entry = requireNotNull(root.typedInput)
        // Only initial ingress allocates here; every recursive edge must be a local move.
        val input = entry.packet.create().also { it.inputMode = 3 }
        val first = shortArrayOf(Short.MIN_VALUE, -137, -1, 0, 1, 79, 311, Short.MAX_VALUE)
        val second = shortArrayOf(31, -71, 101, 907, -1009, 2111, -17003, 27007)
        val x = 3_000_000_017L; val y = -7_000_000_003L
        entry.packet.setLong(input, 0, 0L)
        entry.packet.setObject(input, 1, ShortVector.fromArray(ShortVector.SPECIES_128, first, 0))
        entry.packet.setObject(input, 2, ShortVector.fromArray(ShortVector.SPECIES_128, second, 0))
        entry.packet.setLong(input, 3, depth); entry.packet.setLong(input, 4, x); entry.packet.setLong(input, 5, y)
        var left = first; var right = second
        for (remaining in depth downTo 1) {
            val nextLeft = ShortArray(8) { (right[it] + remaining.toShort()).toShort() }
            val nextRight = ShortArray(8) { (left[it] * 3).toShort() }
            left = nextLeft; right = nextRight
        }
        val reentries = count(program, "selfTailReentries")
        val bounces = count(program, "tailBounces")
        val value = invokeTypedInput(entry, input) { Calls.target(target, it) }
        val label = "$backend/depth=$depth/vectorResult=$vectorResult"
        if (vectorResult) {
            val shape = requireNotNull(root.tupleResult)
            val result = ownedTupleResult(value, shape)
            val actual = shape.layout.getObject(result, 0) as ShortVector
            assertArrayEquals(left, actual.toArray(), label)
        } else {
            val sum = (0 until 8).sumOf { (left[it] - right[it]).toShort().toLong() }
            assertEquals(sum + if (depth % 2 == 0L) x - y else y - x, value, label)
        }
        assertEquals(reentries + depth, count(program, "selfTailReentries"), label)
        assertEquals(bounces, count(program, "tailBounces"), "$label must not construct tail packets")
        assertEquals(0L, count(program, "trampolineIterations"), label)
        val state = language.handoffState.get()
        assertNull(state.pending); assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    private fun exercise(vectorResult: Boolean) = eachBackend(vectorResult) { backend, language, program ->
        // Compile a root that has never seen recursion, then exercise its cold backedge.
        repeat(20) { check(backend, language, program, 0, vectorResult) }
        compile(program.entryTarget("worker"))
        check(backend, language, program, 10_001, vectorResult)
        compile(program.entryTarget("worker"))
        val compiled = count(program, "compiledEntries")
        for (depth in listOf(0L, 1L, 2L, 31L, 10_000L)) check(backend, language, program, depth, vectorResult)
        assertTrue(count(program, "compiledEntries") > compiled, backend)
    }
    @Test fun vectorArithmeticAndWideScalarArgumentsUseParallelSelfMoves() = exercise(false)
    @Test fun vectorResultsRetainTheirOriginalDestinationAcrossSelfMoves() = exercise(true)

    @Test fun strictColdScalarsDoNotQualifyForTypedSelfMoves() {
        val vectorProof = CoreRepresentations.parse(vector)
        val cold = CoreRepresentations.parse(mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false))
        val formal = ArgumentLayout.fromProofs(listOf(vectorProof, cold))!!
        assertFalse(supportsTypedSelf(formal, booleanArrayOf(false, true), formal))
        assertTrue(supportsTypedSelf(formal, booleanArrayOf(false, true),
            ArgumentLayout.fromProofs(listOf(vectorProof, cold.copy(evaluated = true)))!!))
        assertFalse(supportsTypedSelf(formal, booleanArrayOf(false, false), ArgumentLayout.fromProofs(listOf(vectorProof))!!))
    }
}
