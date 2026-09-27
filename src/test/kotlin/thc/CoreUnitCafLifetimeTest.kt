// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import java.io.ByteArrayOutputStream
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import thc.runtime.*

/** Cold source names are semantic references even before JVM nodes exist for them. */
@Timeout(60)
class CoreUnitCafLifetimeTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseIdleFixtureMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun literal(value: Int) = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun box(value: Int) = listOf("app", listOf("con", "uB:B.Box", 1), listOf(literal(value)), listOf(false),
        true, true, mapOf("rep" to (data + ("evaluated" to true))))
    private fun trace(label: String, body: List<Any?>): List<Any?> {
        val bytes = label.toByteArray().joinToString("") { "%02x".format(it) }
        return listOf("case", listOf("app", listOf("prim", "traceEvent#"),
            listOf(listOf("lit", "string-bytes", bytes), listOf("void", mapOf("rep" to state))),
            listOf(false, false), false, false, mapOf("rep" to state)), "traced",
            listOf(listOf("default", null, emptyList<String>(), body)),
            mapOf("rep" to data, "binder" to mapOf("id" to "traced", "lifted" to false, "rep" to state)))
    }
    private fun function(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id.substringAfterLast('.'),
        "type" to "Int# -> Int#", "lifted" to true, "arity" to 1, "rep" to closure,
        "expr" to listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "type" to "Int#",
            "lifted" to false, "coercion" to false, "rep" to integer)), body,
            mapOf("rep" to closure, "resultRep" to integer)))
    private fun caf(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id.substringAfterLast('.'),
        "type" to "Box", "lifted" to true, "arity" to 0, "rep" to data, "expr" to body)
    private fun readCaf(id: String) = listOf("case", listOf("var", id, mapOf("rep" to data)), "boxed",
        listOf(listOf("data", "uB:B.Box", listOf("payload"), listOf("var", "payload", mapOf("rep" to integer)),
            mapOf("binders" to listOf(mapOf("id" to "payload", "name" to "payload", "type" to "Int#",
                "lifted" to false, "coercion" to false, "rep" to integer))))),
        mapOf("rep" to integer, "binder" to mapOf("id" to "boxed", "lifted" to true, "rep" to data)))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** Independent model writer; this does not claim native-export provenance. */
    private fun unit(name: String, bindings: List<Map<String, Any?>>, constructors: List<Any> = emptyList()): Map<String, Any?> {
        val boundary = "optimized-Core-after-Tidy-before-CorePrep"
        val metadata = mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "u$name", "module" to name,
            "boundary" to boundary, "constructors" to constructors)
        val prefix = Json.stringify(metadata).dropLast(1) + ",\"bindings\":"
        val encoded = bindings.map(Json::stringify)
        val bodies = encoded.joinToString(",", "[", "]")
        val original = (prefix + bodies + "}").toByteArray()
        val admitted = Json.stringify(metadata).toByteArray()
        val bytes = original + byteArrayOf(10) + admitted
        var offset = prefix.toByteArray().size + 1
        val rows = bindings.zip(encoded).map { (binding, text) ->
            val row = "${binding.getValue("id")} $offset\n"
            offset += text.toByteArray().size + 1
            row
        }.sorted().joinToString("").toByteArray()
        val json = directory.resolve("$name.jsons")
        val symbols = directory.resolve("$name.symbols")
        Files.write(json, bytes); Files.write(symbols, rows)
        return mapOf("id" to "u$name", "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(rows)),
            "modules" to listOf(mapOf("name" to name, "path" to "$name.json", "sha256" to hash(original),
                "boundary" to boundary, "start" to 0, "end" to original.size,
                "bindingsStart" to prefix.toByteArray().size, "bindingsEnd" to prefix.toByteArray().size + bodies.toByteArray().size,
                "metadataStart" to original.size + 1, "metadataEnd" to bytes.size,
                "containsDelimitedControl" to false, "registrationObligations" to false,
                "mainAlias" to false, "packageScalarDeclarations" to false)))
    }
    private fun fixture(): Path {
        val a = unit("A", listOf(function("uA:A.entry", literal(7))))
        val raised = listOf("app", listOf("prim", "raise#"), listOf(box(23)), listOf(true), false, false, mapOf("rep" to data))
        val b = unit("B", listOf(caf("uB:B.value", trace("success", box(17))), caf("uB:B.failure", trace("failure", raised))),
            listOf(mapOf("id" to "uB:B.Box", "name" to "Box", "kind" to "boxed", "arity" to 1,
                "fieldReps" to listOf(listOf("IntRep")), "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
        val c = unit("C", listOf(function("uC:C.success", readCaf("uB:B.value")), function("uC:C.failure", readCaf("uB:B.failure"))))
        return directory.resolve("packages.json").also { Files.writeString(it, Json.stringify(mapOf(
            "format" to "thc-core-packages", "schema" to 1, "ghc" to "9.14.1", "units" to listOf(a, b, c)))) }
    }
    private fun count(program: ExecutableProgram, name: String) = (program.diagnostics().getValue(name) as Number).toLong()

    private data class Observed(val success: WeakReference<Thunk>, val failure: WeakReference<Thunk>,
        val result: WeakReference<DataValue>, val payload: WeakReference<DataValue>)
    private fun evaluateAndDropHandles(program: ExecutableProgram, language: Language, async: Boolean): Observed {
        val metrics = Metrics(true)
        val force = object : RootNode(language, FrameLayout().build()) {
            @Child private var evaluator = Force(metrics, async)
            override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
        }.callTarget
        val success = program.entryValue("uB:B.value") as Thunk
        val failure = program.entryValue("uB:B.failure") as Thunk
        val result = Calls.target(force, arrayOf(success)) as DataValue
        assertEquals(17L, result.layout.readLong(result, 0))
        val thrown = assertThrows(GuestException::class.java) { Calls.target(force, arrayOf(failure)) }
        val payload = thrown.payload as DataValue
        assertEquals(23L, payload.layout.readLong(payload, 0))
        assertEquals(2L, metrics.thunkEvaluations)
        assertEquals(2, success.state)
        assertEquals(3, failure.state)
        // No target/environment root from this helper can keep the CAF alive.
        assertNull(success.target); assertNull(success.environment)
        assertNull(failure.target); assertNull(failure.environment)
        return Observed(WeakReference(success), WeakReference(failure), WeakReference(result), WeakReference(payload))
    }
    private class Cycle { var other: Cycle? = null }
    private fun sentinel(queue: ReferenceQueue<Cycle>): WeakReference<Cycle> {
        val first = Cycle()
        val second = Cycle()
        first.other = second; second.other = first
        return WeakReference(first, queue)
    }
    private fun collectWithPressure() {
        val queue = ReferenceQueue<Cycle>()
        val sentinel = sentinel(queue)
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            val pressure = Array(16) { ByteArray(1024 * 1024) }
            System.gc()
            Reference.reachabilityFence(pressure)
            if (queue.remove(100) === sentinel) {
                assertNull(sentinel.get())
                return
            }
        } while (System.nanoTime() < deadline)
        fail<Unit>("Allocation pressure plus collection requests did not collect the unrelated cyclic sentinel")
    }

    @Test fun laterColdNameLookupPreservesMemoizedResultAndFailureWithoutRepeatingEffects() {
        val manifest = fixture()
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
            val output = ByteArrayOutputStream()
            Context.newBuilder("thc").err(output).build().use { context ->
                val entry = context.eval("thc", CoreModules.request(listOf("@$manifest"), "uA:A.entry", backend = backend,
                    sourceNotesEnabled = false, asyncExceptions = async))
                assertEquals(7L, entry.execute(0).asLong())
                context.enter()
                try {
                    val program = Language.currentState().coreUnitPrograms.single()
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val prior = evaluateAndDropHandles(program, language, async)
                    assertEquals(3L, count(program, "coreUnitDecodedBindings"), "A plus two CAFs; C is still cold")
                    assertEquals(2L, count(program, "coreUnitSourceOpens"))
                    val effects = "[thc trace event] success\n[thc trace event] failure\n"
                    assertEquals(effects, output.toString(Charsets.UTF_8))
                    val evaluations = count(program, "thunkEvaluations")
                    collectWithPressure()

                    // C's unparsed names were not JVM references to B's mutable cells.
                    val success = program.entryValue("uC:C.success") as Closure
                    assertEquals(17L, Calls.target(success.target, arrayOf(0L, 0L)))
                    val failure = program.entryValue("uC:C.failure") as Closure
                    val observed = assertThrows(GuestException::class.java) { Calls.target(failure.target, arrayOf(0L, 0L)) }
                    assertNotNull(prior.success.get(), "$backend/$async existing CAF identity must survive cold names")
                    assertNotNull(prior.failure.get(), "$backend/$async existing failed CAF must survive cold names")
                    assertSame(prior.success.get(), program.entryValue("uB:B.value"))
                    assertSame(prior.failure.get(), program.entryValue("uB:B.failure"))
                    assertSame(prior.result.get(), (program.entryValue("uB:B.value") as Thunk).value)
                    assertNotNull(prior.payload.get())
                    assertSame(prior.payload.get(), observed.payload, "memoized guest payload, not exception-wrapper identity")
                    assertEquals(effects, output.toString(Charsets.UTF_8), "cold demand must not replay either CAF's effect")
                    assertEquals(evaluations, count(program, "thunkEvaluations"))
                    assertEquals(5L, count(program, "coreUnitDecodedBindings"))
                    assertEquals(3L, count(program, "coreUnitSourceOpens"))
                    assertEquals(7L, entry.execute(1).asLong(), "the original entry and context stay live")
                    Reference.reachabilityFence(program)
                } finally { context.leave() }
            }
        }
    }
}
