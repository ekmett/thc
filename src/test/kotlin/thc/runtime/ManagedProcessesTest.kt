// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(30)
class ManagedProcessesTest {
    @TempDir lateinit var directory: Path
    private val oracle = Path.of(System.getProperty("thc.projectRoot"), "build/process-lifecycle/native/process-oracle")
    private fun args(vararg arguments: String) = arguments.map { it.toByteArray() }
    private fun childArgs(vararg arguments: String) = args(oracle.toString(), *arguments)
    private fun context(processes: Boolean = true): Context = Context.newBuilder("thc")
        .allowNativeAccess(true).allowCreateProcess(processes).allowIO(IOAccess.ALL).build()
    private fun <T> entered(context: Context, action: () -> T): T {
        context.initialize("thc"); context.enter()
        return try { action() } finally { context.leave() }
    }
    private fun <T> service(action: (ManagedProcesses, Context, NativeDirectoryOwner) -> T): T =
        NativeDirectoryOwner(directory).use { owner -> context().use { context ->
            val processes = entered(context) { ManagedProcesses(owner) }
            entered(context) { action(processes, context, owner) }
        } }
    private fun line(result: ProcessResult) = "${result.status} ${result.exitCode ?: 991} ${result.errno}"
    private fun launchHeld(service: ManagedProcesses): ManagedProcesses.Launch {
        val child = service.spawn(childArgs("hold"), emptyList(), input = ManagedProcesses.Stream.Pipe,
            output = ManagedProcesses.Stream.Pipe)
        assertEquals("R", pipeRead(child.output!!, 1))
        return child
    }

    @Test fun automaticReapingPoliciesAreRejectedBeforeSpawnInIsolatedNativeProcesses() {
        val control = oracle.resolveSibling("sigchld-policy")
        for (policy in listOf("default", "ignore", "no-cld-wait", "handler-no-cld-wait")) {
            val native = ProcessBuilder(control.toString(), policy).redirectErrorStream(true).start()
            try {
                assertTrue(native.waitFor(10, TimeUnit.SECONDS), policy)
                val output = native.inputStream.bufferedReader().readText()
                assertEquals(0, native.exitValue(), "$policy: $output")
                val expected = if (policy == "default") "baseline=23 transport=0 spawns=1 exit=23"
                    else "baseline=ECHILD transport=ENOTSUP spawns=0"
                assertEquals("$policy $expected\n", output)
            } finally {
                if (native.isAlive) { native.destroyForcibly(); native.waitFor(5, TimeUnit.SECONDS) }
            }
        }
    }

