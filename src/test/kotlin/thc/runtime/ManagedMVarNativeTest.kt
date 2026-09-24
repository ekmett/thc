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
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ManagedMVarNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/managed-mvars")
    private val names = listOf("transitions", "lazyPayload", "aliasRoundTrip", "unliftedPayload", "closurePayload")
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun manifest(): Map<String, Any?> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(false, manifest["installedArtifactsHashed"])
        for (key in listOf("inputHashes", "artifactHashes")) {
            for ((path, expected) in manifest[key] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale MVar source/artifact: $path")
            }
        }
        assertTrue((manifest["auditStatus"] as Map<String, String>).values.all { it == "accepted" })
        return manifest
    }
    private fun model(name: String, x: Long): Long = when (name) {
        "transitions", "unliftedPayload" -> x * (1 + 257 + 65537) + (x + 17) * 16777259 + 96
        "lazyPayload" -> x + 30
        "aliasRoundTrip" -> (x + if (x < 0) 1 else 0) * 257 +
            (x + if (x >= 0) 1 else 0) * 65537 + 28 * x
        "closurePayload" -> 4 * x + 17
        else -> error(name)
    }
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
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertNull(state.pending)
    }

    @Test fun nativeMVarsWithInlining() = native(true)
    @Test fun nativeMVarsAcrossResidualCalls() = native(false)

    private fun native(inlining: Boolean) {
        val manifest = manifest()
        assertEquals(names, manifest["entryNames"])
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals(845, rows.values.sumOf { it.size })
        assertEquals(845, (manifest["nativeRows"] as Number).toInt())
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (name in names) {
                val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
                assertEquals((manifest["inputs"] as List<Number>).map { it.toLong() }, cases.map { it.first })
                assertEquals(cases.size, cases.map { it.first }.toSet().size)
                cases.forEach { (input, native) -> assertEquals(model(name, input), native, "Native $name/$input") }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val entry = program.entryTarget(name)
                        val host = program.hostEntryTarget(1)
                        val function = context.asValue(EntryValue(program, name, 1))
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        fun check(input: Long, expected: Long) {
                            assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                            released(language)
                        }
                        // Normal compilation policy remains enabled during warmup.
                        cases.forEach { (input, expected) -> check(input, expected) }
                        val targets = activeTargets(host)
                        (targets + entry).distinct().filter { it !== host }.forEach(::compile)
                        assertTrue(function.invokeMember("compile").asBoolean(), "$label host installation")
                        for ((input, expected) in cases) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(input, expected)
                            val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertTrue(after > before, "$label/$input entered compiled guest code")
                            valid(entry, "$label original entry")
                            assertEquals(targets, activeTargets(host), "$label active target identities")
                            targets.forEach { valid(it, "$label active target") }
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                    } finally { context.leave() }
                }
            }
        }
    }
}
