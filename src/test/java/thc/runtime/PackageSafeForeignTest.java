// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.ContextProfile;
import thc.ForeignExceptionFixtureSupport;
import thc.Language;
import thc.Main;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

/** Actual C boundary controls; unchanged erf acquisition is checked separately. */
public class PackageSafeForeignTest {
    @TempDir Path directory;
    private PackageScalarLink library() throws Exception {
        var source = directory.resolve("safe.c"); var output = directory.resolve("safe.bc");
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
              for (unsigned remaining = 20000; remaining != 0; --remaining) {
                if (atomic_load(&phase) == 2) return value;
                usleep(1000);
              }
              return -123.0;
            }
            double requires_argument(double value) { return value; }
            unsigned char *wait_pointer(unsigned char *value) {
              if (wait_value(0.5) != 0.5) return 0;
              ++*value;
              return value;
            }
            """.stripTrailing());
        var cpu = System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch"); var target = cpu + (System.getProperty("os.name").equals("Linux") ? "-unknown-linux-gnu" : "-apple-darwin");
        var process = new ProcessBuilder(System.getenv("THC_CLANG") == null ? "clang" : System.getenv("THC_CLANG"), "--target=" + target, "-std=c11", "-O1", "-emit-llvm", "-c", source.toString(), "-o", output.toString()).redirectErrorStream(true).start();
        var diagnostic = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.waitFor(), diagnostic); var bytes = Files.readAllBytes(output); var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var abi = List.of(new PackageScalarSignature("started", "started", List.of(), "Int32Rep", "ccall", "safe"), new PackageScalarSignature("call_count", "call_count", List.of(), "Int32Rep", "ccall", "safe"),
            new PackageScalarSignature("reset", "reset", List.of(), "void", "ccall", "safe"), new PackageScalarSignature("release", "release", List.of(), "void", "ccall", "safe"),
            new PackageScalarSignature("wait_value", "wait_value", List.of("DoubleRep"), "DoubleRep", "ccall", "safe"), new PackageScalarSignature("wait_pointer", "wait_pointer", List.of("AddrRep"), "AddrRep", "ccall", "safe"),
            // Deliberately inconsistent control: no forged typed Core is admitted.
            // The runtime interop call throws inside the foreign extent.
            new PackageScalarSignature("requires_argument", "requires_argument", List.of(), "DoubleRep", "ccall", "safe"));
        return new PackageScalarLink("safe-boundary-control", target, hash, hash, bytes, abi);
    }
    private static final class Entry extends RootNode {
        private final PackageScalarCall call; @Child private PackageScalarAccess access;
        Entry(Language language, PackageScalarCall call) { super(language); this.call = call; access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) { return switch (call.getResult()) {
            case "DoubleRep" -> access.executeDouble(frame.getArguments(), thc.runtime.Unit.INSTANCE);
            case "Int32Rep" -> (long) access.executeInt(frame.getArguments(), thc.runtime.Unit.INSTANCE);
            case "void" -> { access.executeVoid(frame.getArguments(), thc.runtime.Unit.INSTANCE); yield thc.runtime.Unit.INSTANCE; }
            default -> throw new IllegalStateException("Unexpected safe control ABI");
        }; }
    }
    private Map<String, Object> plus(Map<String, ?> source, String key, Object value) { var result = new LinkedHashMap<String, Object>(source); result.put(key, value); return result; }
    private Map<String, Object> module(PackageScalarLink link, boolean pointer) {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true); var valueRep = pointer ? "AddrRep" : "DoubleRep";
        var value = Map.of("kind", pointer ? "address" : "double", "primReps", List.of(valueRep), "evaluated", true); var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of(valueRep), "components", List.of(state, value), "evaluated", true);
        var descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", pointer ? "wait_pointer" : "wait_value", "unit", link.getUnit(), "isFunction", true), "convention", "ccall", "safety", "safe", "arity", 2L, "suppliedArity", 2L,
            "argumentReps", List.of(plus(value, "evaluated", false), plus(state, "evaluated", false)), "resultRep", plus(result, "evaluated", false));
        var arguments = List.of(List.of("var", "value", Map.of("rep", value)), List.of("var", "state", Map.of("rep", state)));
        var body = List.of("app", List.of("var", "native-safe", Map.of("rep", closure)), arguments, List.of(false, false), false, false, Map.of("rep", result, "foreignCall", descriptor));
        var parameters = List.of(Map.of("id", "value", "name", "value", "lifted", false, "coercion", false, "rep", value), Map.of("id", "state", "name", "state", "lifted", false, "coercion", false, "rep", state));
        return Map.of("bindings", List.of(Map.of("id", "wait", "name", "wait", "lifted", true, "expr", List.of("lam", parameters, body, Map.of("resultRep", result)))), "packageScalarLinks", List.of(link), "instrument", true);
    }
    @Tag("foreign-exceptions-full-core") @ParameterizedTest @CsvSource({
        "ast,platform", "ast-compiled,platform", "bytecode,platform", "bytecode-compiled,platform",
        "ast,loom", "ast-compiled,loom", "bytecode,loom", "bytecode-compiled,loom"})
    public void safeScalarDefersAsyncUntilReturnWhileOtherGuestCallsAndGcProgress(String mode, String hosting) throws Throwable { exerciseSafeReturn(mode, hosting, false); }
    @Tag("foreign-exceptions-full-core") @ParameterizedTest @CsvSource({
        "ast,platform", "ast-compiled,platform", "bytecode,platform", "bytecode-compiled,platform",
        "ast,loom", "ast-compiled,loom", "bytecode,loom", "bytecode-compiled,loom"})
    public void temporarySafePointerPathPreservesNativeStorageAndCompletedResult(String mode, String hosting) throws Throwable { exerciseSafeReturn(mode, hosting, true); }
    private static <T> T admitted(GuestThreads threads, Callable<T> action) throws Exception {
        if (threads.needsHosting()) return threads.hostEntry(null, () -> admitted(threads, action));
        threads.enterCurrent(null, false, true, 0L);
        try { return action.call(); }
        finally { threads.leaveCurrent(); }
    }
    private void exerciseSafeReturn(String mode, String hosting, boolean pointer) throws Throwable {
        var link = library();
        try (var context = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST)
                .option("thc.ThreadHosting", hosting).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); owner.getPackageCbits().link(link);
                var threads = owner.getThreads(); threads.setCapabilityCount(1);
                assertEquals(hosting.equals("loom"), threads.isLoom()); assertEquals(1L, threads.capabilityCount());
                var entries = new LinkedHashMap<String, RootCallTarget>(); for (var signature : link.getAbi()) entries.put(signature.symbol(), new Entry(language, new PackageScalarCall(link, signature)).getCallTarget());
                ExecutableProgram program = mode.startsWith("ast") ? new Program(language, ForeignExceptionFixtureSupport.link(module(link, pointer), "wait"), true) : new BytecodeProgram(language, ForeignExceptionFixtureSupport.link(module(link, pointer), "wait"), true);
                var target = program.entryTarget("wait"); var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                var storage = pointer ? PinnedMemory.allocate(8, 64) : null; Object argument = storage == null ? 0.5 : ManagedAddress.fromAllocation(storage).plus(3);
                Long originalBits = storage == null || storage.nativeSegment() == null ? null : storage.nativeSegment().address();
                class Results { void check(Object result) {
                    var tuple = TupleResults.ownedTupleResult(result, shape);
                    if (pointer) {
                        var address = (ManagedAddress) shape.getLayout().getObject(tuple, 0); assertEquals(originalBits + 3, address.toNativeBits());
                        assertEquals(originalBits, storage.nativeSegment().address(), "no copied or relocated buffer"); assertEquals(storage.readByte(3), address.readWord8(0), "returned alias retains checked backing");
                        for (long index : new long[]{0L, 1L, 2L, 4L, 5L, 6L, 7L}) assertEquals(0L, storage.readByte(index));
                    } else assertEquals(0.5, shape.getLayout().getDouble(tuple, 0));
                } }
                var results = new Results();
                var ready = new CountDownLatch(1); var begin = new CountDownLatch(1); var compiledBefore = new AtomicLong(); var identity = new AtomicLong(); var pending = new AtomicReference<AsyncRequest>(); var completed = new AtomicReference<SavedGuestContinuation>(); var failure = new AtomicReference<Throwable>();
                var worker = threads.newThread(owner.getEnv(), () -> {
                    identity.set(threads.enterCurrent(null, false, true, 0L));
                    try {
                        assertEquals(hosting.equals("loom"), Thread.currentThread().isVirtual());
                        assertFalse(threads.needsHosting());
                        // Preserve the original three setup calls on the same TSO
                        // that executes the first installed foreign call.
                        for (int i = 0; i < 3; i++) { entries.get("reset").call(); var result = ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, argument, thc.runtime.Unit.INSTANCE}); results.check(result); }
                        entries.get("reset").call(); ready.countDown(); if (!begin.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed.");
                        assertEquals(identity.get(), threads.currentId(), "setup and installed call keep one TSO");
                        var result = ScalarTestCalls.callScalarTestTarget(target, new Object[]{0L, argument, thc.runtime.Unit.INSTANCE}); var continuation = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(result)); var request = continuation.asyncRequest();
                        assertSame(pending.get(), request, "delivery occurs at the completed foreign-call cut");
                        assertEquals(identity.get(), threads.currentId(), "foreign return preserves the TSO");
                        if (mode.endsWith("compiled")) {
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore.get());
                            assertTrue(request.compiledCapture, "first installed call reaches the return cut in compiled code");
                            assertSame(target, program.entryTarget("wait"));
                            // The first pending bytecode poll profiles its cold arm and may invalidate the target.
                            if (mode.startsWith("ast")) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        }
                        request.acknowledge(); completed.set(continuation);
                    } catch (Throwable problem) { failure.set(problem); } finally { ready.countDown(); threads.leaveCurrent(); }
                }, 0L, null);
                admitted(threads, () -> {
                    assertFalse(threads.needsHosting(), "controller and resume must hold the same HEC");
                    assertEquals(hosting.equals("loom"), Thread.currentThread().isVirtual());
                    entries.get("started").call(); entries.get("release").call(); entries.get("call_count").call();
                    threads.startThread(worker);
                    try {
                        long warmDeadline = System.nanoTime() + 30_000_000_000L;
                        while (!ready.await(1, TimeUnit.MILLISECONDS) && System.nanoTime() < warmDeadline) if (Long.valueOf(1).equals(entries.get("started").call())) entries.get("release").call();
                        assertEquals(0L, ready.getCount(), "same-TSO setup completes before code installation"); if (failure.get() != null) throw new AssertionError("safe worker failed", failure.get());
                        if (mode.endsWith("compiled")) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
                        compiledBefore.set(((Number) program.diagnostics().get("compiledEntries")).longValue()); begin.countDown(); long deadline = System.nanoTime() + 5_000_000_000L;
                        while (!Long.valueOf(1).equals(entries.get("started").call()) && System.nanoTime() < deadline) Thread.sleep(1);
                        assertEquals(1L, entries.get("started").call(), "other guest call observes the active C loop"); var request = threads.send(identity.get(), "after safe return"); pending.set(request); System.gc();
                        assertEquals(AsyncRequestState.PENDING, request.getState(), "no guest delivery from the opaque foreign frame");
                    } finally { begin.countDown(); entries.get("release").call(); worker.join(25000); }
                    assertFalse(worker.isAlive(), "safe return must release the Java carrier"); if (failure.get() != null) throw new AssertionError("safe worker failed", failure.get()); assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.get().getState()); assertEquals(1L, entries.get("call_count").call());
                    assertFalse(threads.needsHosting(), "completed resumption remains admitted");
                    var result = completed.get().continueWith(thc.runtime.Unit.INSTANCE); results.check(result); if (pointer) assertEquals(4L, storage.readByte(3), "three warm calls and one completed effect, with no replay");
                    assertEquals(1L, entries.get("call_count").call(), "resumption must not replay the foreign effect"); if (mode.startsWith("ast")) assertThrows(RuntimeFault.class, () -> completed.get().continueWith(thc.runtime.Unit.INSTANCE));
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                    return null;
                });
            } finally { context.leave(); }
        }
    }
    @Test public void failingInteropRestoresGuestAndNestedForeignPermissions() throws Exception {
        var link = library();
        try (var context = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); owner.getPackageCbits().link(link);
                PackageScalarSignature signature = null; for (var candidate : link.getAbi()) if (candidate.symbol().equals("requires_argument")) { if (signature != null) throw new IllegalArgumentException("Multiple signatures"); signature = candidate; }
                if (signature == null) throw new java.util.NoSuchElementException(); var target = new Entry(language, new PackageScalarCall(link, signature)).getCallTarget(); long identity = owner.getThreads().enterCurrent();
                try {
                    var request = owner.getThreads().send(identity, "pending across failed call"); var outer = owner.getThreads().enterForeign();
                    try { assertThrows(ArityException.class, () -> target.call()); assertNull(owner.getThreads().poll(target.getRootNode())); assertEquals(AsyncRequestState.PENDING, request.getState()); }
                    finally { owner.getThreads().leaveForeign(outer); }
                    assertSame(request, owner.getThreads().poll(target.getRootNode())); request.acknowledge();
                } finally { owner.getThreads().leaveCurrent(); }
                var restored = owner.getThreads().enterForeign(); try { assertEquals(GuestThreads.DeliveryPermission.NONE, restored); } finally { owner.getThreads().leaveForeign(restored); }
            } finally { context.leave(); }
        }
    }
}
