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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Finite scalar entries exercise promoted local vectors without a vector call ABI. */
class SimdCapabilitySmokeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-capability-smoke")

    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val child = call.currentCallTarget as? RootCallTarget ?: continue
                if (child.rootNode is GuestRoot) visit(child)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }

    private fun compiled(target: RootCallTarget): Boolean =
        target.javaClass.getMethod("isValidLastTier").invoke(target) == true

    @Test fun finiteLocalVectorsCompileOnAstAndBytecode() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghcVersion"])
        assertEquals(2718L, (manifest["rows"] as Number).toLong())
        assertTrue(manifest["nativeOracle"] in listOf("scalar", "scalar-and-vector"))
        for (item in (manifest["inputs"] as List<Map<String, String>>) +
                (manifest["artifacts"] as List<Map<String, String>>)) {
            val file = File(root, item.getValue("path"))
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(item["sha256"], digest, "Stale SIMD smoke input/artifact: ${item["path"]}")
        }
        val module = Json.parse(File(directory, "pre-core/GeneratedSimdSmoke.json").readText()) as Map<String, Any?>
        assertEquals(GeneratedVectors.operations, (manifest["operations"] as List<String>).toSet())
        val rows = File(directory, "cases.tsv").readLines().filter(String::isNotEmpty).map { line ->
            line.split('\t').also { assertEquals(5, it.size) }
        }.groupBy { it[0] }
        assertEquals((0..6).map { "simdSmoke$it" }, manifest["names"])
        assertEquals(manifest["names"], rows.keys.toList())
        assertEquals((manifest["rows"] as Number).toInt(), rows.values.sumOf { it.size })
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                context.initialize("thc")
                context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for ((name, cases) in rows) {
                        val input = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, input)
                            else BytecodeProgram(language, input)
                        val entry = program.entryTarget(name)
                        val host = program.hostEntryTarget(3)
                        val function = context.asValue(EntryValue(program, name, 3))
                        fun check(row: List<String>) {
                            assertEquals(row[4].toLong(), function.execute(row[1].toLong(), row[2].toLong(),
                                row[3].toLong()).asLong(), "$backend/$name/${row.drop(1)}")
                        }
                        cases.forEach(::check)
                        assertEquals(0L, (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            "$backend/$name interpreted cases")
                        val active = targets(host)
                        assertEquals(2, active.size, "$backend/$name target graph")
                        (active + entry).distinct().filter { it !== host }.forEach { target ->
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertTrue(compiled(target), "$backend/$name guest installation")
                        }
                        assertTrue(function.invokeMember("compile").asBoolean(), "$backend/$name host installation")
                        for (row in cases) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            assertEquals(before + 1,
                                (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                                "$backend/$name exact compiled guest entries")
                            assertEquals(active, targets(host), "$backend/$name active target identity")
                            assertTrue(compiled(entry), "$backend/$name entry retained")
                            active.forEach { assertTrue(compiled(it), "$backend/$name call retained") }
                            val state = language.handoffState.get()
                            assertEquals(0, state.arguments.depth)
                            assertEquals(0, state.arguments.retainedReferences())
                            assertEquals(0, state.results.depth)
                            assertEquals(0, state.results.retainedReferences())
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(),
                                "$backend/$name/$counter")
                    }
                } finally { context.leave() }
            }
    }
}
