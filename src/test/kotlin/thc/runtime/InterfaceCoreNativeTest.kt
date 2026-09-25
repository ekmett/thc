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
    private val entries = listOf("opaqueEntry", "inlineEntry", "recursiveEntry")
    private fun source() = Json.parse(File(directory, "InterfaceLibrary.json").readText()) as Map<String, Any?>

    private fun oracle(): List<List<Long>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(entries, manifest["entries"])
        assertEquals("thc-interface-fixture-0.1", manifest["unit"])
        assertEquals(listOf("opaque-body", "private-worker", "recursive-groups", "thin-unavailable",
            "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-rejected",
            "private-flags", "repeat-load"), manifest["controls"])
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
            maxOf(0L, row[0]) * 7 + 11), row) }
        return rows
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
                        val linked = CoreModules.reachable(source(), entry, strictLink = true) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                            else BytecodeProgram(language, linked)
                        val target = program.entryTarget(entry)
                        fun check(row: List<Long>) {
                            assertEquals(row[index + 1], Calls.target(target, arrayOf(0L, row[0])), "$backend/$entry/${row[0]}")
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                        repeat(3) { rows.forEach(::check) }
                        val active = targets(target)
                        if (entry == "opaqueEntry") assertTrue(active.size >= 2, "Keep the opaque private call")
                        active.forEach {
                            it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                            valid(it)
                        }
                        for (row in rows.asReversed() + rows) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= active.size,
                                "$backend/$entry first installed invocation must enter all retained compiled roots")
                            active.forEach(::valid)
                        }
                        assertEquals("reject-at-load", program.diagnostics()["unsupportedPolicy"])
                        assertEquals(0L, (program.diagnostics()["unsupportedTraps"] as Number).toLong())
                        assertEquals(0L, (program.diagnostics()["blackholes"] as Number).toLong())
                    } finally { context.leave() }
                }
    }
}
