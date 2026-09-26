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

    /** Requests use real exported vector primops; only their edge expectations are Java-specific. */
    private fun javaExtremaCases(name: String, cases: List<List<String>>, selectors: Map<String, String>): List<List<String>> {
        val owned = cases.map { it[1].toInt() / 256 }.toSet()
        return selectors.entries.filter { (index, primitive) -> index.toInt() in owned &&
            Regex("(min|max)(Float|Double)X(2|4|8|16)#").matches(primitive) }.flatMap { (index, primitive) ->
            val double = "Double" in primitive
            val count = primitive.substringAfter('X').removeSuffix("#").toInt()
            val sign = if (double) Long.MIN_VALUE else 0x80000000L
            val one = if (double) 0x3ff0000000000000L else 0x3f800000L
            val infinity = if (double) 0x7ff0000000000000L else 0x7f800000L
            val nan = if (double) 0x7ff8000000001234L else 0x7fc01234L
            val pairs = listOf(0L to sign, 0L to 0L, sign to sign, nan to one,
                nan to infinity, nan to (nan + 1), (nan or sign) to (one or sign),
                infinity to one, infinity to (infinity or sign), 1L to 3L,
                (sign or 1L) to 1L, (infinity - 1) to ((infinity - 1) or sign))
                .flatMap { (a,b) -> listOf(a to b, b to a) }.distinct()
            (0 until count).flatMap { lane -> pairs.map { (a,b) ->
                val minimum = primitive.startsWith("min")
                val expected = if (double) {
                    val x = Double.fromBits(a); val y = Double.fromBits(b)
                    val value = if (minimum) Math.min(x,y) else Math.max(x,y)
                    if (value.isNaN()) 0x7ff8000000000000L else value.toRawBits()
                } else {
                    val x = Float.fromBits(a.toInt()); val y = Float.fromBits(b.toInt())
                    val value = if (minimum) Math.min(x,y) else Math.max(x,y)
                    if (value.isNaN()) 0x7fc00000L else value.toRawBits().toLong() and 0xffffffffL
                }
                listOf(name, (index.toInt() * 256 + lane).toString(),
                    (a - lane * 104729L).toString(), (b + lane * 7919L).toString(), expected.toString())
            } }
        }
    }

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
        assertEquals(5430L, (manifest["rows"] as Number).toLong())
        assertTrue(manifest["nativeOracle"] in listOf("scalar", "scalar-and-vector"))
        assertEquals("java-math", manifest["floatingExtrema"])
        assertEquals("finite-without-mixed-zero-ties", manifest["nativeFloatingExtrema"])
        val selectors = manifest["selectors"] as Map<String, String>
        assertEquals(GeneratedVectors.operations.filterNot { it.startsWith("pack") || it.startsWith("unpack") }.toSet(),
            selectors.values.toSet())
        assertEquals(12, selectors.values.count { Regex("(min|max)(Float|Double)X(2|4|8|16)#").matches(it) })
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
        assertEquals((0..14).map { "simdSmoke$it" }, manifest["names"])
        assertEquals(manifest["names"], rows.keys.toList())
        assertEquals((manifest["rows"] as Number).toInt(), rows.values.sumOf { it.size })
        val javaEdges = rows.mapValues { (name, cases) -> javaExtremaCases(name, cases, selectors) }
        assertEquals(1848, javaEdges.values.sumOf { it.size }, "Both extrema and operand orders across all 42 floating lanes")
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                context.initialize("thc")
                context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for ((name, nativeCases) in rows) {
                        val cases = nativeCases + javaEdges.getValue(name)
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
