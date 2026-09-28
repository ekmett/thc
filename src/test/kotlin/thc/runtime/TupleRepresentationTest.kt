// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class TupleRepresentationTest {
    @Test fun emptyTupleProofOwnsParsedListsAndRecomputesOnCopyAndRefinement() {
        val reps = mutableListOf<String>()
        val components = mutableListOf<Any?>()
        val input = mutableMapOf<String, Any?>("kind" to "unknown", "evaluated" to true,
            "primReps" to reps, "aggregate" to "unboxed-tuple", "components" to components)
        val empty = CoreRepresentations.parse(input)
        assertTrue(empty.isEmptyTuple)
        assertNotSame(reps, empty.primReps)
        assertNotSame(components, empty.components)
        reps.add("IntRep")
        components.add(mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf("IntRep")))
        val nonempty = CoreRepresentations.parse(input)
        assertFalse(nonempty.isEmptyTuple)
        reps.clear(); components.clear()
        assertEquals(listOf("IntRep"), nonempty.primReps)
        assertEquals(1, nonempty.components!!.size)
        assertTrue(empty.isEmptyTuple)
        assertTrue(empty.primReps!!.isEmpty()); assertTrue(empty.components!!.isEmpty())
        assertFalse(empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, listOf(empty), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }.isEmptyTuple, "nested empty tuple has one logical component")
        assertFalse(empty.withPrimReps(null).isEmptyTuple)
        assertFalse(empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, null, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }.isEmptyTuple)
        assertFalse(empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, false, originalProof.primReps, originalProof.components, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }.isEmptyTuple)
        assertFalse(empty.let { originalProof -> originalProof.copy(CoreKind.VOID, originalProof.evaluated, originalProof.present, originalProof.primReps, originalProof.components, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }.isEmptyTuple)
        assertTrue(nonempty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, emptyList(), emptyList(), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }.isEmptyTuple)
        assertTrue(CoreRepresentation.UNKNOWN.refine(empty).isEmptyTuple)
        assertTrue(empty.refine(CoreRepresentation.UNKNOWN).isEmptyTuple)
        assertEquals(empty, empty.copy(), "derived proof does not change structural equality")
        assertTrue(empty.withEvaluated(false).isEmptyTuple)
    }

    private val root = File(System.getProperty("thc.projectRoot"))
    private fun map(value: Any?) = value as MutableMap<String, Any?>
    private fun list(value: Any?) = value as MutableList<Any?>
    private fun wrap(proof: Any?): MutableMap<String, Any?> = mutableMapOf("kind" to "unknown", "evaluated" to true,
        "primReps" to map(proof)["primReps"], "aggregate" to "unboxed-tuple", "components" to mutableListOf(proof))
    private fun module() = map(Json.parse(File(root, "build/aggregate-core/AggregateFrontier.json").readText()))
    private fun binding(module: Map<String, Any?>, name: String) = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
    private fun expression(module: Map<String, Any?>, name: String) = list(binding(module, name)["expr"])
    private fun withLanguage(engine: Engine? = null, action: (Language) -> Unit) = Context.newBuilder("thc")
        .also { builder -> if (engine != null) builder.engine(engine) }.build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    @Test fun canonicalShapeKeysPreserveLogicalIdentityWithoutSharingContextLayouts() {
        val state = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
        val integer = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
        val word = integer.withPrimReps(listOf("WordRep"))
        val lifted = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
        val unlifted = lifted.let { originalProof -> originalProof.copy(originalProof.kind, true, originalProof.present, listOf("BoxedRep (Just Unlifted)"), originalProof.components, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }
        val float = CoreRepresentation(CoreKind.FLOAT, true, true, listOf("FloatRep"))
        val double = CoreRepresentation(CoreKind.DOUBLE, true, true, listOf("DoubleRep"))
        fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, true, true,
            fields.flatMap { it.primReps!! }, fields.toList())
        fun vector(lanes: Int, element: String) = CoreRepresentation(CoreKind.VECTOR, true, true, listOf("VecRep $lanes $element"), null, CoreVector(lanes, element))
        fun sum(first: CoreRepresentation, second: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, true, true, listOf("WordRep", "WordRep"), null, null, listOf(first, second), 0, listOf(listOf(1), listOf(1))).also(SumShape::validate)
        val floatVector = vector(4, "FloatElemRep")
        val proofs = listOf(tuple(), tuple(state), tuple(tuple()), tuple(integer), tuple(word),
            tuple(state, integer), tuple(integer, state), tuple(tuple(integer)), tuple(integer, word), tuple(word, integer),
            tuple(lifted), tuple(lifted.let { originalProof -> originalProof.copy(CoreKind.DATA, true, originalProof.present, originalProof.primReps, originalProof.components, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }), tuple(unlifted),
            tuple(float), tuple(double), floatVector, tuple(floatVector), vector(2, "DoubleElemRep"),
            vector(4, "Int32ElemRep"), vector(4, "Word32ElemRep"), sum(integer, word), sum(word, integer),
            sum(integer, integer), sum(tuple(integer), integer))
        Engine.create().use { engine ->
            lateinit var previous: List<TupleShape>
            withLanguage(engine) { language ->
                previous = proofs.map { TupleShape(it, language) }
                for (left in proofs.indices) for (right in proofs.indices)
                    assertEquals(TupleShape.compatible(proofs[left], proofs[right]), previous[left].matches(previous[right]),
                        "logical shape $left / $right")
                assertTrue(previous[10].matches(previous[11]), "WHNF/kind refinements do not change boxed representation")
                for ((left, right) in listOf(0 to 1, 0 to 2, 3 to 4, 5 to 6, 3 to 7, 8 to 9, 10 to 12,
                        13 to 14, 15 to 16, 15 to 17, 18 to 19, 20 to 21, 20 to 22, 22 to 23))
                    assertFalse(previous[left].matches(previous[right]), "distinct logical shapes $left / $right")
            }
            withLanguage(engine) { language ->
                for ((index, proof) in proofs.withIndex()) {
                    val fresh = TupleShape(proof, language)
                    assertTrue(fresh.matches(previous[index]), "same metadata across contexts")
                    assertNotSame(previous[index].language, fresh.language, "THC's EXCLUSIVE context policy remains unchanged")
                    assertNotSame(previous[index].layout, fresh.layout, "storage layouts remain context-owned")
                }
            }
        }
    }
    @Test fun equalPhysicalVectorsDoNotEraseLogicalTupleBoundaries() {
        val mutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { m -> val p = map(map(expression(m, "pair")[3])["resultRep"]); val c = list(p["components"]); c[0] = wrap(c[0]) },
            { m -> val p = map(map(list(expression(m, "pair")[2])[6])["rep"]); val c = list(p["components"]); c[0] = wrap(c[0]) },
            { m -> val p = map(map(map(list(expression(m, "tupleOutstanding")[2])[4])["binder"])["rep"]); val c = list(p["components"]); c[0] = wrap(c[0]) },
            { m -> val alt = list(list(list(expression(m, "tupleOutstanding")[2])[3])[0]); val b = map(list(map(alt[4])["binders"])[0]); b["rep"] = wrap(b["rep"]) },
            { m -> val app = list(expression(m, "pair")[2]); val con = list(app[1]); con[2] = 3L },
            { m -> val app = list(expression(m, "pair")[2]); val arg = list(list(app[2])[0]); map(map(arg[6])["rep"])["primReps"] = listOf("WordRep") },
            { m -> val alt = list(list(list(expression(m, "tupleOutstanding")[2])[3])[0]); val b = map(list(map(alt[4])["binders"])[0]); map(b["rep"])["primReps"] = listOf("WordRep") },
            { m -> val pair = expression(m, "pair"); val app = list(pair[2]); val p = map(map(app[6])["rep"]); list(p["components"])[0] = mutableMapOf("kind" to "unknown", "primReps" to listOf("IntRep"), "evaluated" to true) }
        )
        withLanguage { language ->
            for ((index, mutation) in mutations.withIndex()) for (backend in listOf("ast", "bytecode")) {
                val m = module(); mutation(m)
                val linked = CoreModules.reachable(m, "tupleOutstanding")
                assertThrows(RuntimeFault::class.java, {
                    if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                }, "$backend mutation $index")
            }
        }
        val empty = CoreRepresentations.parse(mapOf("kind" to "unknown", "primReps" to emptyList<String>(),
            "evaluated" to true, "aggregate" to "unboxed-tuple", "components" to emptyList<Any>()))
        val nested = empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, listOf(empty), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }
        assertFalse(TupleShape.compatible(empty, nested))
        assertFalse(TupleShape.compatible(empty, CoreRepresentation(CoreKind.VOID, true, true, emptyList())))
        assertThrows(RuntimeFault::class.java) { empty.refine(nested) }
        val generic = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
        assertTrue(TupleShape.compatible(empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, generic.primReps, listOf(generic), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) },
            empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, generic.primReps, listOf(generic.let { originalProof -> originalProof.copy(CoreKind.DATA, true, originalProof.present, originalProof.primReps, originalProof.components, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }))
    }
    @Test fun completedReferenceLoanIsReleasedEvenWhenTheConsumerShapeIsWrong() = withLanguage { language ->
        val reference = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
        val integer = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
        fun shape(field: CoreRepresentation) = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, true, true,
            field.primReps, listOf(field)), language)
        val source = shape(reference); val wrong = shape(integer)
        val layout = FrameLayout(); val slot = layout.bind("field")
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
        val marker = Any(); FrameAccess.write(frame, slot, marker)
        val token = source.finish(frame, intArrayOf(slot))
        assertEquals(1, language.handoffState.get().results.depth)
        assertThrows(IllegalStateException::class.java) { wrong.consume(frame, token, intArrayOf(slot), 0) }
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().results.retainedReferences())
        // A deoptimized fresh carrier owns no loan and retains raw pointer identity.
        val fresh = source.layout.create(); source.layout.setObject(fresh, 0, marker)
        source.consume(frame, fresh, intArrayOf(slot), 0)
        assertSame(marker, frame.getObject(slot))
        assertEquals(0, language.handoffState.get().results.depth)
        val completion = source.finish(frame, intArrayOf(slot))
        val owned = TupleResultsKt.ownedTupleResult(completion, source)
        assertSame(marker, source.layout.getObject(owned, 0))
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().results.retainedReferences())
        val malformed = source.finish(frame, intArrayOf(slot))
        assertThrows(IllegalStateException::class.java) { TupleResultsKt.ownedTupleResult(malformed, wrong) }
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().results.retainedReferences())
    }
    @Test fun forcingAConsumedLazyFieldCanBlackholeWithoutRetainingTheOutputLoan() = withLanguage { language ->
        for (backend in listOf("ast", "bytecode")) {
            val m = module()
            val outer = list(expression(m, "tupleZeroLazy")[2])
            val outerAlt = list(list(outer[3])[0])
            val choice = list(outerAlt[3])
            val alternatives = list(choice[3]).map(::list)
            val forceBox = alternatives.single { it[0] == "default" }[3]
            alternatives.single { it[0] == "lit" }[3] = forceBox
            val linked = CoreModules.reachable(m, "tupleZeroLazy")
            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
            repeat(2) {
                val error = assertThrows(RuntimeFault::class.java) {
                    Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("tupleZeroLazy"), arrayOf(-1L)))
                }
                assertTrue(error.message.orEmpty().contains("Blackhole"))
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().results.retainedReferences())
            }
            assertEquals(4 * 4097L + 2554L,
                Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("tupleZeroLazy"), arrayOf(4097L))))
        }
    }
    @Test fun lexicalScalarAndJoinBindingsShadowTupleNames() = withLanguage { language ->
        val longRep = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        fun literal(value: Long) = listOf("lit", "int", value.toString(), mapOf("rep" to longRep))
        for (kind in listOf("let", "case", "join")) for (backend in listOf("ast", "bytecode")) {
            val m = module()
            val outer = list(expression(m, "tupleOutstanding")[2])
            val id = outer[2] as String
            val use = listOf("var", id, mapOf("rep" to longRep))
            val body = if (kind == "case") listOf("case", literal(12345L), id,
                listOf(listOf("default", null, emptyList<String>(), use)),
                mapOf("rep" to longRep, "binder" to mapOf("id" to id, "rep" to longRep)))
            else {
                val binder = mutableMapOf<String, Any?>("id" to id, "name" to id, "lifted" to false,
                    "coercion" to false, "rep" to longRep, "expr" to literal(12345L))
                if (kind == "join") { binder["joinValueArity"] = 0L; binder["joinResultRep"] = longRep }
                listOf("let", false, listOf(binder), use, mapOf("rep" to longRep))
            }
            list(list(outer[3])[0])[3] = body
            val linked = CoreModules.reachable(m, "tupleOutstanding")
            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
            assertEquals(12345L, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("tupleOutstanding"), arrayOf(8193L))), "$backend/$kind")
        }
    }
    @Test fun scalarHandoffCannotClaimSingletonReferenceTupleResults() {
        val previous = System.getProperty(HandoffKt.HANDOFF_PROPERTY)
        System.setProperty(HandoffKt.HANDOFF_PROPERTY, "true")
        try { withLanguage { language ->
            val ref = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
            val result = CoreRepresentation(CoreKind.UNKNOWN, true, true, ref.primReps, listOf(ref))
            assertNull(HandoffEntry.create(language, FrameLayout(), emptyList(), result, false))
        } } finally { if (previous == null) System.clearProperty(HandoffKt.HANDOFF_PROPERTY) else System.setProperty(HandoffKt.HANDOFF_PROPERTY, previous) }
    }
}
