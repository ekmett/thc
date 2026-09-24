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
import java.util.Random

class FusedFloatingTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/fused-floating")
    private val one = BigInteger.ONE
    private data class Format(val width: Int) {
        val fraction = if (width == 32) 23 else 52
        val bias = if (width == 32) 127 else 1023
        val sign = 1L shl (width-1)
        val unit = bias.toLong() shl fraction
        val infinity = (2L*bias+1) shl fraction
        val fractionMask = (1L shl fraction)-1
        fun magnitude(x: Long) = x and (sign-1)
        fun nan(x: Long) = magnitude(x) > infinity
        fun names() = listOf("Add", "Sub", "NegAdd", "NegSub").map { "fused${if (width == 32) "Float" else "Double"}$it" }
    }
    private val formats = listOf(Format(32), Format(64))
    private data class Input(val x: Long, val y: Long, val z: Long)
    private data class Row(val name: String, val input: Input, val bits: Long)
    private fun domain(f: Format): List<Input> {
        val values = java.util.TreeSet<Input> { a, b ->
            java.lang.Long.compareUnsigned(a.x, b.x).takeIf { it != 0 } ?:
                java.lang.Long.compareUnsigned(a.y, b.y).takeIf { it != 0 } ?: java.lang.Long.compareUnsigned(a.z, b.z)
        }
        val edges = listOf(0L, 1L, (1L shl f.fraction)-1, 1L shl f.fraction, f.unit-2, f.unit-1,
            f.unit, f.unit+1, f.unit+2, f.infinity-1, f.infinity, f.infinity+1, f.infinity+(1L shl (f.fraction-1))+123)
            .flatMap { listOf(it, it xor f.sign) }
        for (x in edges) for (y in edges) for (z in listOf(0L, f.sign)) values += Input(x,y,z)
        for (x in edges) for (z in listOf(f.unit, f.unit xor f.sign)) {
            values += Input(x,f.unit,z); values += Input(f.unit,x,z); values += Input(f.unit,z,x)
        }
        val cancellations = listOf(Input(f.unit+1,f.unit-2,f.unit xor f.sign),
            Input(f.infinity-1,f.unit+(1L shl f.fraction),(f.infinity-1) xor f.sign),
            Input(1,f.unit-(1L shl f.fraction),1),
            Input(1L shl f.fraction,f.unit-1,(1L shl f.fraction) xor f.sign),
            Input(f.unit+(1L shl (f.fraction-1)),f.unit+3,1))
        for (v in cancellations) for (sx in listOf(0L,f.sign)) for (sy in listOf(0L,f.sign)) for (sz in listOf(0L,f.sign))
            values += Input(v.x xor sx,v.y xor sy,v.z xor sz)
        return values.toList()
    }

    // Exact signed integer * power-of-two arithmetic, then one nearest/even
    // quotient rounding. No floating arithmetic or Math.fma is used by the model.
    private fun model(f: Format, operation: Int, input: Input): Long? {
        val x = input.x xor if (operation >= 2) f.sign else 0L
        val y = input.y
        val z = input.z xor if (operation and 1 != 0) f.sign else 0L
        val ax = f.magnitude(x); val ay = f.magnitude(y); val az = f.magnitude(z)
        val negativeProduct = (x xor y) and f.sign
        if (f.nan(x) || f.nan(y) || f.nan(z) || ax == f.infinity && ay == 0L || ay == f.infinity && ax == 0L) return null
        if (ax == f.infinity || ay == f.infinity) {
            if (az == f.infinity && negativeProduct != (z and f.sign)) return null
            return negativeProduct or f.infinity
        }
        if (az == f.infinity) return z
        fun decode(bits: Long): Pair<BigInteger, Int> {
            val exponent = (f.magnitude(bits) ushr f.fraction).toInt()
            var coefficient = BigInteger.valueOf(bits and f.fractionMask)
            if (exponent != 0) coefficient += one.shiftLeft(f.fraction)
            if (bits and f.sign != 0L) coefficient = -coefficient
            return coefficient to (maxOf(exponent,1)-f.bias-f.fraction)
        }
        val (cx,ex) = decode(x); val (cy,ey) = decode(y); val (cz,ez) = decode(z)
        val base = minOf(ex+ey,ez)
        val sum = (cx*cy).shiftLeft(ex+ey-base) + cz.shiftLeft(ez-base)
        if (sum.signum() == 0) return if ((ax == 0L || ay == 0L) && az == 0L && negativeProduct != 0L && z and f.sign != 0L) f.sign else 0L
        val sign = if (sum.signum() < 0) f.sign else 0L
        val magnitude = sum.abs()
        var step = maxOf(magnitude.bitLength()-1+base-f.fraction,1-f.bias-f.fraction)
        val shift = step-base
        var rounded = if (shift <= 0) magnitude.shiftLeft(-shift) else {
            val divisor = one.shiftLeft(shift)
            val (q,r) = magnitude.divideAndRemainder(divisor)
            if (r.shiftLeft(1) > divisor || r.shiftLeft(1) == divisor && q.testBit(0)) q+one else q
        }
        if (rounded.bitLength() > f.fraction+1) { rounded = rounded.shiftRight(1); step++ }
        if (rounded.bitLength() <= f.fraction) return sign or rounded.toLong()
        val exponent = step+f.fraction+f.bias
        return sign or if (exponent >= 2*f.bias+1) f.infinity else
            (exponent.toLong() shl f.fraction) or (rounded.toLong() and f.fractionMask)
    }
    private fun observed(f: Format, operation: Int, input: Input): Long = if (f.width == 32) {
        val x = Float.fromBits(input.x.toInt()); val y = Float.fromBits(input.y.toInt()); val z = Float.fromBits(input.z.toInt())
        Math.fma(if (operation >= 2) -x else x,y,if (operation and 1 != 0) -z else z).toRawBits().toLong() and 0xffff_ffffL
    } else {
        val x = Double.fromBits(input.x); val y = Double.fromBits(input.y); val z = Double.fromBits(input.z)
        Math.fma(if (operation >= 2) -x else x,y,if (operation and 1 != 0) -z else z).toRawBits()
    }
    private fun matches(f: Format, expected: Long?, actual: Long, label: String) {
        if (expected == null) assertTrue(f.nan(actual), "$label must be NaN (payload/sign unspecified)")
        else assertEquals(expected, actual, label)
    }
    @Test fun exactIntegerModelCoversSingleRoundingSignedZeroAndNonFiniteRules() {
        val random = Random(9141)
        for (f in formats) {
            assertEquals(1538,domain(f).size)
            val mask = if (f.width == 32) 0xffff_ffffL else -1L
            for (input in domain(f) + List(4096) { Input(random.nextLong() and mask,random.nextLong() and mask,random.nextLong() and mask) })
                for (op in 0..3) matches(f,model(f,op,input),observed(f,op,input), "${f.width}/$op/$input")
            val cancellation = Input(f.unit+1,f.unit-2,f.unit xor f.sign)
            assertNotEquals(0L, model(f,0,cancellation))
            val separate = if (f.width == 32) (Float.fromBits(cancellation.x.toInt()) * Float.fromBits(cancellation.y.toInt()) - 1f).toRawBits().toLong()
                else (Double.fromBits(cancellation.x) * Double.fromBits(cancellation.y) - 1.0).toRawBits()
            assertEquals(0L,separate,"separately rounded multiplication loses the nonzero fused residue")
            assertEquals(0L,model(f,2,Input(0,0,0)))
            assertNotEquals(f.sign,model(f,2,Input(0,0,0)),"negating a rounded result would produce wrong -0")
        }
        val f = formats[0]; val input = Input(0x3fc00000,0x3f800003,1)
        val widened = Math.fma(Float.fromBits(input.x.toInt()).toDouble(),Float.fromBits(input.y.toInt()).toDouble(),Float.fromBits(1).toDouble()).toFloat()
        assertNotEquals(model(f,0,input),widened.toRawBits().toLong(),"Float FMA must not pass through Double rounding")
    }

    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun module(stage: String) = json(File(directory,"$stage-core/FloatingAudit.json"))
    private fun provenance(manifest: Map<String,Any?> = json(File(directory,"manifest.json"))) {
        require(manifest.keys == setOf("schema","ghc","ghcInfo","installedArtifactsHashed","nativeFlags",
            "entries","stages","nativeRows","inputHashes","artifactHashes"))
        val inputs = setOf("compiler/test-fixtures/FloatingAudit.hs","compiler/test-fixtures/FloatingAuditNative.hs",
            "thc.cabal","test/haskell-fixtures/Main.hs","test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/FusedFloatingFixtures.hs","compiler/build.sh","compiler/export.sh",
            "compiler/toolchain.sh","compiler/plugin.py","scripts/audit-core.py","scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root,"compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { "compiler/THC/${it.name}" } +
            File(root,"scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { "scripts/${it.name}" }
        val artifacts = setOf("build/fused-floating/oracle.tsv") + listOf("pre","post").flatMap {
            listOf("build/fused-floating/$it-core/FloatingAudit.json","build/fused-floating/$it-audit.json") }
        require((manifest["inputHashes"] as Map<*,*>).keys == inputs)
        require((manifest["artifactHashes"] as Map<*,*>).keys == artifacts)
        assertEquals(1L,manifest["schema"]); assertEquals("9.14.1",manifest["ghc"])
        assertEquals(false,manifest["installedArtifactsHashed"])
        assertEquals(listOf("-O2","-fforce-recomp","-dcore-lint","-dstg-lint"),manifest["nativeFlags"])
        assertEquals(formats.flatMap { it.names() },manifest["entries"])
        assertEquals(listOf("pre","post"),manifest["stages"])
        assertEquals(formats.sumOf { domain(it).size*4 }.toLong(),manifest["nativeRows"])
        for (kind in listOf("inputHashes","artifactHashes")) for ((path,digest) in manifest[kind] as Map<String,String>) {
            val file = File(root,path).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath()))
            assertEquals(digest,MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },"Stale $path")
        }
        for (stage in listOf("pre","post")) assertEquals(true,json(File(directory,"$stage-audit.json"))["accepted"])
    }
    private fun rows(lines: List<String> = File(directory,"oracle.tsv").readLines()): List<Row> {
        val rows = lines.map { line ->
            val fields = line.split('\t'); require(fields.size == 5)
            val bits = fields.drop(1).map { value -> require(Regex("0|[1-9][0-9]*").matches(value)); java.lang.Long.parseUnsignedLong(value) }
            Row(fields[0],Input(bits[0],bits[1],bits[2]),bits[3])
        }
        val expected = formats.flatMap { f -> domain(f).flatMap { input -> f.names().map { it to input } } }
        require(rows.map { it.name to it.input } == expected) { "Missing, duplicate, reordered or invented native inputs" }
        for (f in formats) for (row in rows.filter { it.name in f.names() }) {
            if (f.width == 32) require(row.bits ushr 32 == 0L)
            matches(f,model(f,f.names().indexOf(row.name),row.input),row.bits,"native/${row.name}/${row.input}")
        }
        return rows
    }
    @Test fun nativeCorpusRejectsMissingDuplicateCorruptAndOutOfRangeRows() {
        provenance(); rows()
        val manifest = json(File(directory,"manifest.json"))
        for (kind in listOf("inputHashes","artifactHashes")) {
            val hashes = manifest[kind] as Map<String,String>
            for (path in hashes.keys) assertThrows(IllegalArgumentException::class.java) { provenance(manifest + (kind to (hashes-path))) }
        }
        val text = File(directory,"oracle.tsv").readLines()
        for (bad in listOf(text.drop(1),text+text.first(),text.asReversed(),listOf(text[0]+"\t0")+text.drop(1),
            listOf(text[0].substringBeforeLast('\t')+"\t18446744073709551616")+text.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { rows(bad) }
        assertThrows(AssertionError::class.java) { rows(listOf(text[0].substringBeforeLast('\t')+"\t1")+text.drop(1)) }
    }
    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app" && (value.getOrNull(1) as? List<*>)?.firstOrNull() == "prim") listOf(value) else emptyList()) + value.flatMap(::calls)
        is Map<*,*> -> value.values.flatMap(::calls)
        else -> emptyList()
    }
    @Test fun genuineCoreRetainsEveryFusedTernaryProofAndRejectsCorruption() {
        provenance()
        for (stage in listOf("pre","post")) for (f in formats) for ((operation,name) in f.names().withIndex()) {
            val source = CoreModules.reachable(module(stage),name,strictLink=true)
            val bindings = source["bindings"] as List<Map<String,Any?>>
            assertEquals(2,bindings.size)
            val worker = bindings.single { it["name"] == name+"Worker" }
            val primop = listOf("fmadd","fmsub","fnmadd","fnmsub")[operation]+(if(f.width==32)"Float#" else "Double#")
            val call = calls(worker["expr"]).single()
            assertEquals(primop,(call[1] as List<*>)[1])
            assertEquals(listOf(false,false,false),call[3])
            val proof = listOf(if(f.width==32)"FloatRep" else "DoubleRep")
            val lambda = worker["expr"] as List<*>
            assertEquals(List(3) { proof },(lambda[1] as List<Map<String,Any?>>).map { (it["rep"] as Map<*,*>)["primReps"] })
            assertEquals(proof,((call[6] as Map<*,*>)["rep"] as Map<*,*>)["primReps"])
            for (backend in listOf("ast","bytecode")) context(false).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (corruption in 0..3) {
                        val bad = Json.parse(Json.stringify(source)) as Map<String,Any?>
                        val target = calls((bad["bindings"] as List<Map<String,Any?>>).single { it["name"] == name+"Worker" }["expr"]).single() as MutableList<Any?>
                        when (corruption) {
                            0 -> target[2] = (target[2] as List<*>).dropLast(1)
                            1 -> ((target[6] as MutableMap<String,Any?>)["rep"] as MutableMap<String,Any?>)["primReps"] = listOf(if(f.width==32)"DoubleRep" else "FloatRep")
                            2 -> ((target[6] as MutableMap<String,Any?>)["rep"] as MutableMap<String,Any?>)["primReps"] = listOf("IntRep")
                            3 -> (((target[2] as List<*>)[0] as MutableList<Any?>)[2] as MutableMap<String,Any?>)["rep"] = mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
                        }
                        assertThrows(RuntimeFault::class.java, { program(language,bad,backend) }, "$stage/$backend/$name/corruption=$corruption")
                    }
                } finally { context.leave() }
            }
        }
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining",inlining.toString()).option("engine.BackgroundCompilation","false")
        .option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw")
        .option("engine.SingleTierCompilationThreshold","10000000").build()
    private fun program(language: Language, source: Map<String,Any?>, backend: String): ExecutableProgram =
        if(backend=="ast") Program(language,source) else BytecodeProgram(language,source)
    private fun valid(target: RootCallTarget) = assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
    @Test fun nativeFusedResultsRemainCompiledWithInlining() = native(true)
    @Test fun nativeFusedResultsRemainCompiledAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        provenance(); val rows = rows()
        for (stage in listOf("pre","post")) for (backend in listOf("ast","bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (f in formats) for (name in f.names()) {
                    val source = CoreModules.reachable(module(stage),name,strictLink=true) + ("instrument" to true)
                    val p = program(language,source,backend)
                    val target = p.entryTarget(name)
                    val targets = (source["bindings"] as List<Map<String,Any?>>).map { p.entryTarget(it["id"] as String) }
                    assertEquals(2,targets.size)
                    val selected = rows.filter { it.name == name }
                    fun check(row: Row, compiled: Boolean) {
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val value = Calls.target(target,arrayOf(0L,row.input.x,row.input.y,row.input.z)) as Long
                        matches(f,if(f.nan(row.bits))null else row.bits,value,"$stage/$backend/$name/${row.input}")
                        if(compiled) {
                            assertEquals(before+2,(p.diagnostics().getValue("compiledEntries") as Number).toLong())
                            targets.forEach(::valid)
                        }
                        val state = language.handoffState.get()
                        assertEquals(0,state.arguments.depth); assertEquals(0,state.results.depth)
                        assertEquals(0,state.arguments.retainedReferences()); assertEquals(0,state.results.retainedReferences())
                    }
                    selected.forEach { check(it,false) }
                    targets.asReversed().forEach { it.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(it,true); valid(it) }
                    selected.asReversed().forEach { check(it,true) } // First installed call is checked; no settling.
                    selected.forEach { check(it,true) }
                    assertEquals(0L,(p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
            } finally { context.leave() }
        }
    }
}
