// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

class FloatingRemainderTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val dir = "build/floating-remainder"
    private val names = listOf("Float","Double").flatMap { t -> listOf("asinh","acosh","atanh","min","max").map { it + t } } +
        listOf("decodeWordsDirect","decodeWordsCall","asinhExample")
    private data class Row(val name: String, val a: Long, val b: Long, val values: List<Long>)
    private fun decode(name: String) = name.startsWith("decodeWords")
    private fun floating(name: String) = name.endsWith("Float")
    private fun value(name: String, bits: Long) = if (floating(name)) Float.fromBits(bits.toInt()).toDouble() else Double.fromBits(bits)
    private fun bits(name: String, x: Double) = if (floating(name)) x.toFloat().toRawBits().toLong() and 0xffffffffL else x.toRawBits()
    private fun rows(text: String): List<Row> {
        val result = text.lineSequence().filter(String::isNotEmpty).map {
            val f = it.split(' '); require(f.size == 7)
            Row(f[0], f[1].toLong(), f[2].toLong(), f.drop(3).map(String::toLong))
        }.toList()
        val requests = File(root,"$dir/inputs.tsv").readLines().map {
            val f = it.split(' '); Triple(f[0],f[1].toLong(),f[2].toLong())
        }
        require(result.map { Triple(it.name,it.a,it.b) } == requests) { "Missing, repeated or reordered native rows" }
        require(result.map { it.name }.distinct() == names)
        require(result.size > 5000)
        return result
    }
    private fun evidence(): List<Row> {
        val manifest = Json.parse(File(root,"$dir/manifest.json").readText()) as Map<String,Any?>
        assertEquals(1L,manifest["schema"]); assertEquals("9.14.1",manifest["ghc"]); assertEquals(names,manifest["entries"])
        val inputs = setOf("compiler/test-fixtures/FloatingRemainderAudit.hs","compiler/test-fixtures/FloatingRemainderNative.hs",
            "examples/THC/InverseHyperbolic.hs","thc.cabal","test/haskell-fixtures/Main.hs",
            "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/FloatingRemainderFixtures.hs",
            "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
            "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json") +
            File(root,"compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root,"scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        val commands = listOf("native-build","native-oracle") + listOf("pre","post").flatMap { stage ->
            listOf("$stage-export") + names.map { "$stage-$it-audit" }
        }
        val outputs = setOf("$dir/inputs.tsv","$dir/oracle.tsv","$dir/native/oracle") +
            listOf("pre","post").flatMap { stage ->
                listOf("$dir/$stage-core/FloatingRemainderAudit.json","$dir/$stage-core/THC.InverseHyperbolic.json") +
                    names.map { "$dir/$stage-$it-audit.json" }
            } + commands.flatMap { name -> listOf("stdout","stderr","command.json").map { "$dir/commands/$name.$it" } }
        for ((kind,required) in listOf("inputHashes" to inputs,"artifactHashes" to outputs)) {
            val hashes = manifest[kind] as Map<String,String>; assertEquals(required.toSet(),hashes.keys,kind)
            for ((path,hash) in hashes) assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }, "Stale floating evidence: $path")
        }
        for (stage in listOf("pre","post")) for (name in names) {
            val report = Json.parse(File(root,"$dir/$stage-$name-audit.json").readText()) as Map<*,*>
            assertEquals(true,report["accepted"]); assertEquals(emptyList<Any>(),report["issues"])
            assertEquals(emptyList<Any>(),report["missingGlobals"])
            val primitives = (report["primitives"] as List<Map<String,Any?>>).map { it["name"] }
            assertTrue((if (decode(name)) "decodeDouble_2Int#" else if (name == "asinhExample") "asinhDouble#" else "$name#") in primitives)
        }
        return rows(File(root,"$dir/oracle.tsv").readText()).also { assertEquals(it.size.toLong(),manifest["nativeRows"]) }
    }

    /** Independent integer normalization, deliberately not FloatDecodeOp. */
    private fun words(raw: Long): List<Long> {
        val u = BigInteger(java.lang.Long.toUnsignedString(raw))
        val fraction = u.and(BigInteger.ONE.shiftLeft(52).subtract(BigInteger.ONE))
        val exponent = u.shiftRight(52).and(BigInteger.valueOf(2047)).toInt()
        if (exponent == 0 && fraction.signum() == 0) return listOf(1,0,0,0)
        val shift = if (exponent == 0) 53 - fraction.bitLength() else 0
        val mantissa = if (exponent == 0) fraction.shiftLeft(shift) else fraction.setBit(52)
        return listOf(if (raw < 0) -1 else 1, mantissa.shiftRight(32).toLong(),
            mantissa.and(BigInteger("ffffffff",16)).toLong(), (if (exponent == 0) -1074-shift else exponent-1075).toLong())
    }

    /** 90-digit model uses exact binary inputs, decimal sqrt and a convergent
     * logarithm series after power-of-two reduction, not production log/log1p. */
    private val mc = MathContext(90)
    private val two = BigDecimal(2)
    private fun logSeries(x: BigDecimal): BigDecimal {
        val z = x.subtract(BigDecimal.ONE).divide(x.add(BigDecimal.ONE),mc)
        val z2 = z.multiply(z,mc)
        var term = z; var sum = z
        for (n in 1..110) {
            term = term.multiply(z2,mc)
            sum = sum.add(term.divide(BigDecimal(2*n+1),mc),mc)
        }
        return sum.multiply(two,mc)
    }
    private val logTwo by lazy { logSeries(two) }
    private fun logarithm(input: BigDecimal): BigDecimal {
        var exponent = Math.floor((input.precision() - input.scale() - 1) * 3.321928094887362).toInt()
        var x = if (exponent >= 0) input.divide(two.pow(exponent,mc),mc) else input.multiply(two.pow(-exponent,mc),mc)
        while (x < BigDecimal.ONE) { x = x.multiply(two,mc); exponent-- }
        while (x >= two) { x = x.divide(two,mc); exponent++ }
        return logSeries(x).add(logTwo.multiply(BigDecimal(exponent),mc),mc)
    }
    private fun mathematical(name: String, x: Double): Double {
        if (x.isNaN()) return Double.NaN
        val a = Math.abs(x)
        if (name.startsWith("asinh")) {
            if (!x.isFinite() || a < 1e-20) return x
            val n = BigDecimal(a)
            return Math.copySign(logarithm(n.add(n.multiply(n,mc).add(BigDecimal.ONE).sqrt(mc),mc)).toDouble(),x)
        }
        if (name.startsWith("acosh")) {
            if (x < 1) return Double.NaN
            if (!x.isFinite()) return x
            val n = BigDecimal(x)
            return logarithm(n.add(n.multiply(n,mc).subtract(BigDecimal.ONE).sqrt(mc),mc)).toDouble()
        }
        if (a > 1) return Double.NaN
        if (a == 1.0) return Math.copySign(Double.POSITIVE_INFINITY,x)
        if (a < 1e-20) return x
        val n = BigDecimal(a)
        return Math.copySign(logarithm(BigDecimal.ONE.add(n).divide(BigDecimal.ONE.subtract(n),mc)).divide(two,mc).toDouble(),x)
    }
    private fun close(name: String, expected: Long, actual: Long, label: String) {
        val e = value(name,expected); val a = value(name,actual)
        if (e.isNaN()) assertTrue(a.isNaN(),label)
        else if (!e.isFinite() || e == 0.0) assertEquals(expected,actual,label)
        else {
            assertTrue(a.isFinite() && Math.copySign(1.0,e) == Math.copySign(1.0,a),label)
            val difference = BigInteger(expected.toString()).subtract(BigInteger(actual.toString())).abs()
            assertTrue(difference <= BigInteger.valueOf(2),"$label: $difference ULPs, expected=$e actual=$a")
        }
    }
    private fun selected(row: Row, actual: Long) {
        val x = value(row.name,row.a); val y = value(row.name,row.b)
        if (x.isNaN() || y.isNaN() || x == y) {
            assertTrue(actual == row.a || actual == row.b || value(row.name,actual).isNaN() && (x.isNaN() || y.isNaN()),
                "$row: min/max must select an operand (NaN payload quieting is not portable)")
        } else assertEquals(if (if (row.name.startsWith("min")) x < y else x > y) row.a else row.b,actual,row.toString())
    }

    @Test fun nativeCorpusAndIndependentHighPrecisionModelAgree() {
        val corpus = evidence()
        for (row in corpus) {
            if (decode(row.name)) {
                val expected = words(row.a)
                // Pinned RTS StgPrimFloat.c does not initialize zero's sign.
                if (row.a and Long.MAX_VALUE == 0L) assertEquals(expected.drop(1),row.values.drop(1))
                else assertEquals(expected,row.values,row.toString())
            } else if (row.name.startsWith("min") || row.name.startsWith("max")) selected(row,row.values[0])
            else {
                val x = value(row.name,row.a)
                val expected = bits(row.name,mathematical(row.name,x))
                close(row.name,expected,row.values[0],"native/$row")
                val actual = when {
                    row.name.startsWith("asinh") -> InverseHyperbolic.asinh(x)
                    row.name.startsWith("acosh") -> InverseHyperbolic.acosh(x)
                    else -> InverseHyperbolic.atanh(x)
                }
                close(row.name,expected,bits(row.name,actual),"managed/$row")
            }
        }
        for (name in names.filterNot(::decode)) {
            val group = corpus.filter { it.name == name }
            assertTrue(group.any { value(name,it.a) == 0.0 && it.a != 0L })
            assertTrue(group.any { value(name,it.a).isNaN() })
            assertTrue(group.any { value(name,it.a) == Double.POSITIVE_INFINITY })
            assertTrue(group.any { value(name,it.a) > 0 && value(name,it.a) < if (floating(name)) java.lang.Float.MIN_NORMAL.toDouble() else java.lang.Double.MIN_NORMAL })
        }
        println("Floating remainder: ${corpus.size} native rows/model checks; native zero sign intentionally indeterminate")
    }
    @Test fun malformedMissingRepeatedReorderedAndCorruptRowsReject() {
        evidence()
        val lines = File(root,"$dir/oracle.tsv").readLines()
        for (bad in listOf(lines.drop(1),lines.reversed(),lines+lines.first(),listOf("bad")+lines.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { rows(bad.joinToString("\n")) }
        val first = rows(lines.joinToString("\n")).first { it.name == "asinhDouble" && value(it.name,it.a) == 0.0 }
        assertThrows(AssertionError::class.java) { close(first.name,first.values[0],1L, "corrupt zero") }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*,*> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun fourFieldDecodeRejectsMalformedPhysicalContracts(@TempDir temporary: Path) {
        evidence()
        val name = "decodeWordsDirect"
        for (mutation in listOf("valid","argument","result-carrier","result-arity","partial","over","lifted","bare")) {
            val module = Json.parse(File(root,"$dir/pre-core/FloatingRemainderAudit.json").readText()) as Map<String,Any?>
            val linked = CoreModules.reachable(module,name)
            val app = applications(linked).single { (it[1] as? List<*>)?.take(2) == listOf("prim","decodeDouble_2Int#") }
            val metadata = app[6] as MutableMap<String,Any?>
            val proof = metadata["rep"] as MutableMap<String,Any?>
            val args = app[2] as MutableList<Any?>
            when (mutation) {
                "argument" -> (CoreRepresentations.metadata(args.single() as List<Any?>) as MutableMap<String,Any?>)["rep"] =
                    mapOf("kind" to "float","primReps" to listOf("FloatRep"),"evaluated" to true)
                "result-carrier" -> {
                    (proof["components"] as MutableList<Any?>)[1] =
                        mapOf("kind" to "double","primReps" to listOf("DoubleRep"),"evaluated" to true)
                    (proof["primReps"] as MutableList<Any?>)[1] = "DoubleRep"
                }
                "result-arity" -> {
                    (proof["components"] as MutableList<Any?>).removeLast()
                    (proof["primReps"] as MutableList<Any?>).removeLast()
                }
                "partial" -> { args.clear(); (app[3] as MutableList<Any?>).clear(); metadata.remove("callDemand") }
                "over" -> { args.add(args.single()); (app[3] as MutableList<Any?>).add(false); metadata.remove("callDemand") }
                "lifted" -> (app[3] as MutableList<Any?>)[0] = true
                "bare" -> { val head = (app[1] as List<*>).toList(); app.clear(); app.addAll(head) }
            }
            val input = temporary.resolve("$mutation.json").toFile().apply { writeText(Json.stringify(linked)) }
            val report = temporary.resolve("$mutation-report.json").toFile()
            val process = ProcessBuilder("python3","scripts/audit-core.py",input.path,"--entry",name,"--output",report.path)
                .directory(root).redirectOutput(temporary.resolve("$mutation.stdout").toFile())
                .redirectError(temporary.resolve("$mutation.stderr").toFile()).start()
            if (!process.waitFor(60,TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(); fail<Unit>("Shared auditor timeout: $mutation")
            }
            assertEquals(if (mutation == "valid") 0 else 1,process.exitValue(),mutation)
            assertEquals(mutation == "valid",(Json.parse(report.readText()) as Map<*,*>)["accepted"],mutation)
            for (backend in listOf("ast","bytecode")) context(false).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    if (mutation == "valid") {
                        if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                    } else assertThrows(RuntimeException::class.java, {
                        if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                    }, "$backend/$mutation")
                } finally { context.leave() }
            }
        }
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining",inlining.toString()).option("engine.BackgroundCompilation","false")
        .option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw")
        .option("engine.SingleTierCompilationThreshold","10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(target,true); valid(target)
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode",Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime,target)
        valid(target)
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget,Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it,DirectCallNode::class.java) })
                (call.currentCallTarget as? RootCallTarget)?.takeIf { it.rootNode is GuestRoot }?.let(::visit)
            result.add(target)
        }
        visit(entry); return result
    }
    @Test fun allNativeRowsAcrossResidualCalls() = native(false)
    @Test fun allNativeRowsWithInlining() = native(true)
    private fun native(inlining: Boolean) {
        val corpus = evidence().groupBy { it.name }
        for (stage in listOf("pre","post")) {
            val module = CoreModules.merge(listOf("FloatingRemainderAudit","THC.InverseHyperbolic").map {
                Json.parse(File(root,"$dir/$stage-core/$it.json").readText()) as Map<String,Any?>
            })
            for (backend in listOf("ast","bytecode")) for (name in names) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module,name) + ("instrument" to true)
                    val p: ExecutableProgram = if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                    val entry = p.entryTarget(name)
                    fun count() = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                    fun check(row: Row, installed: Boolean) {
                        val expected = if (decode(name) && row.a and Long.MAX_VALUE == 0L) words(row.a) else row.values
                        for (field in 0 until if (decode(name)) 4 else 1) {
                            val before = count()
                            val label = "$stage/$backend/$name/${row.a}/${row.b}/$field/inlining=$inlining"
                            val arguments = if (name == "asinhExample") arrayOf(0L,row.a)
                                else arrayOf(0L,row.a,if (decode(name)) field.toLong() else row.b)
                            val result = Calls.target(entry,arguments) as Long
                            if (decode(name)) assertEquals(expected[field],result,label)
                            else if (name.startsWith("min") || name.startsWith("max")) selected(row,result)
                            else close(name,row.values[0],result,label)
                            if (installed) assertEquals(before + if (name == "decodeWordsCall") 2L else 1L,count(),label)
                            val h = language.handoffState.get()
                            assertEquals(0,h.arguments.depth); assertEquals(0,h.results.depth)
                            assertEquals(0,h.arguments.retainedReferences()); assertEquals(0,h.results.retainedReferences())
                        }
                    }
                    corpus.getValue(name).forEach { check(it,false) }
                    val active = targets(entry)
                    assertEquals(if (name == "decodeWordsCall") 2 else 1,active.size)
                    active.forEach(::compile)
                    val allocations = language.handoffState.get().results.allocations
                    for (row in corpus.getValue(name).asReversed()) {
                        check(row,true); assertEquals(active,targets(entry)); active.forEach(::valid)
                    }
                    assertEquals(allocations,language.handoffState.get().results.allocations)
                    println("PASS $stage/$backend/$name inlining=$inlining rows=${corpus.getValue(name).size}")
                } finally { context.leave() }
            }
        }
    }
}
