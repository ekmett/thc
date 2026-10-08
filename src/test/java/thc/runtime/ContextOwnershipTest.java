// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import thc.Language;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ContextOwnershipTest {
    @Test void closingOneContextRetiresWeakStableAndNativeOwnersTogether() throws Exception {
        record Roots(Language.State state, Object weak, ManagedAddress stable,
                     ManagedAddress address, long bits, NativeReadOnlyPointer pointer) {}
        try (var engine = Engine.create()) {
            class Checks {
                int finalizerCalls;
                final InteropLibrary interop = InteropLibrary.getUncached();
                Roots capture(Context context) {
                    context.initialize("thc"); context.enter();
                    try {
                        var state = Language.currentState();
                        var address = ManagedAddress.fromHex("616263");
                        var stable = state.getStablePointers().make(address);
                        state.getStablePointers().getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER, stable);
                        var weak = state.getWeaks().make(address, stable, (Supplier<Integer>) () -> ++finalizerCalls);
                        long bits = state.getNativeAddresses().project(address);
                        return new Roots(state, weak, stable, address, bits, Objects.requireNonNull(state.getNativeAddresses().transport(address)));
                    } finally { context.leave(); }
                }
                void live(Context context, Roots roots) throws Exception {
                    context.enter();
                    try {
                        assertEquals(1, roots.state.getWeaks().retainedCount());
                        assertSame(roots.stable, roots.state.getWeaks().dereference(roots.weak).getValue());
                        assertSame(roots.address, roots.state.getStablePointers().dereference(roots.stable));
                        assertTrue(interop.isPointer(roots.pointer));
                        assertEquals(roots.bits, interop.asPointer(roots.pointer));
                        assertEquals(97L, roots.state.getNativeAddresses().recover(roots.bits).readWord8(0));
                    } finally { context.leave(); }
                }
                void retired(Roots roots) {
                    assertEquals(0, roots.state.getWeaks().retainedCount());
                    assertThrows(RuntimeFault.class, () -> roots.state.getWeaks().dereference(roots.weak));
                    assertThrows(RuntimeFault.class, () -> roots.state.getStablePointers().dereference(roots.stable));
                    assertThrows(RuntimeFault.class, () -> roots.state.getStablePointers().getOrSetSharedCAF(
                        SharedCAFStore.EVENT_MANAGER, ManagedAddress.nullAddress()));
                    assertThrows(RuntimeFault.class, () -> roots.state.getNativeAddresses().recover(roots.bits));
                    assertFalse(interop.isPointer(roots.pointer));
                    assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(roots.pointer));
                    assertEquals(0, finalizerCalls);
                }
            }
            var checks = new Checks();
            try (var first = Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build();
                 var second = Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build()) {
                var firstRoots = checks.capture(first);
                var secondRoots = checks.capture(second);
                checks.live(first, firstRoots); checks.live(second, secondRoots);
                first.close();
                checks.retired(firstRoots);
                checks.live(second, secondRoots);
                second.close();
                checks.retired(secondRoots); checks.retired(firstRoots);
            }
        }
    }

    @Test void handoffLayoutsAndThreadUpgradeAreOwnedByEachContext() throws Exception {
        try (var engine = Engine.create()) {
            record Captured(Language.State state, HandoffLayout layout) {}
            java.util.function.Function<Context, Captured> capture = context -> {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var state = Language.currentState(null);
                    assertSame(state.getHandoffLayouts(), language.getHandoffLayouts());
                    return new Captured(state, state.getHandoffLayouts().intern(List.of("IntRep", "BoxedRep (Just Lifted)")));
                } finally { context.leave(); }
            };
            try (var firstContext = Context.newBuilder("thc").engine(engine).build();
                 var secondContext = Context.newBuilder("thc").engine(engine).build()) {
                var firstCaptured = capture.apply(firstContext);
                var secondCaptured = capture.apply(secondContext);
                var first = firstCaptured.state();
                var second = secondCaptured.state();
                assertNotSame(first, second);
                assertNotSame(first.getHandoffLayouts(), second.getHandoffLayouts());
                assertNotSame(first.getJavaScriptImports(), second.getJavaScriptImports());
                assertNotSame(firstCaptured.layout(), secondCaptured.layout());
                assertTrue(first.getSingleThreadedAssumption().isValid());
                assertTrue(second.getSingleThreadedAssumption().isValid());
                // Sequential access by a new thread is enough to leave the
                // lockless phase, before that thread executes guest code.
                var workerError = new AtomicReference<Throwable>();
                var worker = new Thread(() -> {
                    try {
                        firstContext.enter();
                        try { assertSame(first, Language.currentState(null)); }
                        finally { firstContext.leave(); }
                    } catch (Throwable error) { workerError.set(error); }
                });
                worker.setDaemon(true); worker.start(); worker.join(10_000);
                assertFalse(worker.isAlive(), "Second context thread did not leave");
                if (workerError.get() != null) throw new AssertionError("Second context thread failed", workerError.get());
                assertFalse(first.getSingleThreadedAssumption().isValid());
                assertTrue(second.getSingleThreadedAssumption().isValid());
                firstContext.enter();
                try { assertFalse(first.getSingleThreadedAssumption().isValid()); }
                finally { firstContext.leave(); }
            }
        }
    }

    /** Real pinned Truffle preinit is process-global and consumed once; isolate each handoff. */
    @ParameterizedTest @ValueSource(strings = {"runtime", "rejected"})
    void preinitializedContextReplacesBuildAuthority(String mode, @TempDir Path temporary) throws Exception {
        var output = temporary.resolve("preinit.log");
        var command = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
            "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED",
            "--add-exports=org.graalvm.truffle.compiler/com.oracle.truffle.compiler=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-Dthc.handoffSlabs=" + System.getProperty("thc.handoffSlabs", "false"),
            "-cp", System.getProperty("thc.testRuntimeClasspath"), ContextOwnershipTest.class.getName(), mode)
            .redirectErrorStream(true).redirectOutput(output.toFile());
        for (var variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) command.environment().remove(variable);
        var process = command.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Preinitialized context handoff timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    // Pinned upstream inspection proves the public builder consumed the preinitialized
    // owner; a successful fresh fallback context must not satisfy this regression.
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object invoke(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner);
    }
    public static void main(String[] arguments) throws Exception {
        String option = "polyglot.image-build-time.PreinitializeContexts";
        System.clearProperty(option);
        var holder = Class.forName("org.graalvm.polyglot.Engine$ImplHolder");
        var preinitialize = holder.getDeclaredMethod("preInitializeEngine"); preinitialize.setAccessible(true);
        var reset = holder.getDeclaredMethod("resetPreInitializedEngine"); reset.setAccessible(true);
        try {
            System.setProperty(option, "thc");
            preinitialize.invoke(null);
            System.clearProperty(option);
            var implField = holder.getDeclaredField("IMPL"); implField.setAccessible(true);
            var engine = invoke(implField.get(null), "getPreinitializedEngine");
            assertNotNull(engine, "Truffle must retain the preinitialized engine");
            var context = invoke(engine, "getPreInitializedContext");
            Object preparedOwner = null; Language preparedLanguage = null;
            for (var candidate : (Object[]) field(context, "contexts")) {
                if ("thc".equals(invoke(field(candidate, "language"), "getId"))) {
                    preparedOwner = invoke(candidate, "getContextImpl");
                    var instance = invoke(candidate, "getLanguageInstanceOrNull");
                    if (instance != null) preparedLanguage = (Language) field(instance, "spi");
                }
            }
            assertNotNull(preparedOwner, "THC must actually preinitialize, not silently fall back");
            assertNotNull(preparedLanguage);
            assertNull(field(preparedOwner, "state"), "Build-time authority must be detached before capture");
            var owner = preparedOwner; var language = preparedLanguage;
            var runtime = new AtomicReference<Context>();
            var state = new AtomicReference<Language.State>();
            var failure = new AtomicReference<Throwable>();
            boolean reject = arguments[0].equals("rejected");
            // Truffle enters this carrier and creates its thread locals BEFORE patchContext.
            var first = new Thread(() -> {
                try {
                    var errors = new java.io.ByteArrayOutputStream();
                    var builder = Context.newBuilder("thc").allowExperimentalOptions(true)
                        .option("engine.Compilation", "false").allowNativeAccess(false).allowCreateThread(false)
                        .allowIO(org.graalvm.polyglot.io.IOAccess.NONE).arguments("thc", new String[]{"runtime-argument"}).err(errors);
                    if (reject) builder.option("thc.ByteArrayStorage", "native");
                    if (reject) {
                        var error = assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
                            try (var unused = builder.build()) { unused.initialize("thc"); }
                        });
                        assertTrue(error.getMessage().contains("requires native access"), error.getMessage());
                        assertNull(field(owner, "state"), "Failed patch must not restore preparation authority");
                        return;
                    }
                    var active = builder.build(); runtime.set(active); active.initialize("thc"); active.enter();
                    try {
                        assertSame(owner, TruffleLanguage.ContextReference.create(Language.class).get(null));
                        assertSame(language, TruffleLanguage.LanguageReference.create(Language.class).get(null));
                        var current = Language.currentState(); state.set(current);
                        assertSame(current, field(owner, "state"));
                        var env = current.getEnv();
                        assertFalse(env.isPreInitialization()); assertFalse(env.isNativeAccessAllowed());
                        assertFalse(env.isCreateThreadAllowed()); assertFalse(env.isFileIOAllowed());
                        assertArrayEquals(new String[]{"runtime-argument"}, env.getApplicationArguments());
                        env.err().write(42); assertArrayEquals(new byte[]{42}, errors.toByteArray());
                        assertSame(current.getThreads().pollState(Thread.currentThread()), current.getThreadPollState().get());
                        assertNull(current.getThreadPollState().get().getCurrent());
                        assertSame(current.getMaskingState().cell(Thread.currentThread()), current.getThreadMaskingState().get());
                        assertSame(MaskingState.UNMASKED, current.getThreadMaskingState().get().getValue());
                        current.getMaskingState().set(MaskingState.MASKED_INTERRUPTIBLE);
                        assertSame(MaskingState.MASKED_INTERRUPTIBLE, current.getThreadMaskingState().get().getValue());
                        assertSame(current.getStackAnnotations().cell(Thread.currentThread()), current.getThreadAnnotations().get());
                        assertSame(StackAnnotationState.EMPTY, current.getThreadAnnotations().get().getValue());
                        assertTrue(current.getSingleThreadedAssumption().isValid());
                    } finally { active.leave(); }
                } catch (Throwable error) { failure.set(error); }
            });
            first.setDaemon(true); first.start(); first.join(10_000);
            assertFalse(first.isAlive(), "First runtime carrier did not finish");
            try (var active = runtime.get()) {
                if (failure.get() != null) throw new AssertionError("Runtime handoff failed", failure.get());
                if (reject) return;
                active.enter();
                try {
                    assertSame(state.get(), Language.currentState());
                    assertFalse(state.get().getSingleThreadedAssumption().isValid(), "The prepatch carrier must count as first");
                    assertSame(MaskingState.UNMASKED, state.get().getThreadMaskingState().get().getValue());
                } finally { active.leave(); }
            }
            assertNull(field(owner, "state"), "Runtime disposal must detach its authority");
            assertThrows(RuntimeFault.class, () -> state.get().getThreads().checkEntryAllowed());
        } finally { System.clearProperty(option); reset.invoke(null); }
    }

}
