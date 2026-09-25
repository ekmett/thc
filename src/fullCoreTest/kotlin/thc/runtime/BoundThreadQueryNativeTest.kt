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
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

class BoundThreadQueryNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/bound-thread-query")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("compiler.Inlining", "false").option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            result += target
        }
        visit(entry); return result
    }

    @Test fun originalImportedCapabilityMatchesNonthreadedNativeOnFirstInstalledCalls() {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertEquals(8L, manifest["nativeRowsPerMode"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/BoundThreadQueryAudit.hs", "compiler/test-fixtures/BoundThreadQueryNative.hs",
            "compiler/THC/Plugin.hs", "scripts/core_original_foreign.py", "scripts/audit-core.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/bound-thread-query/nonthreaded/oracle.tsv", "build/bound-thread-query/threaded/oracle.tsv"))
        val controls = manifest["nativeControls"] as Map<String, Any?>
        assertEquals(listOf(false, false, false, false, false), controls["nonthreaded"])
        assertEquals(listOf(true, true, false, true, true), controls["threaded"])
        fun rows(mode: String) = File(directory, "$mode/oracle.tsv").readLines().map {
            it.split('\t').let { fields -> fields[0].toLong() to fields[1].toLong() }
        }
        val expected = rows("nonthreaded")
        assertEquals(listOf(Long.MIN_VALUE, -4097L, -1L, 0L, 1L, 42L, 4097L, Long.MAX_VALUE), expected.map { it.first })
        assertTrue(expected.all { it.first == it.second })
        assertEquals(expected.map { it.first to it.first + 1L }, rows("threaded"))
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val audit = json(File(directory, "$stage/audit.json"))
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            assertEquals(setOf("rtsSupportsBoundThreads"), (audit["foreignCalls"] as List<Map<String, Any?>>).map { it["symbol"] }.toSet())
            val merged = CoreModules.merge(paths.map { json(File(root, it)) })
            val module = CoreModules.reachable(merged, "boundThreadQuery", strictLink = true) + ("instrument" to true)
            val calls = OriginalStdioChecks.foreignCalls(module)
            assertTrue(calls.isNotEmpty(), "genuine imported foreign application retained")
            for (call in calls) {
                val proof = call[6] as Map<String, Any?>
                assertTrue(CoreBoundThreadForeign.validate(proof,
                    (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
                    call[3] as List<*>, proof["rep"]))
                CoreBoundThreadForeign.validateHead(call[1] as List<Any?>, false)
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    // Capture actual CAF/worker roots before forcing can erase a completed thunk's target.
                    val coldRoots = (module["bindings"] as List<Map<String, Any?>>).map {
                        program.entryTarget(it["id"] as String).rootNode
                    }.distinct()
                    if (backend == "ast") assertTrue(coldRoots.any {
                        NodeUtil.findAllNodeInstances(it, BoundThreadSupport::class.java).isNotEmpty()
                    }, "$stage original typed AST query node")
                    else assertTrue(coldRoots.filterIsInstance<BytecodeRoot>().any { body ->
                        body.bytecodeNode.instructions.any { it.name.contains("BoundThreadSupport") }
                    }, "$stage distinct typed bytecode query instruction")
                    val original = program.entryTarget("boundThreadQuery")
                    val host = program.hostEntryTarget(1)
                    val function = context.asValue(EntryValue(program, "boundThreadQuery", 1))
                    for ((input, answer) in expected) assertEquals(answer, function.execute(input).asLong())
                    val active = targets(host)
                    assertTrue(active.any { it.rootNode is GuestRoot && (it.rootNode as GuestRoot).isSelf(original) })
                    val installed = (active + original).distinct()
                    for (target in installed) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                    }
                    // The pure imported Bool can be a warmed CAF. These are actual
                    // compiled guest projection entries, not repeated foreign dispatch claims.
                    for ((input, answer) in expected.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(answer, function.execute(input).asLong(), "$stage/$backend first installed $input")
                        val delta = (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before
                        assertTrue(delta > 0L, "$stage/$backend actual compiled guest projection")
                        assertSame(original, program.entryTarget("boundThreadQuery"))
                        assertEquals(active, targets(host))
                        installed.forEach(::valid)
                        println("bound-thread-query $stage/$backend input=$input compiledGuestEntries=$delta targets=" +
                            installed.map { it.rootNode.name + ":valid=" + it.javaClass.getMethod("isValidLastTier").invoke(it) })
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.arguments.retainedReferences())
                        assertEquals(0, handoff.results.depth); assertEquals(0, handoff.results.retainedReferences())
                        assertNull(handoff.pending)
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun genuineOriginalImportsCannotBypassEitherLoaderWithMalformedCertificates() {
        val manifest = json(File(directory, "manifest.json"))
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val original = CoreModules.reachable(CoreModules.merge(paths.map { json(File(root, it)) }), "boundThreadQuery", strictLink = true)
            assertTrue(OriginalStdioChecks.foreignCalls(original).isNotEmpty())
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (mutation in listOf("unit", "safety", "arity", "state", "flags", "result-width", "missing-state", "head")) {
                        val module = Json.parse(Json.stringify(original)) as Map<String, Any?>
                        val call = OriginalStdioChecks.foreignCalls(module).first() as MutableList<Any?>
                        val metadata = call[6] as MutableMap<String, Any?>
                        val descriptor = metadata["foreignCall"] as MutableMap<String, Any?>
                        when (mutation) {
                            "unit" -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "base"
                            "safety" -> descriptor["safety"] = "safe"
                            "arity" -> descriptor["arity"] = 0L
                            "state" -> (descriptor["argumentReps"] as MutableList<Any?>)[0] = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to false)
                            "flags" -> call[3] = listOf(true)
                            "result-width" -> ((descriptor["resultRep"] as MutableMap<String, Any?>)["components"] as MutableList<MutableMap<String, Any?>>)[1]["primReps"] = listOf("Int32Rep")
                            "missing-state" -> ((descriptor["resultRep"] as MutableMap<String, Any?>)["components"] as MutableList<Any?>).removeAt(0)
                            "head" -> call[1] = listOf("prim", "rtsSupportsBoundThreads")
                        }
                        assertThrows(RuntimeFault::class.java, {
                            if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                        }, "$stage/$backend/$mutation")
                    }
                } finally { context.leave() }
            }
        }
    }
}
