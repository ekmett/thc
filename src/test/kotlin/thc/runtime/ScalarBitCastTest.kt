// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ScalarBitCastTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("floatRoundtrip", "floatField", "floatCaptured", "floatDecode", "floatEncode",
        "doubleRoundtrip", "doubleField", "doubleCaptured", "doubleDecode", "doubleEncode")
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "installed")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun count(p: ExecutableProgram) = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    private fun model(name: String, raw: Long): Long = if (name.startsWith("float")) raw and 0xffffffffL else raw
    @Test fun nativeRawBitsWithInlining() = native(true)
    @Test fun nativeRawBitsAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = Json.parse(File(root, "build/scalar-bitcasts/manifest.json").readText()) as Map<String, Any?>
        assertEquals(names, manifest["entries"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale bitcast fixture: $path")
        }
        val rows = File(root, "build/scalar-bitcasts/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, path) in manifest["stages"] as Map<String, String>) {
            val module = Json.parse(File(root, path).readText()) as Map<String, Any?>
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                    val entry = p.entryTarget(name); val host = p.hostEntryTarget(1)
                    val function = context.asValue(EntryValue(p, name, 1)); val label = "$stage/$backend/$name/inline=$inlining"
                    val cases = rows.getValue(name)
                    assertEquals((manifest["inputsByWidth"] as Map<String, List<Number>>).getValue(if (name.startsWith("float")) "32" else "64").map { it.toLong() }, cases.map { it[1].toLong() })
                    fun check(row: List<String>) {
                        val input = row[1].toLong(); val expected = row[2].toLong()
                        assertEquals(model(name, input), expected, "native $label/$input")
                        assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                    }
                    cases.forEach(::check)
                    val active = activeTargets(host)
                    assertTrue(active.size > 1, "$label actual guest call target")
                    active.filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean(), "$label host installation")
                    for (row in cases.asReversed()) {
                        val before = count(p); check(row)
                        assertEquals(before + (manifest["expectedGuestCalls"] as Map<String, Number>).getValue(name).toLong(),
                            count(p), "$label/${row[1]} exact retained guest entries")
                        assertEquals(active, activeTargets(host), "$label active target identities")
                        valid(entry, "$label original"); active.forEach { valid(it, "$label active") }; released(language)
                    }
                    assertEquals(0L, (p.diagnostics().getValue("blackholes") as Number).toLong())
                    assertEquals(0L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    private val signatures = linkedMapOf("castFloatToWord32#" to ("FloatRep" to "Word32Rep"),
        "castWord32ToFloat#" to ("Word32Rep" to "FloatRep"), "castDoubleToWord64#" to ("DoubleRep" to "Word64Rep"),
        "castWord64ToDouble#" to ("Word64Rep" to "DoubleRep"))
    private fun proof(rep: String) = mapOf("kind" to when (rep) { "FloatRep" -> "float"; "DoubleRep" -> "double"; else -> "long" },
        "primReps" to listOf(rep), "evaluated" to true)
    private fun synthetic(name: String): MutableMap<String, Any?> {
        val (input, output) = signatures.getValue(name)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val app = listOf("app", listOf("prim", name), listOf(listOf("var", "x", mapOf("rep" to proof(input)))),
            listOf(false), false, false, mapOf("rep" to proof(output)))
        return Json.parse(Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "instrument" to true,
            "constructors" to emptyList<Any>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", listOf(mapOf("id" to "x", "lifted" to false, "rep" to proof(input))),
                    app, mapOf("rep" to closure, "resultRep" to proof(output)))))))) as MutableMap<String, Any?>
    }
    private fun lambda(module: Map<String, Any?>) = (module["bindings"] as List<Map<String, Any?>>).single()["expr"] as MutableList<Any?>
    @Test fun exactKindsSignednessArityAndScalarFrontiersAreChecked() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in signatures.keys) for (mutation in listOf("argument", "result", "lexical", "partial", "over", "bare", "tuple", "sum")) {
                    val module = synthetic(name); val lam = lambda(module); val app = lam[2] as MutableList<Any?>
                    val argument = ((app[2] as List<List<Any?>>).single()[2] as MutableMap<String, Any?>)
                    when (mutation) {
                        "argument" -> argument["rep"] = proof("IntRep")
                        "result" -> (app[6] as MutableMap<String, Any?>)["rep"] = proof("WordRep")
                        "lexical" -> (lam[1] as List<MutableMap<String, Any?>>).single()["rep"] = proof("WordRep")
                        "partial" -> { (app[2] as MutableList<Any?>).clear(); (app[3] as MutableList<Any?>).clear() }
                        "over" -> { (app[2] as MutableList<Any?>).add((app[2] as List<Any?>).single()); (app[3] as MutableList<Any?>).add(false) }
                        "bare" -> lam[2] = listOf("prim", name)
                        "tuple" -> argument["rep"] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                            "primReps" to emptyList<String>(), "components" to emptyList<Any>(), "evaluated" to true)
                        "sum" -> argument["rep"] = mapOf("kind" to "unknown", "aggregate" to "unboxed-sum",
                            "primReps" to listOf("WordRep", "WordRep"), "alternatives" to listOf(proof("IntRep"), proof("IntRep")),
                            "tagSlot" to 0, "alternativeSlots" to listOf(listOf(1), listOf(1)), "evaluated" to true)
                    }
                    assertThrows(RuntimeException::class.java, { program(language, module, backend) }, "$backend/$name/$mutation")
                }
            } finally { context.leave() }
        }
    }
    @Test fun typedNodesKeepRawBitsAndNeverUseBoxedOperandExecution() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val fbits = 0xff800123L; val dbits = 0xfff0000000000123UL.toLong()
        fun argument(name: String) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = error("bitcast operand was boxed")
            override fun executeLong(frame: VirtualFrame): Long = if (name.contains("32")) fbits else dbits
            override fun executeFloat(frame: VirtualFrame): Float = java.lang.Float.intBitsToFloat(fbits.toInt())
            override fun executeDouble(frame: VirtualFrame): Double = java.lang.Double.longBitsToDouble(dbits)
        }
        for (name in signatures.keys) {
            val node = rawBitCastPrimitive(name, arrayOf(argument(name)))!!
            val actual = when (name) {
                "castWord32ToFloat#" -> java.lang.Float.floatToRawIntBits(node.executeFloat(frame)).toLong() and 0xffffffffL
                "castWord64ToDouble#" -> java.lang.Double.doubleToRawLongBits(node.executeDouble(frame))
                else -> node.executeLong(frame)
            }
            assertEquals(if (name.contains("32")) fbits else dbits, actual, name)
        }
    }
    @Test fun everyFloatNanEncodingAndSelectedDoubleNanPayloadsRemainExact() {
        for (sign in longArrayOf(0, 0x80000000L)) for (payload in 1 until (1 shl 23)) {
            val bits = sign or 0x7f800000L or payload.toLong()
            val value = RawBitCasts.word32ToFloat(bits)
            if ((java.lang.Float.floatToRawIntBits(value).toLong() and 0xffffffffL) != bits || RawBitCasts.floatToWord32(value) != bits)
                fail<Unit>("Float NaN changed: ${bits.toString(16)}")
        }
        for (sign in longArrayOf(0, Long.MIN_VALUE)) for (quiet in longArrayOf(0, 1L shl 51)) for (payload in 1..65535) {
            val bits = sign or 0x7ff0000000000000L or quiet or payload.toLong()
            val value = RawBitCasts.word64ToDouble(bits)
            if (java.lang.Double.doubleToRawLongBits(value) != bits || RawBitCasts.doubleToWord64(value) != bits)
                fail<Unit>("Double NaN changed: ${bits.toULong().toString(16)}")
        }
    }
    @Test fun directTypedCallsPreserveBitsWithExactlyOneCompiledEntryAndRejectWrongCarriers() {
        val fbits = longArrayOf(0, 0x80000000L, 1, 0x007fffff, 0x7f800000, 0xff800000L, 0x7f800001, 0xffc12345L)
        val dbits = longArrayOf(0, Long.MIN_VALUE, 1, 0x000fffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000UL.toLong(),
            0x7ff0000000000001L, 0xfff8000000001234UL.toLong())
        for (backend in listOf("ast", "bytecode")) for (name in signatures.keys) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p = program(language, synthetic(name), backend); val target = p.entryTarget("entry")
                val bits = if (name.contains("32")) fbits else dbits
                fun input(bits: Long): Any = when (name) {
                    "castFloatToWord32#" -> java.lang.Float.intBitsToFloat(bits.toInt())
                    "castDoubleToWord64#" -> java.lang.Double.longBitsToDouble(bits)
                    else -> bits
                }
                fun invoke(bits: Long): Long {
                    val value = Calls.target(target, arrayOf(0L, input(bits)))
                    return when (name) {
                        "castWord32ToFloat#" -> java.lang.Float.floatToRawIntBits(value as Float).toLong() and 0xffffffffL
                        "castWord64ToDouble#" -> java.lang.Double.doubleToRawLongBits(value as Double)
                        else -> value as Long
                    }
                }
                bits.forEach { assertEquals(it, invoke(it)) }; compile(target)
                bits.forEach {
                    val before = count(p); assertEquals(it, invoke(it), "$backend/$name/${it.toULong().toString(16)}")
                    assertEquals(before+1, count(p)); valid(target, "$backend/$name"); released(language)
                }
                assertThrows(RuntimeException::class.java) { Calls.target(target, arrayOf(0L, Any())) }
                released(language); assertEquals(bits.last(), invoke(bits.last()))
            } finally { context.leave() }
        }
    }
}
