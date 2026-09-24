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

class CompareByteArraysTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("shortCompare", "shortPrefix", "shortSuffix", "rangeCompare", "aliasCompare")
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
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
    private fun compare(a: List<Int>, b: List<Int>): Long {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return if (a[i] < b[i]) -1 else 1
        return a.size.compareTo(b.size).toLong()
    }
    private fun model(name: String, raw: Long): Long {
        val k = (raw and 4095).toInt(); val x = (raw and 255).toInt()
        if (name == "shortCompare") return compare(listOf(x, 0, 128, 255),
            listOf(listOf(x, 0, 128, 255), listOf(x, 0, 128), listOf(x, 0, 128, 254), listOf(x, 0, 129, 0), emptyList())[k % 5])
        if (name == "shortPrefix" || name == "shortSuffix") return if (k % 3 == 1) 1 else 0
        val a = listOf(x, 0, 127, 128, 255, 17, 0, 255)
        val b = if (name == "aliasCompare") a else listOf(255, 0, 127, 128, x, 17, 255, 0)
        val from = k % 9; val to = k / 9 % 9; val length = minOf(k / 81 % 9, 8-from, 8-to)
        return compare(a.subList(from, from+length), b.subList(to, to+length))
    }
    @Test fun publicShortByteStringAndRangesWithInlining() = native(true)
    @Test fun publicShortByteStringAndRangesAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = Json.parse(File(root, "build/compare-byte-arrays/manifest.json").readText()) as Map<String, Any?>
        assertEquals(names, manifest["entries"]); assertEquals("sign only", manifest["resultContract"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale comparison fixture: $path")
        }
        val rows = File(root, "build/compare-byte-arrays/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                    val entry = p.entryTarget(name); val host = p.hostEntryTarget(1)
                    val function = context.asValue(EntryValue(p, name, 1)); val label = "$stage/$backend/$name/inline=$inlining"
                    val cases = rows.getValue(name)
                    assertEquals((manifest["inputsByEntry"] as Map<String, List<Number>>).getValue(name).map { it.toLong() }, cases.map { it[1].toLong() })
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
                        assertTrue(count(p) > before, "$label/${row[1]} must enter compiled guest code")
                        assertEquals(active, activeTargets(host), "$label active target identities")
                        valid(entry, "$label original"); active.forEach { valid(it, "$label active") }; released(language)
                    }
                    assertEquals(0L, (p.diagnostics().getValue("blackholes") as Number).toLong())
                    assertEquals(0L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val bytes = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun synthetic(): MutableMap<String, Any?> {
        val proofs = listOf(bytes, long, bytes, long, long)
        val args = proofs.mapIndexed { i, p -> mapOf("id" to "x$i", "lifted" to false, "rep" to p) }
        val app = listOf("app", listOf("prim", "compareByteArrays#"),
            args.mapIndexed { i, p -> listOf("var", "x$i", mapOf("rep" to p["rep"])) }, List(5) { false }, false, false, mapOf("rep" to long))
        return Json.parse(Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "instrument" to true,
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", args, app, mapOf("rep" to closure, "resultRep" to long)))), "constructors" to emptyList<Any>()))) as MutableMap<String, Any?>
    }
    private fun lambda(m: Map<String, Any?>) = (m["bindings"] as List<Map<String, Any?>>).single()["expr"] as MutableList<Any?>
    private fun app(m: Map<String, Any?>) = lambda(m)[2] as MutableList<Any?>
    @Test fun unsignedRangeModelAliasesAndCompleteDomainGuards() {
        for (a in 0..255) for (b in 0..255) assertEquals(a.compareTo(b),
            ManagedByteArray.compare(byteArrayOf(a.toByte()), 0, byteArrayOf(b.toByte()), 0, 1).compareTo(0))
        for (size in 0..7) {
            val a = ByteArray(size) { (it*61+128).toByte() }
            val b = a.reversedArray()
            for (right in listOf(a, b)) for (from in 0..size) for (to in 0..size) for (length in 0..minOf(size-from, size-to)) {
                val expected = compare(a.drop(from).take(length).map { it.toInt() and 255 }, right.drop(to).take(length).map { it.toInt() and 255 })
                assertEquals(expected, ManagedByteArray.compare(a, from.toLong(), right, to.toLong(), length.toLong()).compareTo(0).toLong())
            }
        }
        for (range in invalidRanges) assertThrows(RuntimeFault::class.java, {
            ManagedByteArray.compare(byteArrayOf(0, -1), range[0], byteArrayOf(0, -1), range[1], range[2])
        }, range.toString())
    }
    private val invalidRanges = listOf(listOf(-1L, 0L, 0L), listOf(0L, -1L, 0L), listOf(3L, 0L, 0L), listOf(0L, 3L, 0L),
        listOf(1L, 0L, 2L), listOf(0L, 1L, 2L), listOf(0L, 0L, -1L), listOf(1L, 1L, Long.MAX_VALUE),
        listOf(Long.MAX_VALUE, 0L, 1L), listOf(0L, Long.MAX_VALUE, 1L), listOf(Long.MIN_VALUE, 0L, 0L),
        listOf(0L, Long.MIN_VALUE, 0L), listOf(0L, 0L, Long.MIN_VALUE), listOf(1L shl 32, 0L, 0L), listOf(0L, 1L shl 32, 0L))
    @Test fun bothBackendsUsePrimitiveReturnAndRejectInvalidRangesAndCarriers() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p = program(language, synthetic(), backend); val target = p.entryTarget("entry")
                val a = byteArrayOf(0, -1); val b = byteArrayOf(0, 127)
                fun run(from: Long, to: Long, n: Long, right: Any = b) = Calls.target(target, arrayOf(0L, a, from, right, to, n)) as Long
                val cases = listOf(listOf(0L, 0L, 0L), listOf(2L, 2L, 0L), listOf(0L, 0L, 1L), listOf(0L, 0L, 2L), listOf(1L, 0L, 1L))
                cases.forEach { run(it[0], it[1], it[2]) }; compile(target)
                for (row in cases) {
                    val before = count(p); val value = run(row[0], row[1], row[2])
                    assertEquals(ManagedByteArray.compare(a, row[0], b, row[1], row[2]).compareTo(0), value.compareTo(0))
                    assertEquals(before+1, count(p), "$backend one compiled entry"); valid(target, backend)
                }
                assertEquals(0L, run(0, 0, 2, a))
                for (row in invalidRanges) assertThrows(RuntimeFault::class.java) { run(row[0], row[1], row[2]) }
                for (wrong in listOf(17L, Any(), arrayOf<Any>(1L), ManagedAddress.fromHex("00")))
                    assertThrows(RuntimeFault::class.java) { run(0, 0, 0, wrong) }
                released(language); assertEquals(1, run(0, 0, 2).compareTo(0))
            } finally { context.leave() }
        }
    }
    @Test fun exactSaturationLevityAndPrimitiveProofsAreMandatory() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (mutation in listOf("bare", "partial", "over", "result", "missing-result") + (0..4).flatMap { i ->
                    listOf("missing-$i", "wrong-$i", "lifted-$i", "lexical-$i") }) {
                    val m = synthetic(); val a = app(m)
                    when (mutation) {
                        "bare" -> lambda(m)[2] = listOf("prim", "compareByteArrays#")
                        "partial" -> { (a[2] as MutableList<Any?>).removeLast(); (a[3] as MutableList<Any?>).removeLast() }
                        "over" -> { (a[2] as MutableList<Any?>).add((a[2] as List<Any?>)[4]); (a[3] as MutableList<Any?>).add(false) }
                        "result" -> (a[6] as MutableMap<String, Any?>)["rep"] = long + ("primReps" to listOf("WordRep"))
                        "missing-result" -> (a[6] as MutableMap<String, Any?>).remove("rep")
                        else -> {
                            val i = mutation.substringAfter('-').toInt()
                            val occurrence = ((a[2] as List<List<Any?>>)[i][2] as MutableMap<String, Any?>)
                            when (mutation.substringBefore('-')) {
                                "missing" -> occurrence.remove("rep")
                                "wrong" -> occurrence["rep"] = if (i in listOf(0, 2)) bytes + ("primReps" to listOf("BoxedRep (Just Lifted)")) else long + ("primReps" to listOf("WordRep"))
                                "lifted" -> (a[3] as MutableList<Any?>)[i] = true
                                "lexical" -> ((lambda(m)[1] as List<MutableMap<String, Any?>>)[i])["rep"] =
                                    if (i in listOf(0, 2)) bytes + ("primReps" to listOf("BoxedRep (Just Lifted)")) else long + ("primReps" to listOf("WordRep"))
                            }
                        }
                    }
                    assertThrows(RuntimeException::class.java, { program(language, m, backend) }, "$backend/$mutation")
                }
            } finally { context.leave() }
        }
    }
    @Test fun typedAstEvaluationVisitsAllOperandsInOrderIncludingZeroLength() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val events = mutableListOf<Int>(); val storage = byteArrayOf(1, 2)
        fun reference(i: Int) = object : Expr() { override fun execute(frame: VirtualFrame): Any { events.add(i); return storage } }
        fun number(i: Int, failure: Boolean = false) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = error("primitive child was boxed")
            override fun executeLong(frame: VirtualFrame): Long { events.add(i); if (failure) throw RuntimeFault("operand failed"); return 0 }
        }
        fun expression(failure: Boolean) = byteArrayExpression(ByteArrayOp.COMPARE, CoreRepresentation(CoreKind.LONG),
            arrayOf(reference(0), number(1), reference(2), number(3), number(4, failure)))
        assertEquals(0L, expression(false).executeLong(frame)); assertEquals(listOf(0, 1, 2, 3, 4), events)
        events.clear(); assertThrows(RuntimeFault::class.java) { expression(true).executeLong(frame) }
        assertEquals(listOf(0, 1, 2, 3, 4), events)
    }
}
