// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

/** Original prettyStackEntry, not the JVM diagnostic renderer or the full stack decoder. */
class OriginalStackFormatterTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-stack-formatter/"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private val triples = listOf(
        listOf("entry", "Main", "Fixture.hs:12:3-12:17"),
        listOf("", "", ""),
        listOf("\$wdecode", "GHC.Internal.Stack.Decode", "<source unavailable>"),
        listOf("λ雪😀", "Módulo.例", "路径.hs:1:2"),
        listOf("a.b (c)", "M\tN", "line\nspan"),
        listOf("\u0000last", "Null", "zero\u0000location"))
    private fun expected(row: Int) = triples[row].let { (function, module, location) ->
        "$module.$function ($location)".codePoints().toArray()
    }
    private fun manifest(): Map<String, Any?> {
        val value = json(prefix + "manifest.json")
        require(value["format"] == "thc-original-stack-formatter-fixture" && value["schema"] == 1L)
        require(value["ghc"] == "9.14.1" && value["installedArtifactsHashed"] == false)
        val inputs = value["inputHashes"] as Map<String, String>
        require(inputs["compiler/pinned-ghc-internal/GHC/Internal/Stack/Decode.hs"] ==
            "0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f")
        require(inputs.keys.containsAll(listOf("src/THC/Driver/Wired.hs", "compiler/test-fixtures/OriginalStackFormatter.hs",
            "compiler/test-fixtures/OriginalStackFormatterNative.hs", "test/haskell-fixtures/StackFixtures.hs")))
        val artifacts = value["artifactHashes"] as Map<String, String>
        for ((kind, records) in listOf("input" to inputs, "artifact" to artifacts)) for ((path, digest) in records) {
            require(!File(path).isAbsolute && ".." !in path.split('/'))
            val base = if (kind == "artifact") File(root, prefix).canonicalFile else root.canonicalFile
            require(kind != "artifact" || path.startsWith(prefix))
            val file = File(root, path).canonicalFile
            require(file.toPath().startsWith(base.toPath())) // Before reading: never hash installed GHC symlinks.
            require(hash(file) == digest) { "Stale $kind: $path" }
        }
        val originals = value["originals"] as List<String>
        require(originals.isNotEmpty() && originals.size == originals.toSet().size && originals.all { it in artifacts })
        val stages = value["stages"] as Map<String, String>
        require(stages.keys == setOf("pre", "post") && stages.values.all { it in artifacts })
        require(value["nativeOutput"] in artifacts)
        val audits = value["audits"] as List<String>
        require(audits.size == 2 && audits.all { it in artifacts && json(it)["accepted"] == true })
        val commands = value["commands"] as List<Map<String, Any?>>
        require(commands.size == 9 && commands.all { it["exit"] == 0L && it["timedOut"] != true })
        return value
    }
    private fun rows(manifest: Map<String, Any?>): List<Triple<Long, Long, Long>> {
        val rows = File(root, manifest["nativeOutput"] as String).readLines().map { line ->
            val fields = line.split('\t').map(String::toLong)
            require(fields.size == 3)
            Triple(fields[0], fields[1], fields[2])
        }
        val domain = (0..5).flatMap { row -> (0..100).map { row.toLong() to it.toLong() } }
        require(rows.map { it.first to it.second } == domain)
        for ((row, index, result) in rows) assertEquals(expected(row.toInt()).getOrNull(index.toInt())?.toLong() ?: -1L, result)
        return rows
    }
    private fun linked(manifest: Map<String, Any?>, stage: String, consumer: String): Map<String, Any?> {
        val report = json((manifest["audits"] as List<String>).single { it.endsWith("/$stage-audit.json") })
        val reached = report["reachableBindings"] as List<Map<String, Any?>>
        val ids = reached.map { it["id"] }.toSet()
        val paths = reached.map { it["source"] as String }.toSet()
        require(paths.all { it == consumer || it in manifest["originals"] as List<*> })
        // The complete source modules are large. Retain exact bindings from one parsed
        // module at a time; strict linking below independently checks the audit's slice.
        val originals = paths.map { path ->
            val full = json(path)
            full + ("bindings" to (full["bindings"] as List<Map<String, Any?>>).filter { it["id"] in ids })
        }
        val module = CoreModules.merge(originals)
        val linked = CoreModules.reachable(module, "formatOriginal", strictLink = true)
        val bindings = linked["bindings"] as List<Map<String, Any?>>
        val original = bindings.single { it["id"] == "ghc-internal:GHC.Internal.Stack.Decode.prettyStackEntry" }
        val full = originals.single { it["module"] == "GHC.Internal.Stack.Decode" }
        assertEquals((full["bindings"] as List<Map<String, Any?>>).single { it["id"] == original["id"] }, original)
        assertTrue(bindings.any { it["id"] == "ghc-internal:GHC.Internal.Stack.Decode.\$wprettyStackEntry" })
        assertFalse(bindings.any { (it["id"] as String).contains("decodeStack") })
        return linked + ("instrument" to true)
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget, label: String) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @Test fun freshOriginalFormatterMatchesNativeCodePointsBeforeAndAfterExplicitCompilation() {
        val manifest = manifest()
        val rows = rows(manifest)
        for ((stage, consumer) in manifest["stages"] as Map<String, String>) {
            val module = linked(manifest, stage, consumer)
            for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val target = program.entryTarget("formatOriginal")
                    var compiled = false
                    fun check(row: Long, index: Long, expected: Long) {
                        val label = "$stage/$backend/inlining=$inlining/$row/$index"
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected, Calls.target(target, arrayOf(0L, row, index)), label)
                        if (compiled) {
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before, label)
                            valid(target, label)
                        }
                        val state = language.handoffState.get()
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
                        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
                    }
                    repeat(2) { rows.forEach { (row,index,result) -> check(row,index,result) } }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "$stage/$backend installation")
                    compiled = true
                    rows.forEach { (row,index,result) -> check(row,index,result) } // First call after installation, no settling.
                    rows.asReversed().forEach { (row,index,result) -> check(row,index,result) }
                } finally { context.leave() }
            }
        }
    }
}
