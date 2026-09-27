// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class GenericSumTransportTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/generic-sum-transport")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun evidence(): Map<String, Any?> {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(true, manifest["strictAccepted"])
        for (group in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale generic sum input $path")
            }
        return manifest
    }
    private fun modules(stage: String) = File(directory, "$stage/core").listFiles()!!
        .filter { it.extension == "json" }.map(::json)
    private fun entered(inline: Boolean = true, action: (Context, Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("compiler.Inlining", inline.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(context, TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun program(language: Language, stage: String, backend: String, entry: String): ExecutableProgram {
        val linked = CoreModules.reachable(CoreModules.merge(modules(stage)), "main:GenericSumTransport.$entry", true)
        return if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.arguments.depth)
        assertEquals(0, state.results.retainedReferences()); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun result(name: String): CoreRepresentation {
        val module = json(File(directory, "pre/core/GenericSumTransport.json"))
        val binding = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
        return CoreRepresentations.lambdaResult(binding["expr"] as List<Any?>)
    }
    private fun walk(value: Any?): Sequence<List<Any?>> = sequence {
        if (value is List<*>) { yield(value as List<Any?>); for (child in value) yieldAll(walk(child)) }
        if (value is Map<*, *>) for (child in value.values) yieldAll(walk(child))
    }

    @TestFactory fun genuineActiveValuesMatchNativeOnFirstInstalledCall(): List<DynamicTest> {
        val manifest = evidence()
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(manifest["entries"], rows.keys.toList()); assertEquals(1440, rows.values.sumOf { it.size })
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            listOf("ast", "bytecode").flatMap { backend -> rows.flatMap { (entry, cases) ->
                listOf(true, false).map { inline -> DynamicTest.dynamicTest("$stage/$backend/$entry/inline=$inline") {
                    entered(inline) { context, language ->
                        val program = program(language, stage, backend, entry)
                        val name = "main:GenericSumTransport.$entry"
                        val callable = context.asValue(EntryValue(program, name, 2))
                        fun observe(row: List<String>) {
                            assertEquals(row[3].toLong(), callable.execute(row[1].toLong(), row[2].toLong()).asLong(), row.toString())
                            assertEquals(0L, program.diagnostics()["blackholes"])
                            released(language)
                        }
                        cases.forEach(::observe)
                        assertTrue(callable.invokeMember("compile").asBoolean())
                        for (row in cases.reversed()) {
                            val before = program.diagnostics()["compiledEntries"] as Long
                            observe(row)
                            assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
                            valid(program.entryTarget(name)); valid(program.hostEntryTarget(2))
                        }
                    }
                } }
            } }
        }
    }

    @Test fun originalNativeProjectionAndManagedStorageAreDifferentProofs() {
        evidence()
        val address = result("addressMake")
        assertEquals(listOf("WordRep", "WordRep"), address.primReps)
        assertEquals(listOf(listOf(1), listOf(1)), address.alternativeSlots)
        assertEquals(listOf(CoreKind.LONG, CoreKind.LONG, CoreKind.ADDRESS), SumShape.storage(address).map { it.kind })
        assertEquals(listOf(listOf(2), listOf(1)), SumShape.transport(address).projections)
        val nested = result("nestedMake")
        assertEquals(7, nested.primReps!!.size); assertEquals(8, SumShape.storage(nested).size)
        assertEquals(listOf(listOf(2), listOf(2, 3, 4, 5), listOf(2, 6, 7), listOf(2, 1, 3)),
            SumShape.transport(nested).projections)
        val around = result("aroundMake")
        TupleShape.validate(around)
        assertEquals(9, around.primReps!!.size); assertEquals(10, TupleShape.flatten(around).size)
        assertEquals(CoreKind.VOID, around.components!![1].components!![0].kind)
        assertEquals(emptyList<CoreRepresentation>(), around.components[1].components!![1].components)
        // A physical-width edit cannot erase the authenticated logical tree.
        assertThrows(RuntimeFault::class.java) { SumShape.validate(address.copy(primReps = listOf("WordRep", "AddrRep"))) }
        assertThrows(RuntimeFault::class.java) { SumShape.validate(nested.copy(alternativeSlots = SumShape.transport(nested).projections)) }
        assertThrows(RuntimeFault::class.java) { SumShape.validate(nested.copy(tagSlot = 1)) }
        for (tag in listOf(Long.MIN_VALUE, 0, 5, 0x100000001, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { SumShape.checkedTag(tag, 4) }
    }

    @Test fun addressIdentityAndPrimitiveBitsSurviveReuseWithoutRetainingInactiveBacking() {
        evidence()
        for (backend in listOf("ast", "bytecode")) entered(false) { _, language ->
            val program = program(language, "pre", backend, "addressMake")
            val target = program.entryTarget("main:GenericSumTransport.addressMake")
            val shape = TupleShape(result("addressMake"), language)
            assertEquals(listOf("long", "long", "reference"), shape.layout.reps)
            val frameLayout = FrameLayout(); val slots = IntArray(shape.width) { frameLayout.bind("field$it") }
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), frameLayout.build())
            val bytes = byteArrayOf(7, 19, 31)
            val address = ManagedAddress.fromNativeImageSource(bytes, 1)
            fun observe(selector: Long, bits: Long) {
                shape.consume(frame, Calls.target(target, arrayOf(0L, selector, address, bits)), slots, 0)
                assertEquals(selector + 1, frame.getLong(slots[0]))
                if (selector == 0L) {
                    assertSame(address, frame.getObject(slots[2])); assertEquals(0L, frame.getLong(slots[1]))
                } else {
                    assertSame(ManagedAddress.nullAddress(), frame.getObject(slots[2]))
                    assertEquals(bits, frame.getLong(slots[1]))
                }
                released(language)
            }
            observe(0, Long.MIN_VALUE); observe(1, Long.MAX_VALUE)
            compile(target)
            for (bits in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) for (selector in listOf(0L, 1L)) {
                val before = program.diagnostics()["compiledEntries"] as Long
                observe(selector, bits)
                assertTrue((program.diagnostics()["compiledEntries"] as Long) > before); valid(target)
            }
        }
    }

    @Test fun exactVectorSpeciesAndManagedAddressCarriersRemainChecked() = entered { _, language ->
        evidence()
        val vectors = result("vectorMake")
        val shape = TupleShape(vectors, language)
        assertEquals(listOf("long", "Vector long 2", "Vector float 4"), shape.layout.reps)
        val longVector = VectorLayout(vectors.alternatives!![0]).species.zero()
        assertSame(longVector, shape.checkedReference(1, longVector))
        val floatVector = VectorLayout(vectors.alternatives[1]).species.zero()
        assertThrows(RuntimeFault::class.java) { shape.checkedReference(1, floatVector) }
        assertThrows(RuntimeFault::class.java) { shape.checkedReference(2, longVector) }
        assertThrows(RuntimeFault::class.java) { shape.checkedReference(1, null) }
        val wrong = vectors.alternatives[0].copy(vector = CoreVector.INT32X4)
        assertThrows(RuntimeFault::class.java) {
            SumShape.validate(vectors.copy(alternatives = listOf(wrong) + vectors.alternatives.drop(1)))
        }
        val address = TupleShape(result("addressMake"), language)
        assertThrows(RuntimeFault::class.java) { address.checkedReference(2, 7L) }
    }

    @Test fun malformedVectorPayloadLevityRejectsOnBothBackends() = entered { _, language ->
        evidence()
        for (backend in listOf("ast", "bytecode")) for (flag in listOf(true, "false")) {
            val source = modules("pre")
            val module = source.single { it["module"] == "GenericSumTransport" }
            val bindings = module["bindings"] as List<Map<String, Any?>>
            val maker = bindings.single { it["name"] == "vectorMake" }
            val constructor = walk(maker["expr"]).first { expression ->
                expression.firstOrNull() == "app" && (expression[1] as? List<*>)?.firstOrNull() == "con" &&
                    CoreRepresentations.expression(expression).isSum &&
                    CoreRepresentations.expression((expression[2] as List<List<Any?>>).single()).isVector
            } as MutableList<Any?>
            assertEquals(listOf(false), constructor[3]); constructor[3] = listOf(flag)
            val linked = CoreModules.reachable(CoreModules.merge(source), "main:GenericSumTransport.vectorCase", true)
            assertThrows(RuntimeFault::class.java, {
                if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
            }, "$backend/flag=$flag")
        }
    }

    @Test fun nestedHeapReferenceActivityFollowsBothTagsAndPhysicalNeighbors() {
        evidence()
        for (backend in listOf("ast", "bytecode")) entered { _, language ->
            val program = program(language, "pre", backend, "save")
            val target = program.entryTarget("main:GenericSumTransport.save")
            for (selector in 0L..15L) {
                val value = Calls.target(target, arrayOf(0L, selector, Long.MIN_VALUE, Unit)) as DataValue
                val layout = value.layout
                assertEquals(3, layout.logicalArity); assertEquals(10, layout.arity)
                assertEquals(Long.MIN_VALUE, layout.readLong(value, 0)); assertEquals(Long.MAX_VALUE, layout.readLong(value, 9))
                assertTrue(layout.compactPointer(2))
                val active = selector and 3L == 3L && selector and 4L == 0L
                assertEquals(!active, layout.inactiveSumReference(value, 2), "selector=$selector")
                if (active) assertNotNull(layout.read(value, 2)) else assertNull(layout.read(value, 2))
                if (selector == 3L) {
                    // Only the selected nested sum's tag is meaningful. An
                    // inactive nested tag can be zero; the active one cannot.
                    fun withTags(outer: Long) = layout.allocate().also { changed ->
                        for (index in 0 until layout.arity) when (index) {
                            1 -> layout.initializeLong(changed, index, outer)
                            3 -> layout.initializeLong(changed, index, 0L)
                            else -> layout.copyCompactScalar(value, changed, index)
                        }
                    }
                    val malformed = withTags(4L)
                    assertThrows(RuntimeFault::class.java) { layout.inactiveSumReference(malformed, 2) }
                    val inactive = withTags(3L)
                    assertTrue(layout.inactiveSumReference(inactive, 2))
                }
                released(language)
            }
        }
    }
}
