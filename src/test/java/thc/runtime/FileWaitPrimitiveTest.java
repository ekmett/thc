// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.ContextProfile;
import thc.Language;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongFunction;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;
import static thc.runtime.Unit.INSTANCE;

/** Original payload identity at the primitive boundary; installed-Core proof is separate. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(45)
@SuppressWarnings("unchecked")
class FileWaitPrimitiveTest {
    @TempDir Path directory;
    private final Map<String, Object> fd = map("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> state = map("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> payload = map("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
    private Map<String, Object> module(String name) { return module(name, null); }
    private Map<String, Object> module(String name, List<Object> descriptorOperand) {
        var bad = CoreFileWait.badFd;
        var params = list(map("id", "descriptor", "lifted", false, "rep", fd), map("id", "s", "lifted", false, "rep", state));
        var call = list("app", list("prim", name), list(descriptorOperand != null ? descriptorOperand : list("var", "descriptor", map("rep", fd)),
            list("var", "s", map("rep", state))), list(false, false), false, false, map("rep", state));
        var root = map("id", "wait", "name", "wait", "arity", 2, "lifted", true, "rep", closure,
            "expr", list("lam", params, call, map("rep", closure, "resultRep", state)));
        var original = map("id", bad, "name", "blockedOnBadFD", "arity", 0, "lifted", true, "rep", payload,
            "expr", list("var", bad, map("rep", payload)));
        return map("schema", 1, "ghc", "9.14.1", "module", "Test.FileWait", "bindings", list(root, original), "constructors", List.of());
    }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target)); }
    @Test void astAsyncAdmissionAcceptsAComputedDescriptorBeforeItsBlockingCut() {
        var computed = list("app", list("var", "produceDescriptor", map("rep", closure)),
            list(list("var", "descriptor", map("rep", fd))), list(false), false, false, map("rep", fd));
        for (var name : List.of("waitRead#", "waitWrite#")) {
            var root = ((List<Map<String, Object>>) module(name).get("bindings")).getFirst();
            assertDoesNotThrow(() -> AstAsyncAdmission.validate(List.of(root)));
            var computedRoot = ((List<Map<String, Object>>) module(name, computed).get("bindings")).getFirst();
            assertDoesNotThrow(() -> AstAsyncAdmission.validate(List.of(computedRoot)));
        }
    }
    @Test void firstInstalledWaitsKeepExactDescriptorAndLazyBadFdPayload() throws Exception {
        try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST, false)) {
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var files = Language.currentState(null).getFiles();
                var path = directory.resolve("ready"); Files.writeString(path, "ready");
                var address = ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8));
                for (var backend : List.of("ast", "bytecode")) for (var name : List.of("waitRead#", "waitWrite#")) {
                    long opened = files.open(address, name.equals("waitWrite#") ? 2L : 0L, ForeignSafety.UNSAFE); assertTrue(opened >= 3);
                    var linked = with(CoreModules.reachable(module(name), "wait", true), "instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var target = program.entryTarget("wait"); LongFunction<Object> call = descriptor -> Calls.target(target, new Object[] {0L, descriptor, INSTANCE});
                    for (int i = 0; i < 6; i++) assertSame(INSTANCE, call.apply(opened));
                    var original = (Thunk) program.entryValue(CoreFileWait.badFd);
                    // Graal profiles the guest exception before installation;
                    // the first installed failure must still retain this target.
                    for (int i = 0; i < 2; i++) {
                        var failure = assertThrows(GuestException.class, () -> call.apply(-1)); assertSame(original, failure.getPayload()); assertEquals(0, original.getState());
                    }
                    compile(target); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertSame(INSTANCE, call.apply(opened)); assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    assertTrue(valid(target), backend + "/" + name + " keeps its installed target");
                    for (long bad : new long[] {-1, opened}) {
                        if (bad == opened) assertEquals(0L, files.close(opened, ForeignSafety.UNSAFE));
                        var failure = assertThrows(GuestException.class, () -> call.apply(bad)); assertSame(original, failure.getPayload());
                        assertEquals(0, original.getState(), "RTS payload must remain lazy");
                        assertTrue(valid(target), backend + "/" + name + " remains installed after bad FD " + bad);
                    }
                    assertTrue(valid(target), backend + "/" + name + " remains installed after bad FD");
                }
            } finally { context.leave(); }
        }
    }
    private void awaitBlocked(Language.State state, long read, Future<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (state.getFiles().pendingReadiness(read) == 0 && !future.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
        if (future.isDone()) future.get();
        assertEquals(1, state.getFiles().pendingReadiness(read), "Compiled wait must block on the real FIFO");
    }
    private record Cut(ContinuationResult saved, AsyncRequest request) {}
    private Cut cut(Context context, Language.State state, long read, RootCallTarget target, ExecutorService worker, MaskingState mask) throws Exception {
        var id = new AtomicLong(-1);
        var future = worker.submit(() -> {
            context.enter(); state.getThreads().enterCurrent(mask, false, true, null);
            try {
                id.set(state.getThreads().currentId()); var answer = Calls.target(target, new Object[] {0L, read, INSTANCE});
                if (answer instanceof ContinuationResult continuation) {
                    var request = AsyncContinuations.request(continuation); if (request != null) request.acknowledge();
                }
                return answer;
            } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
        });
        awaitBlocked(state, read, future); assertTrue(valid(target), "Target invalidated on worker entry before the async cut");
        var request = state.getThreads().send(id.get(), "interrupt descriptor wait");
        var saved = (ContinuationResult) future.get(5, TimeUnit.SECONDS);
        assertSame(request, AsyncContinuations.request(saved)); assertTrue(request.compiledCapture);
        assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
        assertEquals(0, state.getFiles().pendingReadiness(read), "The native poll lease must be released at capture"); return new Cut(saved, request);
    }
    @Test void compiledReadWaitResumesItsOriginalTokenAfterInterruptionAndRejectsDescriptorReuse() throws Exception {
        var pipe = directory.resolve("readiness"); var created = new ProcessBuilder("mkfifo", pipe.toString()).start();
        assertTrue(created.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, created.exitValue());
        var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST, false);
        var worker = Executors.newSingleThreadExecutor();
        try {
            context.initialize("thc"); context.enter();
            final Language.State state; final BytecodeProgram program; final RootCallTarget target; final long read, write; final Thunk original;
            try {
                state = Language.currentState(null); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var address = ManagedAddress.fromByteArray((pipe + "\0").getBytes(StandardCharsets.UTF_8));
                read = state.getStdio().open(address, 0x800, 0); write = state.getStdio().open(address, 0x801, 0);
                assertTrue(read >= 3); assertTrue(write >= 3);
                var linked = with(CoreModules.reachable(module("waitRead#"), "wait", true), "instrument", true);
                program = new BytecodeProgram(language, linked, true); target = program.entryTarget("wait"); original = (Thunk) program.entryValue(CoreFileWait.badFd);
                assertEquals(1L, state.getStdio().write(write, ManagedAddress.fromByteArray(new byte[] {7}), 1));
                for (int i = 0; i < 6; i++) assertSame(INSTANCE, Calls.target(target, new Object[] {0L, read, INSTANCE}));
                compile(target); assertEquals(1L, state.getStdio().read(read, ManagedAddress.fromByteArray(new byte[] {0}), 1));
            } finally { context.leave(); }
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var first = cut(context, state, read, target, worker, MaskingState.UNMASKED);
            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
            assertEquals(AsyncRequestState.ACKNOWLEDGED, first.request.getState()); context.enter();
            try {
                assertEquals(1L, state.getStdio().write(write, ManagedAddress.fromByteArray(new byte[] {9}), 1));
                assertSame(INSTANCE, first.saved.continueWith(INSTANCE));
                assertEquals(1L, state.getStdio().read(read, ManagedAddress.fromByteArray(new byte[] {0}), 1));
                // The first captured cut profiles BytecodeRoot's handler and
                // deoptimizes that cold installation. Compile the learned path
                // explicitly before checking later compiled masked waits.
                compile(target);
            } finally { context.leave(); }
            var id = new AtomicLong(-1); var delivered = new AtomicReference<AsyncRequest>();
            var masked = worker.submit(() -> {
                context.enter(); state.getThreads().enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
                try {
                    id.set(state.getThreads().currentId()); var result = Calls.target(target, new Object[] {0L, read, INSTANCE});
                    state.getMaskingState().set(MaskingState.UNMASKED); delivered.set(state.getThreads().poll(target.getRootNode(), true));
                    if (delivered.get() != null) delivered.get().acknowledge(); return result;
                } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            awaitBlocked(state, read, masked); assertTrue(valid(target), "Target invalidated on masked worker entry");
            var maskedRequest = state.getThreads().send(id.get(), "masked descriptor wait"); assertEquals(AsyncRequestState.PENDING, maskedRequest.getState());
            assertFalse(masked.isDone(), "Uninterruptibly masked wait cannot claim the request"); context.enter();
            try { assertEquals(1L, state.getStdio().write(write, ManagedAddress.fromByteArray(new byte[] {5}), 1)); } finally { context.leave(); }
            assertSame(INSTANCE, masked.get(5, TimeUnit.SECONDS)); assertSame(maskedRequest, delivered.get());
            assertEquals(AsyncRequestState.ACKNOWLEDGED, maskedRequest.getState()); assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue());
            assertTrue(valid(target), "Learned masked wait retains its installed target"); context.enter();
            try { assertEquals(1L, state.getStdio().read(read, ManagedAddress.fromByteArray(new byte[] {0}), 1)); } finally { context.leave(); }
            var second = cut(context, state, read, target, worker, MaskingState.MASKED_INTERRUPTIBLE);
            assertEquals(before + 3, ((Number) program.diagnostics().get("compiledEntries")).longValue());
            assertEquals(AsyncRequestState.ACKNOWLEDGED, second.request.getState()); context.enter();
            try {
                assertEquals(0L, state.getFiles().close(read, ForeignSafety.UNSAFE)); var readyFile = directory.resolve("replacement"); Files.writeString(readyFile, "ready");
                var replacement = ManagedAddress.fromByteArray((readyFile + "\0").getBytes(StandardCharsets.UTF_8));
                long newFd = state.getFiles().open(replacement, 0L, ForeignSafety.UNSAFE); assertTrue(newFd >= 3);
                assertEquals(read, state.getFiles().duplicateTo(newFd, read));
                var failure = assertThrows(GuestException.class, () -> second.saved.continueWith(INSTANCE));
                assertSame(original, failure.getPayload()); assertEquals(0, original.getState()); assertTrue(valid(target));
            } finally { context.leave(); }
        } finally { context.close(true); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
