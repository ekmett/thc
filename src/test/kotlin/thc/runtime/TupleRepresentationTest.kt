@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class TupleRepresentationTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun map(value: Any?) = value as MutableMap<String, Any?>
    private fun list(value: Any?) = value as MutableList<Any?>
    private fun wrap(proof: Any?): MutableMap<String, Any?> = mutableMapOf("kind" to "unknown", "evaluated" to true,
        "primReps" to map(proof)["primReps"], "aggregate" to "unboxed-tuple", "components" to mutableListOf(proof))
    private fun module() = map(Json.parse(File(root, "build/aggregate-core/AggregateFrontier.json").readText()))
    private fun binding(module: Map<String, Any?>, name: String) = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
    private fun expression(module: Map<String, Any?>, name: String) = list(binding(module, name)["expr"])
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
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
        val nested = empty.copy(components = listOf(empty))
        assertFalse(TupleShape.compatible(empty, nested))
        assertFalse(TupleShape.compatible(empty, CoreRepresentation(CoreKind.VOID, true, true, emptyList())))
        assertThrows(RuntimeFault::class.java) { empty.refine(nested) }
        val generic = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
        assertTrue(TupleShape.compatible(empty.copy(primReps = generic.primReps, components = listOf(generic)),
            empty.copy(primReps = generic.primReps, components = listOf(generic.copy(kind = CoreKind.DATA, evaluated = true)))))
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
        val previous = System.getProperty(HANDOFF_PROPERTY)
        System.setProperty(HANDOFF_PROPERTY, "true")
        try { withLanguage { language ->
            val ref = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
            val result = CoreRepresentation(CoreKind.UNKNOWN, true, true, ref.primReps, listOf(ref))
            assertNull(HandoffEntry.create(language, FrameLayout(), emptyList(), result, false))
        } } finally { if (previous == null) System.clearProperty(HANDOFF_PROPERTY) else System.setProperty(HANDOFF_PROPERTY, previous) }
    }
}
