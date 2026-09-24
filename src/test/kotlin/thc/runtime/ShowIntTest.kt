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

class ShowIntTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val worker = "ghc-internal:GHC.Internal.Show.\$fShowCallStack_itos'"
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial installation")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    private data class Row(val name: String, val input: Long, val index: Long, val expected: Long)
    @Test fun publicShowAndEveryCharacterWithInlining() = native(true)
    @Test fun publicShowAndEveryCharacterAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = Json.parse(File(root, "build/show-int/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("sources", "artifacts")) for (item in manifest[kind] as List<Map<String, String>>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(item["sha256"], hash, "Stale Show preparation: ${item["path"]}")
        }
        val rows = File(root, "build/show-int/oracle.tsv").readLines().map { line ->
            val p = line.split('\t'); Row(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong())
        }
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.size)
        val inputs = (manifest["inputs"] as List<Number>).map { it.toLong() }
        assertEquals(listOf("showChecksum", "showCharacter"), manifest["entries"])
        assertEquals(inputs, rows.filter { it.name == "showChecksum" }.map { it.input })
        for (x in inputs) {
            val characters = rows.filter { it.name == "showCharacter" && it.input == x }
            val text = x.toString()
            assertEquals((-1..text.length).map { it.toLong() }, characters.map { it.index })
            assertEquals(text, characters.filter { it.index >= 0 && it.index < text.length }.map { it.expected.toInt().toChar() }.joinToString(""))
            assertEquals(-1L, characters.first().expected); assertEquals(-1L, characters.last().expected)
            assertEquals(text.fold(5381L) { acc,c -> acc*33+c.code }, rows.single { it.name == "showChecksum" && it.input == x }.expected)
        }
        val stages = manifest["stages"] as Map<String, List<String>>
        for ((stage, paths) in stages) {
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (name in listOf("showChecksum", "showCharacter")) {
                val audit = Json.parse(File(root, "build/show-int/$stage-$name.audit.json").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"])
                assertTrue((audit["reachableBindings"] as List<Map<String, Any?>>).any { it["id"] == worker })
                val selected = rows.filter { it.name == name }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, name)
                        val instrumented = linked + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, instrumented) else BytecodeProgram(language, instrumented)
                        val arity = if (name == "showChecksum") 1 else 2
                        val function = context.asValue(EntryValue(program, name, arity))
                        val host = program.hostEntryTarget(arity); val original = program.entryTarget(name)
                        val digitWorker = program.entryTarget(worker)
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        fun check(row: Row) {
                            val actual = if (arity == 1) function.execute(row.input).asLong() else function.execute(row.input, row.index).asLong()
                            assertEquals(row.expected, actual, "$label/${row.input}/${row.index}")
                            released(language)
                        }
                        // All lengths, signs, and selector exits are warmed once; no
                        // retry/recompile loop or automatic-compiler limit changes.
                        selected.forEach(::check)
                        val targets = activeTargets(host)
                        assertTrue(targets.size > 1, "$label adopted guest call path")
                        targets.filter { it !== host }.forEach(::compile)
                        compile(digitWorker)
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val state = language.handoffState.get()
                        val resultAllocations = state.results.allocations
                        for (row in selected.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertTrue(after > before, "$label/${row.input}/${row.index}: actual compiled guest entry")
                            assertEquals(targets, activeTargets(host), "$label active target identity")
                            valid(original, "$label original"); valid(digitWorker, "$label actual Show worker")
                            targets.forEach { valid(it, "$label active") }
                        }
                        assertEquals(resultAllocations, state.results.allocations, "$label result slabs reused")
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        println("ShowInt PASS $label rows=${selected.size} activeTargets=${targets.size}")
                    } finally { context.leave() }
                }
            }
        }
    }
}
