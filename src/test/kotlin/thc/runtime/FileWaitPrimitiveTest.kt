// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.ContinuationResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Language
import thc.NativeIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Original payload identity at the primitive boundary; installed-Core proof is separate. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(45)
class FileWaitPrimitiveTest {
    @TempDir lateinit var directory: Path
    private val fd = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"),
        "evaluated" to true)
    private val payload = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"),
        "evaluated" to false)
    private fun module(name: String): Map<String, Any?> {
        val bad = CoreFileWait.badFd
        val params = listOf(mapOf("id" to "descriptor", "lifted" to false, "rep" to fd),
            mapOf("id" to "s", "lifted" to false, "rep" to state))
        val call = listOf("app", listOf("prim", name),
            listOf(listOf("var", "descriptor", mapOf("rep" to fd)),
                listOf("var", "s", mapOf("rep" to state))),
            listOf(false, false), false, false, mapOf("rep" to state))
        val root = mapOf("id" to "wait", "name" to "wait", "arity" to 2, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", params, call,
                mapOf("rep" to closure, "resultRep" to state)))
        val original = mapOf("id" to bad, "name" to "blockedOnBadFD", "arity" to 0,
            "lifted" to true, "rep" to payload,
            "expr" to listOf("var", bad, mapOf("rep" to payload)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Test.FileWait",
            "bindings" to listOf(root, original), "constructors" to emptyList<Any>())
    }
    private fun valid(target: RootCallTarget) =
        target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }

    @Test fun firstInstalledWaitsKeepExactDescriptorAndLazyBadFdPayload() {
        NativeIO.createContext().use { context ->
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val files = Language.currentState().files
                val path = directory.resolve("ready")
                Files.writeString(path, "ready")
                val address = ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0))
                for (backend in listOf("ast", "bytecode")) for (name in listOf("waitRead#", "waitWrite#")) {
                    val opened = files.open(address, if (name == "waitWrite#") 2L else 0L)
                    assertTrue(opened >= 3L)
                    val linked = CoreModules.reachable(module(name), "wait", true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                        else BytecodeProgram(language, linked)
                    val target = program.entryTarget("wait")
                    fun call(descriptor: Long) = Calls.target(target, arrayOf(0L, descriptor, Unit))
                    repeat(6) { assertSame(Unit, call(opened)) }
                    compile(target)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertSame(Unit, call(opened))
                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    assertTrue(valid(target), "$backend/$name keeps its installed target")
                    val original = program.entryValue(CoreFileWait.badFd) as Thunk
                    for (bad in listOf(-1L, opened)) {
                        if (bad == opened) assertEquals(0L, files.close(opened))
                        val failure = assertThrows(GuestException::class.java) { call(bad) }
                        assertSame(original, failure.payload)
                        assertEquals(0, original.state, "RTS payload must remain lazy")
                    }
                    assertTrue(valid(target), "$backend/$name remains installed after bad FD")
                }
            } finally { context.leave() }
        }
    }

    @Test fun compiledReadWaitResumesItsOriginalTokenAfterInterruptionAndRejectsDescriptorReuse() {
        val pipe = directory.resolve("readiness")
        val created = ProcessBuilder("mkfifo", pipe.toString()).start()
        assertTrue(created.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, created.exitValue())
        val context = NativeIO.createContext(emptySet())
        val worker = Executors.newSingleThreadExecutor()
        try {
            context.initialize("thc"); context.enter()
            val state: Language.State
            val program: BytecodeProgram
            val target: RootCallTarget
            val read: Long
            val write: Long
            val original: Thunk
            try {
                state = Language.currentState()
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val address = ManagedAddress.fromByteArray(pipe.toString().toByteArray() + byteArrayOf(0))
                read = state.stdio.open(address, 0x800, 0)
                write = state.stdio.open(address, 0x801, 0)
                assertTrue(read >= 3); assertTrue(write >= 3)
                val linked = CoreModules.reachable(module("waitRead#"), "wait", true) + ("instrument" to true)
                program = BytecodeProgram(language, linked, true)
                target = program.entryTarget("wait")
                original = program.entryValue(CoreFileWait.badFd) as Thunk
                assertEquals(1L, state.stdio.write(write, ManagedAddress.fromByteArray(byteArrayOf(7)), 1))
                repeat(6) { assertSame(Unit, Calls.target(target, arrayOf(0L, read, Unit))) }
                compile(target)
                assertEquals(1L, state.stdio.read(read, ManagedAddress.fromByteArray(byteArrayOf(0)), 1))
            } finally { context.leave() }

            fun awaitBlocked(future: Future<*>) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (state.files.pendingReadiness(read) == 0 && !future.isDone &&
                    System.nanoTime() < deadline)
                    Thread.sleep(1)
                if (future.isDone) future.get()
                assertEquals(1, state.files.pendingReadiness(read), "Compiled wait must block on the real FIFO")
            }
            fun cut(mask: MaskingState): Pair<ContinuationResult, AsyncRequest> {
                val id = AtomicLong(-1)
                val future = worker.submit<Any> {
                    context.enter(); state.threads.enterCurrent(mask)
                    try {
                        id.set(state.threads.currentId())
                        Calls.target(target, arrayOf(0L, read, Unit)).also { answer ->
                            if (answer is ContinuationResult) AsyncContinuations.request(answer)?.acknowledge()
                        }
                    } finally { state.threads.leaveCurrent(); context.leave() }
                }
                awaitBlocked(future)
                val request = state.threads.send(id.get(), "interrupt descriptor wait")
                val saved = future.get(5, TimeUnit.SECONDS) as ContinuationResult
                assertSame(request, AsyncContinuations.request(saved))
                assertTrue(request.compiledCapture)
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                assertEquals(0, state.files.pendingReadiness(read), "The native poll lease must be released at capture")
                return saved to request
            }

            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            val (ready, firstRequest) = cut(MaskingState.UNMASKED)
            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            assertEquals(AsyncRequestState.ACKNOWLEDGED, firstRequest.state)
            context.enter()
            try {
                assertEquals(1L, state.stdio.write(write, ManagedAddress.fromByteArray(byteArrayOf(9)), 1))
                assertSame(Unit, ready.continueWith(Unit))
                assertEquals(1L, state.stdio.read(read, ManagedAddress.fromByteArray(byteArrayOf(0)), 1))
            } finally { context.leave() }

            val id = AtomicLong(-1)
            val delivered = AtomicReference<AsyncRequest>()
            val masked = worker.submit<Any> {
                context.enter(); state.threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE)
                try {
                    id.set(state.threads.currentId())
                    val result = Calls.target(target, arrayOf(0L, read, Unit))
                    state.maskingState.set(MaskingState.UNMASKED)
                    delivered.set(state.threads.poll(target.rootNode, true))
                    delivered.get()?.acknowledge()
                    result
                } finally { state.threads.leaveCurrent(); context.leave() }
            }
            awaitBlocked(masked)
            val maskedRequest = state.threads.send(id.get(), "masked descriptor wait")
            assertEquals(AsyncRequestState.PENDING, maskedRequest.state)
            assertFalse(masked.isDone, "Uninterruptibly masked wait cannot claim the request")
            context.enter()
            try { assertEquals(1L, state.stdio.write(write, ManagedAddress.fromByteArray(byteArrayOf(5)), 1)) }
            finally { context.leave() }
            assertSame(Unit, masked.get(5, TimeUnit.SECONDS))
            assertSame(maskedRequest, delivered.get())
            assertEquals(AsyncRequestState.ACKNOWLEDGED, maskedRequest.state)
            assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            context.enter()
            try { assertEquals(1L, state.stdio.read(read, ManagedAddress.fromByteArray(byteArrayOf(0)), 1)) }
            finally { context.leave() }

            val (closed, secondRequest) = cut(MaskingState.MASKED_INTERRUPTIBLE)
            assertEquals(before + 3, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
            assertEquals(AsyncRequestState.ACKNOWLEDGED, secondRequest.state)
            context.enter()
            try {
                assertEquals(0L, state.files.close(read))
                val readyFile = directory.resolve("replacement")
                Files.writeString(readyFile, "ready")
                val replacement = ManagedAddress.fromByteArray(readyFile.toString().toByteArray() + byteArrayOf(0))
                val newFd = state.files.open(replacement, 0L)
                assertTrue(newFd >= 3)
                assertEquals(read, state.files.duplicateTo(newFd, read))
                val failure = assertThrows(GuestException::class.java) { closed.continueWith(Unit) }
                assertSame(original, failure.payload)
                assertEquals(0, original.state)
                assertTrue(valid(target))
            } finally { context.leave() }
        } finally {
            context.close(true); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
