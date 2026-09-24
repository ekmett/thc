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
import java.nio.file.Files
import java.security.MessageDigest

/** Original prettyStackEntry, not the JVM diagnostic renderer or the full stack decoder. */
class OriginalStackFormatterTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-stack-formatter/"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(file: File) = hash(file.readBytes())
    private val sourceRoot = "compiler/pinned-ghc-internal/"
    // Pin the complete path/hash catalog independently, without duplicating its 49 entries.
    // A changed exporter catalog cannot silently drop or repin a source in the fixture receipt.
    private val pinned by lazy {
        val text = contained("src/THC/Driver/Wired.hs", true).readText()
            .substringAfter("sourceHashes =").substringBefore("\ndata WiredArtifacts")
        val entries = Regex("\\(\"([^\"]+)\", \"([0-9a-f]{64})\"\\)").findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        require(entries.size == 49 && entries.map { it.first }.toSet().size == 49)
        require(hash(entries.sortedBy { it.first }.joinToString("") { (path, digest) -> "$path\u0000$digest\n" }.toByteArray()) ==
            "6c5052c10dc9bb70ff7ae4a37e468f46ab68cbe2a4105c82dabc4f59fd6b137f")
        entries.associate { (path, digest) -> sourceRoot + path to digest }
    }
    private val requiredInputs by lazy {
        pinned.keys + setOf(
            "compiler/test-fixtures/OriginalStackFormatter.hs", "compiler/test-fixtures/OriginalStackFormatterNative.hs",
            "test/haskell-fixtures/StackFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs",
            "thc.cabal", "src/THC/Driver/Wired.hs", "compiler/target-layout.c", "compiler/build.sh", "compiler/export.sh",
            "compiler/toolchain.sh", "compiler/plugin.py", "compiler/THC/Plugin.hs", "compiler/THC/CBV.hs",
            "compiler/THC/Demands.hs", "compiler/THC/Sources.hs", "compiler/THC/Wired.hs", "scripts/audit-core.py",
            "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
            "scripts/core_data_tags.py", "scripts/core_managed_files.py", "scripts/core_md5_foreign.py",
            "scripts/core_original_foreign.py", "scripts/core_package_manifest.py", "scripts/core_sums.py",
            "scripts/core_tuple_inputs.py", "scripts/core_vector_memory.py", "scripts/core_vectors.py")
    }
    private val labels = listOf("ghc-version", "plugin-build", "original-source-export", "pre-export", "post-export",
        "native-compile", "native-observations", "pre-audit", "post-audit")
    private fun contained(path: String, input: Boolean): File {
        require(!File(path).isAbsolute && path.split('/').none { it in setOf("", ".", "..") })
        require(input || path.startsWith(prefix))
        val base = root.canonicalFile.toPath()
        val file = File(root, path).canonicalFile
        // Do not canonicalize the allowed output root: a symlink there must not widen it.
        require(file.toPath().startsWith(if (input) base else base.resolve(prefix)))
        return file
    }
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
    private fun manifest() = checked(json(prefix + "manifest.json"))
    private fun checked(value: Map<String, Any?>): Map<String, Any?> {
        require(value.keys == setOf("format", "schema", "ghc", "installedArtifactsHashed", "originals", "stages",
            "nativeOutput", "audits", "inputHashes", "artifactHashes", "commands", "limit"))
        require(value["format"] == "thc-original-stack-formatter-fixture" && value["schema"] == 1L)
        require(value["ghc"] == "9.14.1" && value["installedArtifactsHashed"] == false)
        require(value["limit"] == "Original prettyStackEntry only; not full original stack decoding or native-frame equivalence.")
        val inputs = value["inputHashes"] as Map<String, String>
        require(inputs.keys == requiredInputs && pinned.all { (path, digest) -> inputs[path] == digest })
        val output = value["nativeOutput"] as String
        require(Regex("build/original-stack-formatter/run-[1-9][0-9]*/logs/native-observations\\.stdout").matches(output))
        val attempt = output.substringBefore("/logs/")
        val sources = pinned.keys.map { it.removePrefix(sourceRoot) }.filter { it.endsWith(".hs") || it.endsWith(".hsc") }
        val expectedOriginals = sources.map { "$attempt/originals/core/${it.substringBeforeLast('.').replace('/', '.')}.json" }.sorted()
        val expectedStages = listOf("pre", "post").associateWith { "$attempt/$it-core/OriginalStackFormatter.json" }
        val expectedAudits = listOf("pre", "post").map { "$attempt/$it-audit.json" }
        val generated = sources.filter { it.endsWith(".hsc") }.map { "$attempt/originals/generated/${it.removeSuffix(".hsc")}.hs" }
        val expectedArtifacts = expectedOriginals + expectedStages.values + expectedAudits + generated +
            listOf("$attempt/native/formatter", "$attempt/originals/generated.json", "$attempt/originals/target-layout.json") +
            labels.flatMap { label -> listOf("stdout", "stderr", "command.json").map { "$attempt/logs/$label.$it" } }
        require(value["originals"] == expectedOriginals && value["stages"] == expectedStages && value["audits"] == expectedAudits)
        val artifacts = value["artifactHashes"] as Map<String, String>
        require(artifacts.keys == expectedArtifacts.toSet())
        val commands = value["commands"] as List<Map<String, Any?>>
        require(commands.size == labels.size && commands.all { it.keys == setOf("argv", "environment", "exit") && it["exit"] == 0L })
        for ((kind, records) in listOf("input" to inputs, "artifact" to artifacts)) for ((path, digest) in records) {
            require(Regex("[0-9a-f]{64}").matches(digest))
            require(hash(contained(path, kind == "input")) == digest) { "Stale $kind: $path" }
        }
        require(expectedAudits.all { json(it)["accepted"] == true })
        for ((label, command) in labels.zip(commands)) require(json("$attempt/logs/$label.command.json") == command)
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

    @Test fun manifestRejectsMissingOrForgedProvenanceBeforeConsumingArtifacts() {
        val value = manifest()
        val inputs = value["inputHashes"] as Map<String, String>
        val artifacts = value["artifactHashes"] as Map<String, String>
        assertEquals(78, requiredInputs.size)
        assertEquals(77, artifacts.size)
        for (path in requiredInputs)
            assertThrows(IllegalArgumentException::class.java, {
                checked(value + ("inputHashes" to (inputs - path)))
            }, path)
        for (path in artifacts.keys)
            assertThrows(IllegalArgumentException::class.java, {
                checked(value + ("artifactHashes" to (artifacts - path)))
            }, path)
        for (path in pinned.keys)
            assertThrows(IllegalArgumentException::class.java, {
                checked(value + ("inputHashes" to (inputs + (path to "0".repeat(64)))))
            }, path)
        for ((field, forged) in listOf("fresh" to true, "installedArtifactsHashed" to true,
                "schema" to 2L, "ghc" to "other", "limit" to "Full original decoder supported"))
            assertThrows(IllegalArgumentException::class.java) { checked(value + (field to forged)) }
        for ((field, hashes) in listOf("inputHashes" to inputs, "artifactHashes" to artifacts)) {
            assertThrows(IllegalArgumentException::class.java) {
                checked(value + (field to (hashes + ("/installed/ghc" to "0".repeat(64)))))
            }
            assertThrows(IllegalArgumentException::class.java) {
                checked(value + (field to (hashes + (hashes.keys.first() to "0".repeat(64)))))
            }
        }
        val commands = value["commands"] as List<Map<String, Any?>>
        assertThrows(IllegalArgumentException::class.java) {
            checked(value + ("commands" to (listOf(commands[0] + ("exit" to 1L)) + commands.drop(1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            checked(value + ("originals" to (value["originals"] as List<*>).dropLast(1)))
        }
    }

    @Test fun artifactAndInputSymlinksCannotEscapeBeforeHashing() {
        val outside = Files.createTempDirectory("stack-formatter-outside-")
        val directory = Files.createTempDirectory(File(root, prefix).toPath(), "containment-")
        val link = directory.resolve("escape")
        try {
            Files.createSymbolicLink(link, outside)
            val path = root.toPath().relativize(link).toString().replace(File.separatorChar, '/') + "/unread-file"
            for (input in listOf(false, true))
                assertThrows(IllegalArgumentException::class.java) { contained(path, input) }
            for (bad in listOf("/installed/ghc", "$prefix../outside", "$prefix./outside"))
                assertThrows(IllegalArgumentException::class.java) { contained(bad, false) }
        } finally {
            Files.deleteIfExists(link); Files.delete(directory); Files.delete(outside)
        }
    }

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
