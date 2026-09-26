// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import jdk.incubator.vector.DoubleVector

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class SimdDoubleVectorTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-doublex2")
    private fun module(stage: String = "pre") = Json.parse(File(directory, "$stage-core/SimdDoubleX2.json").readText()) as Map<String, Any?>
    private fun metadata() = mapOf("kind" to "vector", "evaluated" to true,
        "primReps" to listOf("VecRep 2 DoubleElemRep"), "vector" to mapOf("lanes" to 2L, "element" to "DoubleElemRep"))
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
    private fun sameDouble(expected: Double, actual: Double, label: String = "") {
        if (expected.isNaN()) assertTrue(actual.isNaN(), label)
        else assertEquals(expected.toRawBits(), actual.toRawBits(), label)
    }

    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }

    @Test fun exactShapeAndVectorBackedStorageRemainDistinctFromDoubleTuple() {
        val proof = CoreRepresentations.parse(metadata())
        assertEquals(CoreVectors.proofDouble, proof)
        assertFalse(proof.isTuple); assertFalse(proof.isDouble)
        assertFalse(TupleShape.compatible(proof, CoreVectors.unpackedDouble))
        assertFalse(TupleShape.compatible(proof, CoreVectors.proof32))
        assertFalse(TupleShape.compatible(proof, CoreVectors.proofFloat))
        assertFalse(TupleShape.compatible(proof, CoreVectors.proof))
        assertEquals(List(2) { "DoubleRep" }, CoreVectors.unpackedDouble.primReps)
        assertTrue(CoreVectors.unpackedDouble.components!!.all { it.isDouble })
        assertEquals(Double::class.javaPrimitiveType, DoubleVector::class.java.getMethod("lane", Int::class.javaPrimitiveType).returnType)
        assertThrows(RuntimeFault::class.java) { proof.refine(CoreVectors.unpackedDouble) }
        assertThrows(RuntimeFault::class.java) { proof.refine(CoreVectors.proof32) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.caseResult(listOf(proof, CoreVectors.proof32)) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata() - "vector") }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata() + ("primReps" to List(2) { "DoubleRep" })) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("plusDoubleX2#", listOf(proof), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("plusDoubleX2#", listOf(proof, proof), CoreVectors.proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packDoubleX2#", listOf(CoreVectors.unpackedFloat), proof) }
        val nested = CoreVectors.unpackedDouble.copy(components = listOf(
            CoreRepresentation(CoreKind.UNKNOWN, true, true, listOf("DoubleRep"), listOf(CoreVectors.unpackedDouble.components!![0])),
            CoreVectors.unpackedDouble.components!![1]))
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packDoubleX2#", listOf(nested), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("timesDoubleX2#", listOf(proof, CoreVectors.proof32), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packDoubleX2#", listOf(CoreVectors.unpacked32), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packDoubleX2#", CoreVectors.unpackedDouble.components!!, proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("broadcastDoubleX2#",
            listOf(CoreRepresentation(CoreKind.UNKNOWN, true, true, listOf("DoubleRep"))), proof) }
        assertEquals(CoreVector(8, "DoubleElemRep"), CoreRepresentations.parse(metadata() + mapOf(
            "primReps" to listOf("VecRep 8 DoubleElemRep"), "vector" to mapOf("lanes" to 8L, "element" to "DoubleElemRep"))).vector)
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(metadata() + mapOf(
            "primReps" to listOf("VecRep 3 DoubleElemRep"), "vector" to mapOf("lanes" to 3L, "element" to "DoubleElemRep"))) }
    }

    @Test fun primitiveLanesPreserveMovementBitsAndBinary64Arithmetic() {
        val values = doubleArrayOf(0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
            Double.fromBits(3), -Double.fromBits(3), java.lang.Double.MIN_NORMAL, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.fromBits(0x7ff8000000001234),
            0.5, -1.0, 1.0, 9007199254740992.0, 9007199254740994.0)
        for ((i, a) in values.withIndex()) for ((j, b) in values.withIndex()) {
            val lanes = doubleArrayOf(a, values[(i + 3) % values.size])
            val packed = DoubleVector.broadcast(DoubleVector.SPECIES_128, lanes[0]).withLane(1, lanes[1])
            val broadcast = DoubleVector.broadcast(DoubleVector.SPECIES_128, b)
            for (lane in 0..1) {
                assertEquals(lanes[lane].toRawBits(), packed.lane(lane).toRawBits(), "movement $i/$j/$lane")
                assertEquals(b.toRawBits(), broadcast.lane(lane).toRawBits(), "broadcast $i/$j/$lane")
                sameDouble(lanes[lane] + b, (packed).add(broadcast).lane(lane), "add $i/$j/$lane")
                sameDouble(lanes[lane] - b, (packed).sub(broadcast).lane(lane), "subtract $i/$j/$lane")
                sameDouble(lanes[lane] * b, (packed).mul(broadcast).lane(lane), "multiply $i/$j/$lane")
            }
        }
        val product = (DoubleVector.broadcast(DoubleVector.SPECIES_128, Double.fromBits(0x3ff0000000000001))).mul(DoubleVector.broadcast(DoubleVector.SPECIES_128, Double.fromBits(0x3feffffffffffffe)))
        sameDouble(0.0, (product).add(DoubleVector.broadcast(DoubleVector.SPECIES_128, -1.0)).lane(0), "separate rounding, not FMA")
        val precise = (DoubleVector.broadcast(DoubleVector.SPECIES_128, 1.0)).add(DoubleVector.broadcast(DoubleVector.SPECIES_128, Math.scalb(1.0, -52)))
        sameDouble(Double.fromBits(0x3ff0000000000001), precise.lane(0), "binary64 precision, not binary32")
    }

    @Test fun bothLoadersAcceptVectorFormalsAndRejectForgedProofs() = withLanguage { language ->
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
            { it + ("primReps" to List(2) { "DoubleRep" }) },
            { it + mapOf("primReps" to listOf("VecRep 2 Int64ElemRep"), "vector" to mapOf("lanes" to 2L, "element" to "Int64ElemRep")) },
            { it + mapOf("primReps" to listOf("VecRep 8 DoubleElemRep"), "vector" to mapOf("lanes" to 8L, "element" to "DoubleElemRep")) })
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, input, "vectorArgument"))
            val unsupported = rewrite(input) { it + mapOf("primReps" to listOf("VecRep 3 DoubleElemRep"),
                "vector" to mapOf("lanes" to 3L, "element" to "DoubleElemRep")) } as Map<String, Any?>
            assertThrows(UnsupportedCore::class.java) { program(language, backend, unsupported, "plusCase") }
            assertTrue((program(language, backend, unsupported, "plusCase", true).diagnostics().getValue("deferredUnsupported") as Collection<*>).isNotEmpty())
            for (diagnostic in listOf(false, true)) for (mutation in mutations) {
                val modified = rewrite(input, mutation) as Map<String, Any?>
                assertThrows(RuntimeFault::class.java) { program(language, backend, modified, "plusCase", diagnostic) }
            }
        }
    }

    @Test fun unknownLaneKindCannotHideFloatLiteralInVectorBroadcast() = withLanguage { language ->
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val forged = mapOf("kind" to "unknown", "primReps" to listOf("DoubleRep"), "evaluated" to true)
        val operand = listOf("lit", "float", "1.0", mapOf("rep" to forged))
        val vector = listOf("app", listOf("prim", "broadcastDoubleX2#", mapOf("rep" to closure)),
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

    @Test fun genuineCoreMatchesAvailableOracleAndEntersCompiledCodeForEveryInput() {
        val provenance = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        val stages = provenance["stages"] as List<String>
        assertTrue("pre" in stages)
        assertEquals(true, provenance["positiveAuditsAccepted"])
        for (file in (provenance["sources"] as List<Map<String, String>>) + (provenance["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, file.getValue("path")).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(file["sha256"], hash, "Stale DoubleX2 source/artifact: ${file["path"]}")
        }
        fun rows(filename: String) = File(directory, filename).readLines().associate { row ->
            val parts = row.split('\t')
            (parts.first() to parts.drop(1).dropLast(1).map(String::toLong)) to parts.last()
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
                val target = program.entryTarget(name)
                val stageStructure = (provenance["structure"] as Map<String, Map<String, Any?>>).getValue(stage)
                val compiledEntries = (stageStructure["compiledEntriesByEntry"] as Map<String, Number>).getValue(name).toLong()
                assertTrue(compiledEntries >= 1)
                var compiledTargets = emptyList<RootCallTarget>()
                val handoff = language.handoffState.get()
                var argumentAllocations = 0L
                var resultAllocations = 0L
                fun checkRows(compiled: Boolean = false) {
                    for (input in cases) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val actual = Calls.target(host, arrayOf(closure, input.toTypedArray()))
                        val label = "$stage/$backend/$name/$input"
                        val wanted = expected.getValue(name to input)
                        if (entry["result"] == "long") assertEquals(wanted.toLong(), actual, label)
                        else {
                            assertInstanceOf(Double::class.javaObjectType, actual, label)
                            val value = actual as Double
                            if (wanted == "nan") assertTrue(value.isNaN(), label)
                            else assertEquals(wanted.toULong(), value.toRawBits().toULong(), label)
                        }
                        if (compiled) {
                            assertEquals(before + compiledEntries, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label compiled guest entry")
                            assertEquals(argumentAllocations, handoff.arguments.allocations, label)
                            assertEquals(resultAllocations, handoff.results.allocations, label)
                            val calls = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java).filter { it.callTarget === target }
                            assertTrue(calls.isNotEmpty(), "$label selected guest call")
                            for (call in calls) assertSame(target, call.currentCallTarget, "$label active guest identity")
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$label remains installed")
                            assertEquals(compiledTargets, activeTargets(host), "$label active target identities")
                            compiledTargets.forEach { active ->
                                assertEquals(true, active.javaClass.getMethod("isValidLastTier").invoke(active), "$label active compiled target")
                            }
                        }
                        assertEquals(0, handoff.arguments.depth, label)
                        assertEquals(0, handoff.arguments.retainedReferences(), label)
                        assertEquals(0, handoff.results.depth, label)
                        assertEquals(0, handoff.results.retainedReferences(), label)
                        assertNull(handoff.pending, label)
                    }
                }
                checkRows(); checkRows()
                // The fixture counts every strict nested guest call. Install the
                // active callees before checking that whole compiled call chain.
                compiledTargets = activeTargets(host)
                assertTrue(compiledTargets.size > 1, "$stage/$backend/$name active guest targets")
                val beforeSetup = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                assertEquals(0L, beforeSetup, "The full corpus ran interpreted before installation")
                val beforeCalls = compiledTargets.map { it.javaClass.getMethod("getCallCount").invoke(it) }
                argumentAllocations = handoff.arguments.allocations
                resultAllocations = handoff.results.allocations
                for (active in compiledTargets) {
                    active.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(active, true)
                    assertEquals(true, active.javaClass.getMethod("isValidLastTier").invoke(active), "$stage/$backend/$name installed")
                    Truffle.getRuntime().let { runtime -> runtime.javaClass.getMethod("bypassedInstalledCode",
                        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, active) }
                }
                assertEquals(beforeSetup, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                assertEquals(beforeCalls, compiledTargets.map { it.javaClass.getMethod("getCallCount").invoke(it) })
                checkRows(compiled = true)
                assertEquals(beforeCalls, compiledTargets.map { it.javaClass.getMethod("getCallCount").invoke(it) },
                    "No interpreted active target after installation")
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$name after execution")
                compiledTargets.forEach { active ->
                    assertEquals(true, active.javaClass.getMethod("isValidLastTier").invoke(active), "$stage/$backend/$name active compiled target")
                }
                val diagnostics = program.diagnostics()
                assertEquals(0L, (diagnostics.getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (diagnostics.getValue("blackholes") as Number).toLong())
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }
}
