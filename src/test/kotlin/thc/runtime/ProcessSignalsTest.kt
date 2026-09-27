// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.graalvm.polyglot.Context
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import thc.Json
import thc.Language
import thc.loadEntry

class ProcessSignalsTest {
    private val descriptor = Json.parse(javaClass.getResource("/core/original-signal-install-descriptor.json")!!.readText()) as Map<String, Any?>
    // Original unix System.Posix.Signals core/39.json SHA256
    // f4f562a283aec06f517c9187d13d47b93b876a5f0f807af999bcbd6cab1d44dc;
    // all six original calls have this descriptor, differing from TopHandler only in unit.
    private val unixDescriptor = Json.parse(javaClass.getResource("/core/original-unix-signal-install-descriptor.json")!!.readText()) as Map<String, Any?>
    private val arguments = (descriptor.getValue("argumentReps") as List<Map<String, Any?>>).map { it + ("evaluated" to true) }
    private val result = descriptor.getValue("resultRep") as Map<String, Any?>
    private val metadata = mapOf("rep" to result, "foreignCall" to descriptor)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val boxed = closure + ("kind" to "data")
    private val state = arguments.last()
    private val unitId = "ghc-internal:GHC.Internal.Tuple.()"
    private fun variable(id: String, proof: Map<String, Any?>) = listOf("var", id, mapOf("rep" to proof))
    private fun application(original: Map<String, Any?> = descriptor) = listOf("app", variable("original-signal-fcall", closure),
        arguments.mapIndexed { i, rep -> variable("a$i", rep) }, List(4) { false }, false, false,
        metadata + ("foreignCall" to original))
    private fun module(recordSignal: Boolean = false, original: Map<String, Any?> = descriptor): Map<String, Any?> {
        val inputs = arguments.mapIndexed { i, rep -> mapOf("id" to "a$i", "lifted" to false, "rep" to rep) }
        val binding = mapOf("id" to "install", "name" to "install", "arity" to 4, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", inputs, application(original), mapOf("rep" to closure, "resultRep" to result)))
        val ioResult = mapOf("kind" to "unknown", "primReps" to listOf("BoxedRep (Just Lifted)"),
            "evaluated" to false, "aggregate" to "unboxed-tuple", "components" to listOf(state, boxed))
        val returned = listOf("app", listOf("con", "tuple2", 2, mapOf("rep" to closure)),
            listOf(variable("token", state), listOf("con", unitId, 0, mapOf("rep" to boxed))),
            listOf(false, true), true, true, mapOf("rep" to ioResult))
        var body: List<Any?> = returned
        if (recordSignal) {
            val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
            val number = mapOf("kind" to "long", "primReps" to listOf("Int32Rep"), "evaluated" to true)
            val integer = number + ("primReps" to listOf("IntRep"))
            fun binder(id: String, proof: Map<String, Any?>, lifted: Boolean) =
                mapOf("id" to id, "rep" to proof, "lifted" to lifted)
            val write = listOf("app", listOf("prim", "writeInt32OffAddr#"),
                listOf(variable("infoAddress", address), listOf("lit", "int", "0", mapOf("rep" to integer)),
                    variable("signalNumber", number), variable("token", state)),
                List(4) { false }, false, false, mapOf("rep" to state))
            body = listOf("case", write, "written", listOf(listOf("default", null, emptyList<String>(), returned)),
                mapOf("rep" to ioResult, "binder" to binder("written", state, false)))
            body = listOf("case", variable("signal", boxed), "signalBox", listOf(listOf("data",
                "ghc-internal:GHC.Internal.Int.I32#", listOf("signalNumber"), body,
                mapOf("binders" to listOf(binder("signalNumber", number, false))))),
                mapOf("rep" to ioResult, "binder" to binder("signalBox", boxed, true)))
            body = listOf("case", variable("pointer", boxed), "pointerBox", listOf(listOf("data",
                "ghc-internal:GHC.Internal.Ptr.Ptr", listOf("infoAddress"), body,
                mapOf("binders" to listOf(binder("infoAddress", address, false))))),
                mapOf("rep" to ioResult, "binder" to binder("pointerBox", boxed, true)))
        }

        // Synthetic dispatcher consumer, not replacement evidence for original
        // Conc.Signal. It exercises the exact boxed Ptr/CInt/State transport.
        val dispatcher = mapOf("id" to CoreSignalForeign.dispatcher, "name" to "runHandlersPtr", "arity" to 3,
            "lifted" to true, "rep" to closure, "expr" to listOf("lam", listOf(
                mapOf("id" to "pointer", "lifted" to true, "rep" to boxed),
                mapOf("id" to "signal", "lifted" to true, "rep" to boxed),
                mapOf("id" to "token", "lifted" to false, "rep" to state)),
                body,
                mapOf("rep" to closure, "resultRep" to ioResult)))
        fun constructor(id: String, name: String, fields: List<String>) = mapOf("id" to id, "name" to name,
            "kind" to "boxed", "arity" to fields.size, "tag" to 1, "fieldReps" to fields.map(::listOf),
            "strictFields" to fields.map { false }, "fieldLifted" to fields.map { false })
        return mapOf("bindings" to listOf(binding, dispatcher), "instrument" to true, "constructors" to listOf(
            constructor("ghc-internal:GHC.Internal.Ptr.Ptr", "Ptr", listOf("AddrRep")),
            constructor("ghc-internal:GHC.Internal.Int.I32#", "I32#", listOf("Int32Rep")),
            constructor(unitId, "()", emptyList()),
            mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }
    private fun <T> inside(body: (Language) -> T): T = Context.newBuilder("thc").allowCreateThread(true)
        .allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }

    private fun onBackends(body: (Language, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) inside { body(it, backend) }
    }
    private fun program(language: Language, backend: String, recordSignal: Boolean = false,
                        async: Boolean = true, original: Map<String, Any?> = descriptor): ExecutableProgram =
        if (backend == "ast") Program(language, module(recordSignal, original), async)
        else BytecodeProgram(language, module(recordSignal, original), async)

    @Test fun hardContextExitSurvivesBothHostCleanupSteps() {
        val death = ThreadDeath()
        val restoration = RuntimeFault("native restoration failure")
        val bookkeeping = RuntimeFault("thread bookkeeping failure")
        val cleaned = mutableListOf<String>()
        val observed = assertThrows(ThreadDeath::class.java) {
            finishSignalConsumer(death, {
                cleaned.add("native")
                throw restoration
            }, {
                cleaned.add("thread")
                throw bookkeeping
            })
        }
        assertSame(death, observed)
        assertEquals(listOf("native", "thread"), cleaned)
        assertEquals(listOf(restoration, bookkeeping), death.suppressed.toList())
        val laterDeath = ThreadDeath()
        val failure = RuntimeFault("reader failure")
        var unregistered = false
        assertSame(laterDeath, assertThrows(ThreadDeath::class.java) {
            finishSignalConsumer(failure, { throw laterDeath }, { unregistered = true })
        })
        assertTrue(unregistered)
        assertEquals(listOf(failure), laterDeath.suppressed.toList())
    }

    @Test fun genuineOriginalDeclarationRejectsWidthHeadStateAndDescriptorNearMisses() {
        // Original ghc-internal GHC.Internal.TopHandler core/246.json SHA256
        // 6dd8a0de3bfc8664c9ea1cd2cfee6d687438761adf15a192dfde3c8e21a6fbea.
        assertEquals(ProcessSignalOp.INSTALL, CoreSignalForeign.validate(metadata, arguments, List(4) { false }, result))
        CoreSignalForeign.validateHeads(application())
        for ((key, wrong) in listOf("schema" to 2L, "arity" to 3L, "suppliedArity" to 5L,
            "safety" to "safe", "convention" to "capi", "extra" to 0L))
            assertThrows(RuntimeFault::class.java) {
                CoreSignalForeign.validate(metadata + ("foreignCall" to (descriptor + (key to wrong))), arguments, List(4) { false }, result)
            }
        for (index in arguments.indices) {
            val malformed = arguments.toMutableList()
            malformed[index] = arguments[index] + ("primReps" to listOf("IntRep"))
            assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validate(metadata, malformed, List(4) { false }, result) }
        }
        assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validate(metadata, arguments, listOf(false, false, true, false), result) }
        assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validateHead(variable("original-signal-fcall", closure), true) }
        val forged = application().toMutableList().also { it[1] = listOf("prim", "stg_sig_install") }
        assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validateHeads(forged) }
    }

    @Test fun unixUsesTheExactSameAbiAndRejectsUnrelatedUnits() {
        val target = descriptor.getValue("target") as Map<String, Any?>
        assertEquals(descriptor + ("target" to (target + ("unit" to "unix-2.8.8.0-inplace"))), unixDescriptor)
        val unixMetadata = metadata + ("foreignCall" to unixDescriptor)
        assertTrue(CoreSignalForeign.named(unixMetadata))
        assertEquals(ProcessSignalOp.INSTALL, CoreSignalForeign.validate(unixMetadata, arguments, List(4) { false }, result))
        for (unit in listOf(null, "unix", "unix-2.8.7.0-inplace", "other", 1L)) {
            val wrong = metadata + ("foreignCall" to (descriptor + ("target" to (target + ("unit" to unit)))))
            assertFalse(CoreSignalForeign.named(wrong))
            assertThrows(RuntimeFault::class.java) { CoreSignalForeign.validate(wrong, arguments, List(4) { false }, result) }
        }
    }

    @Test fun compiledEmbeddingDenialRemainsAHostFaultOnBothBackends() = compiledEmbeddingDenial(descriptor)
    @Test fun compiledUnixEmbeddingDenialRemainsAHostFaultOnBothBackends() = compiledEmbeddingDenial(unixDescriptor)

    private fun compiledEmbeddingDenial(original: Map<String, Any?>) = onBackends { language, backend ->
        val program = program(language, backend, original = original)
        val target = program.entryTarget("install")
        val state = Language.currentState()
        state.threads.enterCurrent()
        try {
            fun reject(token: Any = Unit) {
                val failure = assertThrows(RuntimeFault::class.java) {
                    Calls.target(target, arrayOf(0L, 2L, -5L, ManagedAddress.nullAddress(), token))
                }
                assertEquals(RuntimeFault::class.java, failure.javaClass)
                if (token === Unit) assertTrue(failure.message!!.contains("launcher authority"))
            }
            reject(); reject(1L)
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            reject()
            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            // Bytecode DSL resolveThrowable invalidates ordinary host faults;
            // only AbstractTruffleException and ControlFlowException bypass it.
            // Authority denial stays a RuntimeFault, not a catchable guest error.
            if (backend == "bytecode")
                assertEquals(false, target.javaClass.getMethod("isValidLastTier").invoke(target))
            assertEquals(0, language.handoffState.get().results.depth)
            assertEquals(0, language.handoffState.get().results.retainedReferences())
            assertEquals(0, language.handoffState.get().arguments.depth)
            assertEquals(0, language.handoffState.get().arguments.retainedReferences())
        } finally { state.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
    }

    @Test fun synchronousDispatchIsRejectedBeforeTransportAcquisition() = onBackends { language, backend ->
        val service = ManagedSignals(Language.currentState(), language, reducedVmSignals = true) {
            error("synchronous program must not acquire signal transport")
        }
        val failure = assertThrows(RuntimeFault::class.java) { service.bind(program(language, backend, async = false)) }
        assertTrue(failure.message!!.contains("asyncExceptions=true"))
        service.close()
    }

    @Test fun malformedLauncherAsyncPropertyFailsExplicitly() {
        val previous = System.getProperty("thc.asyncExceptions")
        try {
            System.setProperty("thc.asyncExceptions", "enabled")
            Context.newBuilder("thc").build().use { context ->
                assertThrows(IllegalArgumentException::class.java) { loadEntry(context, emptyList(), "unused") }
            }
        } finally {
            if (previous == null) System.clearProperty("thc.asyncExceptions")
            else System.setProperty("thc.asyncExceptions", previous)
        }
    }

    @Test fun launcherAsyncPropertyAndExplicitArgumentPreserveBackendDefaults(@TempDir directory: Path) {
        val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val identity = mapOf("id" to "identity", "name" to "identity", "arity" to 1, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", listOf(mapOf("id" to "x", "lifted" to false, "rep" to integer)),
                variable("x", integer), mapOf("rep" to closure, "resultRep" to integer)))
        val core = directory.resolve("SignalOptions.json").toFile()
        core.writeText(Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "SignalOptions",
            "unit" to "main", "constructors" to emptyList<Any>(), "bindings" to listOf(identity))))
        val previous = System.getProperty("thc.asyncExceptions")
        try {
            for (setting in listOf(null, "true", "false")) {
                if (setting == null) System.clearProperty("thc.asyncExceptions")
                else System.setProperty("thc.asyncExceptions", setting)
                for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context ->
                    val action = loadEntry(context, listOf(core.path), "identity", backend = backend)
                    assertEquals(setting?.toBooleanStrict() ?: (backend == "bytecode"),
                        (Json.parse(action.getMember("diagnostics").asString()) as Map<*, *>)["asyncExceptions"])
                    assertEquals(17L, action.execute(17L).asLong())
                    val explicit = loadEntry(context, listOf(core.path), "identity", backend = backend, asyncExceptions = true)
                    assertEquals(true, (Json.parse(explicit.getMember("diagnostics").asString()) as Map<*, *>)["asyncExceptions"])
                }
            }
        } finally {
            if (previous == null) System.clearProperty("thc.asyncExceptions")
            else System.setProperty("thc.asyncExceptions", previous)
        }
    }

    @Test fun contextTransportKeepsOldActionsAndClosesAfterDelivery() {
        assumeTrue(System.getProperty("os.name") == "Linux")
        onBackends { language, backend ->
            val owner = Language.currentState()
            val events = LinkedBlockingQueue<ProcessSignalTransport.Event>()
            val delivered = CountDownLatch(8)
            val closed = AtomicInteger()
            val fake = object : ProcessSignalTransport {
                private val old = mutableMapOf<Int, Int>()
                private var received = false
                override fun install(signal: Int, action: Int) =
                    ProcessSignalTransport.Result((old.put(signal, action) ?: -1), 0)
                override fun take(): ProcessSignalTransport.Event? {
                    if (received) delivered.countDown()
                    received = false
                    return events.take().takeUnless { it.info.isEmpty() }?.also { received = true }
                }
                override fun wake() { events.offer(ProcessSignalTransport.Event(0, byteArrayOf())) }
                override fun resetWake() { events.removeIf { it.info.isEmpty() } }
                override fun close() { closed.incrementAndGet() }
            }
            val service = ManagedSignals(owner, language, reducedVmSignals = true, userSignalAvailable = { true }) { fake }
            val program = program(language, backend)
            service.bind(program)
            assertThrows(RuntimeFault::class.java) { service.install(2L, -5L, ManagedAddress.nullAddress()) }
            service.authorizeLauncher()
            for (bad in listOf(64L to -5L, 11L to -5L, 2L to -3L, 2L to 1L))
                assertThrows(RuntimeFault::class.java) { service.install(bad.first, bad.second, ManagedAddress.nullAddress()) }
            for (signal in listOf(1L, 2L, 3L, 10L, 12L, 15L, 24L, 25L))
                assertEquals(listOf(-1L, -2L, -4L, -5L), listOf(-2L, -4L, -5L, -1L).map {
                    service.install(signal, it, ManagedAddress.nullAddress()) })
            try {
                for (signal in listOf(1, 2, 3, 10, 12, 15, 24, 25))
                    events.put(ProcessSignalTransport.Event(signal, ByteArray(128) { it.toByte() }))
                assertTrue(TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    TruffleSafepoint.InterruptibleFunction<CountDownLatch, Boolean> { it.await(5, TimeUnit.SECONDS) }, delivered),
                    "typed guest dispatcher must return")
                assertEquals(8, owner.nativeAllocations.liveCount(), "dispatcher images remain context-owned")
            } finally { service.close() }
            assertEquals(1, closed.get())
            assertThrows(RuntimeFault::class.java) { service.install(2L, -5L, ManagedAddress.nullAddress()) }
        }
    }

    @Test fun extendedHandlersRequireReleasedVmSignalsBeforeAcquiringTransport() = onBackends { language, backend ->
        val service = ManagedSignals(Language.currentState(), language, reducedVmSignals = false) {
            error("denied request must not acquire a native signal transport")
        }
        service.bind(program(language, backend))
        service.authorizeLauncher()
        for (signal in listOf(1L, 3L, 10L, 12L, 15L, 24L, 25L)) {
            val failure = assertThrows(RuntimeFault::class.java) {
                service.install(signal, -4L, ManagedAddress.nullAddress())
            }
            assertTrue(failure.message!!.contains("-Xrs"))
        }
        service.close()
    }

    @Test fun userSignalRequiresVerifiedRelocationBeforeAcquiringTransport() = onBackends { language, backend ->
        val service = ManagedSignals(Language.currentState(), language, reducedVmSignals = true,
            userSignalAvailable = { false }) { error("denied request must not acquire signal transport") }
        service.bind(program(language, backend))
        service.authorizeLauncher()
        val failure = assertThrows(RuntimeFault::class.java) {
            service.install(12L, -4L, ManagedAddress.nullAddress())
        }
        assertTrue(failure.message!!.contains("_JAVA_SR_SIGNUM=64"))
        assertThrows(RuntimeFault::class.java) { service.install(64L, -4L, ManagedAddress.nullAddress()) }
        service.close()
    }

    @Test fun dispatcherPassesEachActualSignalThroughTheOriginalBoxedCIntShape() = onBackends { language, backend ->
        val owner = Language.currentState()
        val program = program(language, backend, recordSignal = true)
        val target = SignalDispatchRoot(language, program).callTarget
        val address = owner.nativeAllocations.malloc(128L)
        owner.threads.enterCurrent()
        try {
            for (signal in listOf(1L, 2L, 3L, 10L, 12L, 15L, 24L, 25L)) {
                target.call(address, signal)
                assertEquals(signal, address.readWord8(0))
            }
        } finally { owner.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
    }

    @Test fun dispatcherKeepsPointerValidationBeforeTheSignalCastAndRejectsWithoutWriting() = onBackends { language, backend ->
        val owner = Language.currentState()
        val target = SignalDispatchRoot(language, program(language, backend, recordSignal = true)).callTarget
        val address = owner.nativeAllocations.malloc(128L)
        address.writeWord8(0, 77)
        owner.threads.enterCurrent()
        try {
            val pointerFailure = assertThrows(RuntimeFault::class.java) { target.call("not an address", null) }
            assertEquals("Expected a managed literal Addr# constructor field", pointerFailure.message)
            val nullFailure = assertThrows(NullPointerException::class.java) { target.call(address, null) }
            assertEquals("null cannot be cast to non-null type kotlin.Long", nullFailure.message)
            for (number in listOf(2, "signal"))
                assertThrows(ClassCastException::class.java) { target.call(address, number) }
            assertEquals(77L, address.readWord8(0), "invalid arguments cannot enter the writing guest dispatcher")
            ThreadInventoryCoreEvidence.released(language)
            target.call(address, 12L)
            assertEquals(12L, address.readWord8(0))
            ThreadInventoryCoreEvidence.released(language)
        } finally { owner.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
    }

    @Test fun dispatcherPreservesTheFirstInstalledSignalDelivery() = onBackends { language, backend ->
        val owner = Language.currentState()
        val program = program(language, backend, recordSignal = true)
        val target = SignalDispatchRoot(language, program).callTarget
        val address = owner.nativeAllocations.malloc(128L)
        owner.threads.enterCurrent()
        try {
            target.call(address, 2L)
            assertEquals(2L, address.readWord8(0))
            ThreadInventoryCoreEvidence.install(listOf(target))
            val calls = ThreadInventoryCoreEvidence.interpretedCalls(listOf(target))
            val compiled = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            target.call(address, 25L) // First call after installation; no settling call.
            assertEquals(25L, address.readWord8(0))
            assertTrue(ThreadInventoryCoreEvidence.valid(target), "$backend dispatcher remains installed")
            assertEquals(calls, ThreadInventoryCoreEvidence.interpretedCalls(listOf(target)))
            assertEquals(compiled + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            ThreadInventoryCoreEvidence.released(language)
        } finally { owner.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
    }

    @Test fun dispatcherRejectsAWrongBoxedResultAfterGuestEffects() = onBackends { language, backend ->
        fun returnSignal(value: Any?): Any? = when (value) {
            is List<*> -> if (value.firstOrNull() == "con" && value.getOrNull(1) == unitId)
                variable("signal", boxed) else value.map(::returnSignal)
            is Map<*, *> -> value.mapValues { returnSignal(it.value) }
            else -> value
        }
        val source = returnSignal(module(recordSignal = true)) as Map<String, Any?>
        val program = if (backend == "ast") Program(language, source, true) else BytecodeProgram(language, source, true)
        val target = SignalDispatchRoot(language, program).callTarget
        val owner = Language.currentState()
        val address = owner.nativeAllocations.malloc(128L)
        address.writeWord8(0, 77)
        owner.threads.enterCurrent()
        try {
            val failure = assertThrows(RuntimeFault::class.java) { target.call(address, 12L) }
            assertEquals("Signal dispatcher did not return boxed unit", failure.message)
            assertEquals(12L, address.readWord8(0), "result validation remains after the guest action")
            ThreadInventoryCoreEvidence.released(language)
        } finally { owner.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
    }

    @Test fun nativeEventsCrossTheActualJvmBoundaryInAnIsolatedProcess() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        val classpath = checkNotNull(System.getProperty("thc.testRuntimeClasspath")) {
            "test runner must expose its child JVM classpath"
        }
        val directory = File(System.getProperty("thc.projectRoot"), "build/process-signals")
        directory.mkdirs()
        for (mode in listOf("relocated", "unrelocated", "reduced-signals-disabled")) {
            val output = File.createTempFile("jvm-transport-", ".log", directory)
            val command = mutableListOf(File(System.getProperty("java.home"), "bin/java").path,
                "-Xrs", "--enable-native-access=ALL-UNNAMED")
            if (mode == "reduced-signals-disabled") command.add("-XX:-ReduceSignalUsage")
            command.addAll(listOf("-cp", classpath, ProcessSignalJvmProbe::class.java.name))
            command.add(mode)
            val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output)
            builder.environment().remove("LD_PRELOAD")
            if (mode == "relocated") builder.environment()["_JAVA_SR_SIGNUM"] = "64"
            else builder.environment().remove("_JAVA_SR_SIGNUM")
            val child = builder.start()
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "native signal child timed out: $output")
                assertEquals(0, child.exitValue(), output.readText())
                assertTrue(output.readText().contains(when (mode) {
                    "reduced-signals-disabled" -> "Later VM option disables reduced signal usage"
                    "unrelocated" -> "Unrelocated JVM retains SIGUSR2"
                    else -> "JVM received signals 1,2,3,10,12,15,24,25"
                }), output.readText())
            } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
        }
    }

    @Test fun standaloneScriptReservesSignalWithoutOverwritingUserSettings(@TempDir directory: Path) {
        assumeTrue(System.getProperty("os.name") == "Linux")
        val java = directory.resolve("bin/java").toFile()
        java.parentFile.mkdirs()
        java.writeText("#!/bin/sh\nprintf 'reserved=%s\\n' \"\${_JAVA_SR_SIGNUM-unset}\"\n")
        assertTrue(java.setExecutable(true))
        val script = File(System.getProperty("thc.projectRoot"), "build/scripts/thc")
        for (setting in listOf(null, "64", "12", "")) {
            val builder = ProcessBuilder("sh", script.path).redirectErrorStream(true)
            builder.environment()["JAVA_HOME"] = directory.toString()
            if (setting == null) builder.environment().remove("_JAVA_SR_SIGNUM")
            else builder.environment()["_JAVA_SR_SIGNUM"] = setting
            val child = builder.start()
            try {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                val output = child.inputStream.bufferedReader().readText()
                if (setting == null || setting == "64") {
                    assertEquals(0, child.exitValue(), output)
                    assertEquals("reserved=64\n", output)
                } else {
                    assertNotEquals(0, child.exitValue(), output)
                    assertTrue(output.contains("incompatible setting was preserved"), output)
                    assertFalse(output.contains("reserved="), output)
                }
            } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
        }
    }

    @Test fun nativeChildControlsAndGhcActionOracleHaveMatchingInputs() {
        assumeTrue(System.getProperty("os.name") == "Linux")
        val root = File(System.getProperty("thc.projectRoot"))
        val manifest = Json.parse(File(root, "build/process-signals/manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/ProcessSignalsNative.hs",
            "src/main/c/native-process-signal-api.c", "src/test/c/native-process-signals-test.c",
            "src/test/resources/core/original-signal-install-descriptor.json",
            "src/test/resources/core/original-unix-signal-install-descriptor.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("build/process-signals/oracle.txt",
            "build/process-signals/native-controls.txt"), "build/process-signals/")
        assertEquals("[(1,[-1,-2,-4,-5]),(2,[-1,-2,-4,-5]),(3,[-1,-2,-4,-5]),(10,[-1,-2,-4,-5]),(12,[-1,-2,-4,-5]),(15,[-1,-2,-4,-5]),(24,[-1,-2,-4,-5]),(25,[-1,-2,-4,-5])]\n", File(root, "build/process-signals/oracle.txt").readText())
        assertEquals("41 isolated native signal controls passed\n", File(root, "build/process-signals/native-controls.txt").readText())
    }
}

