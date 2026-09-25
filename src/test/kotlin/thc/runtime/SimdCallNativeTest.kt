// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class SimdCallNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-calls")
    private fun model(name: String, x: Long): Long = if (name == "overCase")
        if (x.toShort().toLong() == 0L) 30L else 44L
        else x.toShort().toLong() + 13L
    private fun valid(target: Any) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun nativeVectorCallsPapAndJoinsKeepCompiledAstAndBytecodeResults() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        for ((path, want) in ((manifest["inputHashes"] as Map<String, String>) +
                (manifest["artifactHashes"] as Map<String, String>))) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(want, hash, "Stale SIMD call artifact $path")
        }
        val entries = manifest["entries"] as List<String>
        assertEquals(setOf("directCase", "papCase", "nestedTupleCase", "joinCase", "overCase"), entries.toSet())
        val cases = entries.flatMap { name -> (manifest["inputs"] as List<Number>).map { input -> name to input.toLong() } }
        assertEquals(45, cases.size)
        val nativeRows = manifest["nativeRows"] as Number?
        val rows = if (nativeRows != null) File(directory, "oracle.tsv").readLines().map { line ->
            val parts = line.split('\t')
            assertEquals(3, parts.size)
            Triple(parts[0], parts[1].toLong(), parts[2].toLong())
        } else cases.map { (name,x) -> Triple(name,x,model(name,x)) }
        assertEquals(cases, rows.map { it.first to it.second })
        if (nativeRows != null) assertEquals(rows.size.toLong(), nativeRows.toLong())
        for ((name,x,want) in rows) assertEquals(model(name,x), want, "${if (nativeRows == null) "Model" else "Native"} $name/$x")
        for (stage in manifest["stages"] as List<String>) for (backend in listOf("ast","bytecode"))
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val source = Json.parse(File(directory, "$stage-core/SimdCallAudit.json").readText()) as Map<String, Any?>
                        for (name in entries) {
                            val linked = CoreModules.reachable(source, name)
                            val program = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            val function = context.asValue(EntryValue(program, name, 1))
                            val cases = rows.filter { it.first == name }
                            for ((_,x,want) in cases) assertEquals(want, function.execute(x).asLong(), "$stage/$backend/$name/$x interpreted")
                            assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend/$name compile")
                            val target = program.entryTarget(name)
                            for ((_,x,want) in cases) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(want, function.execute(x).asLong(), "$stage/$backend/$name/$x compiled")
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                valid(target)
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.depth)
                            }
                        }
                    } finally { context.leave() }
                }
    }
}
