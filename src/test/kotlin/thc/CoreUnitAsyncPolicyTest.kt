// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import thc.runtime.*

/** Synthetic protocol controls, not native-GHC fixture or performance evidence. */
class CoreUnitAsyncPolicyTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val longRep = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closureRep = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun literal(value: Int) = listOf("lit", "int", value.toString(), mapOf("rep" to longRep))
    private fun traceThen(body: List<Any?>, result: Map<String, Any>) = listOf("case",
        listOf("app", listOf("prim", "traceEvent#"),
            listOf(listOf("lit", "string-bytes", "64656d616e64"), listOf("void", mapOf("rep" to stateRep))),
            listOf(false, false), false, false, mapOf("rep" to stateRep)), "traced",
        listOf(listOf("default", null, emptyList<String>(), body)),
        mapOf("rep" to result, "binder" to mapOf("id" to "traced", "lifted" to false, "rep" to stateRep)))
    private fun function(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id.substringAfterLast('.'),
        "type" to "Int# -> Int#", "lifted" to true, "arity" to 1, "rep" to closureRep,
        "expr" to listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "type" to "Int#",
            "lifted" to false, "coercion" to false, "rep" to longRep)), body,
            mapOf("rep" to closureRep, "resultRep" to longRep)))

    /** Model unit-pair writer: exact offsets, explicit filtered metadata, sorted symbol rows. */
    private fun unit(name: String, bindings: List<Map<String, Any?>>, constructors: List<Any> = emptyList()): Map<String, Any?> {
        val metadata = mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "u$name", "module" to name,
            "boundary" to boundary, "constructors" to constructors)
        val prefix = Json.stringify(metadata).dropLast(1) + ",\"bindings\":"
        val encoded = bindings.map(Json::stringify)
        val bodies = encoded.joinToString(",", "[", "]")
        val module = (prefix + bodies + "}").toByteArray()
        val admitted = Json.stringify(metadata).toByteArray()
        val bytes = module + byteArrayOf(10) + admitted
        var offset = prefix.toByteArray().size + 1
        val rows = bindings.zip(encoded).map { (binding, text) ->
            val row = "${binding.getValue("id")} $offset\n"
            offset += text.toByteArray().size + 1
            row
        }.sorted().joinToString("").toByteArray()
        val json = directory.resolve("$name.jsons")
        val symbols = directory.resolve("$name.symbols")
        Files.write(json, bytes)
        Files.write(symbols, rows)
        return mapOf("id" to "u$name", "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(rows)),
            "modules" to listOf(mapOf("name" to name, "path" to "$name.json", "sha256" to hash(module),
                "boundary" to boundary, "start" to 0, "end" to module.size,
                "bindingsStart" to prefix.toByteArray().size,
                "bindingsEnd" to prefix.toByteArray().size + bodies.toByteArray().size,
                "metadataStart" to module.size + 1, "metadataEnd" to bytes.size,
                "containsDelimitedControl" to false, "registrationObligations" to false,
                "mainAlias" to false, "packageScalarDeclarations" to false)))
    }
    private fun fixture(traceEntry: Boolean = false): Path {
        val a = unit("A", listOf(function("uA:A.entry", if (traceEntry) traceThen(literal(7), longRep) else literal(7))))
        val boxed = listOf("app", listOf("con", "uB:B.Box", 1), listOf(literal(17)), listOf(false), true, true,
            mapOf("rep" to (dataRep + ("evaluated" to true))))
        val caf = mapOf("id" to "uB:B.caf", "name" to "caf", "type" to "Box", "lifted" to true,
            "arity" to 0, "rep" to dataRep, "expr" to traceThen(boxed, dataRep))
        val b = unit("B", listOf(function("uB:B.function", traceThen(literal(19), longRep)), caf), listOf(
            mapOf("id" to "uB:B.Box", "name" to "Box", "kind" to "boxed", "arity" to 1,
                "fieldReps" to listOf(listOf("IntRep")), "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
        return directory.resolve("packages.json").also { path ->
            Files.writeString(path, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
                "ghc" to "9.14.1", "units" to listOf(a, b))))
        }
    }
    private fun request(path: Path, backend: String, async: Boolean) = CoreModules.request(
        listOf("@$path"), "uA:A.entry", backend = backend, sourceNotesEnabled = false, asyncExceptions = async)
    private fun count(program: ExecutableProgram, name: String) = (program.diagnostics().getValue(name) as Number).toLong()
    private fun externalSend(threads: GuestThreads, id: Long): AsyncRequest = CompletableFuture.supplyAsync {
        threads.send(id, "pending demand")
    }.get(5, TimeUnit.SECONDS).also { assertFalse(it.forceSelf) }
    private fun context(output: ByteArrayOutputStream) = Context.newBuilder("thc").err(output).build()

    @Test fun publicWrapperHonorsExternalDeliveryPolicyAndBytecodeInspection() {
        val manifest = fixture(traceEntry = true)
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
            var observed = false
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, start: Int, length: Int) {
                    if (!observed) {
                        observed = true
                        val threads = Language.currentState().threads
                        val slot = threads.pollState(Thread.currentThread()).current!!
                        assertEquals(async, slot.externalAsync, "$backend wrapper policy")
                        // This sender is a different Java thread: self-throwTo cannot bypass policy.
                        CompletableFuture.runAsync {
                            if (async) {
                                val pending = threads.send(slot.identity, "policy probe")
                                assertFalse(pending.forceSelf)
                                assertEquals(AsyncRequestState.PENDING, pending.state)
                                assertTrue(pending.cancel())
                                assertEquals(AsyncRequestState.CANCELLED, pending.state)
                            } else assertThrows(UnsupportedCore::class.java) { threads.send(slot.identity, "rejected") }
                        }.get(5, TimeUnit.SECONDS)
                    }
                    super.write(bytes, start, length)
                }
            }
            context(output).use { context ->
                val entry = context.eval("thc", request(manifest, backend, async))
                assertFalse(observed, "loading must not execute the entry effect")
                assertEquals(7L, entry.execute(0).asLong())
                assertTrue(observed)
                assertEquals("[thc trace event] demand\n", output.toString(Charsets.UTF_8))
                assertEquals(backend == "bytecode", entry.hasMember("bytecode"))
                if (backend == "bytecode") assertTrue(entry.getMember("bytecode").asString().isNotBlank())
            }
        }
    }

    @Test fun signalBindingUsesWrapperPolicyBeforeAcquiringTransportOrDemandingDispatcher() {
        val manifest = fixture()
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) context(ByteArrayOutputStream()).use { context ->
            context.eval("thc", request(manifest, backend, async))
            context.enter()
            try {
                val owner = Language.currentState()
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = owner.coreUnitPrograms.single()
                assertEquals(async, program.asynchronousExceptions)
                val decoded = count(program, "coreUnitDecodedBindings")
                val signals = ManagedSignals(owner, language, reducedVmSignals = true) {
                    error("binding alone must not acquire the native signal transport")
                }
                try {
                    if (async) signals.bind(program)
                    else assertTrue(assertThrows(RuntimeFault::class.java) { signals.bind(program) }
                        .message.orEmpty().contains("asyncExceptions=true"))
                    assertEquals(decoded, count(program, "coreUnitDecodedBindings"))
                } finally { signals.close() }
            } finally { context.leave() }
        }
    }

    @Test fun firstColdFunctionDemandDoesNotClaimPendingDeliveryOrMemoizeControlAsFailure() {
        val manifest = fixture()
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            context(output).use { context ->
                context.eval("thc", request(manifest, backend, true))
                context.enter()
                val owner = Language.currentState()
                val id = owner.threads.enterCurrent(externalAsync = true)
                try {
                    val program = owner.coreUnitPrograms.single()
                    val pending = externalSend(owner.threads, id)
                    val closure = program.entryValue("uB:B.function") as Closure
                    assertEquals(AsyncRequestState.PENDING, pending.state, "$backend preparation is not guest execution")
                    assertSame(closure, program.entryValue("uB:B.function"))
                    assertEquals(2L, count(program, "coreUnitDecodedBindings"))
                    assertEquals(0, output.size())
                    val saved = savedGuestContinuation(Calls.target(closure.target, arrayOf(0L, 1L)))!!
                    assertSame(pending, saved.asyncRequest())
                    assertEquals(AsyncRequestState.CLAIMED, pending.state)
                    assertEquals(0, output.size(), "pending delivery precedes the function effect")
                    pending.acknowledge()
                    assertEquals(19L, saved.continueWith(Unit))
                    assertSame(closure, program.entryValue("uB:B.function"))
                    assertEquals(2L, count(program, "coreUnitDecodedBindings"))
                    assertEquals("[thc trace event] demand\n", output.toString(Charsets.UTF_8))
                } finally { owner.threads.leaveCurrent(); context.leave() }
            }
        }
    }

    @Test fun firstColdCafDemandPreservesUnevaluatedIdentityAcrossDeliveryAndResume() {
        val manifest = fixture()
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            context(output).use { context ->
                context.eval("thc", request(manifest, backend, true))
                context.enter()
                val owner = Language.currentState()
                val id = owner.threads.enterCurrent(externalAsync = true)
                try {
                    val program = owner.coreUnitPrograms.single()
                    val pending = externalSend(owner.threads, id)
                    val thunk = program.entryValue("uB:B.caf") as Thunk
                    assertEquals(AsyncRequestState.PENDING, pending.state)
                    assertEquals(0, thunk.state)
                    assertSame(thunk, program.entryValue("uB:B.caf"))
                    assertEquals(0L, count(program, "thunkEvaluations"))
                    assertEquals(0, output.size())
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val metrics = Metrics(true)
                    val force = object : RootNode(language, FrameLayout().build()) {
                        @Child private var evaluator = Force(metrics, true)
                        override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
                    }.callTarget
                    val suspension = assertThrows(ThunkSuspended::class.java) { Calls.target(force, arrayOf(thunk)) }
                    assertSame(pending, suspension.asyncRequest)
                    assertEquals(AsyncRequestState.CLAIMED, pending.state)
                    assertEquals(5, thunk.state)
                    assertEquals(0, output.size())
                    pending.acknowledge()
                    val value = Calls.target(force, arrayOf(thunk)) as DataValue
                    assertEquals(17L, value.layout.readLong(value, 0))
                    assertEquals(2, thunk.state)
                    assertSame(thunk, program.entryValue("uB:B.caf"))
                    assertSame(value, Calls.target(force, arrayOf(thunk)))
                    assertEquals(1L, metrics.thunkEvaluations)
                    assertEquals(2L, count(program, "coreUnitDecodedBindings"))
                    assertEquals("[thc trace event] demand\n", output.toString(Charsets.UTF_8))
                } finally { owner.threads.leaveCurrent(); context.leave() }
            }
        }
    }
}