/** Separate process: the Gradle test worker must never replace its host handlers. */
object ProcessSignalJvmProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("reduced-signals-disabled"))) {
            check(!ManagedSignals.hasReducedVmSignals())
            println("Later VM option disables reduced signal usage")
            return
        }
        check(ManagedSignals.hasReducedVmSignals())
        if (args.contentEquals(arrayOf("unrelocated"))) {
            check(!NativeSignalTransport.userSignalAvailable())
            // Changing the environment after VM startup must not masquerade as
            // releasing HotSpot's existing USR2 disposition. This child alone
            // owns the environment mutation; no signal handler is changed.
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val linker = java.lang.foreign.Linker.nativeLinker()
                val setenv = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS,
                        java.lang.foreign.ValueLayout.JAVA_INT))
                check(setenv.invokeExact(arena.allocateFrom("_JAVA_SR_SIGNUM"), arena.allocateFrom("64"), 1) as Int == 0)
            }
            check(!NativeSignalTransport.userSignalAvailable())
            NativeSignalTransport().use { transport ->
                val failure = runCatching { transport.install(12, -4) }.exceptionOrNull()
                check(failure is RuntimeFault && failure.message!!.contains("_JAVA_SR_SIGNUM=64"))
            }
            println("Unrelocated JVM retains SIGUSR2")
            return
        }
        check(NativeSignalTransport.userSignalAvailable())
        val raise = java.lang.foreign.Linker.nativeLinker().downcallHandle(
            java.lang.foreign.Linker.nativeLinker().defaultLookup().find("raise").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                java.lang.foreign.ValueLayout.JAVA_INT))
        NativeSignalTransport().use { transport ->
            for (signal in listOf(1, 2, 3, 10, 12, 15, 24, 25)) {
                check(transport.install(signal, -4).action == -1)
                check(raise.invokeExact(signal) as Int == 0)
                val event = checkNotNull(transport.take())
                check(event.signal == signal && event.info.isNotEmpty())
            }
        }
        check(NativeSignalTransport.userSignalAvailable()) // USR2 restored; HotSpot's reserved handler survives.
        println("JVM received signals 1,2,3,10,12,15,24,25")
    }
}
