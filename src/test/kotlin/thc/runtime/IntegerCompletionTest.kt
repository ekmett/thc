// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
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
import java.util.Random

class IntegerCompletionTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/integer-completion"
    private val names = listOf("quotRemInt8", "quotRemInt16", "quotRemInt32", "quotRemWord8", "quotRemWord16", "quotRemWord32",
        "shiftRLInt8", "shiftRLInt16", "shiftRLInt32", "quotRemWord2", "mulMay")
    private val counts = listOf(311,311,311,312,312,312,128,192,320,544,320)
    private val modulus = BigInteger.ONE.shiftLeft(64)
    private fun unsigned(value: Long): BigInteger = BigInteger.valueOf(value).mod(modulus)
    private fun module(stage: String = "pre") = Json.parse(File(root,
        "$directory/$stage-core/IntegerCompletionAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean = false) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun program(language: Language, input: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        // Restore a retired JVM-wide boundary without entering the guest.
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode",
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
        valid(target)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertNull(state.pending)
    }
    private fun primitive(name: String) = when {
        name == "mulMay" -> "mulIntMayOflo#"
        name.startsWith("shiftRL") -> "uncheckedS" + name.drop(1) + "#"
        else -> "$name#"
    }
    private data class Row(val name: String, val x: Long, val y: Long, val z: Long, val fields: List<Long>)
    private fun rows(): List<Row> = File(root, "$directory/oracle.tsv").readLines().map {
        val f = it.split('\t'); assertEquals(6, f.size)
        Row(f[0], f[1].toLong(), f[2].toLong(), f[3].toLong(), f.drop(4).map(String::toLong))
    }.also {
        assertEquals(3373, it.size)
        assertEquals(names.zip(counts).toMap(), it.groupingBy { row -> row.name }.eachCount())
        val requests = File(root, "$directory/requests.tsv").readLines()
        assertEquals(requests, it.map { row -> "${row.name}\t${row.x}\t${row.y}\t${row.z}" })
    }
    private fun model(row: Row): List<Long> {
        if (row.name == "mulMay") {
            val product = BigInteger.valueOf(row.x) * BigInteger.valueOf(row.y)
            val overflow = if (product < BigInteger.valueOf(Long.MIN_VALUE) || product > BigInteger.valueOf(Long.MAX_VALUE)) 1L else 0L
            return listOf(overflow, overflow)
        }
        if (row.name == "quotRemWord2") {
            val dividend = unsigned(row.x).shiftLeft(64) + unsigned(row.y)
            return dividend.divideAndRemainder(unsigned(row.z)).map(BigInteger::toLong)
        }
        val bits = row.name.takeLastWhile(Char::isDigit).toInt()
        val width = BigInteger.ONE.shiftLeft(bits)
        fun narrow(value: Long): BigInteger {
            val low = BigInteger.valueOf(value).mod(width)
            return if (row.name.contains("Int") && low.testBit(bits - 1)) low - width else low
        }
        if (row.name.startsWith("shift")) {
            val low = BigInteger.valueOf(row.x).mod(width).shiftRight(row.y.toInt())
            val result = if (low.testBit(bits - 1)) low - width else low
            return List(2) { result.toLong() }
        }
        return narrow(row.x).divideAndRemainder(narrow(row.y)).map(BigInteger::toLong)
    }
    private fun checkHashes() {
        val manifest = Json.parse(File(root, "$directory/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"]); assertEquals(64L, (manifest["wordBits"] as Number).toLong())
        assertEquals(names, manifest["entries"]); assertEquals(3373L, (manifest["nativeRows"] as Number).toLong())
        val inputs = setOf("compiler/test-fixtures/IntegerCompletionAudit.hs", "thc.cabal", "test/haskell-fixtures/Main.hs",
            "test/haskell-fixtures/IntegerCompletionFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py", "scripts/audit-core.py",
            "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { "compiler/THC/${it.name}" } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { "scripts/${it.name}" }
        val commands = listOf("ghc-version", "ghc-info", "pre-export", "pre-audit", "post-export", "post-audit", "native-build", "native-oracle")
        val artifacts = setOf("requests.tsv", "NativeIntegerCompletion.hs", "native/integer-completion-oracle", "oracle.tsv") +
            listOf("pre", "post").flatMap { stage -> listOf("$stage-audit.json", "$stage-core/IntegerCompletionAudit.json") } +
            commands.flatMap { name -> listOf("stdout", "stderr", "command.json").map { "commands/$name.$it" } }
        for ((kind, paths) in listOf("inputHashes" to inputs, "artifactHashes" to artifacts.map { "$directory/$it" }.toSet())) {
            val recorded = manifest[kind] as Map<String, String>
            assertEquals(paths, recorded.keys, "$kind must be a closed inventory")
            for ((path, expected) in recorded) assertEquals(expected,
                MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }, path)
        }
        for (command in commands) {
            val record = Json.parse(File(root, "$directory/commands/$command.command.json").readText()) as Map<String, Any?>
            assertEquals(0L, (record["exit"] as Number).toLong(), command)
        }
    }
    @Test fun nativeAndIndependentModelAgreeInBothBackendsOnEveryInstalledCall() {
        checkHashes()
        val allRows = rows()
        for (row in allRows) {
            val exact = model(row)
            if (row.name == "mulMay") {
                assertEquals(row.fields[0], row.fields[1])
                assertTrue(row.fields[0] in 0L..1L)
                if (row.fields[0] == 0L) assertEquals(0L, exact[0], "Native overflow false negative: $row")
                if (row.x in -2L..2L && row.y in -2L..2L) assertEquals(0L, row.fields[0])
            } else assertEquals(exact, row.fields, "Native $row")
        }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (inlining in listOf(false, true)) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val original = module(stage)
                    for (name in names) {
                        val evidence = ArrayCoreEvidence(original, name)
                        assertEquals(1, evidence.bindings.size)
                        assertTrue(evidence.globalReferences(evidence.root["expr"]).isEmpty())
                        assertEquals(1, evidence.guestLambdas(evidence.root["expr"]).size, "One genuine scalar root")
                        assertEquals(1, evidence.primitiveCounts[primitive(name)], "$stage/$name genuine primitive")
                        val lambda = evidence.guestLambdas(evidence.root["expr"]).single()
                        val expectedLabel = "lambda " + (lambda[1] as List<Map<String, Any?>>).joinToString { it["name"].toString() }
                        val p = program(language, CoreModules.reachable(original, name) + ("instrument" to true), backend)
                        val entry = p.entryTarget(name)
                        assertEquals(expectedLabel, entry.rootNode.name)
                        fun count() = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        fun calls() = entry.javaClass.getMethod("getCallCount").invoke(entry)
                        fun check(row: Row, compiled: Boolean) {
                            for (field in 0..1) {
                                val before = count()
                                try {
                                    assertEquals(model(row)[field], Calls.target(entry, arrayOf(0L, row.x, row.y, row.z, field.toLong())),
                                        "$stage/$backend/inlining=$inlining/$row/$field")
                                    if (compiled) { assertEquals(1L, count() - before, "Every first/subsequent call enters its one source-proven root"); valid(entry) }
                                } finally { released(language) }
                            }
                        }
                        val corpus = allRows.filter { it.name == name }
                        corpus.forEach { check(it, false) }
                        val allocations = language.handoffState.get().let { it.arguments.allocations to it.results.allocations }
                        val before = count(); val interpreterCalls = calls()
                        compile(entry)
                        assertEquals(before, count()); assertEquals(interpreterCalls, calls(), "Compilation setup cannot enter guest code")
                        corpus.asReversed().forEach { row ->
                            check(row, true)
                            assertSame(entry, p.entryTarget(name)); assertEquals(interpreterCalls, calls())
                            assertEquals(allocations, language.handoffState.get().let { it.arguments.allocations to it.results.allocations })
                        }
                        for (key in listOf("unsupportedTraps", "papAllocations", "thunkEvaluations", "blackholes"))
                            assertEquals(0L, (p.diagnostics().getValue(key) as Number).toLong(), key)
                    }
                } finally { context.leave() }
            }
    }
    @Test fun unsignedDoubleWordDivisionMatchesUnboundedArithmeticIncludingTopBitDivisors() {
        val random = Random(9141)
        repeat(20000) {
            val divisor = random.nextLong() or 1L
            val high = unsigned(random.nextLong()).mod(unsigned(divisor)).toLong()
            val low = random.nextLong()
            val expected = (unsigned(high).shiftLeft(64) + unsigned(low)).divideAndRemainder(unsigned(divisor))
            val quotient = unsignedDoubleWordQuotient(high, low, divisor)
            assertEquals(expected[0].toLong(), quotient)
            assertEquals(expected[1].toLong(), low - quotient * divisor)
        }
    }
    @Test fun allEightBitQuotientRemainderInputsAreNarrowedByTheOperation() {
        for (x in -128L..127L) for (y in -128L..127L) if (y != 0L && !(x == -128L && y == -1L)) {
            assertEquals(x / y, TupleArithmeticOp.QUOT_REM_INT8.first(x or (123L shl 8), y or (45L shl 8)))
            assertEquals(x % y, TupleArithmeticOp.QUOT_REM_INT8.second(x, y))
        }
        for (x in 0L..255L) for (y in 1L..255L) {
            assertEquals(x / y, TupleArithmeticOp.QUOT_REM_WORD8.first(x or (123L shl 8), y or (45L shl 8)))
            assertEquals(x % y, TupleArithmeticOp.QUOT_REM_WORD8.second(x, y))
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun integralLexicalAnnotationsDoNotChooseWidthSignednessOrTupleOrder() {
        val integral = setOf("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
            "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep")
        fun project(value: Any?, rep: String): Any? = when (value) {
            is Map<*, *> -> value.mapValues { project(it.value, rep) }
            is List<*> -> value.map { project(it, rep) }
            is String -> if (value in integral) rep else value
            else -> value
        }
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (rep in listOf("IntRep", "Word64Rep")) for (name in names) {
                    val input = project(CoreModules.reachable(module(), name), rep) as Map<String, Any?>
                    val p = program(language, input, backend)
                    val row = when {
                        name == "quotRemWord2" -> Row(name, 3L, -13L, 5L, emptyList())
                        name == "mulMay" -> Row(name, Long.MIN_VALUE, -1L, 0L, emptyList())
                        else -> Row(name, -13L, 5L, 0L, emptyList())
                    }
                    for (field in 0..1) assertEquals(model(row)[field],
                        Calls.target(p.entryTarget(name), arrayOf(0L, row.x, row.y, row.z, field.toLong())), "$backend/$rep/$name")
                    released(language)
                }
            } finally { context.leave() }
        }
    }
    @Test fun actualCarrierAggregateArityAndSaturationErrorsAreRejected() {
        val mutations = listOf<(MutableList<Any?>) -> Unit>(
            { app -> (app[2] as MutableList<Any?>)[0] = listOf("lit", "float", "1.0",
                mapOf("rep" to mapOf("kind" to "float", "primReps" to listOf("FloatRep"), "evaluated" to true))) },
            { app -> (app[3] as MutableList<Any?>)[0] = true },
            { app -> (app[2] as MutableList<Any?>).removeLast(); (app[3] as MutableList<Any?>).removeLast()
                (app[6] as MutableMap<String, Any?>).remove("callDemand") },
            { app -> val rep = (app[6] as MutableMap<String, Any?>)["rep"] as MutableMap<String, Any?>
                rep["kind"] = "float"; rep["primReps"] = listOf("FloatRep"); rep.remove("aggregate"); rep.remove("components") }
        )
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in names) for ((i, mutate) in mutations.withIndex()) for (diagnostic in listOf(false, true)) {
                    // Ordinary scalar lowering selects its physical result
                    // from the instruction, not lexical result/levity tags.
                    // Tuple lowering additionally validates that explicit protocol.
                    if (i in listOf(1, 3) && !name.startsWith("quotRem")) continue
                    val input = CoreModules.reachable(module(), name)
                    val app = applications(input).single { (it[1] as List<*>).take(2) == listOf("prim", primitive(name)) }
                    mutate(app)
                    assertThrows(RuntimeFault::class.java, { program(language, input + ("diagnosticUnsupported" to diagnostic), backend) }, "$backend/$name/mutation$i")
                }
                for (name in names) {
                    val input = CoreModules.reachable(module(), name)
                    val app = applications(input).single { (it[1] as List<*>).take(2) == listOf("prim", primitive(name)) }
                    val prim = (app[1] as List<*>).toList(); app.clear(); app.addAll(prim)
                    assertThrows(UnsupportedCore::class.java, { program(language, input, backend) }, "$backend/$name first-class")
                }
            } finally { context.leave() }
        }
    }
    @Test fun undefinedDivisionInputsFailCleanlyAndValidCallsRecover() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in names.filter { it.startsWith("quotRem") }) {
                    val p = program(language, CoreModules.reachable(module(), name), backend)
                    val target = p.entryTarget(name)
                    val bad = if (name == "quotRemWord2") listOf(Triple(0L,1L,0L), Triple(3L,1L,3L), Triple(-1L,1L,Long.MIN_VALUE))
                        else listOf(Triple(1L,0L,0L), Triple(1L,1L shl name.takeLastWhile(Char::isDigit).toInt(),0L))
                    for ((x,y,z) in bad) {
                        try { assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L,x,y,z,0L)) } }
                        finally { released(language) }
                    }
                    val good = if (name == "quotRemWord2") Row(name,0L,7L,3L,emptyList()) else Row(name,7L,3L,0L,emptyList())
                    for (field in 0..1) assertEquals(model(good)[field], Calls.target(target, arrayOf(0L,good.x,good.y,good.z,field.toLong())))
                    released(language)
                }
            } finally { context.leave() }
        }
    }
}
