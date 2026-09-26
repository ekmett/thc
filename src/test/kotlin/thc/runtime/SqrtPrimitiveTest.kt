// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

class SqrtPrimitiveTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    @BeforeEach fun verifyEvidence() {
        val evidence = Json.parse(File(root, "build/sqrt/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, evidence["schema"])
        assertEquals("9.14.1", evidence["ghc"])
        assertEquals(false, evidence["installedArtifactsHashed"])
        for (key in listOf("inputHashes", "artifactHashes")) for ((path, expected) in evidence[key] as Map<String, String>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale sqrt evidence: $path")
        }
        val required = listOf("scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "scripts/generate-scalar-signatures.py") +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }
                .map { it.relativeTo(root).path }
        assertTrue((evidence["inputHashes"] as Map<String, String>).keys.containsAll(required))
        val entries = listOf("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase") + mathEntries
        assertEquals(entries, evidence["entries"])
        assertEquals(rows().values.sumOf { it.size }.toLong(), evidence["nativeRows"])
        for (stage in listOf("pre", "post")) assertEquals(true,
            (Json.parse(File(root, "build/sqrt/$stage-audit.json").readText()) as Map<*, *>)["accepted"])
    }
    private fun module(stage: String) = Json.parse(File(root, "build/sqrt/$stage-core/SqrtAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial compilation")
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun rows() = File(root, "build/sqrt/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun call(program: ExecutableProgram, entry: String, row: List<String>, label: String) {
        val input: Any = when (entry) {
            "sqrtFloat" -> Float.fromBits(row[1].toLong().toInt())
            "sqrtDouble" -> Double.fromBits(java.lang.Long.parseUnsignedLong(row[1]))
            else -> row[1].toLong()
        }
        val result = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(input)))
        when (entry) {
            "sqrtFloat" -> {
                assertTrue(result is Float, label)
                val expected = Float.fromBits(row[2].toLong().toInt())
                if (expected.isNaN()) assertTrue((result as Float).isNaN(), label)
                else assertEquals(expected.toRawBits(), (result as Float).toRawBits(), label)
            }
            "sqrtDouble" -> {
                assertTrue(result is Double, label)
                val expected = Double.fromBits(java.lang.Long.parseUnsignedLong(row[2]))
                if (expected.isNaN()) assertTrue((result as Double).isNaN(), label)
                else assertEquals(expected.toRawBits(), (result as Double).toRawBits(), label)
            }
            else -> assertEquals(row[2].toLong(), result, label)
        }
    }
    @Test fun publicSqrtMatchesNativeWithInlining() = native(true)
    @Test fun publicSqrtMatchesNativeAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = rows().filterKeys { it in setOf("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase") }
        assertEquals(setOf("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase"), rows.keys)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((entry, selected) in rows) {
                    val linked = CoreModules.reachable(module(stage), entry)
                    val program = program(language, linked, backend)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val target = program.entryTarget(entry)
                    for (row in selected) call(program, entry, row, "$backend/$entry/interpreter/${row[1]}")
                    bindings.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach { compile(program.entryTarget(it["id"] as String)) }
                    for (row in selected) {
                        val label = "$stage/$backend/$entry/${row[1]}/inlining=$inlining"
                        val before = count(program)
                        call(program, entry, row, label)
                        assertEquals(before + if (entry.endsWith("Case")) 2 else 1, count(program), label)
                        valid(target, label)
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            } finally { context.leave() }
        }
    }
    private val mathEntries = listOf("fabs", "exp", "expm1", "log", "log1p", "sin", "cos", "tan",
        "asin", "acos", "atan", "sinh", "cosh", "tanh", "power")
        .flatMap { listOf(it + "Float", it + "Double") }

    private data class Dyadic(val coefficient: BigInteger, val exponent: Int) {
        fun squared() = Dyadic(coefficient.multiply(coefficient), exponent * 2)
        fun compare(other: Dyadic): Int {
            val origin = minOf(exponent, other.exponent)
            return coefficient.shiftLeft(exponent - origin)
                .compareTo(other.coefficient.shiftLeft(other.exponent - origin))
        }
    }

    private fun value(bits: Long, width: Int): Dyadic {
        val fractionWidth = if (width == 32) 23 else 52
        val bias = if (width == 32) 127 else 1023
        val fraction = bits and ((1L shl fractionWidth) - 1)
        val rawExponent = ((bits ushr fractionWidth) and (if (width == 32) 255L else 2047L)).toInt()
        val coefficient = fraction + if (rawExponent == 0) 0L else 1L shl fractionWidth
        return Dyadic(BigInteger.valueOf(coefficient), maxOf(rawExponent, 1) - bias - fractionWidth)
    }

