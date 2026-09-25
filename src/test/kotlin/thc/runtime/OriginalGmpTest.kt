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
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap

/** Genuine installed declarations and native observations; no replacement FFI. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalGmpTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-gmp"
    private fun json(path: String) = Json.parse(File(root, path).readText())
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalGmpAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") as Map<String, Any?> })
    private fun rows(): List<Map<String, Any?>> {
        val manifest = json("$prefix/manifest.json") as Map<String, Any?>
        assertEquals(true, manifest["strictAccepted"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalGmpAudit.hs", "compiler/test-fixtures/OriginalGmpNative.hs",
            "test/haskell-fixtures/OriginalGmpFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> listOf(
                "$prefix/$stage/core/OriginalGmpAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val rows = json("$prefix/oracle.json") as List<Map<String, Any?>>
        assertEquals(92, rows.size)
        assertEquals(GmpForeignOp.entries.map { it.symbol }.toSet(), rows.map { it["symbol"] }.toSet())
        assertEquals(mapOf("originalAdd" to 15, "originalSub" to 15, "originalAddWord" to 8,
            "originalMulWord" to 8, "originalCmp" to 5, "originalMul" to 4, "originalDivWord" to 12,
            "originalModWord" to 5, "originalQuotRem" to 8, "originalQuot" to 4, "originalRem" to 8),
            rows.groupingBy { it["entry"] as String }.eachCount())
        for (stage in listOf("pre", "post")) for (entry in rows.map { it["entry"] as String }.toSet()) {
            val audit = json("$prefix/$stage/$entry.audit.json") as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
        }
        return rows
    }
    private fun bytes(value: Any?): ByteArray {
        val words = value as List<Long>
        return ByteBuffer.allocate(words.size * 8).order(ByteOrder.nativeOrder())
            .also { buffer -> words.forEach(buffer::putLong) }.array()
    }
    private class Observation(row: Map<String, Any?>, convert: (Any?) -> ByteArray) {
        val left = convert(row["leftBefore"])
        val right = convert(row["rightBefore"])
        val output = when (row["alias"]) {
            "output-left" -> left
            "output-right" -> right
            "remainder-left" -> if (row["entry"] == "originalRem") left else convert(row["outputBefore"])
            else -> convert(row["outputBefore"])
        }
        val remainder = if (row["alias"] == "remainder-left" && row["entry"] == "originalQuotRem") left
            else convert(row["remainderBefore"])
        val arguments: Array<Any?> = when (row["entry"]) {
            "originalAdd", "originalSub", "originalMul", "originalQuot", "originalRem" ->
                arrayOf(output, left, row["leftCount"], right, row["rightCount"])
            "originalAddWord", "originalMulWord" -> arrayOf(output, left, row["leftCount"], row["word"])
            "originalCmp" -> arrayOf(left, right, row["leftCount"])
            "originalDivWord" -> arrayOf(output, row["fractional"], left, row["leftCount"], row["word"])
            "originalModWord" -> arrayOf(left, row["leftCount"], row["word"])
            "originalQuotRem" -> arrayOf(output, remainder, row["fractional"], left,
                row["leftCount"], right, row["rightCount"])
            else -> error("Unknown original GMP consumer")
        }
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun context(native: Boolean = true, inline: Boolean = true) = Context.newBuilder("thc")
        .allowNativeAccess(native).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("compiler.Inlining", inline.toString()).option("engine.CompilationFailureAction", "Throw").build()
    private fun load(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)

    @Test fun genuineOriginalCallsMatchAllNativeBuffersOnFirstCompiledEntries() {
        val rows = rows()
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            val calls = OriginalStdioChecks.foreignCalls(source)
            assertEquals(11, calls.size)
            assertEquals(GmpForeignOp.entries.toSet(), calls.map { call ->
                val metadata = call[6] as Map<*, *>
                CoreGmpForeign.validate(metadata, (call[2] as List<List<Any?>>).map {
                    CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>, metadata["rep"])
            }.toSet())
            for (backend in listOf("ast", "bytecode")) for (inline in listOf(false, true))
                context(inline = inline).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        for ((name, examples) in rows.groupBy { it["entry"] as String }) {
                            val linked = CoreModules.reachable(source, name) + ("instrument" to true)
                            val program = load(language, backend, linked)
                            val value = program.entryValue(name)
                            val host = program.hostEntryTarget(Observation(examples.first(), ::bytes).arguments.size)
                            var entry = program.entryTarget(name)
                            var active = emptyList<RootCallTarget>()
                            fun exercise(compiled: Boolean) {
                                for ((index, row) in examples.withIndex()) {
                                    val observation = Observation(row, ::bytes)
                                    val label = "$stage/$backend/inline=$inline/$name/$index"
                                    assertArrayEquals(bytes(row["outputBefore"]), observation.output, "$label output alias initialization")
                                    assertArrayEquals(bytes(row["remainderBefore"]), observation.remainder, "$label remainder alias initialization")
                                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                    // The source-pure cmp/mod consumers are genuine global
                                    // aliases. The ordinary host dispatcher forces their CAF
                                    // and applies the resulting closure, retaining captures.
                                    assertEquals(row["result"], Calls.target(host, arrayOf(value, observation.arguments)), label)
                                    assertArrayEquals(bytes(row["leftAfter"]), observation.left, "$label left")
                                    assertArrayEquals(bytes(row["rightAfter"]), observation.right, "$label right")
                                    assertArrayEquals(bytes(row["outputAfter"]), observation.output, "$label output")
                                    assertArrayEquals(bytes(row["remainderAfter"]), observation.remainder, "$label remainder")
                                    if (compiled) {
                                        assertEquals(before + active.size,
                                            (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label compiled entries")
                                        assertEquals(active, targets(entry), "$label target retention")
                                        active.forEach(::valid)
                                    }
                                    val handoff = language.handoffState.get()
                                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                                    assertEquals(0, handoff.results.retainedReferences())
                                }
                            }
                            exercise(false)
                            entry = program.entryTarget(name)
                            active = targets(entry)
                            assertTrue(active.isNotEmpty())
                            for (target in active) {
                                try {
                                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                                } catch (failure: Exception) {
                                    throw AssertionError("$stage/$backend/inline=$inline/$name: ${target.rootNode.name}", failure)
                                }
                                valid(target)
                            }
                            exercise(true)
                        }
                    } finally { context.leave() }
                }
        }
    }

    @Test fun malformedGenuineMetadataFailsBothLoadersBeforeExecution() {
        rows()
        val source = module("pre")
        val entries = (json("$prefix/oracle.json") as List<Map<String, Any?>>).map { it["entry"] as String }.toSet()
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in entries) {
                    val linked = CoreModules.reachable(source, entry)
                    for (variant in 0 until 5) {
                        val bad = Json.parse(Json.stringify(linked)) as Map<String, Any?>
                        val call = OriginalStdioChecks.foreignCalls(bad).single() as MutableList<Any?>
                        val metadata = call[6] as MutableMap<String, Any?>
                        val descriptor = metadata["foreignCall"] as MutableMap<String, Any?>
                        when (variant) {
                            0 -> descriptor["safety"] = "safe"
                            1 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "base"
                            2 -> (call[3] as MutableList<Any?>)[0] = true
                            3 -> (call[1] as MutableList<Any?>)[1] = 17L
                            4 -> {
                                val argument = (call[2] as List<List<Any?>>)[0]
                                (CoreRepresentations.metadata(argument) as MutableMap<String, Any?>)["rep"] =
                                    mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
                            }
                        }
                        assertThrows(RuntimeFault::class.java, { load(language, backend, bad) }, "$backend/$entry/$variant")
                    }
                }
            } finally { context.leave() }
        }
    }

    @Test fun originalEntryPermissionAndInvalidCountsNeverStoreToGuestBuffers() {
        rows()
        val source = CoreModules.reachable(module("pre"), "originalAddWord")
        for (backend in listOf("ast", "bytecode")) for (native in listOf(false, true))
            context(native = native).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val entry = load(language, backend, source).entryTarget("originalAddWord")
                    val output = ByteArray(24) { 0x5a }; val before = output.copyOf()
                    val input = ByteArray(24) { 0x33 }
                    for (count in if (native) listOf(-1L, 0L, 4L, Long.MAX_VALUE) else listOf(1L)) {
                        assertThrows(RuntimeFault::class.java) { Calls.target(entry, arrayOf(0L, output, input, count, 1L)) }
                        assertArrayEquals(before, output)
                    }
                    if (native) {
                        assertThrows(RuntimeFault::class.java) { Calls.target(entry,
                            arrayOf(0L, output, ManagedAddress.fromByteArray(input), 1L, 1L)) }
                        assertArrayEquals(before, output)
                    }
                } finally { context.leave() }
            }
    }
}
