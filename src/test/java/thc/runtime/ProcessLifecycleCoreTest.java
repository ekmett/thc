// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.Node;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.*;
import thc.runtime.Unit;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Invokes actual original-package FCalls through both interpreters, not direct provider calls. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(90)
@SuppressWarnings("unchecked")
public class ProcessLifecycleCoreTest {
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    @TempDir Path directory;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path oracle = root.resolve("build/process-lifecycle/native/process-oracle");
    private final String prefix = "build/process-lifecycle/core";
    private static ManagedAddress nil() { return ManagedAddress.nullAddress(); }
    private Map<String, Object> json(String name) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(root.resolve(prefix + "/" + name + ".json"))); }
    private static ManagedAddress cell() { return cell(4); }
    private static ManagedAddress cell(long width) { return Language.currentState().getNativeAllocations().malloc(width); }
    private static ManagedAddress string(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8); var address = cell(bytes.length + 1L);
        for (int i = 0; i < bytes.length; i++) address.writeWord8(i, bytes[i]); address.writeWord8(bytes.length, 0); return address;
    }
    private static ManagedAddress vector(String... values) {
        var array = cell((values.length + 1L) * 8); for (int i = 0; i < values.length; i++) array.writeAddressElementIndex(i, string(values[i]));
        array.writeAddressElementIndex(values.length, nil()); return array;
    }
    private static long integer(ManagedAddress address) { return ManagedAddressRead.INT32.readInt(address, 0); }
    private static String text(ManagedAddress address) {
        var bytes = new byte[(int) address.cStringLength()]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) address.readWord8(i);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private Map<String, String> nativeRows(String mode) throws Exception {
        var process = new ProcessBuilder(oracle.toString(), mode).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Native process oracle timed out"); assertEquals(0, process.exitValue());
            var result = new LinkedHashMap<String, String>(); var reader = process.inputReader(StandardCharsets.UTF_8); String row;
            while ((row = reader.readLine()) != null) { int boundary = row.indexOf(' '); result.put(row.substring(0, boundary), row.substring(boundary + 1)); }
            return result;
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }
    private static boolean waiting(ManagedProcesses processes) throws Exception {
        synchronized (processes) {
            var field = ManagedProcesses.class.getDeclaredField("children"); field.setAccessible(true); var children = (Map<?, ?>) field.get(processes);
            for (var child : children.values()) synchronized (Objects.requireNonNull(child)) {
                var waiters = child.getClass().getDeclaredField("waiters"); waiters.setAccessible(true); if (!((Collection<?>) waiters.get(child)).isEmpty()) return true;
            }
            return false;
        }
    }
    @FunctionalInterface private interface Predicate { boolean test() throws Exception; }
    private static void eventually(String label, Predicate predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.test() && System.nanoTime() < deadline) Thread.sleep(1); assertTrue(predicate.test(), label);
    }
    private static long call(ExecutableProgram program, String name, Object... arguments) {
        var packet = new Object[arguments.length + 1]; packet[0] = 0L; System.arraycopy(arguments, 0, packet, 1, arguments.length);
        return (Long) Calls.target(program.entryTarget(name), packet);
    }
    @ParameterizedTest @CsvSource({"pre, ast", "post, ast", "pre, bytecode", "post, bytecode"})
    void originalInterruptibleWaitSavesErrnoAndNeverReplays(String stage, String backend) throws Throwable {
        try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST, FfiMode.NATIVE, true)) {
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState();
                var module = new LinkedHashMap<>(json(stage)); module.put("instrument", true);
                ExecutableProgram plain = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                ExecutableProgram async = backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module, true);
                var wait = async.entryTarget("processWait");
                for (boolean installed : new boolean[]{false, true}) {
                    if (installed) {
                        wait.getClass().getMethod("compile", boolean.class).invoke(wait, true); assertEquals(true, wait.getClass().getMethod("isValidLastTier").invoke(wait));
                        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, wait);
                    }
                    for (var scenario : List.of("unmasked", "masked", "uninterruptible", "completed")) {
                        var mask = switch (scenario) { case "unmasked" -> MaskingState.UNMASKED; case "masked" -> MaskingState.MASKED_INTERRUPTIBLE; default -> MaskingState.MASKED_UNINTERRUPTIBLE; };
                        var pipes = List.of(cell(), cell(), cell());
                        long pid = call(plain, "processCreate", vector(oracle.toString(), "hold"), nil(), nil(), -1L, -1L, -2L, pipes.get(0), pipes.get(1), pipes.get(2), nil(), nil(), 0L, cell(8));
                        assertTrue(pid > 0); long input = integer(pipes.get(0)), output = integer(pipes.get(1)); var ready = cell();
                        assertEquals(1L, state.getStdio().read(output, ready, 1)); assertEquals((long) 'R', ready.readWord8(0));
                        var destination = cell(); destination.writeNativeScalar(0, 4, 991); var identity = new AtomicLong(); var captured = new AtomicReference<SavedGuestContinuation>();
                        var failure = new AtomicReference<Throwable>(); var done = new CountDownLatch(1); var gateEntered = new CountDownLatch(1); var gateRelease = new CountDownLatch(1);
                        long before = ((Number) async.diagnostics().get("compiledEntries")).longValue();
                        var worker = new Thread(() -> {
                            context.enter(); identity.set(state.getThreads().enterCurrent()); state.getMaskingState().set(mask); state.getStdio().setErrno(73);
                            try {
                                var result = Calls.target(wait, new Object[]{0L, pid, destination}); var continuation = SavedGuestContinuations.savedGuestContinuation(result);
                                if (scenario.equals("uninterruptible")) {
                                    assertNull(continuation); assertEquals(0L, result); assertEquals(73L, state.getStdio().errno()); assertEquals(mask, state.getMaskingState().get());
                                    state.getMaskingState().set(MaskingState.UNMASKED); var request = state.getThreads().poll(new Node() {}, true); assertNotNull(request); request.acknowledge();
                                } else {
                                    assertNotNull(continuation, scenario + " must yield after its completed result"); var request = Objects.requireNonNull(continuation.asyncRequest());
                                    assertEquals("process wait delivery", request.getPayload()); if (installed) assertTrue(request.compiledCapture, scenario + " first installed completion cut");
                                    request.acknowledge(); captured.set(continuation);
                                }
                            } catch (Throwable error) { failure.set(error); }
                            finally { state.getThreads().leaveCurrent(); state.getMaskingState().remove(); context.leave(); done.countDown(); }
                        }); worker.start();
                        try {
                            eventually("Original wait acquired an owned native watch", () -> waiting(NativeFileProvider.current().getProcesses()));
                            if (scenario.equals("completed")) {
                                // Hold FOREIGN at a real wake while /proc observes exit; this action never reaps.
                                state.getEnv().submitThreadLocal(new Thread[]{worker}, new ThreadLocalAction(true, false) {
                                    @Override protected void perform(Access access) {
                                        gateEntered.countDown();
                                        try { if (!gateRelease.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed."); }
                                        catch (InterruptedException error) { throw propagate(error); }
                                        state.getMaskingState().set(MaskingState.UNMASKED);
                                    }
                                }); assertTrue(gateEntered.await(10, TimeUnit.SECONDS));
                            }
                            var request = state.getThreads().send(identity.get(), "process wait delivery");
                            if (scenario.equals("completed") || scenario.equals("uninterruptible")) {
                                if (scenario.equals("uninterruptible")) {
                                    assertFalse(done.await(30, TimeUnit.MILLISECONDS), "MaskedUninterruptible must keep waiting"); assertEquals(AsyncRequestState.PENDING, request.getState());
                                }
                                assertEquals(1L, state.getStdio().write(input, string("x"), 1));
                                if (scenario.equals("completed")) {
                                    eventually("Owned child exited before pending delivery", () -> { var stat = Files.readString(Path.of("/proc/" + pid + "/stat")); return stat.substring(stat.lastIndexOf(") ") + 2).startsWith("Z "); });
                                    assertEquals(AsyncRequestState.PENDING, request.getState(), "FOREIGN never claims delivery"); gateRelease.countDown();
                                }
                            }
                            assertTrue(done.await(10, TimeUnit.SECONDS), scenario + " original wait did not finish"); if (failure.get() != null) throw failure.get();
                            if (installed) { assertEquals(before + 1, ((Number) async.diagnostics().get("compiledEntries")).longValue()); assertEquals(true, wait.getClass().getMethod("isValidLastTier").invoke(wait)); }
                            var continuation = captured.get();
                            if (continuation != null) {
                                state.getThreads().enterCurrent();
                                try { state.getStdio().setErrno(99); assertEquals(scenario.equals("completed") ? 0L : -1L, continuation.continueWith(Unit.INSTANCE)); assertEquals(scenario.equals("completed") ? 73L : 4L, state.getStdio().errno()); }
                                finally { state.getThreads().leaveCurrent(); }
                            }
                            if (scenario.equals("unmasked") || scenario.equals("masked")) {
                                assertEquals(991L, integer(destination), "Interrupted wait cannot publish an exit code"); assertEquals(0L, call(plain, "processPoll", pid, cell()), "Interrupted wait must leave the child alive and owned");
                                assertEquals(1L, state.getStdio().write(input, string("x"), 1)); assertEquals(0L, call(plain, "processWait", pid, destination));
                            } else assertEquals(-1L, call(plain, "processWait", pid, cell()), "Resumption cannot reap a second time");
                            assertEquals(23L, integer(destination)); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        } finally {
                            gateRelease.countDown(); if (worker.isAlive()) call(plain, "processTerminate", pid); worker.join(10000); assertFalse(worker.isAlive()); state.getFiles().close(input); state.getFiles().close(output);
                        }
                    }
                }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @CsvSource({"pre, ast", "post, ast", "pre, bytecode", "post, bytecode"})
    void originalProcessCoreMatchesNativeBeforeAndAfterInstallation(String stage, String backend) throws Exception {
        var manifest = json("manifest"); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(List.of("processCreate", "processPoll", "processWait", "processTerminate"), manifest.get("entries"));
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("inputHashes"), Set.of("test/fixtures/compiler/ProcessLifecycleAudit.hs", "test/haskell-fixtures/ProcessLifecycleFixtures.hs"), null);
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("artifactHashes"), Set.of(prefix + "/pre.json", prefix + "/post.json"), prefix + "/");
        var expected = nativeRows("oracle"); var creation = nativeRows("creation-oracle"); assertEquals(10, expected.size()); assertEquals(3, creation.size());
        try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST, FfiMode.NATIVE, true)) {
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); var module = new LinkedHashMap<>(json(stage)); module.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var targets = new LinkedHashMap<String, RootCallTarget>(); for (var name : List.of("processCreate", "processPoll", "processWait", "processTerminate")) targets.put(name, program.entryTarget(name));
                record Child(long pid, long input, long output, long error) {}
                class Exercise {
                    boolean installed;
                    void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
                    long call(String name, Object... arguments) throws Exception {
                        var target = targets.get(name); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); if (installed) valid(target);
                        var packet = new Object[arguments.length + 1]; packet[0] = 0L; System.arraycopy(arguments, 0, packet, 1, arguments.length); long result = (Long) Calls.target(target, packet);
                        if (installed) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), stage + "/" + backend + "/" + name + " first installed entry"); valid(target); }
                        var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences()); return result;
                    }
                    Child create(ManagedAddress command, ManagedAddress environment, ManagedAddress cwd, long[] streams) throws Exception {
                        var outputs = new ArrayList<ManagedAddress>(); for (int i = 0; i < 3; i++) { var output = cell(); output.writeNativeScalar(0, 4, 991); outputs.add(output); }
                        var failure = cell(8); long pid = call("processCreate", command, cwd, environment, streams[0], streams[1], streams[2], outputs.get(0), outputs.get(1), outputs.get(2), nil(), nil(), 0L, failure);
                        assertTrue(pid > 0, stage + "/" + backend + " creation failed: " + (failure.readAddressElementIndex(0) == nil() ? "null" : text(failure.readAddressElementIndex(0)))); assertSame(nil(), failure.readAddressElementIndex(0));
                        for (int i = 0; i < streams.length; i++) if (streams[i] != -1L) assertEquals(991L, integer(outputs.get(i)));
                        return new Child(pid, streams[0] == -1 ? integer(outputs.get(0)) : -1, streams[1] == -1 ? integer(outputs.get(1)) : -1, streams[2] == -1 ? integer(outputs.get(2)) : -1);
                    }
                    void close(Child child) { for (long fd : new long[]{child.input(), child.output(), child.error()}) if (fd >= 0) assertEquals(0L, state.getFiles().close(fd)); }
                    String read(long fd, int maximum) {
                        var buffer = cell(maximum); long received = state.getStdio().read(fd, buffer, maximum); assertTrue(received >= 0 && received <= maximum);
                        var bytes = new byte[(int) received]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) buffer.readWord8(i); return new String(bytes, StandardCharsets.UTF_8);
                    }
                    void write(long fd, String value) { assertEquals((long) value.getBytes(StandardCharsets.UTF_8).length, state.getStdio().write(fd, string(value), value.getBytes(StandardCharsets.UTF_8).length)); }
                    String status(String name, long pid) throws Exception {
                        var code = cell(); code.writeNativeScalar(0, 4, 991); state.getStdio().captureForeignErrno(0);
                        long result = name.equals("processTerminate") ? call(name, pid) : call(name, pid, code); return result + " " + integer(code) + " " + state.getStdio().errno();
                    }
                    void same(String row, String name, long pid) throws Exception { assertEquals(Objects.requireNonNull(expected.get(row)), status(name, pid), stage + "/" + backend + "/" + row); }
                    Child held() throws Exception { var child = create(vector(oracle.toString(), "hold"), nil(), nil(), new long[]{-1, -1, -2}); assertEquals("R", read(child.output(), 1)); return child; }
                    void exercise() throws Exception {
                        var first = held(); same("running", "processPoll", first.pid()); write(first.input(), "x"); same("wait", "processWait", first.pid());
                        same("poll-after-wait", "processPoll", first.pid()); same("wait-after-wait", "processWait", first.pid()); close(first);
                        var stopped = held(); same("terminate", "processTerminate", stopped.pid()); same("terminated-wait", "processWait", stopped.pid()); close(stopped);
                        var poll = create(vector(oracle.toString(), "exit", "17"), nil(), nil(), new long[]{-2, -1, -2}); assertEquals("", read(poll.output(), 1));
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); var polled = status("processPoll", poll.pid());
                        while (polled.startsWith("0 ") && System.nanoTime() < deadline) polled = status("processPoll", poll.pid()); assertEquals(Objects.requireNonNull(expected.get("poll-exit")), polled);
                        same("poll-after-poll", "processPoll", poll.pid()); same("wait-after-poll", "processWait", poll.pid()); close(poll); state.getEnvironment().put(string("PATH=/bin"));
                        var path = create(vector("true"), vector("PATH=/missing-child-path"), nil(), new long[]{-2, -2, -2}); same("parent-path", "processWait", path.pid());
                        record Failure(String name, String command, ManagedAddress cwd) {}
                        for (var row : List.of(new Failure("missing-command", "/definitely-missing-thc-command", nil()), new Failure("missing-cwd", "/bin/true", string("/definitely-missing-thc-directory")))) {
                            var outputs = new ArrayList<ManagedAddress>(); for (int i = 0; i < 3; i++) { var output = cell(); output.writeNativeScalar(0, 4, 991); outputs.add(output); }
                            var failure = cell(8); state.getStdio().captureForeignErrno(0);
                            long result = call("processCreate", vector(row.command()), row.cwd(), nil(), -2L, -2L, -2L, outputs.get(0), outputs.get(1), outputs.get(2), nil(), nil(), 0L, failure);
                            var codes = new StringJoiner(" "); for (var output : outputs) codes.add(Long.toString(integer(output)));
                            assertEquals(Objects.requireNonNull(creation.get(row.name())), result + " " + state.getStdio().errno() + " " + text(failure.readAddressElementIndex(0)) + " " + codes);
                        }
                        var cwd = Files.createTempDirectory(directory, stage + "-" + backend + "-"); Files.writeString(cwd.resolve("value"), "cwd-data"); NativeFileProvider.current().changeDirectory(NativeDirectoryOwner.pathBytes(cwd));
                        var environment = create(vector("sh", "-c", "printf '%s:%s:' \"$THC_VALUE\" \"$$\"; /bin/cat value"), vector("THC_VALUE=child", "PATH=/missing-child-path"), nil(), new long[]{-2, -1, -2});
                        var output = new StringBuilder(); while (true) { var chunk = read(environment.output(), 128); if (chunk.isEmpty()) break; output.append(chunk); }
                        assertEquals("child:" + environment.pid() + ":cwd-data", output.toString()); assertEquals("0 0 0", status("processWait", environment.pid())); close(environment);
                    }
                }
                var exercise = new Exercise(); exercise.exercise();
                for (var target : targets.values()) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); exercise.valid(target); }
                var runtime = Truffle.getRuntime(); var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, targets.get("processCreate")); exercise.installed = true; exercise.exercise();
                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
            } finally { context.leave(); }
        }
    }
}
