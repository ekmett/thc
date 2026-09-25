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

class InterfaceCoreNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/interface-core")
    private val entries = listOf("opaqueEntry", "inlineEntry", "recursiveEntry", "coercionEntry")
    private fun source(entry: String = "opaqueEntry") = Json.parse(File(directory,
        if (entry == "coercionEntry") "CBVCoercionAudit.json" else "InterfaceLibrary.json").readText()) as Map<String, Any?>

    private fun oracle(): List<List<Long>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(entries, manifest["entries"])
        assertEquals("thc-interface-fixture-0.1", manifest["unit"])
        assertEquals(listOf("opaque-body", "private-worker", "recursive-groups", "thin-unavailable",
            "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-rejected",
            "private-flags", "repeat-load", "helper-protocol", "installed-cbv-worker", "installed-wired-unit"), manifest["controls"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "$kind/$path")
            }
        val rows = File(directory, "logs/native-oracle.stdout").readLines().map { line ->
            line.split(' ').map(String::toLong)
        }
        assertEquals((-10L..10L).toList(), rows.map { it[0] })
        rows.forEach { row -> assertEquals(listOf(row[0], row[0] * 7 + 11, row[0] + 3,
            maxOf(0L, row[0]) * 7 + 11, row[0] + 7), row) }
        return rows
    }

    @Test fun helperPreservesGenuineInstalledWorkerCbvMarks() {
        oracle()
        val direct = Json.parse(File(directory, "direct/CBVCoercionAudit.json").readText()) as Map<String, Any?>
        val loaded = source("coercionEntry")
        fun worker(module: Map<String, Any?>) = (module["bindings"] as List<Map<String, Any?>>)
            .single { it["name"] == "\$wwitnessed" }
        val original = worker(direct)
        val hydrated = worker(loaded)
        assertEquals(listOf(false, false, true), original["entryStrict"])
        assertEquals(original["entryStrict"], hydrated["entryStrict"])
        assertEquals("ghc-id", original["entryStrictSource"])
        assertEquals("ghc-id", hydrated["entryStrictSource"])
        assertEquals((original["info"] as Map<*, *>)["cbvMarks"], (hydrated["info"] as Map<*, *>)["cbvMarks"])
        val parameters = (hydrated["expr"] as List<*>)[1] as List<Map<String, Any?>>
        assertEquals(true, parameters[0]["coercion"])
        assertEquals(emptyList<String>(), CoreRepresentations.binder(parameters[0]).primReps)
        assertEquals(true, parameters[2]["lifted"])
        val missing = Json.parse(File(directory, "logs/helper-thin.stdout").readText()) as Map<String, Any?>
        assertEquals("unavailable", missing["status"])
        assertEquals("complete-interface-core", missing["capability"])
        assertFalse(missing.containsKey("core"))
        val command = Json.parse(File(directory, "logs/helper-thin.command.json").readText()) as Map<String, Any?>
        assertEquals(3L, (command["exit"] as Number).toLong())
        val wired = Json.parse(File(directory, "wired-unit.json").readText()) as Map<String, Any?>
        val wiredResponse = Json.parse(File(directory, "logs/helper-wired-unit.stdout").readText()) as Map<String, Any?>
        val wiredCommand = Json.parse(File(directory, "logs/helper-wired-unit.command.json").readText()) as Map<String, Any?>
        assertEquals("ghc-internal", wired["interfaceUnit"])
        assertNotEquals(wired["registeredUnit"], wired["interfaceUnit"])
        assertEquals(wired["expectedExit"], wiredCommand["exit"])
        if (wired["completeCore"] == true) {
            assertEquals("loaded", wiredResponse["status"])
            assertEquals(wired["interfaceUnit"], (wiredResponse["core"] as Map<*, *>)["unit"])
        } else {
            assertEquals("unavailable", wiredResponse["status"])
            assertEquals("complete-interface-core", wiredResponse["capability"])
            assertEquals(wired["registeredUnit"], wiredResponse["unit"])
            assertFalse(wiredResponse.containsKey("core"))
        }
    }

    @Test fun completeInterfacePreservesMetadataAndPassesStrictAdmission() {
        oracle()
        val source = source()
        assertEquals("optimized-Core-after-Tidy-before-CorePrep", source["boundary"])
        assertEquals("thc-interface-fixture-0.1", source["unit"])
        assertFalse(File(directory, "source/InterfaceLibrary.hs").exists())
        val bindings = source["bindings"] as List<Map<String, Any?>>
        val worker = bindings.single { it["name"] == "privateWorker" }
        assertTrue((worker["id"] as String).startsWith("thc-interface-fixture-0.1:InterfaceLibrary.privateWorker_"))
        assertTrue((source["groups"] as List<Map<String, Any?>>).any { it["recursive"] == true })
        assertTrue((source["constructors"] as List<Map<String, Any?>>).any {
            (it["id"] as String).endsWith("InterfaceLibrary.Token") })
        val files = source["sourceFiles"] as List<Map<String, Any?>>
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it["content"] == null }, "Missing source text must not be fabricated")
        assertTrue((source["sourceSpans"] as List<*>).isNotEmpty())
        for (entry in entries) {
            val audit = Json.parse(File(directory, "$entry-audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], entry)
            assertEquals(emptyList<Any>(), audit["issues"], entry)
            assertEquals(emptyList<Any>(), audit["missingGlobals"], entry)
        }
    }

    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val found = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val roots = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(node)
            roots.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }
                .filter { it.rootNode is GuestRoot }.forEach(::visit)
            found += target
        }
        visit(entry)
        return found
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)

    @Test fun recoveredBodiesMatchNativeInFirstInstalledAstAndBytecodeCode() {
        val rows = oracle()
        for (backend in listOf("ast", "bytecode")) for ((index, entry) in entries.withIndex())
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false")
                .option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(source(entry), entry, strictLink = true) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                            else BytecodeProgram(language, linked)
                        val target = program.entryTarget(entry)
                        // Save the target before a CAF update clears it. A
                        // memoized constant is not a per-invocation function.
                        val constants = (linked["bindings"] as List<Map<String, Any?>>).mapNotNull { binding ->
                            (program.entryValue(binding["id"] as String) as? Thunk)?.let {
                                it to checkNotNull(it.target)
                            }
                        }
                        fun check(row: List<Long>) {
                            assertEquals(row[index + 1], Calls.target(target, arrayOf(0L, row[0])), "$backend/$entry/${row[0]}")
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                        repeat(3) { rows.forEach(::check) }
                        val active = targets(target)
                        val memoized = constants.filter { it.first.state == 2 }
                        val perCall = active.filterNot { candidate -> memoized.any { it.second === candidate } }
                        val thunkCounts = program.diagnostics().getValue("thunkEvaluationsByLabel")
                        if (entry == "coercionEntry") {
                            assertEquals(1, memoized.size, "The original lifted Spine CAF was evaluated once")
                            assertNull(memoized.single().first.target)
                            assertTrue(active.any { it === memoized.single().second })
                            assertEquals(2, perCall.size)
                            assertEquals(setOf("coercionEntry", "\$wwitnessed"), perCall.map {
                                (it.rootNode as GuestRoot).coreIdentity?.occurrence
                            }.toSet())
                        } else assertEquals(active, perCall)
                        if (entry == "opaqueEntry") assertTrue(active.size >= 2, "Keep the opaque private call")
                        active.forEach {
                            it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                            valid(it)
                        }
                        for (row in rows.asReversed() + rows) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            val entered = (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before
                            assertTrue(entered >= perCall.size,
                                "$backend/$entry first installed invocation entered $entered of ${perCall.map { it.rootNode.name }}")
                            if (entry == "coercionEntry") {
                                assertEquals(2L, entered, "Entry and real CBV worker must both execute compiled")
                                assertEquals(thunkCounts, program.diagnostics().getValue("thunkEvaluationsByLabel"),
                                    "The memoized Spine CAF must not execute again")
                            }
                            active.forEach(::valid)
                        }
                        assertEquals("reject-at-load", program.diagnostics()["unsupportedPolicy"])
                        assertEquals(0L, (program.diagnostics()["unsupportedTraps"] as Number).toLong())
                        assertEquals(0L, (program.diagnostics()["blackholes"] as Number).toLong())
                    } finally { context.leave() }
                }
    }
}
