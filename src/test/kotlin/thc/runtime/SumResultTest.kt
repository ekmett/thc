// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class SumResultTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    @BeforeEach fun verifyEvidence() = verifySumEvidence(root)
    private fun module(stage: String, extended: Boolean = false) = Json.parse(File(root, "build/${if (extended) "sum-result" else "sum-layout"}/$stage-core/${if (extended) "SumResultAudit" else "SumLayoutAudit"}.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial compilation: $target")
    }
    private fun activeTargets(host: RootCallTarget): List<RootCallTarget> {
        val visited = linkedSetOf<RootCallTarget>()
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!visited.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) })
                visit(call.currentCallTarget as RootCallTarget)
            targets.add(target)
        }
        visit(host)
        return targets
    }
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    @Test fun nativeResultsInline() = native(true)
    @Test fun nativeResultsResidual() = native(false)
    @Test fun extendedNativeResultsInline() = native(true, true)
    @Test fun extendedNativeResultsResidual() = native(false, true)
    @Test fun originalFrontierResultsInline() = native(true, frontier = true)
    @Test fun originalFrontierResultsResidual() = native(false, frontier = true)
    private fun native(inlining: Boolean, extended: Boolean = false, frontier: Boolean = false) {
        val supported = setOf("sumCase", "directCase", "lazyCase", "zeroCase", "unitCase", "boxedKindsCase", "floatDoubleCase")
        val rows = File(root, "build/${if (frontier) "aggregate-native" else if (extended) "sum-result" else "sum-layout"}/oracle.tsv").readLines().map { it.split('\t') }.filter { if (frontier) it[0] in setOf("sumPayload", "sumZeroLazy", "coldSum") else extended || it[0] in supported }.groupBy { it[0] }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((name, inputs) in rows) {
                    val source = if (frontier) Json.parse(File(root, "build/${if (stage == "pre") "aggregate-core" else "aggregate-post-core"}/AggregateFrontier.json").readText()) as Map<String, Any?> else module(stage, extended)
                    val linked = CoreModules.reachable(source, name)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val target = program.entryTarget(bindings.single { it["name"] == name }["id"] as String)
                    val host = program.hostEntryTarget(1)
                    val entry = program.entryValue(name)
                    fun invoke(x: Long): Any? = Calls.target(host, arrayOf(entry, arrayOf(x)))
                    var active = emptyList<RootCallTarget>()
                    fun check(compiled: Boolean) {
                        for (row in inputs) {
                            val label = "$stage/$backend/$name/${row[1]}/inline=$inlining"
                            if (compiled && row[2] == "throws") continue
                            val before = count(program)
                            val selfBefore = (program.diagnostics()["selfTailReentries"] as Number).toLong()
                            val bounceBefore = (program.diagnostics()["trampolineIterations"] as Number).toLong()
                            if (row[2] == "throws") assertThrows(GuestException::class.java) { invoke(row[1].toLong()) }
                            else assertEquals(row[2].toLong(), invoke(row[1].toLong()), label)
                            if (compiled) {
                                valid(target, label); valid(host, label)
                                assertEquals(active, activeTargets(host), "$label active target identity")
                                active.forEach { valid(it, label) }
                                val expected = when (name) {
                                    "directCase", "coldSum" -> 1L
                                    "sumPayload" -> 2L // The strict Box Int# is constructed in the producer root.
                                    "zeroCase", "forwardCase", "pairedCase", "mixedCase", "selfCase", "effectStateCase", "effectEmptyCase", "throwCase" -> 3L
                                    "outstandingCase" -> 5L
                                    "mutualCase" -> 6L // Two A reentries reuse its frame; B enters three times.
                                    "boxedKindsCase" -> if (row[1].toLong() < 0) 2L else 3L
                                    else -> 2L
                                }
                                assertEquals(expected, count(program) - before, "$label ${program.diagnostics()}")
                                if (name == "selfCase" || name == "mutualCase") {
                                    assertEquals(if (name == "selfCase") 5L else 2L,
                                        (program.diagnostics()["selfTailReentries"] as Number).toLong() - selfBefore, label)
                                    assertEquals(0L, (program.diagnostics()["trampolineIterations"] as Number).toLong() - bounceBefore, label)
                                }
                            }
                            val state = language.handoffState.get()
                            assertEquals(0, state.results.depth, label); assertEquals(0, state.results.retainedReferences(), label)
                            assertEquals(0, state.arguments.depth, label); assertEquals(0, state.arguments.retainedReferences(), label)
                        }
                    }
                    check(false)
                    active = activeTargets(host)
                    assertTrue(active.size > 1, "Missing observed host guest-call target")
                    (listOf(target) + active).distinct().forEach(::compile)
                    check(true)
                    inputs.firstOrNull { it[2] == "throws" }?.let { row ->
                        assertThrows(GuestException::class.java) { invoke(row[1].toLong()) }
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                        val recovery = inputs.first { it[2] != "throws" }
                        assertEquals(recovery[2].toLong(), invoke(recovery[1].toLong()))
                    }
                }
            } finally { context.leave() }
        }
    }
    @Test fun independentPairInputsInline() = paired(true)
    @Test fun independentPairInputsResidual() = paired(false)
    private fun paired(inlining: Boolean) {
        val rows = File(root, "build/sum-result/oracle-pairs.tsv").readLines().map { it.split('\t') }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module(stage, true), "pairedInputs")
                val bindings = linked["bindings"] as List<Map<String, Any?>>
                val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                val original = program.entryTarget(bindings.single { it["name"] == "pairedInputs" }["id"] as String)
                val host = program.hostEntryTarget(2)
                val entry = program.entryValue("pairedInputs")
                fun invoke(row: List<String>) = Calls.target(host, arrayOf(entry, arrayOf(row[1].toLong(), row[2].toLong())))
                rows.forEach { assertEquals(it[3].toLong(), invoke(it)) }
                val active = activeTargets(host)
                assertTrue(active.size > 1)
                (listOf(original) + active).distinct().forEach(::compile)
                for (row in rows) {
                    val label = "$stage/$backend/pairedInputs/$row/inline=$inlining"
                    val before = count(program)
                    assertEquals(row[3].toLong(), invoke(row), label)
                    assertEquals(2L, count(program) - before, label)
                    valid(original, label); valid(host, label)
                    assertEquals(active, activeTargets(host), "$label active target identity")
                    active.forEach { valid(it, label) }
                    val state = language.handoffState.get()
                    assertEquals(0, state.results.depth, label); assertEquals(0, state.results.retainedReferences(), label)
                    assertEquals(0, state.arguments.depth, label); assertEquals(0, state.arguments.retainedReferences(), label)
                }
            } finally { context.leave() }
        }
    }
}
