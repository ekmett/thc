// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreUnitLoadTest {
    @TempDir lateinit var directory: Path
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun literal(value: Int) = listOf("lit", "int", value.toString())
    private fun call(id: String, argument: Any?) = listOf("app", listOf("var", id), listOf(argument), listOf(false))
    private fun choice(zero: Any?, nonzero: Any?) = listOf("case", listOf("var", "x"), "scrutinee",
        listOf(listOf("lit", listOf("int", "0"), emptyList<Any>(), zero), listOf("default", null, emptyList<Any>(), nonzero)))
    private fun binding(id: String, body: Any?) = mapOf("id" to id, "name" to id.substringAfterLast('.'),
        "type" to "Int# -> Int#", "lifted" to true, "arity" to 1,
        "expr" to listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "type" to "Int#", "lifted" to false, "coercion" to false)), body))

    /** Model writer for focused runtime tests, not the production publisher. */
    private fun unit(name: String, body: Any?, padding: String = ""): Map<String, Any?> {
        val id = "u$name:$name.entry"
        val prefix = "{\"schema\":1,\"ghc\":\"9.14.1\",\"unit\":\"u$name\",\"module\":\"$name\",\"boundary\":\"$boundary\",\"bindings\":"
        val expr = Json.stringify(listOf(binding(id, body), binding("u$name:$name.unused", listOf("unsupported", padding))))
        val suffix = ",\"constructors\":[]}"
        val bytes = (prefix + expr + suffix).toByteArray()
        val json = directory.resolve("$name.jsons")
        val symbols = directory.resolve("$name.symbols")
        Files.write(json, bytes)
        val first = prefix.toByteArray().size + 1
        val unused = prefix.toByteArray().size + 1 + Json.stringify(binding(id, body)).toByteArray().size + 1
        val rows = "$id $first\nu$name:$name.unused $unused\n".toByteArray()
        Files.write(symbols, rows)
        return mapOf("id" to "u$name", "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(rows)),
            "modules" to listOf(mapOf("name" to name, "path" to "$name.json", "sha256" to hash(bytes),
                "boundary" to boundary, "start" to 0, "end" to bytes.size,
                "bindingsStart" to prefix.toByteArray().size,
                "bindingsEnd" to prefix.toByteArray().size + expr.toByteArray().size,
                "containsDelimitedControl" to false, "registrationObligations" to false, "mainAlias" to false)))
    }
    private fun fixture(badB: Boolean = false, cycle: Boolean = false): Path {
        val a = unit("A", choice(literal(7), call("uB:B.entry", listOf("var", "x"))))
        val b = unit("B", if (badB) listOf("unsupported", "demanded bad B")
            else if (cycle) call("uA:A.entry", literal(0))
            else choice(call("uC:C.entry", literal(0)), listOf("app", listOf("prim", "+#"),
                listOf(listOf("var", "x"), literal(1)), listOf(false, false))))
        val c = unit("C", listOf("unsupported", "untouched C"), "x".repeat(1024 * 1024))
        val manifest = directory.resolve("packages.json")
        Files.writeString(manifest, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
            "ghc" to "9.14.1", "units" to listOf(a, b, c))))
        return manifest
    }
    private fun request(path: Path, backend: String, async: Boolean = false, verify: Boolean = false) =
        CoreModules.request(listOf("@$path"), "uA:A.entry", backend = backend, sourceNotesEnabled = false,
            asyncExceptions = async, verifyArtifacts = verify)
    private fun count(value: Value, field: String) = ((Json.parse(value.getMember("diagnostics").asString()) as Map<*, *>)[field] as Number).toLong()

    @Test fun coldReferencesDoNotOpenOtherUnitsAndFirstDemandReusesTheBinding() {
        val manifest = fixture()
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend, async))
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
            assertEquals(1L, count(entry, "coreUnitDecodedModules"))
            assertEquals(1L, count(entry, "coreUnitDecodedBindings"))
            assertEquals(1L, count(entry, "loweredRootCount"))
            assertEquals(7L, entry.execute(0).asLong())
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
            assertEquals(2L, entry.execute(1).asLong())
            assertEquals(2L, count(entry, "coreUnitSourceOpens"))
            assertEquals(2L, count(entry, "coreUnitDecodedBindings"))
            val reads = count(entry, "coreUnitSourceByteReads")
            assertEquals(3L, entry.execute(2).asLong())
            assertEquals(reads, count(entry, "coreUnitSourceByteReads"))
            assertEquals(0L, count(entry, "coreUnitHashBytesScanned"))
        }
    }
    @Test fun missingOrBadColdUnitFailsOnlyAtDemandAndDoesNotTouchThirdUnit() {
        val manifest = fixture(badB = true)
        Files.delete(directory.resolve("C.jsons"))
        Files.delete(directory.resolve("C.symbols"))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend))
            assertEquals(7L, entry.execute(0).asLong())
            val failure = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) { entry.execute(1) }
            assertTrue(failure.message.orEmpty().contains("unsupported", ignoreCase = true))
            val decoded = count(entry, "coreUnitDecodedBindings")
            assertThrows(org.graalvm.polyglot.PolyglotException::class.java) { entry.execute(1) }
            assertEquals(decoded, count(entry, "coreUnitDecodedBindings"))
            assertEquals(2L, count(entry, "coreUnitSourceOpens"))
        }
    }
    @Test fun mutualFunctionReferencesPrepareWithoutRecursingThroughColdBodies() {
        val manifest = fixture(cycle = true)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend))
            assertEquals(7L, entry.execute(1).asLong())
            assertEquals(2L, count(entry, "coreUnitDecodedBindings"))
        }
    }
    @Test fun sharedEngineContextsOwnSeparateMappingsAndClosingOneDoesNotPoisonTheOther() {
        val manifest = fixture()
        Engine.create().use { engine ->
            val source = Source.newBuilder("thc", request(manifest, "bytecode"), "unit-model").cached(true).build()
            val first = Context.newBuilder("thc").engine(engine).build()
            val second = Context.newBuilder("thc").engine(engine).build()
            try {
                val a = first.eval(source)
                val b = second.eval(source)
                assertEquals(2L, a.execute(1).asLong())
                assertEquals(1L, count(b, "coreUnitDecodedBindings"))
                first.close()
                assertEquals(3L, b.execute(2).asLong())
                assertEquals(2L, count(b, "coreUnitDecodedBindings"))
            } finally { first.close(); second.close() }
        }
    }
}
