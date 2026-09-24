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
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Genuine exported Core, native GHC, and an independent scalar byte oracle. */
class SimdInt32ByteArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-int32x4-bytearray")
    private fun withLanguage(inlining: Boolean, action: (Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("compiler.Inlining", inlining.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000")
        .build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @Test fun nativeMemoryCoreHasExactCompiledEntriesWithInlining() = nativeCore(true)
    @Test fun nativeMemoryCoreHasExactCompiledEntriesWithoutInlining() = nativeCore(false)
    @Test fun genuineExportedVectorBoundariesRemainRejected() {
        val provenance = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        for (stage in provenance["stages"] as List<String>) for (backend in listOf("ast", "bytecode")) withLanguage(true) { language ->
            val module = Json.parse(File(directory, "$stage-core/SimdInt32X4ByteArray.json").readText()) as Map<String, Any?>
            for (name in listOf("vectorArgument", "readTupleEscape", "readVectorEscape")) {
                val linked = CoreModules.reachable(module, name)
                assertThrows(RuntimeFault::class.java, {
                    if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                }, "$stage/$backend/$name")
            }
        }
    }
    private fun nativeCore(inlining: Boolean) {
        val provenance = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        val stages = provenance["stages"] as List<String>
        assertTrue("pre" in stages); assertEquals(true, provenance["positiveAuditsAccepted"])
        for (file in (provenance["sources"] as List<Map<String, String>>) + (provenance["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, file.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(file["sha256"], hash, "Stale Int32X4 memory source/artifact: ${file["path"]}")
        }
        fun rows(filename: String): Map<Pair<String, List<Long>>, Long> {
            val lines = File(directory, filename).readLines()
            val result = lines.associate { row ->
                val parts = row.split('\t'); (parts.first() to parts.drop(1).dropLast(1).map(String::toLong)) to parts.last().toLong()
            }
            assertEquals(lines.size, result.size, "Duplicate corpus rows")
            return result
        }
        val expected = rows("expected.tsv")
        assertEquals(9666, expected.size)
        if (provenance["nativeRows"] != null) {
            assertEquals(expected, rows("oracle.tsv"))
            assertEquals(expected.size.toLong(), (provenance["nativeRows"] as Number).toLong())
        }
        val entries = provenance["entries"] as List<Map<String, Any?>>
        val declared = entries.flatMap { entry ->
            (entry["cases"] as List<List<Number>>).map { entry["name"] to it.map(Number::toLong) }
        }
        assertEquals(declared.size, declared.toSet().size)
        assertEquals(declared.toSet(), expected.keys)
        val counts = provenance["expectedGuestCallsByEntry"] as Map<String, Number>
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage(inlining) { language ->
            val module = Json.parse(File(directory, "$stage-core/SimdInt32X4ByteArray.json").readText()) as Map<String, Any?>
            for (entry in entries) {
                val name = entry["name"] as String; val arity = (entry["arity"] as Number).toInt()
                val cases = (entry["cases"] as List<List<Number>>).map { row -> row.map(Number::toLong) }
                assertTrue(cases.isNotEmpty()); assertTrue(cases.all { it.size == arity })
                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                val p: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                val host = p.hostEntryTarget(arity); val closure = p.entryValue(name); val target = p.entryTarget(name)
                val callCount = counts.getValue(name).toLong()
                // The outer scalar root calls a runRW lambda, and worker wrappers
                // additionally call their one opaque worker. Counts come from Core.
                assertEquals(if (name in listOf("vectorUnitCase", "scalarUnitCase")) 2L else 3L, callCount)
                assertEquals(callCount, (provenance["checkedGuestCallsByStage"] as Map<String, Number>).getValue("$stage/$name").toLong())
                var targets = emptyList<RootCallTarget>()
                fun check(compiled: Boolean) {
                    for (input in cases) {
                        val label = "$stage/$backend/$name/$input/inlining=$inlining"
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected.getValue(name to input), Calls.target(host, arrayOf(closure, input.toTypedArray())), label)
                        if (compiled) {
                            assertEquals(before + callCount, (p.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label compiled guest entries")
                            val active = activeTargets(target)
                            assertEquals(targets.size, active.size, "$label active target count")
                            assertTrue(active.all { candidate -> targets.any { it === candidate } }, "$label active identities")
                            targets.forEach { valid(it, label) }
                            val calls = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java).filter { it.callTarget === target }
                            assertTrue(calls.isNotEmpty(), "$label selected entry")
                            calls.forEach { assertSame(target, it.currentCallTarget, "$label selected identity") }
                        }
                        val state = language.handoffState.get()
                        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                    }
                }
                check(false)
                targets = activeTargets(target)
                assertEquals(callCount.toInt(), targets.size, "$stage/$backend/$name guest roots")
                for (t in targets) {
                    t.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(t, true)
                    valid(t, "initial installation")
                }
                check(true)
                for (counter in listOf("unsupportedTraps", "blackholes"))
                    assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong(), counter)
            }
        }
    }
}
