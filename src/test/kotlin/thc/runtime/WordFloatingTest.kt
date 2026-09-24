// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Random

class WordFloatingTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/word-floating")
    private val one = BigInteger.ONE
    private fun power(bit: Int) = one.shiftLeft(bit)
    private fun unsigned(x: Long) = BigInteger(java.lang.Long.toUnsignedString(x))
    private val names = listOf("wordFloat", "wordDouble")
    private fun json(path: String) = Json.parse(File(directory, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = json("$stage-core/WordFloatingAudit.json")
    private data class Row(val input: Long, val floatBits: Int, val doubleBits: Long)

    // Independent integer quotient/remainder model: never use a floating cast.
    private fun roundedBits(x: Long, precision: Int): Long {
        val n = unsigned(x)
        if (n.signum() == 0) return 0
        var exponent = n.bitLength() - 1
        val shift = exponent + 1 - precision
        var significand = if (shift <= 0) n.shiftLeft(-shift) else {
            val (q, r) = n.divideAndRemainder(power(shift))
            val halfway = power(shift - 1)
            if (r > halfway || r == halfway && q.testBit(0)) q + one else q
        }
        if (significand.bitLength() > precision) { significand = significand.shiftRight(1); exponent++ }
        val bias = if (precision == 24) 127 else 1023
        return ((exponent + bias).toLong() shl (precision - 1)) or
            significand.clearBit(precision - 1).toLong()
    }

    private fun domain(): List<Long> {
        val values = sortedSetOf(BigInteger.ZERO, one, BigInteger.TWO, BigInteger.valueOf(3), power(64) - one)
        for (bit in 1..63) for (delta in -1..1) values += power(bit) + BigInteger.valueOf(delta.toLong())
        for (precision in listOf(24, 53)) for (bit in precision..63)
            for (odd in listOf(1, 3, 5)) for (delta in -1..1)
                values += power(bit) + power(bit - precision) * BigInteger.valueOf(odd.toLong()) + BigInteger.valueOf(delta.toLong())
        return values.filter { it.signum() >= 0 && it < power(64) }.map { it.toLong() }
    }
    private fun rows(): List<Row> {
        val rows = File(directory, "oracle.tsv").readLines().map {
            val fields = it.split('\t'); assertEquals(3, fields.size)
            Row(java.lang.Long.parseUnsignedLong(fields[0]), fields[1].toLong().toInt(), fields[2].toLong())
        }
        assertEquals(domain(), rows.map { it.input }, "Native domain must be complete, ordered and duplicate-free")
        for (row in rows) {
            assertEquals(roundedBits(row.input, 24).toInt(), row.floatBits, "native Float ${unsigned(row.input)}")
            assertEquals(roundedBits(row.input, 53), row.doubleBits, "native Double ${unsigned(row.input)}")
        }
        return rows
    }

    @Test fun unsignedRoundingMatchesIndependentIntegerModel() {
        val random = Random(9141)
        for (x in domain() + List(10000) { random.nextLong() }) {
            assertEquals(roundedBits(x, 24).toInt(), WordFloatingConversions.toFloat(x).toRawBits(), "Float ${unsigned(x)}")
            assertEquals(roundedBits(x, 53), WordFloatingConversions.toDouble(x).toRawBits(), "Double ${unsigned(x)}")
        }
        // Mutation controls: signed conversion and Double->Float both give
        // wrong answers on this corpus, including positive signed-range inputs.
        assertNotEquals(roundedBits(-1, 53), (-1L).toDouble().toRawBits())
        for (bit in listOf(54, 62, 63)) {
            val x = (power(bit) + power(bit - 24) + one).toLong()
            assertNotEquals(roundedBits(x, 24).toInt(), WordFloatingConversions.toDouble(x).toFloat().toRawBits())
        }
    }

    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, input: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @Test fun genuineConversionsMatchNativeWithInlining() = native(true)
    @Test fun genuineConversionsMatchNativeAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        provenanceAndAuthenticUnfoldedPrimopsRemainExact()
        val rows = rows()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in names) {
                    val linked = CoreModules.reachable(module(stage), name)
                    val program = program(language, linked, backend)
                    val host = program.hostEntryTarget(1); val entry = program.entryValue(name)
                    val targets = (linked["bindings"] as List<Map<String, Any?>>).map { program.entryTarget(it["id"] as String) }
                    fun check(row: Row) {
                        val result = Calls.target(host, arrayOf(entry, arrayOf(row.input)))
                        val label = "$stage/$backend/$name/inlining=$inlining/${unsigned(row.input)}"
                        if (name == "wordFloat") {
                            assertTrue(result is Float, label)
                            assertEquals(row.floatBits, (result as Float).toRawBits(), label)
                        } else {
                            assertTrue(result is Double, label)
                            assertEquals(row.doubleBits, (result as Double).toRawBits(), label)
                        }
                    }
                    rows.forEach(::check)
                    targets.asReversed().forEach {
                        it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                        valid(it, "initial installation")
                    }
                    // Start with top-bit-set max, then replay all rows twice.
                    for (row in listOf(rows.last()) + rows + rows.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        targets.forEach { valid(it, "$stage/$backend/$name first/subsequent installed invocation") }
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            } finally { context.leave() }
        }
    }

    @Test fun provenanceAndAuthenticUnfoldedPrimopsRemainExact() {
        val manifest = json("manifest.json")
        assertEquals("9.14.1", manifest["ghc"]); assertEquals(64L, manifest["wordBits"])
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertEquals(names, manifest["entries"]); assertEquals(listOf("pre", "post"), manifest["stages"])
        assertEquals(rows().size.toLong(), manifest["nativeRows"])
        val sources = manifest["inputHashes"] as Map<String, String>
        val required = listOf("thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/WordFloatingFixtures.hs", "compiler/test-fixtures/WordFloatingAudit.hs",
            "compiler/test-fixtures/WordFloatingNative.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh",
            "compiler/toolchain.sh", "compiler/plugin.py") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        assertEquals(required.toSet(), sources.keys)
        val artifacts = manifest["artifactHashes"] as Map<String, String>
        assertEquals((listOf("oracle.tsv") + listOf("pre", "post").flatMap {
            listOf("$it-core/WordFloatingAudit.json", "$it-audit.json")
        }).map { "build/word-floating/$it" }.toSet(), artifacts.keys)
        for ((path, expected) in sources + artifacts) assertEquals(expected,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())), "Stale fixture $path")
        for (stage in listOf("pre", "post")) {
            val audit = json("$stage-audit.json")
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
            assertEquals(setOf("word2Float#", "word2Double#"), (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet())
            for (name in names) {
                val binding = (module(stage)["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
                val lambda = binding["expr"] as List<Any?>
                val parameter = (lambda[1] as List<Map<String, Any?>>).single()
                assertEquals(listOf("WordRep"), (parameter["rep"] as Map<*, *>)["primReps"])
                val calls = primitiveCalls(lambda)
                assertEquals(1, calls.size)
                assertEquals(if (name == "wordFloat") "word2Float#" else "word2Double#", (calls.single()[1] as List<*>)[1])
                val argument = (calls.single()[2] as List<List<Any?>>).single()
                assertEquals(listOf("var", parameter["id"]), argument.take(2)) // not constant-folded
                assertEquals(listOf("WordRep"), ((argument[2] as Map<*, *>)["rep"] as Map<*, *>)["primReps"])
                val result = listOf(if (name == "wordFloat") "FloatRep" else "DoubleRep")
                assertEquals(result, ((calls.single()[6] as Map<*, *>)["rep"] as Map<*, *>)["primReps"])
                assertEquals(result, ((lambda[3] as Map<*, *>)["resultRep"] as Map<*, *>)["primReps"])
            }
        }
    }
    private fun primitiveCalls(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app" && (value.getOrNull(1) as? List<*>)?.firstOrNull() == "prim")
            listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::primitiveCalls)
        is Map<*, *> -> value.values.flatMap(::primitiveCalls)
        else -> emptyList()
    }

    @Test fun exactWordArgumentFloatingResultAndUnaryArityAreRequired() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in names) for (variant in listOf("argument", "result", "arity")) {
                    val linked = CoreModules.reachable(module(stage), name)
                    val binding = (linked["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
                    val lambda = binding["expr"] as List<Any?>
                    val app = primitiveCalls(lambda).single()
                    if (variant == "argument") {
                        val proof = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
                        ((lambda[1] as List<MutableMap<String, Any?>>).single())["rep"] = proof
                        (((app[2] as List<List<Any?>>).single())[2] as MutableMap<String, Any?>)["rep"] = proof
                    } else if (variant == "result") {
                        val other = if (name == "wordFloat") "double" else "float"
                        (app[6] as MutableMap<String, Any?>)["rep"] = mapOf("kind" to other,
                            "primReps" to listOf(if (other == "float") "FloatRep" else "DoubleRep"), "evaluated" to true)
                    } else {
                        (app[2] as MutableList<Any?>).clear(); (app[3] as MutableList<Any?>).clear()
                        (app[6] as MutableMap<String, Any?>)["callDemand"] = mapOf("arity" to 0, "strictArgs" to emptyList<Boolean>())
                    }
                    assertThrows(RuntimeFault::class.java) { program(language, linked, backend) }
                }
            } finally { context.leave() }
        }
    }
}
