// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

class SimdFloatVectorTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-floatx4")
    private fun module(stage: String = "pre") = Json.parse(File(directory, "$stage-core/SimdFloatX4.json").readText()) as Map<String, Any?>
    private fun metadata() = mapOf("kind" to "vector", "evaluated" to true,
        "primReps" to listOf("VecRep 4 FloatElemRep"), "vector" to mapOf("lanes" to 4L, "element" to "FloatElemRep"))
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun program(language: Language, backend: String, input: Map<String, Any?>, entry: String, diagnostic: Boolean = false): ExecutableProgram {
        val linked = CoreModules.reachable(input, entry) + mapOf("instrument" to true, "diagnosticUnsupported" to diagnostic)
        return if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    }
    private fun sameFloat(expected: Float, actual: Float, label: String = "") {
        if (expected.isNaN()) assertTrue(actual.isNaN(), label)
        else assertEquals(expected.toRawBits(), actual.toRawBits(), label)
    }

    @Test fun exactShapeAndVectorBackedStorageRemainDistinctFromFloatTuple() {
        val proof = CoreRepresentations.parse(metadata())
        assertEquals(CoreVectors.proofFloat, proof)
        assertFalse(proof.isTuple); assertFalse(proof.isFloat)
        assertFalse(TupleShape.compatible(proof, CoreVectors.unpackedFloat))
        assertFalse(TupleShape.compatible(proof, CoreVectors.proof32))
        assertEquals(List(4) { "FloatRep" }, CoreVectors.unpackedFloat.primReps)
        assertTrue(CoreVectors.unpackedFloat.components!!.all { it.isFloat })
        val fields = FloatX4::class.java.declaredFields
        assertEquals(1, fields.size)
        assertEquals("jdk.incubator.vector.FloatVector", fields.single().type.name)
        assertTrue(Modifier.isPrivate(fields.single().modifiers) && Modifier.isFinal(fields.single().modifiers))
        assertTrue(FloatX4::class.java.declaredConstructors.all { Modifier.isPrivate(it.modifiers) })
        assertEquals(Float::class.javaPrimitiveType, FloatX4::class.java.getMethod("lane", Int::class.javaPrimitiveType).returnType)
        assertThrows(RuntimeFault::class.java) { proof.refine(CoreVectors.unpackedFloat) }
        assertThrows(RuntimeFault::class.java) { proof.refine(CoreVectors.proof32) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.caseResult(listOf(proof, CoreVectors.proof32)) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata() - "vector") }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata() + ("primReps" to List(4) { "FloatRep" })) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("timesFloatX4#", listOf(proof, CoreVectors.proof32), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packFloatX4#", listOf(CoreVectors.unpacked32), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packFloatX4#", CoreVectors.unpackedFloat.components!!, proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("broadcastFloatX4#",
            listOf(CoreRepresentation(CoreKind.UNKNOWN, true, true, listOf("FloatRep"))), proof) }
        // FloatX8 is supported; a three-lane vector still has no carrier.
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(metadata() + mapOf(
            "primReps" to listOf("VecRep 3 FloatElemRep"), "vector" to mapOf("lanes" to 3L, "element" to "FloatElemRep"))) }
    }

    @Test fun primitiveLanesPreserveMovementBitsAndBinary32Arithmetic() {
        val values = floatArrayOf(0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.fromBits(3), -Float.fromBits(3), java.lang.Float.MIN_NORMAL, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.fromBits(0x7fc01234),
            0.5f, -1.0f, 1.0f, 16777216.0f, 3.0f)
        for ((i, a) in values.withIndex()) for ((j, b) in values.withIndex()) {
            val lanes = floatArrayOf(a, values[(i + 3) % values.size], values[(j + 7) % values.size], b)
            val packed = FloatX4.pack(lanes[0], lanes[1], lanes[2], lanes[3])
            val broadcast = FloatX4.broadcast(b)
            for (lane in 0..3) {
                assertEquals(lanes[lane].toRawBits(), packed.lane(lane).toRawBits(), "movement $i/$j/$lane")
                assertEquals(b.toRawBits(), broadcast.lane(lane).toRawBits(), "broadcast $i/$j/$lane")
                sameFloat(lanes[lane] + b, FloatX4.add(packed, broadcast).lane(lane), "add $i/$j/$lane")
                sameFloat(lanes[lane] - b, FloatX4.subtract(packed, broadcast).lane(lane), "subtract $i/$j/$lane")
                sameFloat(lanes[lane] * b, FloatX4.multiply(packed, broadcast).lane(lane), "multiply $i/$j/$lane")
            }
        }
        val product = FloatX4.multiply(FloatX4.broadcast(Float.fromBits(0x3f800001)), FloatX4.broadcast(Float.fromBits(0x3f7ffffe)))
        sameFloat(0.0f, FloatX4.add(product, FloatX4.broadcast(-1.0f)).lane(0), "separate rounding, not FMA")
    }

    @Test fun bothLoadersRejectForgedVectorProofsAndVectorFormalArguments() = withLanguage { language ->
        val input = module()
        fun rewrite(value: Any?, mutation: (Map<String, Any?>) -> Map<String, Any?>): Any? = when (value) {
            is Map<*, *> -> {
                val map = value as Map<String, Any?>
                if (map["kind"] == "vector") mutation(map) else map.mapValues { rewrite(it.value, mutation) }
            }
            is List<*> -> value.map { rewrite(it, mutation) }
            else -> value
        }
        val mutations: List<(Map<String, Any?>) -> Map<String, Any?>> = listOf(
            { it - "vector" },
            { it + ("primReps" to List(4) { "FloatRep" }) },
            { it + mapOf("primReps" to listOf("VecRep 4 Int32ElemRep"), "vector" to mapOf("lanes" to 4L, "element" to "Int32ElemRep")) })
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, input, "vectorArgument"))
            val unsupported = rewrite(input) { it + mapOf("primReps" to listOf("VecRep 3 FloatElemRep"),
                "vector" to mapOf("lanes" to 3L, "element" to "FloatElemRep")) } as Map<String, Any?>
            assertThrows(UnsupportedCore::class.java) { program(language, backend, unsupported, "plusCase") }
            assertTrue((program(language, backend, unsupported, "plusCase", true).diagnostics().getValue("deferredUnsupported") as Collection<*>).isNotEmpty())
            for (diagnostic in listOf(false, true)) for (mutation in mutations) {
                val modified = rewrite(input, mutation) as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, modified, "plusCase", diagnostic) }
            }
        }
    }

    @Test fun unknownLaneKindCannotHideDoubleLiteralInVectorBroadcast() = withLanguage { language ->
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val forged = mapOf("kind" to "unknown", "primReps" to listOf("FloatRep"), "evaluated" to true)
        val operand = listOf("lit", "double", "1.0", mapOf("rep" to forged))
        val vector = listOf("app", listOf("prim", "broadcastFloatX4#", mapOf("rep" to closure)),
            listOf(operand), listOf(false), false, true, mapOf("rep" to metadata()))
        val body = listOf("case", vector, "v", listOf(listOf("default", null, emptyList<String>(),
            listOf("lit", "int", "1", mapOf("rep" to long)), mapOf("binders" to emptyList<Any>()))),
            mapOf("rep" to long, "binder" to mapOf("id" to "v", "lifted" to false, "rep" to metadata())))
        val input = mapOf("schema" to 1, "ghc" to "9.14.1", "constructors" to emptyList<Any>(), "bindings" to listOf(
            mapOf("id" to "root", "name" to "root", "arity" to 0, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", emptyList<Any>(), body, mapOf("rep" to closure, "resultRep" to long)))))
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true))
            assertThrows(RuntimeFault::class.java) { program(language, backend, input, "root", diagnostic) }
    }

    @Test fun genuineCoreMatchesNativeAndEntersCompiledCodeForEveryInput() {
        val provenance = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        val stages = provenance["stages"] as List<String>
        assertTrue("pre" in stages)
        assertEquals(true, provenance["positiveAuditsAccepted"])
        for (file in (provenance["sources"] as List<Map<String, String>>) + (provenance["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, file.getValue("path")).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(file["sha256"], hash, "Stale FloatX4 source/artifact: ${file["path"]}")
        }
        fun rows(filename: String) = File(directory, filename).readLines().associate { row ->
            val parts = row.split('\t')
            (parts.first() to parts.drop(1).dropLast(1).map(String::toLong)) to parts.last().toLong()
        }
        val expected = rows("expected.tsv")
        if (provenance["nativeRows"] != null) {
            assertEquals(expected, rows("oracle.tsv"))
            assertEquals(expected.size.toLong(), (provenance["nativeRows"] as Number).toLong())
        }
        val entries = provenance["entries"] as List<Map<String, Any?>>
        val declared = entries.flatMap { entry -> (entry["cases"] as List<List<Number>>).map { entry["name"] to it.map(Number::toLong) } }.toSet()
        assertEquals(declared, expected.keys)
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage { language ->
            for (entry in entries) {
                val name = entry["name"] as String
                val arity = (entry["arity"] as Number).toInt()
                val cases = (entry["cases"] as List<List<Number>>).map { row -> row.map(Number::toLong) }
                assertTrue(cases.isNotEmpty()); assertTrue(cases.all { it.size == arity })
                val program = program(language, backend, module(stage), name)
                val host = program.hostEntryTarget(arity)
                val closure = program.entryValue(name)
                fun checkRows(compiled: Boolean = false) {
                    for (input in cases) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val actual = Calls.target(host, arrayOf(closure, input.toTypedArray()))
                        val label = "$stage/$backend/$name/$input"
                        assertEquals(expected.getValue(name to input), actual, label)
                        if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before, label)
                    }
                }
                checkRows(); checkRows()
                val target = program.entryTarget(name)
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$name installed")
                checkRows(compiled = true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$name after execution")
                val diagnostics = program.diagnostics()
                assertEquals(0L, (diagnostics.getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (diagnostics.getValue("blackholes") as Number).toLong())
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }
}
