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

class ShowWordListTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("wordChecksum", "wordCharacter", "listChecksum", "listCharacter")
    private val wordWorker = "ghc-internal:GHC.Internal.Show.showWord"
    private val listWorkers = listOf("ghc-internal:GHC.Internal.Show.\$fShowList_showl",
        "ghc-internal:GHC.Internal.Show.\$fShowCallStack_itos'", "ghc-internal:GHC.Internal.CString.unpackCString#")
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
    private data class Row(val name: String, val input: Long, val shape: Long, val index: Long, val expected: Long)
    private fun text(name: String, input: Long, shape: Long): String {
        if (name.startsWith("word")) return java.lang.Long.toUnsignedString(input)
        val values = when (shape) {
            0L -> emptyList()
            1L -> listOf(input)
            3L -> listOf(input, input + 1, -input)
            else -> error("Unexpected list shape: $shape")
        }
        return values.joinToString(",", "[", "]")
    }
    @Test fun publicWordAndListCharactersWithInlining() = native(true)
    @Test fun publicWordAndListCharactersAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = Json.parse(File(root, "build/show-word-list/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("sources", "artifacts")) for (item in manifest[kind] as List<Map<String, String>>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(item["sha256"], hash, "Stale Show Word/list preparation: ${item["path"]}")
        }
        val rows = File(root, "build/show-word-list/oracle.tsv").readLines().map { line ->
            val p = line.split('\t'); Row(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong())
        }
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.size)
        val inputs = (manifest["inputs"] as List<Number>).map { it.toLong() }
        val shapes = (manifest["listShapes"] as List<Number>).map { it.toLong() }
        assertEquals(entries, manifest["entries"])
        assertEquals(listOf(0L, 1L, 3L), shapes)
        assertEquals(530, inputs.size); assertEquals(inputs.size, inputs.toSet().size)
        assertTrue(inputs.containsAll(listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)))
        val expectedRows = buildList {
            for (name in entries) for (input in inputs) for (shape in if (name.startsWith("word")) listOf(0L) else shapes) {
                val formatted = text(name, input, shape)
                if (name.endsWith("Character")) {
                    for (index in -1..formatted.length) add(Row(name, input, shape, index.toLong(),
                        if (index in formatted.indices) formatted[index].code.toLong() else -1L))
                } else add(Row(name, input, shape, 0, formatted.fold(5381L) { acc,c -> acc*33+c.code }))
            }
        }
        assertEquals(expectedRows, rows, "Every unsigned/list character, checksum, and end sentinel matches independently")
        val stages = manifest["stages"] as Map<String, List<String>>
        for ((stage, paths) in stages) {
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (name in entries) {
                val workerIds = if (name.startsWith("word")) listOf(wordWorker) else listWorkers
                val audit = Json.parse(File(root, "build/show-word-list/$stage-$name.audit.json").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"])
                assertTrue((audit["reachableBindings"] as List<Map<String, Any?>>).map { it["id"] }.containsAll(workerIds))
                val selected = rows.filter { it.name == name }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, name)
                        val instrumented = linked + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, instrumented) else BytecodeProgram(language, instrumented)
                        val arity = when (name) { "wordChecksum" -> 1; "listCharacter" -> 3; else -> 2 }
                        val function = context.asValue(EntryValue(program, name, arity))
                        val host = program.hostEntryTarget(arity); val original = program.entryTarget(name)
                        val workers = workerIds.map { program.entryTarget(it) }
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        fun check(row: Row) {
                            val actual = when (name) {
                                "wordChecksum" -> function.execute(row.input).asLong()
                                "wordCharacter" -> function.execute(row.input, row.index).asLong()
                                "listChecksum" -> function.execute(row.input, row.shape).asLong()
                                else -> function.execute(row.input, row.shape, row.index).asLong()
                            }
                            assertEquals(row.expected, actual, "$label/${row.input}/${row.shape}/${row.index}")
                            released(language)
                        }
                        // Warm each retained native row once. No extra settling calls,
                        // compilation retries or automatic-compiler threshold changes.
                        selected.forEach(::check)
                        val targets = activeTargets(host)
                        assertTrue(targets.size > 1, "$label adopted guest call path")
                        targets.filter { it !== host }.forEach(::compile)
                        workers.forEach(::compile)
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val state = language.handoffState.get()
                        val resultAllocations = state.results.allocations
                        for (row in selected.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertTrue(after > before, "$label/${row.input}/${row.shape}/${row.index}: actual compiled guest entry")
                            assertEquals(targets, activeTargets(host), "$label active target identity")
                            valid(original, "$label original")
                            workers.forEach { valid(it, "$label original Show worker") }
                            targets.forEach { valid(it, "$label active") }
                        }
                        assertEquals(resultAllocations, state.results.allocations, "$label result slabs reused")
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        println("ShowWordList PASS $label rows=${selected.size} activeTargets=${targets.size}")
                    } finally { context.leave() }
                }
            }
        }
    }
}
