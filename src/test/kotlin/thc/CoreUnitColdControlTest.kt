// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir

/** Small independent protocol model, not native-export or compilation evidence. */
class CoreUnitColdControlTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val tag = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
    private fun tuple(field: Map<String, Any>) = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to field.getValue("primReps"), "components" to listOf(state, field), "evaluated" to true)
    private fun binder(id: String, proof: Map<String, Any>) = mapOf("id" to id, "name" to id,
        "type" to if (proof == state) "State# RealWorld" else if (proof == integer) "Int#" else "PromptTag# Int#",
        "lifted" to false, "coercion" to false, "rep" to proof)
    private fun variable(id: String, proof: Map<String, Any>) = listOf("var", id, mapOf("rep" to proof))
    private fun literal(value: Int) = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun application(head: List<Any?>, args: List<Any?>, flags: List<Boolean>, proof: Map<String, Any>) =
        listOf("app", head, args, flags, false, false, mapOf("rep" to proof))
    private fun pair(stateValue: List<Any?>, value: List<Any?>) =
        listOf("app", listOf("con", "uB:B.Pair", 2), listOf(stateValue, value), listOf(false, false), true, true,
            mapOf("rep" to tuple(integer)))
    private fun unpack(scrutinee: List<Any?>, field: Map<String, Any>, name: String, body: List<Any?>) =
        listOf("case", scrutinee, "$name-pair", listOf(listOf("data", "uB:B.Pair", listOf("$name-state", name), body,
            mapOf("binders" to listOf(binder("$name-state", state), binder(name, field))))),
            mapOf("rep" to integer, "binder" to binder("$name-pair", tuple(field))))
    private fun promptBody(): List<Any?> {
        val add = application(listOf("prim", "+#"), listOf(variable("x", integer), literal(11)),
            listOf(false, false), integer)
        val action = listOf("lam", listOf(binder("action-state", state)), pair(variable("action-state", state), add),
            mapOf("rep" to closure, "resultRep" to tuple(integer)))
        val prompt = application(listOf("prim", "prompt#"),
            listOf(variable("tag", tag), action, variable("tag-state", state)), listOf(false, true, false), tuple(integer))
        val fresh = application(listOf("prim", "newPromptTag#"), listOf(listOf("void", mapOf("rep" to state))),
            listOf(false), tuple(tag))
        return unpack(fresh, tag, "tag", unpack(prompt, integer, "answer", variable("answer", integer)))
    }
    private fun function(id: String, body: List<Any?>) = mapOf("id" to id, "name" to "entry", "type" to "Int# -> Int#",
        "lifted" to true, "arity" to 1, "rep" to closure, "expr" to listOf("lam", listOf(binder("x", integer)), body,
            mapOf("rep" to closure, "resultRep" to integer)))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Exact unit-pair offsets and summaries, with a separate exhaustive-loader model control. */
    private fun unit(name: String, body: List<Any?>, control: Boolean): Map<String, Any?> {
        val id = "u$name:$name.entry"
        val constructors = if (control) listOf(mapOf("id" to "uB:B.Pair", "name" to "(#,#)",
            "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)) else emptyList()
        val metadata = mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "u$name", "module" to name,
            "boundary" to boundary, "constructors" to constructors)
        val prefix = Json.stringify(metadata).dropLast(1) + ",\"bindings\":"
        val encoded = Json.stringify(listOf(function(id, body)))
        val original = (prefix + encoded + "}").toByteArray()
        val admitted = Json.stringify(metadata).toByteArray()
        val bytes = original + byteArrayOf(10) + admitted
        val json = directory.resolve("$name.jsons")
        val symbols = directory.resolve("$name.symbols")
        val rows = "$id ${prefix.toByteArray().size + 1}\n".toByteArray()
        Files.write(json, bytes)
        Files.write(symbols, rows)
        Files.write(directory.resolve("$name.json"), original)
        return mapOf("id" to "u$name", "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(rows)),
            "modules" to listOf(mapOf("name" to name, "path" to "$name.json", "sha256" to hash(original),
                "boundary" to boundary, "start" to 0, "end" to original.size,
                "bindingsStart" to prefix.toByteArray().size, "bindingsEnd" to prefix.toByteArray().size + encoded.toByteArray().size,
                "metadataStart" to original.size + 1, "metadataEnd" to bytes.size,
                "containsDelimitedControl" to control, "registrationObligations" to false,
                "mainAlias" to false, "packageScalarDeclarations" to false)))
    }
    private fun fixture(callControl: Boolean = false): Path {
        val call = application(listOf("var", "uB:B.entry"), listOf(variable("x", integer)), listOf(false), integer)
        // Retain an ordinary caller suffix: x=2 -> prompt returns13 -> caller returns18.
        val body = if (callControl) application(listOf("prim", "+#"), listOf(call, literal(5)), listOf(false, false), integer)
            else literal(7)
        val units = listOf(unit("A", body, false), unit("B", promptBody(), true))
        return directory.resolve("packages.json").also { Files.writeString(it, Json.stringify(mapOf(
            "format" to "thc-core-packages", "schema" to 1, "ghc" to "9.14.1", "units" to units))) }
    }
    private fun request(source: String, entry: String, backend: String, async: Boolean) =
        CoreModules.request(listOf(source), entry, backend = backend, sourceNotesEnabled = false, asyncExceptions = async)
    private fun count(entry: Value, name: String) =
        ((Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>)[name] as Number).toLong()
    private fun assertExistingAstAsyncPolicy(failure: PolyglotException) =
        assertTrue(failure.message.orEmpty().contains("Delimited continuations do not yet preserve AST async captures"), failure.message)

    @Test fun exhaustiveLoaderModelPreservesTheExistingPromptPolicy() {
        fixture()
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) Context.create("thc").use { context ->
            val source = request(directory.resolve("B.json").toString(), "uB:B.entry", backend, async)
            if (backend == "ast" && async) assertExistingAstAsyncPolicy(assertThrows(PolyglotException::class.java) {
                context.eval("thc", source)
            }) else assertEquals(13L, context.eval("thc", source).execute(2).asLong(), "$backend/$async")
        }
    }

    @Test fun unrelatedControlSummaryDoesNotOpenOrRejectTheOrdinaryEntry() {
        val manifest = fixture()
        // Missing files prove coldness independently of decoded-value counters.
        Files.delete(directory.resolve("B.jsons"))
        Files.delete(directory.resolve("B.symbols"))
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) Context.create("thc").use { context ->
            val entry = context.eval("thc", request("@$manifest", "uA:A.entry", backend, async))
            assertEquals(7L, entry.execute(2).asLong(), "$backend/$async")
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
            assertEquals(1L, count(entry, "coreUnitDecodedModules"))
            assertEquals(1L, count(entry, "coreUnitDecodedBindings"))
        }
    }

    @Test fun firstCrossModulePromptDemandUsesExistingPolicyAndPreservesTheCallerSuffix() {
        val manifest = fixture(callControl = true)
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) Context.create("thc").use { context ->
            val entry = context.eval("thc", request("@$manifest", "uA:A.entry", backend, async))
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
            assertEquals(1L, count(entry, "coreUnitDecodedBindings"))
            if (backend == "ast" && async) assertExistingAstAsyncPolicy(assertThrows(PolyglotException::class.java) {
                entry.execute(2)
            }) else {
                assertEquals(18L, entry.execute(2).asLong(), "$backend/$async first demand")
                assertEquals(2L, count(entry, "coreUnitDecodedBindings"))
                val reads = count(entry, "coreUnitSourceByteReads")
                assertEquals(19L, entry.execute(3).asLong())
                assertEquals(reads, count(entry, "coreUnitSourceByteReads"))
            }
        }
    }

    @Test fun directAndCrossModulePromptRejectAnActiveTransactionBeforeTheAction() {
        val manifest = fixture(callControl = true)
        for (backend in listOf("ast", "bytecode")) for (selected in listOf("uA:A.entry", "uB:B.entry")) {
            Context.create("thc").use { context ->
                val entry = context.eval("thc", request("@$manifest", selected, backend, false))
                context.enter()
                try {
                    val stm = Language.currentState().stm
                    var completed = false
                    val failure = assertThrows(PolyglotException::class.java) {
                        stm.atomically(null, { error("unexpected nested transaction") }) {
                            entry.execute(2)
                            completed = true
                        }
                    }
                    assertTrue(failure.message.orEmpty().contains("STM transaction frames do not support explicit delimited capture"), failure.message)
                    assertFalse(completed)
                    assertFalse(stm.hasTransaction())
                } finally { context.leave() }
                // A failed transactional attempt does not poison the prepared
                // definition or prevent its supported nontransactional use.
                assertEquals(if (selected == "uA:A.entry") 18L else 13L, entry.execute(2).asLong())
            }
        }
    }
}
