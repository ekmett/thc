// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ArityException
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import thc.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Actual C boundary controls; unchanged erf acquisition is checked separately. */
class PackageSafeForeignTest {
    @TempDir lateinit var directory: Path

    private fun library(): PackageScalarLink {
        val source = directory.resolve("safe.c")
        val output = directory.resolve("safe.bc")
        Files.writeString(source, """
            #define _DEFAULT_SOURCE 1
            #include <stdatomic.h>
            #include <unistd.h>
            static _Atomic int phase;
            static _Atomic int calls;
            int started(void) { return atomic_load(&phase); }
            int call_count(void) { return atomic_load(&calls); }
            void reset(void) { atomic_store(&phase, 0); atomic_store(&calls, 0); }
            void release(void) { atomic_store(&phase, 2); }
            double wait_value(double value) {
              if (atomic_fetch_add(&calls, 1) != 0) return -321.0;
              atomic_store(&phase, 1);
              /* A finite native leaf, like the native libm leaves used by erf.
                 This test does not add usleep to producer ABI admission. */
              usleep(100000);
              return value;
            }
            double requires_argument(double value) { return value; }
        """.trimIndent())
        val cpu = if (System.getProperty("os.arch") == "amd64") "x86_64" else System.getProperty("os.arch")
        val target = if (System.getProperty("os.name") == "Linux") "$cpu-unknown-linux-gnu" else "$cpu-apple-darwin"
        val process = ProcessBuilder(System.getenv("THC_CLANG") ?: "clang", "--target=$target", "-std=c11",
            "-O1", "-emit-llvm", "-c", source.toString(), "-o", output.toString()).redirectErrorStream(true).start()
        val diagnostic = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), diagnostic)
        val bytes = Files.readAllBytes(output)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val abi = listOf(
            PackageScalarSignature("started", "started", emptyList(), "Int32Rep", safety = "safe"),
            PackageScalarSignature("call_count", "call_count", emptyList(), "Int32Rep", safety = "safe"),
            PackageScalarSignature("reset", "reset", emptyList(), "void", safety = "safe"),
            PackageScalarSignature("release", "release", emptyList(), "void", safety = "safe"),
            PackageScalarSignature("wait_value", "wait_value", listOf("DoubleRep"), "DoubleRep", safety = "safe"),
            // Deliberately inconsistent control: no forged typed Core is admitted.
            // The runtime interop call throws inside the foreign extent.
            PackageScalarSignature("requires_argument", "requires_argument", emptyList(), "DoubleRep", safety = "safe"))
        return PackageScalarLink("safe-boundary-control", target, hash, hash, bytes, abi)
    }

    private class Entry(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = when (call.result) {
            "DoubleRep" -> access.executeDouble(frame.arguments, Unit)
            "Int32Rep" -> access.executeLong(frame.arguments, Unit)
            "void" -> { access.executeVoid(frame.arguments, Unit); Unit }
            else -> error("Unexpected safe control ABI")
        }
    }

    private fun module(link: PackageScalarLink): Map<String, Any?> {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val double = mapOf("kind" to "double", "primReps" to listOf("DoubleRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val result = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "primReps" to listOf("DoubleRep"),
            "components" to listOf(state, double), "evaluated" to true)
        val descriptor = mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to "wait_value",
            "unit" to link.unit, "isFunction" to true), "convention" to "ccall", "safety" to "safe",
            "arity" to 2L, "suppliedArity" to 2L,
            "argumentReps" to listOf(double + ("evaluated" to false), state + ("evaluated" to false)),
            "resultRep" to (result + ("evaluated" to false)))
        val arguments = listOf(listOf("var", "value", mapOf("rep" to double)),
            listOf("var", "state", mapOf("rep" to state)))
        val body = listOf("app", listOf("var", "native-safe", mapOf("rep" to closure)), arguments,
            listOf(false, false), false, false, mapOf("rep" to result, "foreignCall" to descriptor))
        val parameters = listOf("value" to double, "state" to state).map { (name, rep) ->
            mapOf("id" to name, "name" to name, "lifted" to false, "coercion" to false, "rep" to rep) }
        return mapOf("bindings" to listOf(mapOf("id" to "wait", "name" to "wait", "lifted" to true,
            "expr" to listOf("lam", parameters, body, mapOf("resultRep" to result)))),
            "packageScalarLinks" to listOf(link), "instrument" to true)
    }

    @ParameterizedTest @ValueSource(strings = ["ast", "ast-compiled", "bytecode", "bytecode-compiled"])
    fun safeScalarDefersAsyncUntilReturnWhileOtherGuestCallsAndGcProgress(mode: String) {
        val link = library()
        Context.newBuilder("thc").allowNativeAccess(true).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val owner = Language.currentState()
                    owner.packageCbits.link(link)
                    val entries = link.abi.associate { it.symbol to Entry(language, PackageScalarCall(link, it)).callTarget }
                    val program: ExecutableProgram = if (mode.startsWith("ast")) Program(language, module(link), true)
                        else BytecodeProgram(language, module(link), true)
                    val target = program.entryTarget("wait")
                    val shape = checkNotNull((target.rootNode as GuestRoot).tupleResult)
                    entries.getValue("started").call()
                    entries.getValue("release").call()
                    entries.getValue("call_count").call()
                    val ready = CountDownLatch(1)
                    val begin = CountDownLatch(1)
                    val compiledBefore = AtomicLong()
                    val identity = AtomicLong()
                    val pending = AtomicReference<AsyncRequest>()
                    val completed = AtomicReference<SavedGuestContinuation>()
                    val failure = AtomicReference<Throwable>()
                    val worker = Thread {
                        context.enter()
                        identity.set(owner.threads.enterCurrent())
                        try {
                            // Warm on the same registered carrier that will
                            // execute the first installed foreign call.
                            repeat(3) {
                                entries.getValue("reset").call()
                                val result = Calls.target(target, arrayOf(0L, 0.5, Unit))
                                assertEquals(0.5, shape.layout.getDouble(ownedTupleResult(result, shape), 0))
                            }
                            entries.getValue("reset").call()
                            ready.countDown()
                            check(begin.await(10, TimeUnit.SECONDS))
                            val result = Calls.target(target, arrayOf(0L, 0.5, Unit))
                            val continuation = checkNotNull(savedGuestContinuation(result))
                            val request = continuation.asyncRequest()
                            assertSame(pending.get(), request, "delivery occurs at the completed foreign-call cut")
                            if (mode.endsWith("compiled")) {
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > compiledBefore.get())
                                assertTrue(request!!.compiledCapture, "first installed call reaches the return cut in compiled code")
                            }
                            request!!.acknowledge()
                            completed.set(continuation)
                        } catch (problem: Throwable) { failure.set(problem) }
                        finally { ready.countDown(); owner.threads.leaveCurrent(); context.leave() }
                    }
                    worker.start()
                    try {
                        assertTrue(ready.await(10, TimeUnit.SECONDS))
                        failure.get()?.let { throw it }
                        if (mode.endsWith("compiled")) {
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                        }
                        compiledBefore.set((program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        begin.countDown()
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (entries.getValue("started").call() != 1L && System.nanoTime() < deadline) Thread.sleep(1)
                        assertEquals(1L, entries.getValue("started").call(), "other guest call observes the active C loop")
                        val request = owner.threads.send(identity.get(), "after safe return")
                        pending.set(request)
                        System.gc()
                        assertEquals(AsyncRequestState.PENDING, request.state, "no guest delivery from the opaque foreign frame")
                    } finally {
                        begin.countDown()
                        entries.getValue("release").call()
                        worker.join(5000)
                    }
                    assertFalse(worker.isAlive, "scalar safe return must release the Java carrier")
                    failure.get()?.let { throw it }
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.get().state)
                    assertEquals(1L, entries.getValue("call_count").call())
                    owner.threads.enterCurrent()
                    try {
                        val result = completed.get().continueWith(Unit)
                        assertEquals(0.5, shape.layout.getDouble(ownedTupleResult(result, shape), 0))
                        assertEquals(1L, entries.getValue("call_count").call(), "resumption must not replay the foreign effect")
                        if (mode.startsWith("ast"))
                            assertThrows(RuntimeFault::class.java) { completed.get().continueWith(Unit) }
                        assertEquals(0, language.handoffState.get().arguments.depth)
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                    } finally { owner.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }

    @Test fun failingInteropRestoresGuestAndNestedForeignPermissions() {
        val link = library()
        Context.newBuilder("thc").allowNativeAccess(true).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val owner = Language.currentState()
                    owner.packageCbits.link(link)
                    val signature = link.abi.single { it.symbol == "requires_argument" }
                    val target = Entry(language, PackageScalarCall(link, signature)).callTarget
                    val identity = owner.threads.enterCurrent()
                    try {
                        val request = owner.threads.send(identity, "pending across failed call")
                        val outer = owner.threads.enterForeign()
                        try {
                            assertThrows(ArityException::class.java) { target.call() }
                            assertNull(owner.threads.poll(target.rootNode))
                            assertEquals(AsyncRequestState.PENDING, request.state)
                        } finally { owner.threads.leaveForeign(outer) }
                        assertSame(request, owner.threads.poll(target.rootNode))
                        request.acknowledge()
                    } finally { owner.threads.leaveCurrent() }
                    val restored = owner.threads.enterForeign()
                    try { assertEquals(GuestThreads.DeliveryPermission.NONE, restored) }
                    finally { owner.threads.leaveForeign(restored) }
                } finally { context.leave() }
            }
    }
}
