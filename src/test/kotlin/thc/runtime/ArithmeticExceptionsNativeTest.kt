// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.CorePackageManifest
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class ArithmeticExceptionsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/arithmetic-exceptions")
    private val payloads = mapOf(
        "divideOrAdd" to "ghc-internal:GHC.Internal.Exception.Type.divZeroException",
        "underflowOrAdd" to "ghc-internal:GHC.Internal.Exception.Type.underflowException",
        "overflowOrAdd" to "ghc-internal:GHC.Internal.Exception.Type.overflowException"
    )
    private val caught = mapOf("catchDivide" to "divideOrAdd",
        "catchUnderflow" to "underflowOrAdd", "catchOverflow" to "overflowOrAdd")

    private fun evidence(): Map<String, Any?> {
        val receipt = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, receipt["schema"])
        assertEquals("9.14.1", receipt["ghc"])
        assertEquals((payloads.keys + caught.keys).toList(), receipt["entries"])
        assertEquals(listOf(-2L, -1L, 0L, 1L, 7L), receipt["inputs"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, hash, "$kind/$path")
            }
        return receipt
    }

    private fun rows(): Map<String, List<Pair<Long, String>>> {
        val grouped = File(directory, "oracle.tsv").readLines().map { it.split('\t') }
            .groupBy({ it[0] }, { it[1].toLong() to it[2] })
        assertEquals(30, grouped.values.sumOf { it.size })
        for (name in payloads.keys + caught.keys)
            assertEquals(listOf(-2L, -1L, 0L, 1L, 7L), grouped.getValue(name).map { it.first })
        assertEquals("divide by zero", grouped.getValue("divideOrAdd").single { it.first == 0L }.second)
        assertEquals("arithmetic underflow", grouped.getValue("underflowOrAdd").single { it.first == -1L }.second)
        assertEquals("arithmetic overflow", grouped.getValue("overflowOrAdd").single { it.first == -2L }.second)
        return grouped
    }

    @Test fun nativeTypedArithmeticExceptionsAndExportsAreCurrent() {
        evidence()
        rows()
    }

    /** Set this to a selected-GHC complete-Core packages.json; no substitute payload is generated. */
    @Test fun selectedInstalledPayloadsCatchAndRethrowInCompiledGuestCode() {
        val selected = System.getProperty("thc.arithmeticCompleteCore")
            ?: System.getenv("THC_ARITHMETIC_COMPLETE_CORE")
        assumeTrue(selected != null, "Set THC_ARITHMETIC_COMPLETE_CORE to the selected complete-Core package manifest")
        val receipt = evidence()
        val cases = rows()
        val documents = StringBuilder("[")
        CorePackageManifest.appendModules(documents, selected!!)
        documents.append(']')
        val installed = (Json.parse(documents.toString()) as List<Map<String, Any?>>)
            .filter { it["schema"] == 1L }
        assertTrue(installed.any { it["unit"] == "ghc-internal" &&
            it["module"] == "GHC.Internal.Exception.Type" }, "Missing actual installed payload module")
        val stages = receipt["stages"] as Map<String, List<String>>
        // Archive-only foreign registrations remain excluded from execution.
        // strictLink rejects this focused closure if it actually needs one.
        for (stage in listOf("pre", "post")) {
            val exported = Json.parse(File(root, stages.getValue(stage).first()).readText()) as Map<String, Any?>
            val merged = CoreModules.merge(installed + exported)
            for (backend in listOf("ast", "bytecode"))
                Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        for (name in payloads.keys + caught.keys) {
                            val linked = CoreModules.reachable(merged, name, strictLink = true) + ("instrument" to true)
                            val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                                else BytecodeProgram(language, linked)
                            val target = program.entryTarget(name)
                            val payload = payloads[name]?.let { program.entryValue(it) }
                            fun check(input: Long, native: String) {
                                if (payload != null && native.toLongOrNull() == null) {
                                    val raised = assertThrows(GuestException::class.java) {
                                        Calls.target(target, arrayOf(0L, input))
                                    }
                                    assertSame(payload, raised.payload, "$stage/$backend/$name/$input genuine CAF")
                                } else assertEquals(native.toLong(), Calls.target(target, arrayOf(0L, input)),
                                    "$stage/$backend/$name/$input")
                            }
                            cases.getValue(name).forEach { (input, native) -> check(input, native) }
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                            for ((input, native) in cases.getValue(name)) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                check(input, native)
                                val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertTrue(after > before, "$stage/$backend/$name/$input entered compiled guest code")
                            }
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        }
                    } finally { context.leave() }
                }
        }
    }
}
