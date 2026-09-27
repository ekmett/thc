// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.ThreadLocalAction
import com.oracle.truffle.api.nodes.Node
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import thc.ContextProfile
import thc.Json
import thc.Language
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Integration gate: invokes actual exported original-package FCalls through
 * both interpreters. No direct ManagedProcesses/ManagedProcessForeign calls. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(90)
class ProcessLifecycleCoreTest {
    @TempDir lateinit var directory: Path
    private val root = Path.of(System.getProperty("thc.projectRoot"))
    private val oracle = root.resolve("build/process-lifecycle/native/process-oracle")
    private val prefix = "build/process-lifecycle/core"
    private val nil get() = ManagedAddress.nullAddress()
    private fun json(name: String) = Json.parse(Files.readString(root.resolve("$prefix/$name.json"))) as Map<String, Any?>
    private fun cell(width: Long = 4) = Language.currentState().nativeAllocations.malloc(width)
    private fun string(value: String) = value.toByteArray().let { bytes -> cell(bytes.size.toLong() + 1).also { address ->
        bytes.forEachIndexed { index, byte -> address.writeWord8(index.toLong(), byte.toLong()) }
        address.writeWord8(bytes.size.toLong(), 0)
    } }
    private fun vector(vararg values: String) = cell((values.size.toLong() + 1) * 8).also { array ->
        values.forEachIndexed { index, value -> array.writeAddressElementIndex(index.toLong(), string(value)) }
        array.writeAddressElementIndex(values.size.toLong(), nil)
    }
    private fun int(address: ManagedAddress) = ManagedAddressRead.INT32.readInt(address, 0).toLong()
    private fun text(address: ManagedAddress) = ByteArray(address.cStringLength().toInt()) { address.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8)
    private fun nativeRows(mode: String): Map<String, String> {
        val native = ProcessBuilder(oracle.toString(), mode).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            assertTrue(native.waitFor(10, TimeUnit.SECONDS), "Native process oracle timed out")
            assertEquals(0, native.exitValue())
            return native.inputStream.bufferedReader().readLines().associate { row ->
                val boundary = row.indexOf(' ')
                row.substring(0, boundary) to row.substring(boundary + 1)
            }
        } finally {
            if (native.isAlive) { native.destroyForcibly(); native.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    // Observe only this context's owned children, not native PID authority.
    private fun waiting(processes: ManagedProcesses): Boolean = synchronized(processes) {
        val children = ManagedProcesses::class.java.getDeclaredField("children").also { it.isAccessible = true }
            .get(processes) as Map<*, *>
        children.values.any { child -> synchronized(child!!) {
            (child.javaClass.getDeclaredField("waiters").also { it.isAccessible = true }.get(child) as Collection<*>).isNotEmpty()
        } }
    }

    private fun eventually(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(predicate(), label)
    }

    @ParameterizedTest
    @CsvSource("pre, ast", "post, ast", "pre, bytecode", "post, bytecode")
    fun originalInterruptibleWaitSavesErrnoAndNeverReplays(stage: String, backend: String) {
        NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST, allowProcesses = true).use { context ->
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                val module = json(stage) + ("instrument" to true)
                val plain: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                val async: ExecutableProgram = if (backend == "ast") Program(language, module, true) else BytecodeProgram(language, module, true)
                val wait = async.entryTarget("processWait")
                fun call(name: String, vararg args: Any?) = Calls.target(plain.entryTarget(name), arrayOf(0L, *args)) as Long
                for (installed in listOf(false, true)) {
                    if (installed) {
                        wait.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(wait, true)
                        assertEquals(true, wait.javaClass.getMethod("isValidLastTier").invoke(wait))
                        val runtime = Truffle.getRuntime()
                        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, wait)
                    }
                    for (scenario in listOf("unmasked", "masked", "uninterruptible", "completed")) {
                        val mask = when (scenario) {
                            "unmasked" -> MaskingState.UNMASKED
                            "masked" -> MaskingState.MASKED_INTERRUPTIBLE
                            else -> MaskingState.MASKED_UNINTERRUPTIBLE
                        }
                        val pipes = List(3) { cell() }
                        val pid = call("processCreate", vector(oracle.toString(), "hold"), nil, nil, -1L, -1L, -2L,
                            pipes[0], pipes[1], pipes[2], nil, nil, 0L, cell(8))
                        assertTrue(pid > 0)
                        val input = int(pipes[0]); val output = int(pipes[1]); val ready = cell()
                        assertEquals(1L, state.stdio.read(output, ready, 1)); assertEquals('R'.code.toLong(), ready.readWord8(0))
                        val destination = cell().also { it.writeNativeScalar(0, 4, 991) }
                        val identity = AtomicLong()
                        val captured = AtomicReference<SavedGuestContinuation>()
                        val failure = AtomicReference<Throwable>()
                        val done = CountDownLatch(1)
                        val gateEntered = CountDownLatch(1); val gateRelease = CountDownLatch(1)
                        val before = (async.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val worker = Thread {
                            context.enter()
                            identity.set(state.threads.enterCurrent())
                            state.maskingState.set(mask)
                            state.stdio.setErrno(73)
                            try {
                                val result = Calls.target(wait, arrayOf(0L, pid, destination))
                                val continuation = savedGuestContinuation(result)
                                if (scenario == "uninterruptible") {
                                    assertNull(continuation); assertEquals(0L, result); assertEquals(73L, state.stdio.errno())
                                    assertEquals(mask, state.maskingState.get())
                                    state.maskingState.set(MaskingState.UNMASKED)
                                    val request = state.threads.poll(object : Node() {}, true)
                                    assertNotNull(request); request!!.acknowledge()
                                } else {
                                    assertNotNull(continuation, "$scenario must yield after its completed result")
                                    val request = continuation!!.asyncRequest()!!
                                    assertEquals("process wait delivery", request.payload)
                                    if (installed) assertTrue(request.compiledCapture, "$scenario first installed completion cut")
                                    request.acknowledge(); captured.set(continuation)
                                }
                            } catch (error: Throwable) { failure.set(error) }
                            finally { state.threads.leaveCurrent(); state.maskingState.remove(); context.leave(); done.countDown() }
                        }
                        worker.start()
                        try {
                            eventually("Original wait acquired an owned native watch") { waiting(NativeFileProvider.current().processes) }
                            if (scenario == "completed") {
                                // Hold the original FOREIGN extent at a real Truffle
                                // wake while the owned child exits. No wait/reap occurs
                                // in this action; /proc merely observes its zombie state.
                                state.env.submitThreadLocal(arrayOf(worker), object : ThreadLocalAction(true, false) {
                                    override fun perform(access: Access) {
                                        gateEntered.countDown()
                                        check(gateRelease.await(10, TimeUnit.SECONDS))
                                        state.maskingState.set(MaskingState.UNMASKED)
                                    }
                                })
                                assertTrue(gateEntered.await(10, TimeUnit.SECONDS))
                            }
                            val request = state.threads.send(identity.get(), "process wait delivery")
                            if (scenario == "completed" || scenario == "uninterruptible") {
                                if (scenario == "uninterruptible") {
                                    assertFalse(done.await(30, TimeUnit.MILLISECONDS), "MaskedUninterruptible must keep waiting")
                                    assertEquals(AsyncRequestState.PENDING, request.state)
                                }
                                assertEquals(1L, state.stdio.write(input, string("x"), 1))
                                if (scenario == "completed") {
                                    eventually("Owned child exited before pending delivery") {
                                        Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").startsWith("Z ")
                                    }
                                    assertEquals(AsyncRequestState.PENDING, request.state, "FOREIGN never claims delivery")
                                    gateRelease.countDown()
                                }
                            }
                            assertTrue(done.await(10, TimeUnit.SECONDS), "$scenario original wait did not finish")
                            failure.get()?.let { throw it }
                            if (installed) {
                                assertEquals(before + 1, (async.diagnostics().getValue("compiledEntries") as Number).toLong())
                                assertEquals(true, wait.javaClass.getMethod("isValidLastTier").invoke(wait))
                            }
                            captured.get()?.let { continuation ->
                                state.threads.enterCurrent()
                                try {
                                    state.stdio.setErrno(99) // Another carrier/handler may have changed it.
                                    assertEquals(if (scenario == "completed") 0L else -1L, continuation.continueWith(Unit))
                                    assertEquals(if (scenario == "completed") 73L else 4L, state.stdio.errno())
                                } finally { state.threads.leaveCurrent() }
                            }
                            if (scenario in listOf("unmasked", "masked")) {
                                assertEquals(991L, int(destination), "Interrupted wait cannot publish an exit code")
                                assertEquals(0L, call("processPoll", pid, cell()), "Interrupted wait must leave the child alive and owned")
                                assertEquals(1L, state.stdio.write(input, string("x"), 1))
                                assertEquals(0L, call("processWait", pid, destination))
                            } else assertEquals(-1L, call("processWait", pid, cell()), "Resumption cannot reap a second time")
                            assertEquals(23L, int(destination))
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        } finally {
                            gateRelease.countDown()
                            if (worker.isAlive) call("processTerminate", pid)
                            worker.join(10000)
                            assertFalse(worker.isAlive)
                            state.files.close(input); state.files.close(output)
                        }
                    }
                }
            } finally { context.leave() }
        }
    }

