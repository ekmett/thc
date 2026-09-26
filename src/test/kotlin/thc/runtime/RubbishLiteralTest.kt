// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RubbishLiteralTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/rubbish-literals"
    private val scalarNames = listOf("IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep",
        "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep", "FloatRep", "DoubleRep", "AddrRep", "Lifted", "Unlifted")
    private val names = listOf("Lifted", "Unlifted", "IntRep", "Int32Rep").map { "original$it" } +
        scalarNames.map { "scalar$it" } + listOf("boxedData", "boxedClosure")
    private fun json(file: String) = Json.parse(File(root, "$prefix/$file").readText()) as Map<String, Any?>
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun verifyEvidence() {
        val manifest = json("manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals(names, manifest["entries"])
        assertEquals(147L, manifest["nativeRows"])
        val inputs = manifest["inputHashes"] as Map<String, String>
        val expectedInputs = listOf("compiler/test-fixtures/RubbishLiteralAudit.hs", "test/haskell-fixtures/RubbishLiteralFixtures.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
            "scripts/audit-core.py", "scripts/core-capabilities.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        assertEquals(expectedInputs.toSet(), inputs.keys)
        val artifacts = manifest["artifactHashes"] as Map<String, String>
        val commands = listOf("version", "info", "libdir", "imports-ghc-internal", "pre-audit", "post-audit", "frontiers-audit")
        assertEquals((listOf("pre.json", "post.json", "oracle.json", "originals.json", "pre.audit.json", "post.audit.json", "frontiers.json", "frontiers.audit.json") +
            commands.flatMap { command -> listOf("stdout", "stderr", "command.json").map { "logs/$command.$it" } })
            .map { "$prefix/$it" }.toSet(), artifacts.keys)
        for ((path, digest) in inputs + artifacts) assertEquals(digest, hash(File(root, path)), "Stale rubbish fixture: $path")
        val installed = (manifest["installedInterfaces"] as List<Map<String, String>>).associate { it.getValue("path") to it.getValue("sha256") }
        assertEquals(1, installed.size)
        assertEquals(setOf("Manager.hi"), installed.keys.map { File(it).name }.toSet())
        for ((path, digest) in installed) { assertTrue(File(path).isAbsolute); assertEquals(digest, hash(File(path)), "Changed original interface: $path") }
        val occurrences = json("originals.json")["occurrences"] as List<List<String>>
        assertEquals(manifest["originalOccurrences"], occurrences.size.toLong())
        assertEquals(setOf("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "IntRep", "Int32Rep"), occurrences.map { it[1] }.toSet())
        assertTrue(occurrences.any { it[0] == "GHC.Internal.Event.Manager.\$wstep" })
        assertEquals(6, occurrences.size)
        for (stage in listOf("pre", "post")) {
            val audit = json("$stage.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            assertEquals(names.size, literals(json("$stage.json")).size)
        }
    }
    private fun literals(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::literals)
        is List<*> -> if (value.take(2) == listOf("lit", "rubbish")) listOf(value as List<Any?>) else value.flatMap(::literals)
        else -> emptyList()
    }
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)

    @Test fun originalAndScalarNativeContinuationsMatchInterpreted() = native(false)
    @Test fun originalAndScalarNativeContinuationsMatchFirstInstalledEntry() = native(true)
    private fun native(compiled: Boolean) {
        verifyEvidence()
        val rows = json("oracle.json")["rows"] as List<List<Any?>>
        assertEquals(147, rows.size)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val program = load(language, backend, json("$stage.json") + ("instrument" to true))
            for (name in names) {
                val target = program.entryTarget(name)
                if (compiled) {
                    // Establish ordinary JIT specialization with checked interpreter
                    // rows, then compile once. No post-compile settling or retry.
                    for (row in rows.filter { it[0] == name })
                        assertEquals(row[2], Calls.target(target, arrayOf(0L, row[1])), "$stage/$backend/$name interpreter profile")
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$name first compile")
                }
                for (row in rows.filter { it[0] == name }) {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals((row[1] as Long) + 17, row[2])
                    assertEquals(row[2], Calls.target(target, arrayOf(0L, row[1])), "$stage/$backend/$name/${row[1]}")
                    if (compiled) {
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$name/${row[1]} remains compiled")
                        assertEquals(1L, (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before)
                    }
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            }
            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
        } }
    }

    @Test fun fillersUseNonBottomScalarAndCheckedBoxedCarriers() = context().use { context -> entered(context) { language ->
        verifyEvidence()
        val decoder = RubbishLiterals(language)
        for (literal in literals(json("pre.json"))) {
            val proof = RubbishLiterals.proof(literal)
            val value = decoder.decode(proof)
            assertFalse(value is Thunk)
            when (proof.kind) {
                CoreKind.LONG -> assertEquals(0L, value)
                CoreKind.FLOAT -> assertEquals(0, (value as Float).toRawBits())
                CoreKind.DOUBLE -> assertEquals(0L, (value as Double).toRawBits())
                CoreKind.ADDRESS -> assertEquals(0L, (value as ManagedAddress).toNativeBits())
                CoreKind.CLOSURE -> assertTrue(value is Closure)
                CoreKind.DATA, CoreKind.OBJECT -> assertTrue(value is DataValue)
                else -> fail("Unexpected rubbish carrier")
            }
            proof.referenceCarrier()?.let { assertTrue(it.isInstance(value)) }
        }
    } }

    private fun changeLiterals(value: Any?, replace: (List<Any?>) -> List<Any?>): Any? = when (value) {
        is Map<*, *> -> value.mapValues { changeLiterals(it.value, replace) }
        is List<*> -> if (value.take(2) == listOf("lit", "rubbish")) replace(value as List<Any?>) else value.map { changeLiterals(it, replace) }
        else -> value
    }
    private fun auditRejected(module: Map<String, Any?>, directory: Path, label: String) {
        val input = directory.resolve("$label.json").toFile()
        input.writeText(Json.stringify(module))
        val output = directory.resolve("$label.audit.json").toFile()
        val process = ProcessBuilder("python3", File(root, "scripts/audit-core.py").path, "--entry", "scalarIntRep",
            "--output", output.path, input.path).directory(root).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), text)
        assertNotEquals(0, process.exitValue(), text)
        val result = Json.parse(output.readText()) as Map<*, *>
        assertEquals(false, result["accepted"])
        assertTrue((result["issues"] as List<*>).isNotEmpty())
    }

    @Test fun malformedRubbishProofsAndUnsupportedRepresentationsFailClosed(@TempDir directory: Path) {
        val original = CoreModules.reachable(json("pre.json"), "scalarIntRep")
        val changes: List<Pair<String, (List<Any?>) -> List<Any?>>> = listOf(
            "missing" to { it.take(3) },
            "wrong-rep" to { it.toMutableList().apply { this[2] = "FloatRep" } },
            "unknown-levity" to { it.toMutableList().apply { this[2] = "BoxedRep Nothing" } },
            "tuple" to { it.toMutableList().apply { this[2] = "TupleRep '[IntRep]" } },
            "sum" to { it.toMutableList().apply { this[2] = "SumRep '[IntRep, WordRep]" } },
            "vector" to { it.toMutableList().apply { this[2] = "VecRep 4 Int32ElemRep" } },
            "unevaluated" to { literal -> literal.toMutableList().apply {
                val metadata = literal[3] as Map<String, Any?>
                this[3] = metadata + ("rep" to ((metadata["rep"] as Map<String, Any?>) + ("evaluated" to false)))
            } })
        for ((label, change) in changes) {
            val bad = changeLiterals(original, change) as Map<String, Any?>
            auditRejected(bad, directory, label)
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                assertThrows(RuntimeFault::class.java, { load(language, backend, bad).entryTarget("scalarIntRep") }, "$backend/$label")
            } }
        }
    }

    @Test fun genuineAggregateAndVectorRubbishRemainExplicitFrontiers() {
        verifyEvidence()
        val audit = json("frontiers.audit.json")
        assertEquals(false, audit["accepted"])
        val issues = audit["issues"] as List<Map<String, Any?>>
        assertEquals(4, issues.count { it["code"] == "unsupported-literal" })
        for (name in listOf("emptyTuple", "singletonTuple", "sum", "vector")) {
            val module = CoreModules.reachable(json("frontiers.json"), name)
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                assertThrows(RuntimeFault::class.java, { load(language, backend, module).entryTarget(name) }, "$backend/$name")
            } }
        }
    }

    @Test fun rubbishCannotBecomeALiteralAlternative(@TempDir directory: Path) {
        val original = CoreModules.reachable(json("pre.json"), "scalarIntRep")
        fun change(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.mapValues { change(it.value) }
            is List<*> -> if (value.firstOrNull() == "default")
                value.toMutableList().apply { this[0] = "lit"; this[1] = listOf("rubbish", "IntRep") }
                else value.map(::change)
            else -> value
        }
        val bad = change(original) as Map<String, Any?>
        auditRejected(bad, directory, "pattern")
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            assertThrows(RuntimeFault::class.java) { load(language, backend, bad).entryTarget("scalarIntRep") }
        } }
    }
}
