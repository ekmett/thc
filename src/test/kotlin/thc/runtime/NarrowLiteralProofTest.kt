// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class NarrowLiteralProofTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/narrow-literal-proofs")
    private val kinds = listOf("Int8", "Word8", "Int16", "Word16", "Int32", "Word32")
    private val unknown = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
    @BeforeEach fun provenance() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        for (section in listOf("sources", "artifacts")) for (record in manifest[section] as List<Map<String, String>>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, record.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(record["sha256"], hash, "Stale narrow literal input: ${record["path"]}")
        }
        assertEquals(54L, manifest["nativeRows"])
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial installation")
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun module(stage: String): Map<String, Any?> =
        Json.parse(File(directory, "$stage-core/NarrowLiteralProofAudit.json").readText()) as Map<String, Any?>
    private fun visitWrites(value: Any?, action: (MutableList<Any?>) -> Unit): Int {
        var count = 0
        if (value is List<*> && value.firstOrNull() == "app") {
            val fn = value[1] as List<*>
            if (fn.firstOrNull() == "prim" && kinds.any { fn[1] == "write${it}Array#" }) {
                val literal = (value[2] as List<*>)[2] as MutableList<Any?>
                assertEquals("lit", literal[0]); assertTrue(kinds.any { it.lowercase() == literal[1] })
                action(literal); count++
            }
        }
        when (value) {
            is Map<*, *> -> value.values.forEach { count += visitWrites(it, action) }
            is List<*> -> value.forEach { count += visitWrites(it, action) }
        }
        return count
    }
    private fun project(stage: String, variant: String, proof: Map<String, Any?>? = null): Map<String, Any?> {
        val module = module(stage)
        assertEquals(24, visitWrites(module) { literal ->
            val original = (literal[3] as Map<String, Any?>)["rep"] as Map<String, Any?>
            val name = kinds.single { it.lowercase() == literal[1] }
            assertEquals(mapOf("kind" to "long", "primReps" to listOf(name+"Rep"), "evaluated" to true), original)
            when (variant) {
                "absent" -> literal.subList(3, literal.size).clear()
                "unknown" -> (literal[3] as MutableMap<String, Any?>)["rep"] = unknown
                "bad" -> (literal[3] as MutableMap<String, Any?>)["rep"] = proof
                "exact" -> Unit
                else -> error(variant)
            }
        })
        return module
    }
    @Test fun directWritesPreserveIntrinsicRepresentationsWithInlining() = native(true)
    @Test fun directWritesPreserveIntrinsicRepresentationsAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }
        assertEquals(54, rows.size)
        val integral = listOf("IntRep", "WordRep", "Int64Rep", "Word64Rep")
        for (stage in listOf("pre", "post")) for (variant in listOf("exact", "absent", "unknown") + integral) {
            val projected = if (variant in integral) project(stage, "bad",
                mapOf("kind" to "long", "primReps" to listOf(variant), "evaluated" to true)) else project(stage, variant)
            for (kind in kinds) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val name = "write${kind}Literal"
                    val p = program(language, CoreModules.reachable(projected, name) + ("instrument" to true), backend)
                    val function = context.asValue(EntryValue(p, name, 1))
                    val host = p.hostEntryTarget(1); val original = p.entryTarget(name)
                    val selected = rows.filter { it[0] == name }
                    val bits = kind.filter { it.isDigit() }.toInt()
                    val values = if (kind.startsWith("Int")) listOf(-(1L shl (bits-1)), -1L, 0L, (1L shl (bits-1))-1)
                        else listOf(0L, (1L shl (bits-1))-1, 1L shl (bits-1), (1L shl bits)-1)
                    val label = "$stage/$backend/$kind/$variant/inlining=$inlining"
                    fun check(row: List<String>) {
                        val input = row[1].toLong(); val expected = values[(input and 3).toInt()]
                        assertEquals(expected, row[2].toLong(), "$label independent sign/index model")
                        assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                        val state = language.handoffState.get()
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
                        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
                    }
                    selected.forEach(::check)
                    val active = targets(host)
                    assertTrue(active.size > 1, "$label observed host-to-guest path")
                    active.filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    for (row in selected.asReversed()) {
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertTrue((p.diagnostics().getValue("compiledEntries") as Number).toLong() > before, "$label compiled guest entry")
                        assertEquals(active, targets(host), "$label active identities")
                        valid(original, "$label original"); active.forEach { valid(it, "$label active") }
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes"))
                        assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                    println("NarrowLiteralProof PASS $label rows=${selected.size}")
                } finally { context.leave() }
            }
        }
    }
    @Test fun differentCarriersAndMalformedPresentProofsFailBothLoaders() {
        val bad = listOf(emptyMap(), mapOf("kind" to "long", "primReps" to listOf("Int8Rep")),
            mapOf("kind" to "unknown", "primReps" to "Int8Rep", "evaluated" to true),
            mapOf("kind" to "unknown", "primReps" to listOf("Int8Rep"), "evaluated" to true),
            mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true),
            mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true),
            mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
                "aggregate" to "unboxed-tuple", "components" to emptyList<Any>()))
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (stage in listOf("pre", "post")) for (proof in bad) for (kind in kinds) {
                    val module = CoreModules.reachable(project(stage, "bad", proof), "write${kind}Literal")
                    assertThrows(RuntimeFault::class.java, { program(language, module, backend) }, "$stage/$backend/$kind/$proof")
                }
            } finally { context.leave() }
        }
    }
    @Test fun intrinsicProofDoesNotAdmitInvalidValuesOrBroadenOtherLiteralKinds() {
        for (kind in kinds) {
            val bits = kind.filter { it.isDigit() }.toInt()
            val invalid = if (kind.startsWith("Int")) listOf(-(1L shl (bits-1))-1, 1L shl (bits-1))
                else listOf(-1L, 1L shl bits)
            for (backend in listOf("ast", "bytecode")) context(true).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (value in invalid) {
                        val module = CoreModules.reachable(project("pre", "absent"), "write${kind}Literal")
                        assertEquals(4, visitWrites(module) { it[2] = value.toString() })
                        assertThrows(RuntimeFault::class.java) { program(language, module, backend) }
                    }
                } finally { context.leave() }
            }
        }
        for (kind in listOf("int", "word", "int64", "word64", "float", "double", "char", "string-bytes", "null-addr"))
            for (metadata in listOf(emptyList(), listOf(mapOf("rep" to unknown)))) {
                val proof = CoreRepresentations.expression(listOf("lit", kind, "0") + metadata)
                assertEquals(CoreKind.UNKNOWN, proof.kind, kind); assertNull(proof.primReps, kind)
            }
    }
}
