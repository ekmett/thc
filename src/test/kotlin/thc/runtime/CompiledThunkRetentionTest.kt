// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File

class CompiledThunkRetentionTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    @Test fun freshlyEvaluatedBoxedArrayThunksRetainFirstCompiledHostCall() {
        val manifest = Json.parse(File(root, "build/boxed-arrays/manifest.json").readText()) as Map<String, Any?>
        val rows = File(root, "build/boxed-arrays/oracle.tsv").readLines().map { it.split('\t') }
            .filter { it[0] == "boxedSTRecursive" }.map { it[1].toLong() to it[3].toLong() }
        assertEquals(41, rows.size)
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>)
            check(stage, paths, "boxedSTRecursive", rows, rows.asReversed())
    }

    @Test fun cachedFloatingNaNThunksRetainEveryCompiledCall() {
        val rows = File(root, "build/floating/oracle.tsv").readLines().map { it.split('\t') }
        for (entry in listOf("floatComparisons", "doubleComparisons")) {
            val selected = rows.filter { it[0] == entry }.map { it[1].toLong() to it[2].toLong() }
            assertTrue(selected.any { it.first == -16777216L })
            check("pre", listOf("build/floating/core/FloatingAudit.json"), entry, selected,
                // Select the cached NaN branch on the very first compiled call.
                selected.sortedBy { if (it.first == -16777216L) 0 else 1 })
        }
    }

    private fun check(stage: String, paths: List<String>, entry: String,
                      warm: List<Pair<Long, Long>>, compiled: List<Pair<Long, Long>>) {
        val module = CoreModules.reachable(CoreModules.merge(paths.map {
            Json.parse(File(root, it).readText()) as Map<String, Any?>
        }), entry) + ("instrument" to true)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                val function = context.asValue(EntryValue(program, entry, 1))
                val host = program.hostEntryTarget(1)
                val original = program.entryTarget(entry)
                fun active() = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                    .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                warm.forEach { (input, expected) -> assertEquals(expected, function.execute(input).asLong()) }
                val observed = active()
                assertTrue(observed.isNotEmpty())
                assertTrue(function.invokeMember("compile").asBoolean())
                val targets = (listOf(host, original) + observed).distinct()
                val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                fun valid(label: String) {
                    for (target in targets) {
                        assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target),
                            "$stage/$backend/$entry/$label/$target")
                    }
                }
                valid("installed")
                for ((index, row) in compiled.withIndex()) {
                    valid("before-$index/${row.first}")
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(row.second, function.execute(row.first).asLong())
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                    assertEquals(observed, active())
                    valid("after-$index/${row.first}")
                }
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().arguments.depth)
            } finally { context.leave() }
        }
    }
}
