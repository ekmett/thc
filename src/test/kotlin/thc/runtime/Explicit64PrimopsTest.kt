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

class Explicit64PrimopsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun manifest() = Json.parse(File(root, "build/explicit64-primops/manifest.json").readText()) as Map<String, Any?>
    private fun module() = Json.parse(File(root, "build/explicit64-primops/core/Explicit64PrimopsAudit.json").readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun visit(action: (Language, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend) }
            finally { context.leave() }
        }
    }
    private fun verifyHashes(data: Map<String, Any?>) {
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in data[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale explicit64 fixture: $path")
        }
    }
    private fun mathematical(entry: Map<String, Any?>, left: Long, right: Long): Long {
        val modulus = BigInteger.ONE.shiftLeft(64)
        val rawX = BigInteger.valueOf(left); val rawY = BigInteger.valueOf(right)
        val x = if (entry["unsigned"] == true) rawX.mod(modulus) else rawX
        val y = if (entry["unsigned"] == true) rawY.mod(modulus) else rawY
        fun bit(value: Boolean) = if (value) BigInteger.ONE else BigInteger.ZERO
        val result = when (entry["operation"]) {
            "identity" -> x
            "literals" -> when (left) { 0L -> BigInteger.ZERO; 1L -> BigInteger.valueOf(Long.MAX_VALUE); 2L -> modulus - BigInteger.ONE; else -> modulus.shiftRight(1) }
            "case" -> BigInteger.valueOf(when (left) { 0L -> 11L; Long.MIN_VALUE -> 13L; -1L -> 17L; else -> 19L })
            "negate" -> -x
            "plus" -> x+y
            "sub" -> x-y
            "times" -> x*y
            "quot" -> x/y
            "rem" -> x%y
            "eq" -> bit(x == y); "ne" -> bit(x != y)
            "lt" -> bit(x < y); "le" -> bit(x <= y); "gt" -> bit(x > y); "ge" -> bit(x >= y)
            "and" -> x.and(y); "or" -> x.or(y); "xor" -> x.xor(y); "not" -> x.not()
            "shiftL" -> x.shiftLeft(right.toInt())
            "shiftRA" -> rawX.shiftRight(right.toInt())
            "shiftRL" -> rawX.mod(modulus).shiftRight(right.toInt())
            else -> error("Unknown explicit64 operation")
        }
        return result.toLong()
    }
    @Test fun exactNativeScalarEntriesAgreeWithIndependentModelAndInstalledCode() {
        val manifest = manifest(); verifyHashes(manifest)
        val entries = manifest["entries"] as List<Map<String, Any?>>
        assertEquals(36, entries.count { it["primitive"] != null })
        val rows = File(root, "build/explicit64-primops/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(entries.map { it["name"] }.toSet(), rows.keys)
        visit { language, backend ->
            for (entry in entries) {
                val name=entry["name"] as String; val arity=(entry["arity"] as Number).toInt()
                val cases=rows.getValue(name).map { listOf(it[1].toLong(), it[2].toLong(), it[3].toLong()) }
                for ((x,y,native) in cases) assertEquals(mathematical(entry,x,y), native, "native $name($x,$y)")
                val module=CoreModules.reachable(module(),name)
                val lambda=((module["bindings"] as List<Map<String,Any?>>).single()["expr"] as List<Any?>)
                assertEquals((entry["arguments"] as List<String>).map { listOf(it) },
                    (lambda[1] as List<Map<String,Any?>>).map { CoreRepresentations.binder(it).primReps })
                assertEquals(listOf(entry["result"]), CoreRepresentations.lambdaResult(lambda).primReps)
                val program=program(language,module,backend); val value=program.entryValue(name); val host=program.hostEntryTarget(arity)
                fun check(row:List<Long>) {
                    val args=if(arity==1) arrayOf(row[0]) else arrayOf(row[0],row[1])
                    assertEquals(row[2],Calls.target(host,arrayOf(value,args)),"$backend/$name($args)")
                }
                cases.forEach(::check)
                val target=program.entryTarget(name);compile(target)
                val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                cases.asReversed().forEach(::check)
                assertEquals(cases.size.toLong(),(program.diagnostics().getValue("compiledEntries") as Number).toLong()-before,name)
                valid(target)
                assertEquals(0L,(program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L,(program.diagnostics().getValue("blackholes") as Number).toLong())
            }
        }
    }
    private fun raw(body: List<Any?>, arity: Int = 1) = mapOf("schema" to 1,"ghc" to "9.14.1","module" to "Explicit64Control",
        "constructors" to emptyList<Any?>(),"bindings" to listOf(mapOf("id" to "entry","name" to "entry","arity" to arity,"lifted" to true,
            "expr" to listOf("lam",List(arity) { mapOf("id" to "x$it","lifted" to false) },body))))
    @Test fun malformedAritiesAndWord64LiteralsRejectAtLoadIncludingCaseAlternatives() = visit { language,backend ->
        for (diagnostic in listOf(false,true)) {
            for (entry in manifest()["entries"] as List<Map<String,Any?>>) {
                val primitive=entry["primitive"] as? String ?: continue
                val arity=(entry["arity"] as Number).toInt()
                for (supplied in listOf(arity-1,arity+1)) {
                    val body=listOf("app",listOf("prim",primitive),List(supplied) { listOf("var","x$it") },List(supplied) { false })
                    val error=assertThrows(RuntimeFault::class.java) { program(language,raw(body,supplied)+("diagnosticUnsupported" to diagnostic),backend) }
                    assertTrue(error.message.orEmpty().contains("Primitive arity mismatch: $primitive"),error.message)
                }
            }
            for (alternative in listOf(false,true)) {
                fun body(value:String):List<Any?> = if(!alternative) listOf("lit","word64",value) else
                    listOf("case",listOf("var","x0"),"whole",listOf(
                        listOf("lit",listOf("word64",value),emptyList<String>(),listOf("lit","int","1")),
                        listOf("default",null,emptyList<String>(),listOf("lit","int","0"))))
                for (value in listOf("0","1","9223372036854775808","18446744073709551615")) {
                    val program=program(language,raw(body(value))+("diagnosticUnsupported" to diagnostic),backend)
                    val bits=value.toULong().toLong()
                    assertEquals(if(alternative) 1L else bits,Calls.target(program.hostEntryTarget(1),arrayOf(program.entryValue("entry"),arrayOf(bits))))
                    if(alternative) assertEquals(0L,Calls.target(program.hostEntryTarget(1),arrayOf(program.entryValue("entry"),arrayOf(bits xor 1))))
                }
                for (value in listOf("-1","18446744073709551616","","+1","01","-0"," 1","1.0")) {
                    val error=assertThrows(RuntimeFault::class.java) { program(language,raw(body(value))+("diagnosticUnsupported" to diagnostic),backend) }
                    assertTrue(error.message.orEmpty().contains("Invalid word64 literal"),error.message)
                }
            }
        }
    }
    @Test fun sharedLongCarrierDoesNotPermitReplacingInt64ProofWithWord64() = visit { language,backend ->
        for (diagnostic in listOf(false,true)) {
            val module=CoreModules.reachable(module(),"plusInt64")
            val lambda=((module["bindings"] as List<Map<String,Any?>>).single()["expr"] as List<Any?>)
            val binder=(lambda[1] as List<MutableMap<String,Any?>>)[0]
            (binder["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("Word64Rep")
            val error=assertThrows(RuntimeFault::class.java) { program(language,module+("diagnosticUnsupported" to diagnostic),backend) }
            assertTrue(error.message.orEmpty().contains("Conflicting Core scalar representation proofs"),error.message)
        }
    }
}
