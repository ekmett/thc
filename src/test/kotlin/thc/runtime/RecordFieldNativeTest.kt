// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest

class RecordFieldNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/record-fields")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>

    @Test fun fieldNamespacesAndCrossModuleAliasesMatchNative() {
        val manifest = json(File(directory, "manifest.json"))
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val bytes = File(root, path).readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "$kind/$path")
            }
        val rows = File(directory, "logs/post-native.stdout").readLines().map { line ->
            line.split(' ').map(String::toLong)
        }
        assertEquals(File(directory, "logs/pre-native.stdout").readText(),
            File(directory, "logs/post-native.stdout").readText())
        assertEquals(listOf(-100L, -10L, -1L, 0L, 1L, 10L, 100L), rows.map { it[0] })
        for (stage in listOf("pre", "post", "installed")) {
            val sources = listOf("RecordFieldLibrary", "RecordFieldClient").map {
                json(File(directory, "$stage/$it.json"))
            }
            val bindings = sources.flatMap { it["bindings"] as List<Map<String, Any?>> }
            val ids = bindings.map { it["id"] as String }
            assertEquals(ids.size, ids.toSet().size, "$stage: distinct GHC identities must not collide")
            for (field in listOf("\$fld:LeftRecord:shared", "\$fld:RightRecord:shared", "\$fld:Plain:unique"))
                assertTrue(ids.any { it.endsWith("RecordFieldLibrary.$field") }, "$stage/$field")
            for ((column, entry) in listOf("fieldAlias", "duplicateFields").withIndex()) {
                val audit = json(File(directory, "$stage/$entry-audit.json"))
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["issues"])
                val linked = CoreModules.reachable(CoreModules.merge(sources), entry, strictLink = true) +
                    ("instrument" to true)
                for (backend in listOf("ast", "bytecode"))
                    Context.newBuilder("thc").allowExperimentalOptions(true)
                        .option("engine.BackgroundCompilation", "false")
                        .option("engine.MultiTier", "false")
                        .option("engine.CompilationFailureAction", "Throw")
                        .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                            context.initialize("thc"); context.enter()
                            try {
                                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                                val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                                    else BytecodeProgram(language, linked)
                                val function = context.asValue(EntryValue(program, entry, 1))
                                rows.forEach { assertEquals(it[column + 1], function.execute(it[0]).asLong()) }
                                assertTrue(function.invokeMember("compile").asBoolean())
                                for (row in rows.asReversed()) {
                                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                    assertEquals(row[column + 1], function.execute(row[0]).asLong(), "$stage/$backend/$entry")
                                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                }
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.depth)
                                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                            } finally { context.leave() }
                        }
            }
        }
    }
}
