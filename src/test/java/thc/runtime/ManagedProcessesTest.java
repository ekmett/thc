// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ManagedProcesses.Stream.Endpoint.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(30)
public class ManagedProcessesTest {
    @TempDir Path directory;
    private final Path oracle = Path.of(System.getProperty("thc.projectRoot"), "build/process-lifecycle/native/process-oracle");
    private static List<byte[]> args(String... arguments) {
        var result = new ArrayList<byte[]>();
        for (var argument : arguments) result.add(argument.getBytes(StandardCharsets.UTF_8));
        return result;
    }
    private List<byte[]> childArgs(String... arguments) {
        var result = args(oracle.toString()); result.addAll(args(arguments)); return result;
    }
    private Context context() { return context(true); }
    private Context context(boolean processes) {
        return Context.newBuilder("thc").allowNativeAccess(true).allowCreateProcess(processes).allowIO(IOAccess.ALL).build();
    }
    @FunctionalInterface private interface Action<T> { T run() throws Throwable; }
    @FunctionalInterface private interface Service { void run(ManagedProcesses processes, Context context, NativeDirectoryOwner owner) throws Throwable; }
    private static <T> T entered(Context context, Action<T> action) throws Throwable {
        context.initialize("thc"); context.enter();
        try { return action.run(); } finally { context.leave(); }
    }
    private void service(Service action) throws Throwable {
        try (var owner = new NativeDirectoryOwner(directory); var context = context()) {
            var processes = entered(context, () -> new ManagedProcesses(owner));
            entered(context, () -> { action.run(processes, context, owner); return null; });
        }
    }
    private static String line(ProcessResult result) { return result.getStatus() + " " + (result.getExitCode() == null ? 991 : result.getExitCode()) + " " + result.getErrno(); }
    private ManagedProcesses.Launch launchHeld(ManagedProcesses service) throws Throwable {
        var child = service.spawn(childArgs("hold"), List.of(), null, PIPE, PIPE, CLOSED, 0, null, null, null);
        assertEquals("R", pipeRead(child.getOutput(), 1)); return child;
    }
    @Test void automaticReapingPoliciesAreRejectedBeforeSpawnInIsolatedNativeProcesses() throws Exception {
        var control = oracle.resolveSibling("sigchld-policy");
        for (var policy : List.of("default", "ignore", "no-cld-wait", "handler-no-cld-wait")) {
            var process = new ProcessBuilder(control.toString(), policy).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), policy);
                var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), policy + ": " + output);
                var expected = policy.equals("default") ? "baseline=23 transport=0 spawns=1 exit=23" : "baseline=ECHILD transport=ENOTSUP spawns=0";
                assertEquals(policy + " " + expected + "\n", output);
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        }
    }
    @Test void rawLifecycleAndReapingMatchTheOriginalNativeProcessPackage() throws Throwable {
        service((processes, context, owner) -> {
            var process = new ProcessBuilder(oracle.toString(), "oracle").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            var expected = new LinkedHashMap<String, String>();
            for (var value : readLines(process)) {
                int split = value.indexOf(' '); expected.put(value.substring(0, split), value.substring(split + 1));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); assertEquals(10, expected.size());
            var first = launchHeld(processes);
            same(expected, "running", processes.poll(first.getHandle())); pipeWrite(first.getInput(), "x");
            same(expected, "wait", processes.waitFor(first.getHandle()));
            same(expected, "poll-after-wait", processes.poll(first.getHandle())); same(expected, "wait-after-wait", processes.waitFor(first.getHandle()));
            var second = launchHeld(processes);
            same(expected, "terminate", processes.terminate(second.getHandle())); same(expected, "terminated-wait", processes.waitFor(second.getHandle()));
            var third = processes.spawn(childArgs("exit", "17"), List.of(), null, CLOSED, PIPE, CLOSED, 0, null, null, null);
            assertEquals("", pipeRead(third.getOutput(), 1)); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            var result = processes.poll(third.getHandle());
            while (result.getStatus() == 0 && System.nanoTime() < deadline) { Thread.sleep(1); result = processes.poll(third.getHandle()); }
            same(expected, "poll-exit", result); same(expected, "poll-after-poll", processes.poll(third.getHandle())); same(expected, "wait-after-poll", processes.waitFor(third.getHandle()));
            assertEquals(new ProcessResult(0, null, 3), processes.terminate(third.getHandle()), "Retired identity cannot signal a reused PID");
            var searched = processes.spawn(args("true"), args("PATH=/missing-child-path"), null, CLOSED, CLOSED, CLOSED, 0, null, null, "/bin".getBytes(StandardCharsets.UTF_8));
            same(expected, "parent-path", processes.waitFor(searched.getHandle()));
        });
    }
    private static void same(Map<String, String> expected, String name, ProcessResult result) { assertEquals(Objects.requireNonNull(expected.get(name)), line(result), name); }
    @Test void pipesEnvironmentAndContextDirectoryReachTheRealChild() throws Throwable {
        service((processes, context, owner) -> {
            var pipes = processes.spawn(childArgs("pipes"), List.of(), null, PIPE, PIPE, PIPE, 0, null, null, null);
            pipeWrite(pipes.getInput(), "hello\n"); assertEquals("out:hello\n", pipeRead(pipes.getOutput(), 1024)); assertEquals("err:hello\n", pipeRead(pipes.getError(), 1024));
            assertEquals(new ProcessResult(0, 0, 0), processes.waitFor(pipes.getHandle()));
            var environment = processes.spawn(childArgs("environment"), args("THC_CHILD_VALUE=context-only"), null, CLOSED, PIPE, CLOSED, 0, null, null, null);
            assertEquals("context-only\n", pipeRead(environment.getOutput(), 1024)); assertEquals(0, processes.waitFor(environment.getHandle()).getExitCode());
            assertNotEquals("context-only", System.getenv("THC_CHILD_VALUE"));
            var old = Files.createDirectory(directory.resolve("before")); Files.writeString(old.resolve("value"), "anchored"); owner.change(old);
            var moved = directory.resolve("after"); Files.move(old, moved); Files.createDirectory(old); Files.writeString(old.resolve("value"), "wrong");
            var shell = processes.spawn(args("/bin/sh", "-c", "cat value"), args("PATH=/usr/bin:/bin"), null, CLOSED, PIPE, CLOSED, 0, null, null, null);
            assertEquals("anchored", pipeRead(shell.getOutput(), 1024)); assertEquals(0, processes.waitFor(shell.getHandle()).getExitCode());
            Files.createSymbolicLink(moved.resolve("own-command"), oracle);
            var searched = processes.spawn(args("own-command", "exit", "19"), args("PATH=/missing-child-path"), null, CLOSED, CLOSED, CLOSED, 0, null, null, ".".getBytes(StandardCharsets.UTF_8));
            assertEquals(19, processes.waitFor(searched.getHandle()).getExitCode());
        });
    }
    @Test void failedCreationAndUnsupportedOptionsHaveNoChildOrPipeLeak() throws Throwable {
        service((processes, context, owner) -> {
            long before = fdCount();
            for (int i = 0; i < 12; i++) {
                var failure = assertThrows(ProcessSpawnException.class, () -> processes.spawn(args("/definitely-missing-thc-command"), List.of(), null, PIPE, PIPE, PIPE, 0, null, null, null));
                assertEquals(2, failure.getErrno()); assertEquals(ProcessFailureStage.SPAWN, failure.getStage());
                var cwd = assertThrows(ProcessSpawnException.class, () -> processes.spawn(childArgs("exit", "0"), List.of(), "missing".getBytes(StandardCharsets.UTF_8), CLOSED, CLOSED, CLOSED, 0, null, null, null));
                assertEquals(2, cwd.getErrno()); assertEquals(ProcessFailureStage.SPAWN, cwd.getStage());
            }
            assertThrows(UnsupportedOperationException.class, () -> processes.spawn(childArgs("exit", "0"), List.of(), null, CLOSED, CLOSED, CLOSED, 0, null, 0L, null));
            assertThrows(UnsupportedOperationException.class, () -> processes.spawn(childArgs("exit", "0"), List.of(), null, CLOSED, CLOSED, CLOSED, 0x4, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> processes.spawn(List.of(new byte[]{47, 0, 98}), List.of())); assertEquals(before, fdCount());
        });
    }
    @Test void nativeInheritedDescriptorCyclesPreserveBothSources() throws Throwable {
        var firstPath = directory.resolve("first"); var secondPath = directory.resolve("second");
        Files.write(firstPath, new byte[]{42}); Files.write(secondPath, new byte[]{73});
        try (var owner = new NativeDirectoryOwner(directory);
             var context = NativeFileProvider.createContext(Set.of(), thc.ContextProfile.NATIVE, thc.FfiMode.NATIVE, true)) {
            entered(context, () -> {
                var provider = NativeFileProvider.current();
                try (var first = provider.open(firstPath.toString(), 0); var second = provider.open(secondPath.toString(), 0);
                     var anchor = owner.borrow(); var arena = Arena.ofConfined()) {
                    int a = first.duplicateDescriptor(), b = -1;
                    var result = arena.allocate(24, 4); result.fill((byte) -1);
                    try {
                        b = second.duplicateDescriptor();
                        int[] targets = {Math.min(a, b), Math.max(a, b)}, sources = {Math.max(a, b), Math.min(a, b)};
                        assertEquals(0, NativeProcessApi.spawn(childArgs("descriptors", Integer.toString(a), Integer.toString(b)),
                            List.of(), anchor.getDescriptor(), null, new int[]{-2, -1, -2}, targets, sources, 0, null, result));
                        int pidfd = result.get(ValueLayout.JAVA_INT, 4);
                        var poll = arena.allocate(8, 4); poll.set(ValueLayout.JAVA_INT, 0, pidfd); poll.set(ValueLayout.JAVA_SHORT, 4, (short) 1);
                        assertEquals(1, NativePollApi.poll(poll, 5000, 1));
                        assertEquals(new ProcessResult(1, 0, 0), NativeProcessApi.poll(pidfd));
                        var output = arena.allocate(32);
                        long size = (long) ReadWrite.read.invokeExact(result.get(ValueLayout.JAVA_INT, 12), output, 32L);
                        assertTrue(size >= 0 && size <= 32);
                        assertEquals("[73,42]\n", new String(output.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
                    } finally {
                        try { disposeSpawn(result); }
                        finally { NativePollApi.close(a); if (b >= 0) NativePollApi.close(b); }
                    }
                }
                return null;
            });
        }
    }
    @Test void impossibleNativeInheritanceTargetsReleaseEveryStagedOwner() throws Throwable {
        try (var owner = new NativeDirectoryOwner(directory); var anchor = owner.borrow(); var arena = Arena.ofConfined()) {
            var limit = arena.allocate(16, 8);
            assertEquals(0, (int) ReadWrite.getrlimit.invokeExact(7, limit)); // Linux RLIMIT_NOFILE.
            long soft = limit.get(ValueLayout.JAVA_LONG, 0);
            org.junit.jupiter.api.Assumptions.assumeTrue(soft > 3 && soft < Integer.MAX_VALUE, "finite CInt descriptor limit");
            var result = arena.allocate(24, 4); result.fill((byte) -1);
            try {
                // Resolve the native API before counting descriptor owners.
                assertEquals(9, NativeProcessApi.spawn(childArgs("exit", "99"), List.of(), anchor.getDescriptor(), null,
                    new int[]{-1, -1, -1}, new int[]{(int) soft}, new int[]{anchor.getDescriptor()}, 0, null, result));
                long before = fdCount();
                for (int target : new int[]{(int) soft, (int) soft - 1}) {
                    assertEquals(target == soft ? 9 : 22, NativeProcessApi.spawn(childArgs("exit", "99"), List.of(),
                        anchor.getDescriptor(), null, new int[]{-1, -1, -1}, new int[]{target},
                        new int[]{anchor.getDescriptor()}, 0, null, result));
                    for (int index = 0; index < 5; index++) assertEquals(-1, result.getAtIndex(ValueLayout.JAVA_INT, index));
                    assertEquals(before, fdCount(), "Rejected target must not retain a staged native descriptor");
                }
            } finally { disposeSpawn(result); }
        }
    }
    private static void disposeSpawn(MemorySegment result) {
        int pidfd = result.get(ValueLayout.JAVA_INT, 4);
        try { if (pidfd >= 0) NativeProcessApi.dispose(pidfd); }
        finally { for (int index = 1; index < 5; index++) {
            int fd = result.getAtIndex(ValueLayout.JAVA_INT, index); if (fd >= 0) NativePollApi.close(fd);
        } }
    }
    @Test void creationFailureStagesMatchOriginalNativeImports() throws Throwable {
        service((processes, context, owner) -> {
            var process = new ProcessBuilder(oracle.toString(), "creation-oracle").redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS)); var observed = readLines(process);
                assertEquals(0, process.exitValue(), observed.toString());
                assertEquals(List.of("missing-command -1 2 posix_spawnp 991 991 991", "missing-cwd -1 2 posix_spawnp 991 991 991", "create-success positive 0 null 991 991 991"), observed);
                for (var row : new String[][]{{"missing-command", "/definitely-missing-thc-command", null}, {"missing-cwd", "/bin/true", "/definitely-missing-thc-directory"}}) {
                    var failed = assertThrows(ProcessSpawnException.class, () -> processes.spawn(args(row[1]), List.of(), row[2] == null ? null : row[2].getBytes(StandardCharsets.UTF_8), CLOSED, CLOSED, CLOSED, 0, null, null, null));
                    String found = null; for (var value : observed) if (value.startsWith(row[0])) { found = value; break; }
                    var fields = Objects.requireNonNull(found).split(" "); assertEquals(Integer.parseInt(fields[2]), failed.getErrno()); assertEquals(fields[3], failed.getStage().getOperation());
                }
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        });
    }
    @Test void processPermissionAndHandleOwnershipAreIndependentOfNativeAccess() throws Throwable {
        try (var owner = new NativeDirectoryOwner(directory)) {
            try (var denied = context(false)) { entered(denied, () -> assertThrows(SecurityException.class, () -> new ManagedProcesses(owner))); }
            try (var first = context(); var second = context()) {
                var one = entered(first, () -> new ManagedProcesses(owner)); var two = entered(second, () -> new ManagedProcesses(owner));
                var launched = entered(first, () -> launchHeld(one));
                entered(first, () -> { assertTrue(ProcessHandle.of(one.processId(launched.getHandle())).orElseThrow().isAlive()); return null; });
                entered(second, () -> {
                    assertThrows(RuntimeFault.class, () -> one.poll(launched.getHandle())); assertThrows(RuntimeFault.class, () -> two.poll(launched.getHandle()));
                    assertThrows(RuntimeFault.class, () -> two.terminate(launched.getHandle())); assertThrows(RuntimeFault.class, () -> two.processId(launched.getHandle())); return null;
                });
                entered(first, () -> { assertEquals(0, one.poll(launched.getHandle()).getStatus()); assertEquals(1, one.terminate(launched.getHandle()).getStatus()); assertEquals(-15, one.waitFor(launched.getHandle()).getExitCode()); return null; });
            }
        }
    }
    @Test void unpublishedRollbackClosesOnlyItsOwnChildAndNumericIdsKeepTombstones() throws Throwable {
        service((processes, context, owner) -> {
            var child = launchHeld(processes); var sibling = launchHeld(processes); int pid = processes.publishProcessId(child.getHandle());
            assertEquals(processes.processId(child.getHandle()), pid); assertSame(child.getHandle(), processes.fromProcessId(pid));
            var process = ProcessHandle.of(pid).orElseThrow(); processes.abortUnpublished(child.getHandle()); assertFalse(process.isAlive());
            assertThrows(ClosedChannelException.class, () -> child.getInput().duplicate()); assertThrows(ClosedChannelException.class, () -> child.getOutput().duplicate());
            assertSame(child.getHandle(), processes.fromProcessId(pid), "Rollback must not release a reserved numeric identity"); assertEquals(0, processes.poll(sibling.getHandle()).getStatus());
            int siblingPid = processes.publishProcessId(sibling.getHandle()); pipeWrite(sibling.getInput(), "x"); assertEquals(23, processes.waitFor(sibling.getHandle()).getExitCode());
            assertSame(sibling.getHandle(), processes.fromProcessId(siblingPid)); assertEquals(new ProcessResult(1, 0, 10), processes.poll(processes.fromProcessId(siblingPid)));
            assertEquals(new ProcessResult(0, null, 3), processes.terminate(processes.fromProcessId(siblingPid)));
        });
    }
    @Test @SuppressWarnings("unchecked") void forcedNumericCollisionReapsNewExactHandleWithoutReplacingRetainedIdentity() throws Throwable {
        service((processes, context, owner) -> {
            var old = processes.spawn(childArgs("exit", "7"), List.of()); processes.publishProcessId(old.getHandle()); assertEquals(7, processes.waitFor(old.getHandle()).getExitCode());
            var fresh = launchHeld(processes); int pid = processes.processId(fresh.getHandle()); var process = ProcessHandle.of(pid).orElseThrow();
            // Exercise the collision branch without forcing PID reuse or changing either native PID.
            var ids = ManagedProcesses.class.getDeclaredField("processIds"); ids.setAccessible(true);
            var retained = (Map<Integer, ManagedProcesses.Handle>) ids.get(processes); retained.put(pid, old.getHandle());
            var failure = assertThrows(NativeFileException.class, () -> processes.publishProcessId(fresh.getHandle())); assertEquals(11, failure.getErrno());
            assertFalse(process.isAlive(), "Collision cleanup targets the new owned pidfd"); assertSame(old.getHandle(), processes.fromProcessId(pid));
            assertEquals(new ProcessResult(1, 0, 10), processes.poll(old.getHandle())); assertThrows(ClosedChannelException.class, () -> fresh.getInput().duplicate());
        });
    }
    @Test void waitCancellationDoesNotReapOrReplayAndASecondWaitCanFinish() throws Throwable {
        try (var owner = new NativeDirectoryOwner(directory); var context = context()) {
            var processes = entered(context, () -> new ManagedProcesses(owner)); var child = entered(context, () -> launchHeld(processes));
            class Cancelled extends RuntimeException {}
            entered(context, () -> {
                assertThrows(Cancelled.class, () -> processes.waitFor(child.getHandle(), null, () -> { throw new Cancelled(); }));
                assertEquals(new ProcessResult(0, 0, 0), processes.poll(child.getHandle())); pipeWrite(child.getInput(), "x");
                assertEquals(new ProcessResult(0, 23, 0), processes.waitFor(child.getHandle())); assertEquals(new ProcessResult(-1, null, 10), processes.waitFor(child.getHandle())); return null;
            });
        }
    }
    @Test void closingARegistryWakesBlockedWaitAndReapsOnlyItsChild() throws Throwable {
        var pool = Executors.newSingleThreadExecutor();
        try (var owner = new NativeDirectoryOwner(directory); var context = context()) {
            var processes = entered(context, () -> new ManagedProcesses(owner)); var independent = entered(context, () -> new ManagedProcesses(owner));
            var child = entered(context, () -> launchHeld(processes)); var sibling = entered(context, () -> launchHeld(independent));
            var osChild = entered(context, () -> ProcessHandle.of(processes.processId(child.getHandle())).orElseThrow()); var blocked = new CountDownLatch(1);
            var root = entered(context, () -> new RootNode(TruffleLanguage.LanguageReference.create(Language.class).get(null)) {
                @Override public Object execute(VirtualFrame frame) { return processes.waitFor(child.getHandle(), this, blocked::countDown); }
            }.getCallTarget());
            var waiting = pool.submit(() -> { try { entered(context, () -> root.call()); return (Throwable) null; } catch (Throwable error) { return error; } });
            assertTrue(blocked.await(5, TimeUnit.SECONDS)); processes.close(); assertFalse(osChild.isAlive(), "Disposal kills and reaps the owned native child");
            assertInstanceOf(ClosedChannelException.class, waiting.get(5, TimeUnit.SECONDS));
            entered(context, () -> { assertEquals(0, independent.poll(sibling.getHandle()).getStatus()); pipeWrite(sibling.getInput(), "x"); assertEquals(23, independent.waitFor(sibling.getHandle()).getExitCode()); return null; });
            assertThrows(ClosedChannelException.class, () -> child.getOutput().duplicate());
        } finally { pool.shutdownNow(); }
    }
    @Test void reapedHandlesRetainIdentityWithoutAccumulatingNativeDescriptors() throws Throwable {
        service((processes, context, owner) -> {
            long before = fdCount();
            for (int i = 0; i < 32; i++) {
                var child = processes.spawn(childArgs("exit", "17"), List.of()); int pid = processes.processId(child.getHandle());
                assertEquals(new ProcessResult(0, 17, 0), processes.waitFor(child.getHandle())); assertEquals(pid, processes.processId(child.getHandle()));
                assertEquals(new ProcessResult(1, 0, 10), processes.poll(child.getHandle())); assertEquals(new ProcessResult(-1, null, 10), processes.waitFor(child.getHandle()));
                assertEquals(new ProcessResult(0, null, 3), processes.terminate(child.getHandle()));
            }
            assertEquals(before, fdCount());
        });
    }
    @Test void hardContextCancellationCleansBlockedWaitAndOwnedDescriptors() throws Throwable {
        var pool = Executors.newSingleThreadExecutor();
        try (var owner = new NativeDirectoryOwner(directory)) {
            var context = context();
            try {
                var processes = entered(context, () -> new ManagedProcesses(owner)); var child = entered(context, () -> launchHeld(processes)); var blocked = new CountDownLatch(1);
                var root = entered(context, () -> new RootNode(TruffleLanguage.LanguageReference.create(Language.class).get(null)) {
                    @Override public Object execute(VirtualFrame frame) { return processes.waitFor(child.getHandle(), this, blocked::countDown); }
                }.getCallTarget());
                var waiting = pool.submit(() -> { try { entered(context, () -> root.call()); return (Throwable) null; } catch (Throwable error) { return error; } });
                assertTrue(blocked.await(5, TimeUnit.SECONDS)); context.close(true); assertNotNull(waiting.get(5, TimeUnit.SECONDS));
                assertThrows(ClosedChannelException.class, () -> child.getInput().duplicate()); assertThrows(ClosedChannelException.class, () -> child.getOutput().duplicate());
            } finally { context.close(true); pool.shutdownNow(); }
        }
    }
    private static List<String> readLines(Process process) throws Exception {
        var result = new ArrayList<String>(); var reader = process.inputReader(StandardCharsets.UTF_8); String line;
        while ((line = reader.readLine()) != null) result.add(line); return result;
    }
    private static long fdCount() throws Exception { try (var files = Files.list(Path.of("/proc/self/fd"))) { return files.count(); } }
    private static String pipeRead(ManagedProcesses.Pipe pipe, int maximum) throws Throwable {
        int fd = pipe.duplicate();
        try (var arena = Arena.ofConfined()) {
            var poll = arena.allocate(8, 4); poll.set(ValueLayout.JAVA_INT, 0, fd); poll.set(ValueLayout.JAVA_SHORT, 4, (short) 1);
            assertEquals(1, NativePollApi.poll(poll, 5000, 1)); var bytes = arena.allocate((long) maximum);
            long size = (long) ReadWrite.read.invokeExact(fd, bytes, (long) maximum); assertTrue(size >= 0 && size <= maximum);
            return new String(bytes.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        } finally { NativePollApi.close(fd); }
    }
    private static void pipeWrite(ManagedProcesses.Pipe pipe, String value) throws Throwable {
        int fd = pipe.duplicate();
        try (var arena = Arena.ofConfined()) {
            var input = value.getBytes(StandardCharsets.UTF_8); var bytes = arena.allocate((long) input.length); bytes.copyFrom(MemorySegment.ofArray(input));
            assertEquals((long) input.length, (long) ReadWrite.write.invokeExact(fd, bytes, (long) input.length));
        } finally { NativePollApi.close(fd); }
    }
    private static final class ReadWrite {
        private static final Linker linker = Linker.nativeLinker();
        private static final FunctionDescriptor signature = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
        static final MethodHandle getrlimit = linker.downcallHandle(linker.defaultLookup().find("getrlimit").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        static final MethodHandle read = linker.downcallHandle(linker.defaultLookup().find("read").orElseThrow(), signature);
        static final MethodHandle write = linker.downcallHandle(linker.defaultLookup().find("write").orElseThrow(), signature);
    }
}
