// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StableNamesTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("sameLifted", "sameUnlifted", "differentUnlifted", "unevaluatedName")
    private val inputs = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
    private fun expected(entry: String, input: Long) = when (entry) {
        "sameLifted", "sameUnlifted" -> 2L
        "differentUnlifted" -> 0L
        else -> input
    }

    @Test fun namesUseIdentityWithoutInvokingReferentsAndRemainContextOwned() {
        class Hostile {
            override fun equals(other: Any?): Boolean = error("Referent equality evaluated")
            override fun hashCode(): Int = error("Referent hash evaluated")
        }
        val registry = StableNames()
        val other = StableNames()
        val first = Hostile()
        val name = registry.make(first)
        assertSame(name, registry.make(first))
        assertNotSame(name, registry.make(Hostile()))
        assertEquals(registry.hash(name), registry.hash(registry.make(first)))
        assertThrows(RuntimeFault::class.java) { other.hash(name) }
        assertThrows(RuntimeFault::class.java) { registry.hash(first) }
        assertThrows(RuntimeFault::class.java) { registry.make(null) }
        registry.close()
        assertThrows(RuntimeFault::class.java) { registry.hash(name) }
        assertThrows(RuntimeFault::class.java) { registry.make(first) }
        other.close()
    }

    @Test fun concurrentNamingKeepsOneCanonicalToken() {
        val registry = StableNames()
        val value = Any()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val names = pool.invokeAll(List(64) { Callable { registry.make(value) } }).map { it.get() }
            names.forEach { assertSame(names.first(), it) }
        } finally { pool.shutdownNow(); registry.close() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun originalCorePreservesSharingHashesAndLazinessInBothBackends() {
        val manifest = Json.parse(File(root, "build/stable-names/manifest.json").readText()) as Map<*, *>
        assertEquals(entries, manifest["entries"])
        for (group in listOf("inputHashes", "artifactHashes")) for ((path, hash) in manifest[group] as Map<*, *>) {
            val bytes = File(root, path as String).readBytes()
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, path)
        }
        assertEquals(inputs.flatMap { n -> entries.map { expected(it, n) } }, manifest["native"])
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) for (entry in entries) {
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val module = Json.parse(File(root, "build/stable-names/$stage/core/StableNames.json").readText()) as Map<String, Any?>
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = CoreModules.reachable(module, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, entry, 1))
                    inputs.forEach { n -> assertEquals(expected(entry, n), function.execute(n).asLong(), "$stage/$backend/$entry/$n") }
                    assertTrue(function.invokeMember("compile").asBoolean())
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(expected(entry, 17), function.execute(17L).asLong(), "$stage/$backend/$entry first installed call")
                    val evidence = ArrayCoreEvidence(module, entry)
                    // The unforced bottom is a separate CAF, not an executed root.
                    evidence.stateLambda(evidence.root["expr"])
                    assertEquals(evidence.guestLambdas(evidence.root["expr"]).size.toLong(),
                        (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before,
                        "$stage/$backend/$entry original entry and immediate state lambdas")
                    ThreadInventoryCoreEvidence.released(language)
                } finally { context.leave() }
            }
        }
    }
}
