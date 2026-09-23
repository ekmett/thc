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

class UnsafeEqualityTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun module(stage: String): Map<String, Any?> = CoreModules.merge(listOf("UnsafeEqualityAudit", "THC.InterfaceClosure").map {
        Json.parse(File(root, "build/unsafe-equality/$stage-core/$it.json").readText()) as Map<String, Any?>
    })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target, "installed")
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
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        assertEquals(0, language.handoffState.get().arguments.depth)
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().arguments.retainedReferences())
        assertEquals(0, language.handoffState.get().results.retainedReferences())
    }
    private fun verifyEvidence() {
        val evidence = Json.parse(File(root, "build/unsafe-equality/provenance.json").readText()) as Map<String, Any?>
        val records = listOf("sources", "artifacts").flatMap { evidence[it] as List<Map<String, String>> }
        for (record in records) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, record.getValue("path")).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(record["sha256"], hash, "Stale unsafe-equality evidence: ${record["path"]}")
        }
        for (stage in listOf("pre", "post")) assertEquals(true,
            (Json.parse(File(root, "build/unsafe-equality/$stage-audit.json").readText()) as Map<*, *>)["accepted"])
    }
    @Test fun nativeCasesWithInlining() = native(true)
    @Test fun nativeCasesAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        verifyEvidence()
        val rows = File(root, "build/unsafe-equality/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(49, rows.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) for ((name, selected) in rows) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, CoreModules.reachable(module(stage), name) + ("instrument" to true), backend)
                val function = context.asValue(EntryValue(program, name, 1)); val host = program.hostEntryTarget(1)
                val original = program.entryTarget(name); val label = "$stage/$backend/$name/inline=$inlining"
                fun check(row: List<String>) = assertEquals(row[2].toLong(), function.execute(row[1].toLong()).asLong(), "$label/${row[1]}")
                selected.forEach(::check)
                val targets = activeTargets(host)
                assertTrue(targets.size > 1, "$label actual adopted guest target")
                targets.filter { it !== host }.forEach(::compile)
                assertTrue(function.invokeMember("compile").asBoolean())
                for (row in selected.asReversed()) {
                    val before = count(program); check(row)
                    val expectedEntries = if (name == "unusedCase" && row[1].toLong() >= 0) 1L else 2L
                    assertEquals(before + expectedEntries, count(program), "$label/${row[1]} exact compiled guest entries")
                    assertEquals(targets, activeTargets(host), "$label active target identities")
                    valid(original, "$label original"); targets.forEach { valid(it, "$label active") }
                    released(language)
                }
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            } finally { context.leave() }
        }
    }

    @Test fun nonmatchingFirstClassProofsRemainStrictFrontiers() {
        verifyEvidence()
        for (stage in listOf("pre", "post")) for (name in listOf("firstClassProof", "liveBinder")) {
            val audit = Json.parse(File(root, "build/unsafe-equality/$stage-$name-audit.json").readText()) as Map<*, *>
            assertEquals(false, audit["accepted"])
            assertEquals(listOf("ghc-internal:GHC.Internal.Unsafe.Coerce.unsafeEqualityProof"),
                (audit["missingGlobals"] as List<Map<*, *>>).map { it["id"] })
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    assertThrows(UnsupportedCore::class.java) {
                        program(language, CoreModules.reachable(module(stage), name), backend)
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun wrongCalleeAndDemandedBottomIntentionallyDeoptWithoutPublishingResults() {
        verifyEvidence()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (inlining in listOf(true, false)) for (name in listOf("wrongCalleeCase", "demandedBottomCase")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, CoreModules.reachable(module(stage), name) + ("instrument" to true), backend)
                val host = program.hostEntryTarget(1)
                val original = program.entryTarget(name)
                fun fail() = assertThrows(GuestException::class.java) {
                    Calls.target(host, arrayOf(program.entryValue(name), arrayOf(9L)))
                }
                val initial = fail(); released(language)
                assertEquals("main:UnsafeEqualityAudit.Failure", (initial.payload as DataValue).layout.id)
                val active = activeTargets(host)
                active.filter { it !== host }.forEach(::compile)
                compile(original); compile(host)
                valid(original, name); active.forEach { valid(it, name) }
                val before = count(program)
                val raised = fail(); released(language)
                assertSame(initial.payload, raised.payload, "Preserve the real guest exception payload")
                // raise# deliberately transfers to the interpreter and invalidates.
                // An always-throwing graph can deopt before entry instrumentation;
                // this checks installed-code invalidation, not compiled value execution.
                val validity = active.map { it.javaClass.getMethod("isValidLastTier").invoke(it) as Boolean }
                assertTrue(validity.any { !it }, "$stage/$backend/$name/inlining=$inlining intentional raise# deopt")
                println("raise-control $stage/$backend/$name/inlining=$inlining installed=${active.size} " +
                    "validAfter=$validity compiledEntryDelta=${count(program) - before}")
                assertEquals(active, activeTargets(host))
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            } finally { context.leave() }
        }
    }
}
