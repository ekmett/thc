// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Public ABI controls, independently modeled rather than claimed as GHC exports. */
class NarrowPublicEntryTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun integer(rep: String) = mapOf("kind" to "long", "primReps" to listOf(rep), "evaluated" to true)
    private fun variable(id: String, rep: Map<String, Any>) = listOf("var", id, mapOf("rep" to rep))
    private fun literal(tag: String, value: String, rep: Map<String, Any>) = listOf("lit", tag, value, mapOf("rep" to rep))
    private fun primitive(name: String, inputs: List<List<Any?>>, rep: Map<String, Any>) =
        listOf("app", listOf("prim", name), inputs, List(inputs.size) { false }, false, false, mapOf("rep" to rep))
    private fun binding(id: String, arity: Int, body: List<Any?>, rep: Map<String, Any> = closure) =
        mapOf("id" to "uN:N.$id", "name" to id, "arity" to arity, "lifted" to (rep == closure), "rep" to rep, "expr" to body)
    private fun function(inputs: List<Pair<String, Map<String, Any>>>, result: Map<String, Any>, body: List<Any?>) =
        binding("impl", inputs.size, listOf("lam", inputs.map { (id, rep) ->
            mapOf("id" to id, "name" to id, "lifted" to false, "coercion" to false, "rep" to rep)
        }, body, mapOf("rep" to closure, "resultRep" to result)))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun request(bindings: List<Map<String, Any?>>, backend: String, indexed: Boolean): String {
        val metadata = mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "uN", "module" to "N",
            "boundary" to "optimized-Core-after-Tidy-before-CorePrep", "constructors" to emptyList<Any>())
        val module = metadata + ("bindings" to bindings)
        if (!indexed) return Json.stringify(mapOf("backend" to backend, "entry" to "uN:N.entry",
            "asyncExceptions" to false, "modules" to listOf(module)))
        val original = Json.stringify(module).toByteArray()
        val admitted = Json.stringify(metadata).toByteArray()
        val bytes = original + byteArrayOf(10) + admitted
        val json = directory.resolve("N.jsons")
        val symbols = directory.resolve("N.symbols")
        Files.write(json, bytes)
        val record = CoreJsonIndex.fromBytes(original).use { index ->
            val bodies = index.root.member("bindings")!!
            val rows = bodies.elements().map { (it.member("id")!!.decode() as String) to it.start }.sortedBy { it.first }
            Files.writeString(symbols, rows.joinToString("") { (id, offset) -> "$id $offset\n" })
            mapOf("name" to "N", "path" to "N.json", "sha256" to hash(original), "boundary" to metadata["boundary"],
                "start" to 0, "end" to original.size, "bindingsStart" to bodies.start, "bindingsEnd" to bodies.endExclusive,
                "metadataStart" to original.size + 1, "metadataEnd" to bytes.size, "containsDelimitedControl" to false,
                "registrationObligations" to false, "mainAlias" to false, "packageScalarDeclarations" to false)
        }
        val unit = mapOf("id" to "uN", "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(Files.readAllBytes(symbols))), "modules" to listOf(record))
        val manifest = directory.resolve("packages.json")
        Files.writeString(manifest, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
            "ghc" to "9.14.1", "units" to listOf(unit))))
        return CoreModules.request(listOf("@$manifest"), "uN:N.entry", true, false, backend, false, false, null, false, null, true)
    }
    private fun check(bindings: List<Map<String, Any?>>, backend: String, indexed: Boolean, body: (Value) -> Unit) {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build().use { context ->
                body(context.eval("thc", request(bindings, backend, indexed)))
            }
    }
    private fun entries(entry: Value) = ((Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>)["thunkEvaluations"] as Number).toLong()

    @ParameterizedTest @CsvSource("ast,false", "bytecode,false", "ast,true", "bytecode,true")
    fun thunkAliasRetainsSignedInputProofWithoutForcingBeforeValidation(backend: String, indexed: Boolean) {
        val rep = integer("Int32Rep")
        check(listOf(function(listOf("x" to rep), rep, variable("x", rep)),
            binding("entry", 1, variable("uN:N.impl", closure))), backend, indexed) { entry ->
            val before = entries(entry)
            for (bad in listOf<Any>(2147483648L, -2147483649L, 1.5, "1"))
                assertThrows(PolyglotException::class.java) { entry.execute(bad) }
            assertEquals(before, entries(entry), "invalid host inputs must not force the alias")
            repeat(3) { assertEquals(-1L, entry.execute(-1L).asLong()); assertEquals(-2147483648L, entry.execute(Int.MIN_VALUE).asLong()) }
            assertTrue(entry.invokeMember("compile").asBoolean())
            assertEquals(2147483647L, entry.execute(Int.MAX_VALUE).asLong())
        }
    }

    @ParameterizedTest @CsvSource("ast,false", "bytecode,false", "ast,true", "bytecode,true")
    fun aliasResultZeroExtendsWord32WithMachineWordInput(backend: String, indexed: Boolean) {
        val word = integer("WordRep"); val narrow = integer("Word32Rep")
        check(listOf(function(listOf("x" to word), narrow, primitive("wordToWord32#", listOf(variable("x", word)), narrow)),
            binding("entry", 1, variable("uN:N.impl", closure))), backend, indexed) { entry ->
            repeat(3) { assertEquals(4294967295L, entry.execute(4294967295L).asLong()); assertEquals(2147483648L, entry.execute(2147483648L).asLong()) }
        }
    }

    @ParameterizedTest @CsvSource("ast,false", "bytecode,false", "ast,true", "bytecode,true")
    fun partialApplicationUsesRemainingNotOriginalInputProofs(backend: String, indexed: Boolean) {
        val wide = integer("IntRep"); val narrow = integer("Word32Rep")
        val pap = listOf("app", variable("uN:N.impl", closure), listOf(literal("int", "7", wide)),
            listOf(false), false, false, mapOf("rep" to closure))
        check(listOf(function(listOf("ignored" to wide, "x" to narrow), narrow, variable("x", narrow)),
            binding("entry", 1, pap)), backend, indexed) { entry ->
            val before = entries(entry)
            assertThrows(PolyglotException::class.java) { entry.execute(-1L) }
            assertThrows(PolyglotException::class.java) { entry.execute(4294967296L) }
            assertEquals(before, entries(entry))
            repeat(3) { assertEquals(4294967295L, entry.execute(4294967295L).asLong()); assertEquals(0L, entry.execute(0L).asLong()) }
        }
    }

    @ParameterizedTest @CsvSource("ast,false", "bytecode,false", "ast,true", "bytecode,true")
    fun nonClosureScalarResultRetainsExactWord32Proof(backend: String, indexed: Boolean) {
        val rep = integer("Word32Rep")
        check(listOf(binding("entry", 0, literal("word32", "4294967295", rep), rep)), backend, indexed) { entry ->
            repeat(3) { assertEquals(4294967295L, entry.execute().asLong()) }
        }
    }
}
