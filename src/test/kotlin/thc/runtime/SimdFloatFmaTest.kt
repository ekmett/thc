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

/** FloatX4 and DoubleX2 share one native executable and pre/post Core export. */
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
        for ((names, proof, wrong) in listOf(
            Triple(CoreVectors.fusedFloat, CoreVectors.proofFloat, CoreVectors.proofDouble),
            Triple(CoreVectors.fusedDouble, CoreVectors.proofDouble, CoreVectors.proofFloat))) for (name in names) {
            val good = List(3) { proof }
            CoreVectors.validate(name, good, proof)
            for (index in good.indices) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate(name, good.toMutableList().also { it[index] = wrong }, proof)
            }
            for (count in listOf(2, 4)) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate(name, List(count) { proof }, proof)
            }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, good, wrong) }
            assertThrows(RuntimeFault::class.java) { CoreVectors.validateFlags(listOf(false, true, false)) }
        }
    }

    @Test fun doubleSingleRoundingAndSignVariantsKeepIeeeControls() {
        val x = Double.fromBits(0x3ff0000000000001L)
        val y = Double.fromBits(0x3feffffffffffffeL)
        assertEquals(0.0, x * y - 1.0)
        assertEquals((-Math.scalb(1.0, -104)).toRawBits(), DoubleX2.fused(0,
            DoubleX2.broadcast(x), DoubleX2.broadcast(y), DoubleX2.broadcast(-1.0)).lane(0).toRawBits())
        val controls = listOf(Triple(0.0, 0.0, 0.0), Triple(-0.0, 0.0, -0.0),
            Triple(2.0, 3.0, 4.0), Triple(Double.POSITIVE_INFINITY, 0.0, 1.0),
            Triple(Double.NaN, 1.0, 0.0), Triple(Double.MAX_VALUE, 2.0, -Double.MAX_VALUE))
        for (operation in 0..3) for ((a,b,c) in controls) {
            val expected = Math.fma(if (operation >= 2) -a else a, b, if (operation and 1 != 0) -c else c)
            val actual = DoubleX2.fused(operation, DoubleX2.broadcast(a), DoubleX2.broadcast(b), DoubleX2.broadcast(c)).lane(0)
            if (expected.isNaN()) assertTrue(actual.isNaN(), "NaN payload is unspecified")
            else assertEquals(expected.toRawBits(), actual.toRawBits(), "$operation/$a/$b/$c")
        }
        assertEquals(0L, DoubleX2.fused(2, DoubleX2.broadcast(0.0),
            DoubleX2.broadcast(0.0), DoubleX2.broadcast(0.0)).lane(0).toRawBits())
    }

    @Test fun genuineCoreAndNativeLaneBitsSurviveBothCompiledBackends() {
        for (double in listOf(false, true)) checkGenuineCoreAndNativeLaneBits(double)
    }

    private fun checkGenuineCoreAndNativeLaneBits(double: Boolean) {
        val names = if (double) listOf("doubleAddCase", "doubleSubCase", "doubleNegAddCase", "doubleNegSubCase") else this.names
        val laneCount = if (double) 2 else 4
        fun bits(value: Double) = if (double) value.toRawBits() else this.bits(value.toFloat())
        fun same(expected: Long, actual: Long, label: String) {
            if (!double) this.same(expected, actual, label)
            else if (Double.fromBits(expected).isNaN()) assertTrue(Double.fromBits(actual).isNaN(), "$label NaN (payload unspecified)")
            else assertEquals(expected, actual, label)
        }
        fun lanes(input: List<Long>): List<Triple<Double, Double, Double>> {
            if (!double) return this.lanes(input).map { (x,y,z) -> Triple(x.toDouble(), y.toDouble(), z.toDouble()) }
            val (x,y,z) = input.map(Double::fromBits)
            return listOf(Triple(x,y,z), Triple(y,z,-x))
        }
        fun model(operation: Int, lane: Triple<Double, Double, Double>): Long {
            val (x,y,z) = lane
            return if (double) bits(Math.fma(if (operation >= 2) -x else x, y, if (operation and 1 != 0) -z else z))
                else this.model(operation, Triple(x.toFloat(), y.toFloat(), z.toFloat()))
        }
        fun unsigned(value: Long) = java.lang.Long.toUnsignedString(value)
        fun parseBits(value: String) = java.lang.Long.parseUnsignedLong(value)
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        val exportOnly = System.getProperty("os.arch") in listOf("aarch64", "arm64")
        assertEquals(if (exportOnly) listOf("pre") else listOf("pre", "post"), manifest["stages"])
        assertEquals(if (exportOnly) null else 528L, manifest["nativeRows"], "Native preparation cannot silently downgrade")
        assertEquals(listOf("-mavx", "-mfma"), manifest["nativeFlags"])
        for ((path, want) in ((manifest["inputHashes"] as Map<String, String>) +
                (manifest["artifactHashes"] as Map<String, String>))) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(want, hash, "Stale shared SIMD fused fixture $path")
        }
        assertEquals(names, manifest[if (double) "doubleEntries" else "entries"])
        for (stage in manifest["stages"] as List<String>) {
            val audit = Json.parse(File(directory, "$stage-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], "$stage canonical audit")
            assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val candidate = Json.parse(File(directory, "$stage-double-audit.json").readText()) as Map<String, Any?>
            assertEquals(false, candidate["accepted"], "DoubleX2 remains gated until native/compiled validation")
            assertEquals(emptyList<Any?>(), candidate["missingGlobals"])
            val issues = candidate["issues"] as List<Map<String, Any?>>
            assertEquals(setOf("unsupported-primitive"), issues.map { it["code"] }.toSet())
            assertEquals(CoreVectors.fusedDouble.toSet(), issues.map { it["detail"] }.toSet())
        }
        val inputs = if (double) (manifest["doubleInputs"] as List<List<String>>).map { row -> row.map(::parseBits) }
            else (manifest["inputs"] as List<List<Number>>).map { row -> row.map { it.toLong() } }
        assertEquals(22, inputs.size)
        val native = if (manifest["nativeRows"] != null) File(directory, "oracle.txt").readLines().map { it.split(' ') }.also {
            assertEquals(528, it.size)
        }.filter { it[0] in names } else null
        val expectedRequests = names.flatMap { name -> inputs.flatMap { input -> (0 until laneCount).map { lane ->
            listOf(name) + input.map(::unsigned) + lane.toString()
        } } }
        if (native != null) {
            assertEquals(expectedRequests, native.map { it.take(5) })
            assertEquals(4 * 22 * laneCount, native.size)
            for (row in native) same(model(names.indexOf(row[0]), lanes(row.subList(1,4).map(::parseBits))[row[4].toInt()]),
                parseBits(row[5]), "Native ${row.take(5)}")
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
                            val expected = native?.first { row -> row.take(5) == listOf(name) + input.map(::unsigned) + lane.toString() }
                                ?.last()?.let(::parseBits) ?: model(operation, lanes(input)[lane])
                            same(expected, function.execute(input[0],input[1],input[2],lane.toLong()).asLong(),
                                "$stage/$backend/$name/$input/$lane")
                        }
                        for (input in inputs) for (lane in 0 until laneCount) check(input, lane)
                        assertTrue(function.invokeMember("compile").asBoolean())
                        for (input in inputs) for (lane in 0 until laneCount) {
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
                        assertEquals(3 * laneCount, inputLayout.logical.physicalArity)
                        assertEquals(if (double) CoreVector.DOUBLEX2 else CoreVector.FLOATX4, resultShape.proof.vector)
                        fun workerCall(input: List<Long>) {
                            val laneInputs = lanes(input)
                            val loan = language.handoffState.get().arguments.acquire(inputLayout.packet)
                            loan.inputMode = 1
                            inputLayout.packet.setLong(loan, 0, 0L)
                            for (index in 0 until laneCount) {
                                laneInputs[index].toList().forEachIndexed { argument, value ->
                                    val slot = inputLayout.header + argument * laneCount + index
                                    if (double) inputLayout.packet.setDouble(loan, slot, value)
                                    else inputLayout.packet.setFloat(loan, slot, value.toFloat())
                                }
                            }
                            val result = ownedTupleResult(invokeTypedInput(worker, loan) { Calls.target(worker, it) }, resultShape)
                            for (lane in 0 until laneCount) same(model(operation, laneInputs[lane]),
                                bits(if (double) resultShape.layout.getDouble(result, lane)
                                    else resultShape.layout.getFloat(result, lane).toDouble()), "$stage/$backend/$name worker lane $lane")
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