    @Test fun rawLifecycleAndReapingMatchTheOriginalNativeProcessPackage() = service { processes, _, _ ->
        val native = ProcessBuilder(oracle.toString(), "oracle").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val expected = native.inputStream.bufferedReader().readLines().associate { line ->
            val split = line.indexOf(' ')
            line.substring(0, split) to line.substring(split + 1)
        }
        assertTrue(native.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, native.exitValue())
        assertEquals(10, expected.size)
        fun same(name: String, result: ProcessResult) = assertEquals(expected.getValue(name), line(result), name)
        val first = launchHeld(processes)
        same("running", processes.poll(first.handle))
        pipeWrite(first.input!!, "x")
        same("wait", processes.waitFor(first.handle))
        same("poll-after-wait", processes.poll(first.handle))
        same("wait-after-wait", processes.waitFor(first.handle))
        val second = launchHeld(processes)
        same("terminate", processes.terminate(second.handle))
        same("terminated-wait", processes.waitFor(second.handle))
        val third = processes.spawn(childArgs("exit", "17"), emptyList(), output = ManagedProcesses.Stream.Pipe)
        assertEquals("", pipeRead(third.output!!, 1))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var result = processes.poll(third.handle)
        while (result.status == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1); result = processes.poll(third.handle)
        }
        same("poll-exit", result)
        same("poll-after-poll", processes.poll(third.handle))
        same("wait-after-poll", processes.waitFor(third.handle))
        assertEquals(ProcessResult(0, null, 3), processes.terminate(third.handle), "Retired identity cannot signal a reused PID")
        val searched = processes.spawn(args("true"), args("PATH=/missing-child-path"), searchPath = "/bin".toByteArray())
        same("parent-path", processes.waitFor(searched.handle))
    }

    @Test fun pipesEnvironmentAndContextDirectoryReachTheRealChild() = service { processes, _, owner ->
        val pipes = processes.spawn(childArgs("pipes"), emptyList(), input = ManagedProcesses.Stream.Pipe,
            output = ManagedProcesses.Stream.Pipe, error = ManagedProcesses.Stream.Pipe)
        pipeWrite(pipes.input!!, "hello\n")
        assertEquals("out:hello\n", pipeRead(pipes.output!!, 1024))
        assertEquals("err:hello\n", pipeRead(pipes.error!!, 1024))
        assertEquals(ProcessResult(0, 0, 0), processes.waitFor(pipes.handle))

        val environment = processes.spawn(childArgs("environment"), args("THC_CHILD_VALUE=context-only"),
            output = ManagedProcesses.Stream.Pipe)
        assertEquals("context-only\n", pipeRead(environment.output!!, 1024))
        assertEquals(0, processes.waitFor(environment.handle).exitCode)
        assertNotEquals("context-only", System.getenv("THC_CHILD_VALUE"))

        val old = Files.createDirectory(directory.resolve("before"))
        Files.writeString(old.resolve("value"), "anchored")
        owner.change(old)
        val moved = directory.resolve("after")
        Files.move(old, moved)
        Files.createDirectory(old)
        Files.writeString(old.resolve("value"), "wrong")
        val shell = processes.spawn(args("/bin/sh", "-c", "cat value"), args("PATH=/usr/bin:/bin"),
            output = ManagedProcesses.Stream.Pipe)
        assertEquals("anchored", pipeRead(shell.output!!, 1024))
        assertEquals(0, processes.waitFor(shell.handle).exitCode)
        // The parent's explicit search PATH uses the child cwd.
        Files.createSymbolicLink(moved.resolve("own-command"), oracle)
        val searched = processes.spawn(args("own-command", "exit", "19"), args("PATH=/missing-child-path"), searchPath = ".".toByteArray())
        assertEquals(19, processes.waitFor(searched.handle).exitCode)
    }

    @Test fun failedCreationAndUnsupportedOptionsHaveNoChildOrPipeLeak() = service { processes, _, _ ->
        val before = fdCount()
        repeat(12) {
            val failure = assertThrows(ProcessSpawnException::class.java) {
                processes.spawn(args("/definitely-missing-thc-command"), emptyList(),
                    input = ManagedProcesses.Stream.Pipe, output = ManagedProcesses.Stream.Pipe, error = ManagedProcesses.Stream.Pipe)
            }
            assertEquals(2, failure.errno)
            assertEquals(ProcessFailureStage.SPAWN, failure.stage)
            val cwd = assertThrows(ProcessSpawnException::class.java) {
                processes.spawn(childArgs("exit", "0"), emptyList(), cwd = "missing".toByteArray())
            }
            assertEquals(2, cwd.errno)
            assertEquals(ProcessFailureStage.SPAWN, cwd.stage)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            processes.spawn(childArgs("exit", "0"), emptyList(), childUser = 0)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            processes.spawn(childArgs("exit", "0"), emptyList(), flags = 0x4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            processes.spawn(listOf(byteArrayOf(47, 0, 98)), emptyList())
        }
        assertEquals(before, fdCount())
    }

    @Test fun creationFailureStagesMatchOriginalNativeImports() = service { processes, _, _ ->
        val native = ProcessBuilder(oracle.toString(), "creation-oracle").redirectErrorStream(true).start()
        try {
            assertTrue(native.waitFor(10, TimeUnit.SECONDS))
            val observed = native.inputStream.bufferedReader().readLines()
            assertEquals(0, native.exitValue(), observed.toString())
            assertEquals(listOf("missing-command -1 2 posix_spawnp 991 991 991",
                "missing-cwd -1 2 posix_spawnp 991 991 991", "create-success positive 0 null 991 991 991"), observed)
            for ((name, command, cwd) in listOf(Triple("missing-command", "/definitely-missing-thc-command", null),
                Triple("missing-cwd", "/bin/true", "/definitely-missing-thc-directory"))) {
                val failed = assertThrows(ProcessSpawnException::class.java) {
                    processes.spawn(args(command), emptyList(), cwd = cwd?.toByteArray())
                }
                val fields = observed.first { it.startsWith(name) }.split(' ')
                assertEquals(fields[2].toInt(), failed.errno)
                assertEquals(fields[3], failed.stage.operation)
            }
        } finally {
            if (native.isAlive) { native.destroyForcibly(); native.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun processPermissionAndHandleOwnershipAreIndependentOfNativeAccess() {
        NativeDirectoryOwner(directory).use { owner ->
            context(false).use { denied -> entered(denied) {
                assertThrows(SecurityException::class.java) { ManagedProcesses(owner) }
            } }
            context().use { first -> context().use { second ->
                val one = entered(first) { ManagedProcesses(owner) }
                val two = entered(second) { ManagedProcesses(owner) }
                val launched = entered(first) { launchHeld(one) }
                entered(first) { assertTrue(ProcessHandle.of(one.processId(launched.handle).toLong()).orElseThrow().isAlive) }
                entered(second) {
                    assertThrows(RuntimeFault::class.java) { one.poll(launched.handle) }
                    assertThrows(RuntimeFault::class.java) { two.poll(launched.handle) }
                    assertThrows(RuntimeFault::class.java) { two.terminate(launched.handle) }
                    assertThrows(RuntimeFault::class.java) { two.processId(launched.handle) }
                }
                entered(first) {
                    assertEquals(0, one.poll(launched.handle).status)
                    assertEquals(1, one.terminate(launched.handle).status)
                    assertEquals(-15, one.waitFor(launched.handle).exitCode)
                }
            } }
        }
    }

    @Test fun unpublishedRollbackClosesOnlyItsOwnChildAndNumericIdsKeepTombstones() = service { processes, _, _ ->
        val child = launchHeld(processes)
        val sibling = launchHeld(processes)
        val pid = processes.publishProcessId(child.handle)
        assertEquals(processes.processId(child.handle), pid)
        assertSame(child.handle, processes.fromProcessId(pid))
        val native = ProcessHandle.of(pid.toLong()).orElseThrow()
        processes.abortUnpublished(child.handle)
        assertFalse(native.isAlive)
        assertThrows(ClosedChannelException::class.java) { child.input!!.duplicate() }
        assertThrows(ClosedChannelException::class.java) { child.output!!.duplicate() }
        assertSame(child.handle, processes.fromProcessId(pid), "Rollback must not release a reserved numeric identity")
        assertEquals(0, processes.poll(sibling.handle).status)
        val siblingPid = processes.publishProcessId(sibling.handle)
        pipeWrite(sibling.input!!, "x")
        assertEquals(23, processes.waitFor(sibling.handle).exitCode)
        assertSame(sibling.handle, processes.fromProcessId(siblingPid))
        assertEquals(ProcessResult(1, 0, 10), processes.poll(processes.fromProcessId(siblingPid)))
        assertEquals(ProcessResult(0, null, 3), processes.terminate(processes.fromProcessId(siblingPid)))
    }

    @Test fun forcedNumericCollisionReapsNewExactHandleWithoutReplacingRetainedIdentity() = service { processes, _, _ ->
        val old = processes.spawn(childArgs("exit", "7"), emptyList())
        processes.publishProcessId(old.handle)
        assertEquals(7, processes.waitFor(old.handle).exitCode)
        val fresh = launchHeld(processes)
        val pid = processes.processId(fresh.handle)
        val native = ProcessHandle.of(pid.toLong()).orElseThrow()
        // Exercise the collision branch deterministically without pretending to
        // force Linux PID reuse or changing either child's actual native PID.
        val ids = ManagedProcesses::class.java.getDeclaredField("processIds").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val retained = ids.get(processes) as MutableMap<Int, ManagedProcesses.Handle>
        retained[pid] = old.handle
        val failure = assertThrows(NativeFileException::class.java) { processes.publishProcessId(fresh.handle) }
        assertEquals(11, failure.errno)
        assertFalse(native.isAlive, "Collision cleanup targets the new owned pidfd")
        assertSame(old.handle, processes.fromProcessId(pid))
        assertEquals(ProcessResult(1, 0, 10), processes.poll(old.handle))
        assertThrows(ClosedChannelException::class.java) { fresh.input!!.duplicate() }
        Unit
    }

    @Test fun waitCancellationDoesNotReapOrReplayAndASecondWaitCanFinish() {
        NativeDirectoryOwner(directory).use { owner -> context().use { context ->
            val processes = entered(context) { ManagedProcesses(owner) }
            val child = entered(context) { launchHeld(processes) }
            class Cancelled : RuntimeException()
            entered(context) {
                assertThrows(Cancelled::class.java) {
                    processes.waitFor(child.handle, beforeBlock = { throw Cancelled() })
                }
                assertEquals(ProcessResult(0, 0, 0), processes.poll(child.handle))
                pipeWrite(child.input!!, "x")
                assertEquals(ProcessResult(0, 23, 0), processes.waitFor(child.handle))
                assertEquals(ProcessResult(-1, null, 10), processes.waitFor(child.handle))
            }
        } }
    }

    @Test fun closingARegistryWakesBlockedWaitAndReapsOnlyItsChild() {
        val pool = Executors.newSingleThreadExecutor()
        try { NativeDirectoryOwner(directory).use { owner -> context().use { context ->
            val processes = entered(context) { ManagedProcesses(owner) }
            val independent = entered(context) { ManagedProcesses(owner) }
            val child = entered(context) { launchHeld(processes) }
            val sibling = entered(context) { launchHeld(independent) }
            val osChild = entered(context) { ProcessHandle.of(processes.processId(child.handle).toLong()).orElseThrow() }
            val blocked = CountDownLatch(1)
            val root = entered(context) {
                object : RootNode(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) {
                    override fun execute(frame: VirtualFrame): Any = processes.waitFor(child.handle, this) { blocked.countDown() }
                }.callTarget
            }
            val waiting = pool.submit<Throwable?> { try { entered(context) { root.call() }; null } catch (error: Throwable) { error } }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            processes.close()
            assertFalse(osChild.isAlive, "Disposal kills and reaps the owned native child")
            assertInstanceOf(ClosedChannelException::class.java, waiting.get(5, TimeUnit.SECONDS))
            entered(context) {
                assertEquals(0, independent.poll(sibling.handle).status)
                pipeWrite(sibling.input!!, "x")
                assertEquals(23, independent.waitFor(sibling.handle).exitCode)
            }
            assertThrows(ClosedChannelException::class.java) { child.output!!.duplicate() }
        } } } finally { pool.shutdownNow() }
    }

    @Test fun reapedHandlesRetainIdentityWithoutAccumulatingNativeDescriptors() = service { processes, _, _ ->
        val before = fdCount()
        repeat(32) {
            val child = processes.spawn(childArgs("exit", "17"), emptyList())
            val pid = processes.processId(child.handle)
            assertEquals(ProcessResult(0, 17, 0), processes.waitFor(child.handle))
            assertEquals(pid, processes.processId(child.handle))
            assertEquals(ProcessResult(1, 0, 10), processes.poll(child.handle))
            assertEquals(ProcessResult(-1, null, 10), processes.waitFor(child.handle))
            assertEquals(ProcessResult(0, null, 3), processes.terminate(child.handle))
        }
        assertEquals(before, fdCount())
    }

    @Test fun hardContextCancellationCleansBlockedWaitAndOwnedDescriptors() {
        val pool = Executors.newSingleThreadExecutor()
        NativeDirectoryOwner(directory).use { owner ->
            val context = context()
            try {
                val processes = entered(context) { ManagedProcesses(owner) }
                val child = entered(context) { launchHeld(processes) }
                val blocked = CountDownLatch(1)
                val root = entered(context) {
                    object : RootNode(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) {
                        override fun execute(frame: VirtualFrame): Any = processes.waitFor(child.handle, this) { blocked.countDown() }
                    }.callTarget
                }
                val waiting = pool.submit<Throwable?> { try { entered(context) { root.call() }; null } catch (error: Throwable) { error } }
                assertTrue(blocked.await(5, TimeUnit.SECONDS))
                context.close(true)
                assertNotNull(waiting.get(5, TimeUnit.SECONDS))
                assertThrows(ClosedChannelException::class.java) { child.input!!.duplicate() }
                assertThrows(ClosedChannelException::class.java) { child.output!!.duplicate() }
            } finally { context.close(true); pool.shutdownNow() }
        }
    }

    private fun fdCount() = Files.list(Path.of("/proc/self/fd")).use { it.count() }
    private fun pipeRead(pipe: ManagedProcesses.Pipe, maximum: Int): String {
        val fd = pipe.duplicate()
        return try { Arena.ofConfined().use { arena ->
            val poll = arena.allocate(8, 4)
            poll.set(ValueLayout.JAVA_INT, 0, fd); poll.set(ValueLayout.JAVA_SHORT, 4, 1)
            assertEquals(1, NativePollApi.poll(poll, 5000, 1))
            val bytes = arena.allocate(maximum.toLong())
            val size = ReadWrite.read.invokeExact(fd, bytes, maximum.toLong()) as Long
            assertTrue(size in 0..maximum.toLong())
            String(bytes.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE))
        } } finally { NativePollApi.close(fd) }
    }
    private fun pipeWrite(pipe: ManagedProcesses.Pipe, value: String) {
        val fd = pipe.duplicate()
        try { Arena.ofConfined().use { arena ->
            val input = value.toByteArray()
            val bytes = arena.allocate(input.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(input)) }
            assertEquals(input.size.toLong(), ReadWrite.write.invokeExact(fd, bytes, input.size.toLong()) as Long)
        } } finally { NativePollApi.close(fd) }
    }
    private object ReadWrite {
        private val linker = Linker.nativeLinker()
        private val signature = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        val read = linker.downcallHandle(linker.defaultLookup().find("read").orElseThrow(), signature)
        val write = linker.downcallHandle(linker.defaultLookup().find("write").orElseThrow(), signature)
    }
}
