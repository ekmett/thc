// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class SimdFloatFmaTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-floatx4-fma")
    private val names = listOf("addCase", "subCase", "negAddCase", "negSubCase")
    private fun bits(value: Float) = value.toRawBits().toLong() and 0xffff_ffffL
    private fun same(expected: Long, actual: Long, label: String) {
        if (Float.fromBits(expected.toInt()).isNaN())
            assertTrue(Float.fromBits(actual.toInt()).isNaN(), "$label NaN (payload unspecified)")
        else assertEquals(expected, actual, label)
    }
    private fun lanes(input: List<Long>): List<Triple<Float, Float, Float>> {
        val (x,y,z) = input.map { Float.fromBits(it.toInt()) }
        return listOf(Triple(x,y,z), Triple(y,z,x), Triple(z,x,y), Triple(x,y,-z))
    }
    private fun model(operation: Int, lane: Triple<Float, Float, Float>): Long {
        val (x,y,z) = lane
        return bits(Math.fma(if (operation >= 2) -x else x, y, if (operation and 1 != 0) -z else z))
    }

    @Test fun singleRoundingAndOperandNegationPreserveCancellationAndSignedZero() {
        val x = Float.fromBits(0x3f800001)
        val y = Float.fromBits(0x3f7ffffe)
        assertEquals(0.0f, x * y - 1f)
        assertEquals(bits(-Math.scalb(1.0f, -46)), bits(FloatX4.fused(0,
            FloatX4.broadcast(x), FloatX4.broadcast(y), FloatX4.broadcast(-1f)).lane(0)))
        for (operation in 0..3) {
            val a = FloatX4.pack(0f, -0f, 2f, -2f)
            val b = FloatX4.pack(0f, 0f, 3f, 3f)
            val c = FloatX4.pack(0f, -0f, 4f, -4f)
            val actual = FloatX4.fused(operation, a, b, c)
            for (lane in 0..3) same(model(operation, Triple(a.lane(lane), b.lane(lane), c.lane(lane))),
                bits(actual.lane(lane)), "$operation/$lane")
        }
        assertEquals(0L, bits(FloatX4.fused(2, FloatX4.broadcast(0f),
            FloatX4.broadcast(0f), FloatX4.broadcast(0f)).lane(0)), "Negating the rounded answer would give -0")
    }

    @Test fun exactThreeVectorInputsAndResultAreRequired() {
        for (name in CoreVectors.fusedFloat) {
            val good = List(3) { CoreVectors.proofFloat }
            CoreVectors.validate(name, good, CoreVectors.proofFloat)
            for (index in good.indices) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate(name, good.toMutableList().also { it[index] = CoreVectors.proofDouble }, CoreVectors.proofFloat)
            }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, good.take(2), CoreVectors.proofFloat) }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, good, CoreVectors.proofDouble) }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validateFlags(listOf(false, true, false)) }
        }
    }

    @Test fun genuineCoreAndNativeLaneBitsSurviveBothCompiledBackends() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        for ((path, want) in ((manifest["inputHashes"] as Map<String, String>) +
                (manifest["artifactHashes"] as Map<String, String>))) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(want, hash, "Stale FloatX4 fused fixture $path")
        }
        assertEquals(names, manifest["entries"])
        val inputs = (manifest["inputs"] as List<List<Number>>).map { row -> row.map { it.toLong() } }
        assertEquals(22, inputs.size)
        val native = if (manifest["nativeRows"] != null) File(directory, "oracle.txt").readLines().map { it.split(' ') } else null
        val expectedRequests = names.flatMap { name -> inputs.flatMap { input -> (0..3).map { lane ->
            listOf(name) + input.map(Long::toString) + lane.toString()
        } } }
        if (native != null) {
            assertEquals(expectedRequests, native.map { it.take(5) })
            assertEquals(352L, manifest["nativeRows"])
            for (row in native) same(model(names.indexOf(row[0]), lanes(row.subList(1,4).map(String::toLong))[row[4].toInt()]),
                row[5].toLong(), "Native ${row.take(5)}")
        }
        for (stage in manifest["stages"] as List<String>) for (backend in listOf("ast", "bytecode"))
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = Json.parse(File(directory, "$stage-core/SimdFloatFma.json").readText()) as Map<String, Any?>
                    for ((operation,name) in names.withIndex()) {
                        val linked = CoreModules.reachable(source, name) + mapOf("instrument" to true)
                        val program = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val function = context.asValue(EntryValue(program, name, 4))
                        fun check(input: List<Long>, lane: Int) {
                            val expected = native?.first { row -> row.take(5) == listOf(name) + input.map(Long::toString) + lane.toString() }
                                ?.last()?.toLong() ?: model(operation, lanes(input)[lane])
                            same(expected, function.execute(input[0],input[1],input[2],lane.toLong()).asLong(),
                                "$stage/$backend/$name/$input/$lane")
                        }
                        for (input in inputs) for (lane in 0..3) check(input, lane)
                        assertTrue(function.invokeMember("compile").asBoolean())
                        for (input in inputs) for (lane in 0..3) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(input, lane)
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                                "Every first-installed lane call must enter compiled code")
                            assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                            assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                            assertEquals(0, language.handoffState.get().results.retainedReferences())
                            assertNull(language.handoffState.get().pending)
                        }
                        // Compile the genuine vector worker itself as well: a
                        // compiled scalar wrapper alone could hide an interpreted FMA.
                        val worker = program.entryTarget(name.removeSuffix("Case") + "Worker")
                        val workerRoot = worker.rootNode as GuestRoot
                        val inputLayout = requireNotNull(workerRoot.typedInput)
                        val resultShape = requireNotNull(workerRoot.tupleResult)
                        assertEquals(3, inputLayout.logical.logicalArity)
                        assertEquals(12, inputLayout.logical.physicalArity)
                        assertEquals(CoreVector.FLOATX4, resultShape.proof.vector)
                        fun workerCall(input: List<Long>) {
                            val laneInputs = lanes(input)
                            val loan = language.handoffState.get().arguments.acquire(inputLayout.packet)
                            loan.inputMode = 1
                            inputLayout.packet.setLong(loan, 0, 0L)
                            for (index in 0..3) {
                                val (x,y,z) = laneInputs[index]
                                inputLayout.packet.setFloat(loan, inputLayout.header + index, x)
                                inputLayout.packet.setFloat(loan, inputLayout.header + 4 + index, y)
                                inputLayout.packet.setFloat(loan, inputLayout.header + 8 + index, z)
                            }
                            val result = ownedTupleResult(invokeTypedInput(worker, loan) { Calls.target(worker, it) }, resultShape)
                            for (lane in 0..3) same(model(operation, laneInputs[lane]),
                                bits(resultShape.layout.getFloat(result, lane)), "$stage/$backend/$name worker lane $lane")
                        }
                        inputs.forEach(::workerCall)
                        worker.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(worker, true)
                        for (input in inputs) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            workerCall(input)
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            assertEquals(true, worker.javaClass.getMethod("isValidLastTier").invoke(worker))
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                    }
                } finally { context.leave() }
            }
    }
}
