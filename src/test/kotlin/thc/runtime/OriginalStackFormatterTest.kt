// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Original formatter and source-overlay proofs, not the JVM renderer or the full stack decoder. */
class OriginalStackFormatterTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-stack-formatter/"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(file: File) = hash(file.readBytes())
    private val sourceRoot = "compiler/pinned-ghc-internal/"
    // Pin the complete path/hash catalog independently, without duplicating its 51 entries.
    // A changed exporter catalog cannot silently drop or repin a source in the fixture receipt.
    private val pinned by lazy {
        val text = contained("src/THC/Driver/Wired.hs", true).readText()
            .substringAfter("sourceHashes =").substringBefore("\ndata WiredArtifacts")
        val entries = Regex("\\(\"([^\"]+)\", \"([0-9a-f]{64})\"\\)").findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        require(entries.size == 51 && entries.map { it.first }.toSet().size == 51)
        require(hash(entries.sortedBy { it.first }.joinToString("") { (path, digest) -> "$path\u0000$digest\n" }.toByteArray()) ==
            "ced9014c4d14b3fcd866ecc5a75e8f999b1d020095272757a0a4b2d8f66173ce")
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

    private class DeferredAction(language: Language, private val shape: TupleShape,
                                 private val layout: FrameLayout = FrameLayout(),
                                 private val valueSlot: Int = layout.bind("value")) : GuestRoot(language, layout.build()) {
        var calls = 0
        init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any {
            calls++
            FrameAccess.write(frame, valueSlot, "deferred action result")
            return shape.finish(frame, intArrayOf(valueSlot))
        }
    }

    @Test fun freshOriginalEnumWorkerResolvesClosureTypeErrorWithoutAlias() {
        val receipt = manifest()
        val path = sourceRoot + "GHC/Internal/Enum.hs"
        assertEquals("e4dcf86915b01dcc732ed319fe02759858aea1534c68427826ba5f9c6908860f", hash(contained(path, true)))
        val originals = receipt["originals"] as List<String>
        val full = json(originals.single { it.endsWith("/GHC.Internal.Enum.json") })
        val id = "ghc-internal:GHC.Internal.Enum.\$wtoEnumError"
        val original = (full["bindings"] as List<Map<String, Any?>>).single { it["id"] == id }
        assertEquals("\$wtoEnumError", original["name"])
        val callerModule = json(originals.single { it.endsWith("/GHC.Internal.ClosureTypes.json") })
        val caller = (callerModule["bindings"] as List<Map<String, Any?>>)
            .single { it["id"] == "ghc-internal:GHC.Internal.ClosureTypes.\$wlvl" }
        fun references(value: Any?): Int = when (value) {
            is List<*> -> if (value.getOrNull(0) == "var" && value.getOrNull(1) == id) 1 else value.sumOf(::references)
            is Map<*, *> -> value.values.sumOf(::references)
            else -> 0
        }
        assertEquals(1, references(caller["expr"]), "Fresh original caller must resolve the exact Enum worker")
        val linked = CoreModules.merge(listOf(full, callerModule))
        assertEquals(original, (linked["bindings"] as List<Map<String, Any?>>).single { it["id"] == id })
        // This proves source identity and resolution, not full admission of the
        // worker's cold ErrorCall/Typeable/backtrace dependency graph.
    }

    @Test fun freshOriginalUnsafeWorkerLinksWithoutAliasAndDefersItsAction() {
        val receipt = manifest()
        val full = json((receipt["originals"] as List<String>).single { it.endsWith("/GHC.Internal.IO.Unsafe.json") })
        val id = "ghc-internal:GHC.Internal.IO.Unsafe.unsafeDupableInterleaveIO1"
        val original = (full["bindings"] as List<Map<String, Any?>>).single { it["id"] == id }
        assertEquals("unsafeDupableInterleaveIO1", original["name"])
        val executionStack = json((receipt["originals"] as List<String>).single { it.endsWith("/GHC.Internal.ExecutionStack.Internal.json") })
        val caller = (executionStack["bindings"] as List<Map<String, Any?>>)
            .single { it["id"] == "ghc-internal:GHC.Internal.ExecutionStack.Internal.stackFrames" }
        fun references(value: Any?): Int = when (value) {
            is List<*> -> if (value.getOrNull(0) == "var" && value.getOrNull(1) == id) 1 else value.sumOf(::references)
            is Map<*, *> -> value.values.sumOf(::references)
            else -> 0
        }
        assertEquals(2, references(caller["expr"]), "Fresh original caller must resolve the exact worker, not an alias")
        val lambda = original["expr"] as List<Any?>
        val formals = lambda[1] as List<Map<String, Any?>>
        val result = (lambda[3] as Map<String, Any?>)["resultRep"] as Map<String, Any?>
        val fields = result["components"] as List<Map<String, Any?>>
        val closure = original["rep"] as Map<String, Any?>
        val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        fun binder(name: String, proof: Map<String, Any?>, lifted: Boolean) =
            mapOf("id" to name, "name" to name, "rep" to proof, "lifted" to lifted)
        fun variable(name: String, proof: Map<String, Any?>) = listOf("var", name, mapOf("rep" to proof))
        val scalar = listOf("lit", "int", "17", mapOf("rep" to integer))
        // Only these scalar consumers are synthetic. The worker, its source metadata,
        // tuple constructor and every reachable source binding remain untouched.
        fun consumer(force: Boolean): Map<String, Any?> {
            val call = listOf("app", variable(id, closure), listOf(variable("action", formals[0]["rep"] as Map<String, Any?>),
                listOf("void", mapOf("rep" to fields[0]))), listOf(true, false), false, false, mapOf("rep" to result))
            val answer = if (!force) scalar else listOf("case", variable("value", fields[1]), "forced",
                listOf(listOf("default", null, emptyList<String>(), scalar)),
                mapOf("rep" to integer, "binder" to binder("forced", fields[1], true)))
            val body = listOf("case", call, "pair", listOf(listOf("data", "ghc-internal:GHC.Internal.Types.(#,#)",
                listOf("state", "value"), answer, mapOf("binders" to listOf(binder("state", fields[0], false),
                    binder("value", fields[1], true))))),
                mapOf("rep" to integer, "binder" to binder("pair", result, false)))
            val name = if (force) "demand" else "discard"
            return mapOf("id" to name, "name" to name, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", listOf(formals[0] + mapOf("id" to "action", "name" to "action")), body,
                    mapOf("rep" to closure, "resultRep" to integer)))
        }
        for (force in listOf(false, true)) {
            val entry = consumer(force)
            val module = full + ("bindings" to listOf(original, entry))
            assertThrows(IllegalArgumentException::class.java) {
                CoreModules.reachable(module + ("bindings" to listOf(entry)), entry["id"] as String, strictLink = true)
            }
            val linked = CoreModules.reachable(module, entry["id"] as String, strictLink = true) + ("instrument" to true)
            assertEquals(original, (linked["bindings"] as List<Map<String, Any?>>).single { it["id"] == id })
            for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val action = DeferredAction(language, TupleShape(CoreRepresentations.parse(result), language))
                    val argument = Closure(null, arity = 1, target = action.callTarget)
                    val target = program.entryTarget(entry["id"] as String)
                    fun check(compiled: Boolean) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val calls = action.calls
                        assertEquals(17L, Calls.target(target, arrayOf(0L, argument)))
                        assertEquals(calls + if (force) 1 else 0, action.calls)
                        if (compiled) {
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            valid(target, "$backend/$force/inlining=$inlining")
                        }
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    }
                    repeat(2) { check(false) }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "$backend installation")
                    repeat(2) { check(true) }
                } finally { context.leave() }
            }
        }
    }

    @Test fun manifestRejectsMissingOrForgedProvenanceBeforeConsumingArtifacts() {
        val value = manifest()
        val inputs = value["inputHashes"] as Map<String, String>
        val artifacts = value["artifactHashes"] as Map<String, String>
        assertEquals(80, requiredInputs.size)
        assertEquals(79, artifacts.size)
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