    private fun midpoint(left: Dyadic, right: Dyadic): Dyadic {
        val exponent = minOf(left.exponent, right.exponent)
        return Dyadic(left.coefficient.shiftLeft(left.exponent - exponent)
            .add(right.coefficient.shiftLeft(right.exponent - exponent)), exponent - 1)
    }

    // Search representable roots with exact dyadic squares; the midpoint square
    // decides nearest-even without calling any JVM floating sqrt implementation.
    private fun exactSqrtBits(bits: Long, width: Int): Long? {
        val sign = 1L shl (width - 1)
        val infinity = if (width == 32) 0x7f800000L else 0x7ff0000000000000L
        val magnitude = bits and (sign - 1)
        if (magnitude == 0L) return bits
        if ((bits and sign) != 0L || magnitude > infinity) return null
        if (magnitude == infinity) return infinity
        val input = value(bits, width)
        var low = 0L
        var high = infinity - 1
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (value(mid, width).squared().compare(input) <= 0) low = mid else high = mid - 1
        }
        val lower = value(low, width)
        if (lower.squared().compare(input) == 0) return low
        val comparison = midpoint(lower, value(low + 1, width)).squared().compare(input)
        return if (comparison > 0 || comparison == 0 && low % 2 == 0L) low else low + 1
    }

    @Test fun nativeSqrtMatchesIndependentExactRoundingAndIntegerConsumers() {
        val rows = rows()
        var nanRows = 0
        for (entry in listOf("sqrtFloat", "sqrtDouble")) {
            val width = if (entry == "sqrtFloat") 32 else 64
            assertEquals(196, rows.getValue(entry).size, "$entry native domain")
            for (row in rows.getValue(entry)) {
                val bits = java.lang.Long.parseUnsignedLong(row[1])
                val native = java.lang.Long.parseUnsignedLong(row[2])
                val expected = exactSqrtBits(bits, width)
                if (expected == null) {
                    val infinity = if (width == 32) 0x7f800000L else 0x7ff0000000000000L
                    val fraction = if (width == 32) 0x007fffffL else 0x000fffffffffffffL
                    assertEquals(infinity, native and infinity, "$entry/${row[1]} exponent")
                    assertTrue((native and fraction) != 0L, "$entry/${row[1]} NaN fraction")
                    nanRows++
                } else assertEquals(expected, native, "$entry/${row[1]} exact bits")
            }
        }
        assertEquals(104, nanRows)
        for (entry in listOf("floatCase", "doubleCase")) {
            assertEquals(13, rows.getValue(entry).size, "$entry native domain")
            for (row in rows.getValue(entry))
                assertEquals(BigInteger(row[1]).sqrt().toLong(), row[2].toLong(), "$entry/${row[1]}")
        }
    }

    @Test fun exportedScalarMathRetainsExactTypedPrimitiveCalls() {
        val primitive = mapOf("powerFloat" to "powerFloat#", "powerDouble" to "**##")
        fun names(value: Any?): List<String> = when (value) {
            is List<*> -> (if (value.firstOrNull() == "prim") listOf(value[1] as String) else emptyList()) +
                value.flatMap(::names)
            is Map<*, *> -> value.values.flatMap(::names)
            else -> emptyList()
        }
        for (stage in listOf("pre", "post")) {
            val exported = module(stage)
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else
                "optimized-Core-after-Tidy-before-CorePrep", exported["boundary"])
            val bindings = (exported["bindings"] as List<Map<String, Any?>>).associateBy { it["name"] as String }
            for (entry in listOf("sqrtFloat", "sqrtDouble") + mathEntries) {
                val lambda = bindings.getValue(entry)["expr"] as List<*>
                val rep = if (entry.endsWith("Float")) "FloatRep" else "DoubleRep"
                val kind = if (entry.endsWith("Float")) "float" else "double"
                assertEquals("lam", lambda[0], "$stage/$entry")
                val inputRep = (lambda[1] as List<Map<String, Any?>>).single()["rep"] as Map<*, *>
                val resultRep = (lambda[3] as Map<String, Map<String, Any?>>).getValue("resultRep")
                assertEquals(listOf(rep), inputRep["primReps"])
                assertEquals(kind, inputRep["kind"])
                assertEquals(true, inputRep["evaluated"])
                assertEquals(listOf(rep), resultRep["primReps"])
                assertEquals(kind, resultRep["kind"])
                val expected = primitive[entry] ?: if (entry.startsWith("sqrt")) "$entry#" else
                    entry.removeSuffix("Float").removeSuffix("Double") + if (entry.endsWith("Float")) "Float#" else "Double#"
                assertEquals(listOf(expected), names(lambda[2]), "$stage/$entry primitive")
            }
        }
    }

    private fun mathCall(program: ExecutableProgram, entry: String, row: List<String>, label: String) {
        val float = entry.endsWith("Float")
        val input: Any = if (float) Float.fromBits(row[1].toLong().toInt())
            else Double.fromBits(java.lang.Long.parseUnsignedLong(row[1]))
        val result = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(input)))
        if (float) {
            assertTrue(result is Float, label)
            val actual = result as Float
            val expected = Float.fromBits(row[2].toLong().toInt())
            if (expected.isNaN()) assertTrue(actual.isNaN(), label)
            else if (expected.isInfinite() || expected == 0f)
                assertEquals(expected.toRawBits(), actual.toRawBits(), label)
            else assertTrue(abs(actual - expected) <= max(8 * Math.ulp(expected), abs(expected) * 2e-6f),
                "$label: native=$expected JVM=$actual")
        } else {
            assertTrue(result is Double, label)
            val actual = result as Double
            val expected = Double.fromBits(java.lang.Long.parseUnsignedLong(row[2]))
            if (expected.isNaN()) assertTrue(actual.isNaN(), label)
            else if (expected.isInfinite() || expected == 0.0)
                assertEquals(expected.toRawBits(), actual.toRawBits(), label)
            else assertTrue(abs(actual - expected) <= max(16 * Math.ulp(expected), abs(expected) * 2e-14),
                "$label: native=$expected JVM=$actual")
        }
    }

    @Test fun scalarMathMatchesNativeThroughTypedCompiledAstAndBytecode() {
        val rows = rows()
        assertTrue(rows.keys.containsAll(mathEntries))
        assertEquals(402, mathEntries.sumOf { rows.getValue(it).size })
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in mathEntries) {
                    val program = program(language, CoreModules.reachable(module(stage), entry), backend)
                    val target = program.entryTarget(entry)
                    val selected = rows.getValue(entry)
                    for (row in selected) mathCall(program, entry, row, "$stage/$backend/$entry/interpreter/${row[1]}")
                    compile(target)
                    val before = count(program)
                    for (row in selected) {
                        mathCall(program, entry, row, "$stage/$backend/$entry/compiled/${row[1]}")
                        valid(target, "$stage/$backend/$entry")
                    }
                    assertEquals(before + selected.size, count(program), "$stage/$backend/$entry")
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            } finally { context.leave() }
        }
    }
    @Test fun coldNaNsNegativesSubnormalsAndZeroSignsDoNotInvalidateCompiledSqrt() {
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in listOf("sqrtFloat", "sqrtDouble")) {
                    val selected = rows().getValue(entry)
                    val program = program(language, CoreModules.reachable(module("pre"), entry), backend)
                    val one = if (entry == "sqrtFloat") 0x3f800000L else 0x3ff0000000000000L
                    val warm = selected.single { it[1] == one.toString() }
                    repeat(20) { call(program, entry, warm, "warm") }
                    val target = program.entryTarget(entry); compile(target)
                    for (row in selected) {
                        val before = count(program)
                        call(program, entry, row, "$backend/$entry/cold/${row[1]}")
                        assertEquals(before + 1, count(program), "$backend/$entry/${row[1]}")
                        valid(target, "$backend/$entry/${row[1]}")
                    }
                }
            } finally { context.leave() }
        }
    }
    @Test fun sqrtRejectsConflictingResultStorageAndWrongArity() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in listOf("sqrtFloat", "sqrtDouble")) for (variant in listOf("result", "arity")) {
                    val linked = CoreModules.reachable(module(stage), entry)
                    val lambda = ((linked["bindings"] as List<Map<String, Any?>>).single()["expr"] as List<Any?>)
                    val body = lambda[2] as List<Any?>
                    val other = if (entry == "sqrtFloat") "double" else "float"
                    val proof = mapOf("kind" to other, "primReps" to listOf(if (other == "float") "FloatRep" else "DoubleRep"), "evaluated" to true)
                    if (variant == "arity") {
                        (body[2] as MutableList<Any?>).clear()
                        (body[3] as MutableList<Any?>).clear()
                        (body[6] as MutableMap<String, Any?>)["callDemand"] = mapOf("arity" to 0, "strictArgs" to emptyList<Boolean>())
                    } else {
                        (lambda[3] as MutableMap<String, Any?>)["resultRep"] = proof
                        (body[6] as MutableMap<String, Any?>)["rep"] = proof
                    }
                    val failure = assertThrows(RuntimeFault::class.java) { program(language, linked, backend) }
                    val detail = if (variant == "arity") "Primitive arity mismatch: $entry#"
                        else "Conflicting Core representation proofs"
                    assertTrue(failure.message.orEmpty().contains(detail), failure.message)
                }
            } finally { context.leave() }
        }
    }
}