    @ParameterizedTest
    @CsvSource("pre, ast", "post, ast", "pre, bytecode", "post, bytecode")
    fun originalProcessCoreMatchesNativeBeforeAndAfterInstallation(stage: String, backend: String) {
        val manifest = json("manifest")
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(listOf("processCreate", "processPoll", "processWait", "processTerminate"), manifest["entries"])
        OriginalStdioChecks.hashes(root.toFile(), manifest["inputHashes"], setOf(
            "compiler/test-fixtures/ProcessLifecycleAudit.hs", "test/haskell-fixtures/ProcessLifecycleFixtures.hs"))
        OriginalStdioChecks.hashes(root.toFile(), manifest["artifactHashes"], setOf("$prefix/pre.json", "$prefix/post.json"), "$prefix/")
        val expected = nativeRows("oracle")
        val creation = nativeRows("creation-oracle")
        assertEquals(10, expected.size); assertEquals(3, creation.size)
            NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST, allowProcesses = true).use { context ->
                context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val state = Language.currentState()
                    val module = json(stage) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val targets = listOf("processCreate", "processPoll", "processWait", "processTerminate")
                        .associateWith(program::entryTarget)
                    var installed = false
                    fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    fun call(name: String, vararg arguments: Any?): Long {
                        val target = targets.getValue(name)
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (installed) valid(target)
                        val result = Calls.target(target, arrayOf(0L, *arguments)) as Long
                        if (installed) {
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$stage/$backend/$name first installed entry")
                            valid(target)
                        }
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                        return result
                    }
                    data class Child(val pid: Long, val input: Long, val output: Long, val error: Long)
                    fun create(command: ManagedAddress, environment: ManagedAddress = nil, cwd: ManagedAddress = nil,
                        streams: LongArray = longArrayOf(-2, -2, -2)): Child {
                        val outputs = List(3) { cell().also { it.writeNativeScalar(0, 4, 991) } }
                        val failure = cell(8)
                        val pid = call("processCreate", command, cwd, environment, streams[0], streams[1], streams[2],
                            outputs[0], outputs[1], outputs[2], nil, nil, 0L, failure)
                        assertTrue(pid > 0, "$stage/$backend creation failed: ${if (failure.readAddressElementIndex(0) === nil) "null" else text(failure.readAddressElementIndex(0))}")
                        assertSame(nil, failure.readAddressElementIndex(0))
                        for (index in streams.indices) if (streams[index] != -1L) assertEquals(991L, int(outputs[index]))
                        return Child(pid, if (streams[0] == -1L) int(outputs[0]) else -1,
                            if (streams[1] == -1L) int(outputs[1]) else -1, if (streams[2] == -1L) int(outputs[2]) else -1)
                    }
                    fun close(child: Child) { for (fd in listOf(child.input, child.output, child.error)) if (fd >= 0) assertEquals(0L, state.files.close(fd)) }
                    fun read(fd: Long, maximum: Int): String {
                        val buffer = cell(maximum.toLong())
                        val received = state.stdio.read(fd, buffer, maximum.toLong())
                        assertTrue(received in 0..maximum.toLong())
                        return ByteArray(received.toInt()) { buffer.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8)
                    }
                    fun write(fd: Long, value: String) = assertEquals(value.toByteArray().size.toLong(), state.stdio.write(fd, string(value), value.toByteArray().size.toLong()))
                    fun status(name: String, pid: Long): String {
                        val code = cell().also { it.writeNativeScalar(0, 4, 991) }
                        state.stdio.captureForeignErrno(0)
                        val result = if (name == "processTerminate") call(name, pid) else call(name, pid, code)
                        return "$result ${int(code)} ${state.stdio.errno()}"
                    }
                    fun same(row: String, name: String, pid: Long) = assertEquals(expected.getValue(row), status(name, pid), "$stage/$backend/$row")
                    fun held(): Child = create(vector(oracle.toString(), "hold"), streams = longArrayOf(-1, -1, -2)).also {
                        assertEquals("R", read(it.output, 1))
                    }
                    fun exercise() {
                        val first = held()
                        same("running", "processPoll", first.pid)
                        write(first.input, "x")
                        same("wait", "processWait", first.pid)
                        same("poll-after-wait", "processPoll", first.pid)
                        same("wait-after-wait", "processWait", first.pid)
                        close(first)
                        val stopped = held()
                        same("terminate", "processTerminate", stopped.pid)
                        same("terminated-wait", "processWait", stopped.pid)
                        close(stopped)
                        val poll = create(vector(oracle.toString(), "exit", "17"), streams = longArrayOf(-2, -1, -2))
                        assertEquals("", read(poll.output, 1))
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        var polled = status("processPoll", poll.pid)
                        while (polled.startsWith("0 ") && System.nanoTime() < deadline) polled = status("processPoll", poll.pid)
                        assertEquals(expected.getValue("poll-exit"), polled)
                        same("poll-after-poll", "processPoll", poll.pid)
                        same("wait-after-poll", "processWait", poll.pid)
                        close(poll)
                        state.environment.put(string("PATH=/bin"))
                        val path = create(vector("true"), vector("PATH=/missing-child-path"))
                        same("parent-path", "processWait", path.pid)
                        for ((name, command, cwd) in listOf(Triple("missing-command", "/definitely-missing-thc-command", nil),
                            Triple("missing-cwd", "/bin/true", string("/definitely-missing-thc-directory")))) {
                            val outputs = List(3) { cell().also { it.writeNativeScalar(0, 4, 991) } }
                            val failure = cell(8)
                            state.stdio.captureForeignErrno(0)
                            val result = call("processCreate", vector(command), cwd, nil, -2L, -2L, -2L,
                                outputs[0], outputs[1], outputs[2], nil, nil, 0L, failure)
                            assertEquals(creation.getValue(name), "$result ${state.stdio.errno()} ${text(failure.readAddressElementIndex(0))} ${outputs.joinToString(" ") { int(it).toString() }}")
                        }
                        val cwd = Files.createTempDirectory(directory, "$stage-$backend-")
                        Files.writeString(cwd.resolve("value"), "cwd-data")
                        NativeFileProvider.current().changeDirectory(NativeDirectoryOwner.pathBytes(cwd))
                        val environment = create(vector("sh", "-c", "printf '%s:%s:' \"\$THC_VALUE\" \"$$\"; /bin/cat value"),
                            vector("THC_VALUE=child", "PATH=/missing-child-path"), streams = longArrayOf(-2, -1, -2))
                        val output = StringBuilder()
                        while (true) { val chunk = read(environment.output, 128); if (chunk.isEmpty()) break; output.append(chunk) }
                        assertEquals("child:${environment.pid}:cwd-data", output.toString())
                        assertEquals("0 0 0", status("processWait", environment.pid))
                        close(environment)
                    }
                    exercise()
                    for (target in targets.values) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                    }
                    // Restore the pinned runtime's shared host entry prerequisite
                    // without executing a settling call or repeating effects.
                    val runtime = Truffle.getRuntime()
                    val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    runtime.javaClass.getMethod("bypassedInstalledCode", targetClass)
                        .invoke(runtime, targets.getValue("processCreate"))
                    installed = true
                    exercise()
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
    }
}
