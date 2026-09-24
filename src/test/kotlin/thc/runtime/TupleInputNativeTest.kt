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
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

/** Genuine GHC boundaries, including actual PAP and overapplication in both exports. */
class TupleInputNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val folder = File(root, "build/tuple-input")
    @BeforeEach fun currentEvidence() {
        val evidence = Json.parse(File(folder, "provenance.json").readText()) as Map<String, Any?>
        for (kind in listOf("sources", "artifacts")) for (item in evidence[kind] as List<Map<String, String>>) {
            val path = File(item.getValue("path")).let { if (it.isAbsolute) it else File(root, it.path) }
            val hash = MessageDigest.getInstance("SHA-256").digest(path.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(item["sha256"], hash, "Stale typed-input evidence: $path")
        }
        for (stage in listOf("pre", "post")) {
            val audit = Json.parse(File(folder, "$stage-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], "$stage strict native audit")
        }
        val checks = Json.parse(File(folder, "checks.json").readText()) as Map<String, Any?>
        for (coverage in checks["coverage"] as List<Map<String, Any?>>)
            assertEquals(entries, (coverage["expectedGuestEntries"] as Map<String, Number>).mapValues { it.value.toLong() },
                "Retained source paths must support the measured guest-entry expectations")
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial installation: $target")
    }
    private fun targets(host: RootCallTarget): List<RootCallTarget> {
        val visited = linkedSetOf<RootCallTarget>()
        val ordered = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!visited.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) })
                visit(call.currentCallTarget as RootCallTarget)
            ordered.add(target)
        }
        visit(host)
        return ordered
    }
    private fun released(language: Language, label: String) {
        val state = language.handoffState.get()
        assertNull(state.pending, label)
        assertEquals(0, state.arguments.depth, label); assertEquals(0, state.arguments.retainedReferences(), label)
        assertEquals(0, state.results.depth, label); assertEquals(0, state.results.retainedReferences(), label)
    }
    // These count retained guest roots, not logical self calls: recur's matching
    // self transfers reuse its frame. The public fixture/preparer pins the paths.
    private val entries = mapOf("pairCase" to 2L, "mixedCase" to 2L, "indirectCase" to 3L,
        "prefixCase" to 2L, "papCase" to 4L, "overCase" to 4L, "lazyCase" to 2L,
        "roundTripCase" to 4L, "selfCase" to 2L, "stateCase" to 3L,
        "nestedCase" to 2L, "deadCase" to 2L, "effectCase" to 4L, "selfDepth" to 2L, "pairInputs" to 2L)
    @Test fun nativeTypedInputsInline() = native(true, 1)
    @Test fun nativeTypedInputsResidual() = native(false, 1)
    @Test fun independentTypedInputLanesInlineAndResidual() { native(true, 2); native(false, 2) }
    private fun native(inlining: Boolean, arity: Int) {
        val rows = File(folder, if (arity == 1) "oracle.tsv" else "oracle-pairs.tsv").readLines()
            .map { it.split('\t') }.groupBy { it[0] }
        assertEquals(if (arity == 1) 139 else 7, rows.values.sumOf { it.size })
        assertEquals(if (arity == 1) 14 else 1, rows.size)
        for (stage in listOf("pre", "post")) {
            val module = Json.parse(File(folder, "$stage-core/TupleInputAudit.json").readText()) as Map<String, Any?>
            for ((name, cases) in rows) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val host = program.hostEntryTarget(arity)
                    val original = program.entryTarget(name)
                    val closure = program.entryValue(name)
                    val label = "$stage/$backend/$name/inlining=$inlining"
                    fun invoke(row: List<String>): Any? {
                        val inputs = row.subList(1, row.lastIndex).map { it.toLong() as Any? }.toTypedArray()
                        assertEquals(arity, inputs.size)
                        return Calls.target(host, arrayOf(closure, inputs))
                    }
                    cases.forEach { assertEquals(it.last().toLong(), invoke(it), "$label/interpreted/$it"); released(language, label) }
                    val active = targets(host)
                    assertTrue(active.size > 1, "$label missing observed guest call target")
                    (listOf(original) + active).distinct().forEach(::compile)
                    // Repair the existing host entry prerequisite without invoking
                    // guest code, exactly as the production compile operation does.
                    val runtime = com.oracle.truffle.api.Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                        .invoke(runtime, host)
                    for (row in cases.asReversed()) {
                        val before = program.diagnostics()["compiledEntries"] as Long
                        assertEquals(row.last().toLong(), invoke(row), "$label/compiled/$row")
                        assertEquals(entries.getValue(name), (program.diagnostics()["compiledEntries"] as Long) - before,
                            "$label/$row compiled guest entries: ${program.diagnostics()}")
                        valid(original, "$label original"); valid(host, "$label host")
                        assertEquals(active, targets(host), "$label active target identities")
                        active.forEach { valid(it, "$label active") }; released(language, label)
                    }
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                    assertEquals(0L, program.diagnostics()["blackholes"])
                } finally { context.leave() }
            }
        }
    }
    @Test fun nativeZeroWidthFailurePrecedesInputPublication() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = Json.parse(File(folder, "$stage-core/TupleInputAudit.json").readText()) as Map<String, Any?>
                val linked = CoreModules.reachable(module, "effectCase")
                val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                fun call(x: Long) = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("effectCase"), arrayOf(x)))
                assertEquals(26L, call(7L))
                assertThrows(GuestException::class.java) { call(-1L) }; released(language, "$stage/$backend/throw")
                assertEquals(26L, call(7L)); released(language, "$stage/$backend/recovery")
            } finally { context.leave() }
        }
    }
}
