@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class EmptyTupleInputNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val folder = File(root, "build/empty-tuple-input")
    private fun module(stage: String) = Json.parse(File(folder, "$stage-core/EmptyTupleInputAudit.json").readText()) as Map<String, Any?>
    @BeforeEach fun currentEvidence() {
        val evidence = Json.parse(File(folder, "provenance.json").readText()) as Map<String, Any?>
        for (kind in listOf("sources", "artifacts")) for (item in evidence[kind] as List<Map<String, String>>) {
            val path = item.getValue("path")
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(item.getValue("sha256"), actual, "Stale empty-input evidence: $path")
        }
        for (stage in listOf("pre", "post")) {
            val audit = Json.parse(File(folder, "$stage-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], "$stage strict native audit")
        }
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000").option("engine.CompilationFailureAction", "Throw")
        .option("compiler.CompilationTimeout", "30").option("compiler.MaximumGraalGraphSize", "100000")
        .option("compiler.Inlining", inlining.toString()).build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences()); assertNull(state.pending)
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    @Test fun genuineEmptyInputsExecuteInlinedWithInstalledHostAndGuestEntries() = native(true)
    @Test fun genuineEmptyInputsExecuteAcrossResidualCallsWithInstalledHostAndGuestEntries() = native(false)
    private fun native(inlining: Boolean) {
        val rows = File(folder, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(118, rows.values.sumOf { it.size }); assertEquals(17, rows.size)
        for (stage in listOf("pre", "post")) for ((name, cases) in rows) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module(stage), name) + ("instrument" to true)
                val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                val host = program.hostEntryTarget(1)
                val function = context.asValue(EntryValue(program, name, 1))
                val label = "$stage/$backend/$name/inlining=$inlining"
                fun check(row: List<String>) {
                    assertEquals(row[2].toLong(), function.execute(row[1].toLong()).asLong(), "$label/${row[1]}")
                    released(language)
                }
                cases.forEach(::check)
                assertTrue(function.invokeMember("compile").asBoolean(), "$label installation")
                val original = program.entryTarget(name)
                fun activeTargets() = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                    .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                    .ifEmpty { listOf(original) }.toSet()
                val active = activeTargets()
                for (row in cases.asReversed()) {
                    val before = program.diagnostics()["compiledEntries"] as Long
                    check(row)
                    assertTrue(program.diagnostics()["compiledEntries"] as Long > before, "$label/${row[1]} compiled guest entry")
                    assertEquals(active, activeTargets(), "$label current active identities")
                    valid(original, "$label original guest"); valid(host, "$label host")
                    active.forEach { valid(it, "$label active guest") }
                }
                assertEquals(0L, program.diagnostics()["unsupportedTraps"]); assertEquals(0L, program.diagnostics()["blackholes"])
            } finally { context.leave() }
        }
    }
    @Test fun nativeEmptyProducerFailurePrecedesDeadFormalOrPapAndLeavesNoLoans() {
        for (stage in listOf("pre", "post")) for (name in listOf("effectCase", "effectPapCase")) for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module(stage), name)
                val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                fun call(x: Long) = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(name), arrayOf(x)))
                val good = call(7L)
                assertThrows(GuestException::class.java) { call(-1L) }; released(language)
                assertEquals(good, call(7L)); released(language)
            } finally { context.leave() }
        }
    }
}
