// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

/** Real C callbacks: no synthetic callable, ownership token or native address. */
class PackageFinalizerTest {
    @TempDir Path directory;
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private PackageScalarLink library() throws Exception { return library("finalizer-test", "second"); }
    private PackageScalarLink library(String unit, String secondSymbol) throws Exception {
        Path source = directory.resolve(unit + ".c"), artifact = directory.resolve(unit + ".bc");
        Files.writeString(source, """
            void first(unsigned char *p) { if (p) *p = *p * 10 + 1; }
            void second(unsigned char *p) { if (p) *p = *p * 10 + 2; }
            """);
        var command = new ArrayList<String>();
        command.add(System.getenv().getOrDefault("THC_CLANG", "clang"));
        if (System.getProperty("os.name").equals("Linux")) command.add("--target=" +
            (System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch")) + "-unknown-linux-gnu");
        command.addAll(List.of("-O1", "-emit-llvm", "-c", source.toString(), "-o", artifact.toString()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        byte[] bytes = Files.readAllBytes(artifact);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var abi = List.of(new PackageScalarSignature("first", "first", List.of("AddrRep"), "void"),
            new PackageScalarSignature(secondSymbol, "second", List.of("AddrRep"), "void"));
        return new PackageScalarLink(unit, "test-host", sha, sha, bytes, abi, "llvm-bitcode", Set.of("first", "second"));
    }
    @Test void nativeCallbacksKeepPointerIdentityNewestFirstAndExactlyOnceAfterDeath() throws Exception {
        var link = library();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null);
                state.getPackageCbits().link(link); state.getPackageCbits().link(link);
                var provider = state.cbits();
                var first = provider.finalizerLabel("first"); var second = provider.finalizerLabel("second");
                assertTrue(first.sameLocation(provider.finalizerLabel("first"))); assertFalse(first.sameLocation(second));
                byte[] bytes = {71, 0, 93}; var pointer = ManagedAddress.fromByteArray(bytes).plus(1);
                Object action = new Object(); var weak = state.getWeaks().make(new Object(), new Object(), action);
                assertEquals(1L, state.getWeaks().addCFinalizer(first, pointer, 0, weak, provider));
                assertEquals(1L, state.getWeaks().addCFinalizer(second, pointer, 0, weak, provider));
                assertEquals(1L, state.getWeaks().addCallback(weak, () ->
                    assertEquals(0L, state.getWeaks().dereference(weak).getFlag(), "DEAD is visible before C")));
                var result = state.getWeaks().finalize(weak);
                assertSame(action, result.getValue()); assertEquals(1L, result.getFlag());
                assertArrayEquals(new byte[]{71, 21, 93}, bytes, "C visits the actual interior pointer, newest first");
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                assertEquals(0L, state.getWeaks().addCFinalizer(first, pointer, 0, weak, provider));
                assertArrayEquals(new byte[]{71, 21, 93}, bytes, "dead callbacks never replay");
                first.finalizerFunction().invoke(ManagedAddress.nullAddress());
            } finally { context.leave(); }
        }
    }
    @Test void declaredFinalizerLoadsItsComponentOnFirstUse() throws Exception {
        var link = library();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); owner.getPackageCbits().declare(link);
                var expression = new CFinalizerLabels("first", new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep")));
                var label = expression.resolve();
                assertSame(label, expression.resolve(), "repeated demand reuses the context-owned address");
                assertSame(label.finalizerFunction(), expression.resolve().finalizerFunction());
                byte[] bytes = {3}; label.finalizerFunction().invoke(ManagedAddress.fromByteArray(bytes));
                assertArrayEquals(new byte[]{31}, bytes);
                owner.getPackageCbits().link(link);
                assertSame(label.finalizerFunction(), owner.getPackageCbits().finalizer("first"));
            } finally { context.leave(); }
        }
    }
    @Test void foreignClosedAndAmbiguousOwnersNeverPublishOrInvokeCallbacks() throws Exception {
        var link = library(); var conflicting = library("conflicting-finalizer-test", "unpublished");
        try (var first = context()) {
            first.initialize("thc"); first.enter();
            try {
                var state = Language.currentState(null); state.getPackageCbits().link(link);
                var callback = new CFinalizerLabels("first",
                    new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"))).resolve();
                byte[] bytes = {0}; var pointer = ManagedAddress.fromByteArray(bytes);
                var failed = assertThrows(RuntimeFault.class, () -> state.getPackageCbits().link(conflicting));
                assertSame(failed, assertThrows(RuntimeFault.class, () -> state.getPackageCbits().finalizer("unpublished")),
                    "failed registration cannot supply a callable label");
                assertTrue(callback.sameLocation(state.cbits().finalizerLabel("first")));
                try (var second = context()) {
                    second.initialize("thc"); second.enter();
                    try {
                        var other = Language.currentState(null); other.getPackageCbits().link(link);
                        assertThrows(RuntimeFault.class, () -> callback.finalizerFunction().invoke(pointer));
                        var weak = other.getWeaks().make(new Object(), new Object(), null);
                        assertThrows(RuntimeFault.class, () -> other.getWeaks().addCFinalizer(callback, pointer, 0, weak, other.cbits()));
                    } finally { second.leave(); }
                }
                state.getPackageCbits().close();
                assertThrows(RuntimeFault.class, () -> callback.finalizerFunction().invoke(pointer));
                assertArrayEquals(new byte[]{0}, bytes);
            } finally { first.leave(); }
        }
    }
    @Test void wrongEnvironmentAndDisposedAllocationRejectWithoutRevivingWeakState() throws Exception {
        var link = library();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); state.getPackageCbits().link(link);
                var provider = state.cbits(); var function = provider.finalizerLabel("first");
                var pointer = state.getNativeAllocations().malloc(8);
                var key = new Object(); var weak = state.getWeaks().make(key, key, null);
                assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function, pointer, 1, weak, provider));
                assertEquals(1L, state.getWeaks().addCFinalizer(function, pointer, 0, weak, provider));
                java.lang.ref.Reference.reachabilityFence(key);
                state.getNativeAllocations().free(pointer);
                assertThrows(RuntimeFault.class, () -> state.getWeaks().finalize(weak));
                assertEquals(0L, state.getWeaks().dereference(weak).getFlag());
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag(), "failed callback is not replayed");
            } finally { context.leave(); }
        }
    }
    private static final class Entry extends RootNode {
        private final CFinalizerFunction function;
        int compiledCalls;
        Entry(Language language, CFinalizerFunction function) { super(language); this.function = function; }
        @Override public Object execute(VirtualFrame frame) {
            if (com.oracle.truffle.api.CompilerDirectives.inCompiledCode()) compiledCalls++;
            function.invoke((ManagedAddress) frame.getArguments()[0]); return Unit.INSTANCE;
        }
    }
    @Test void firstInstalledCallbackUsesTheLivePointerAndRetainsItsCode() throws Exception {
        var link = library();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); state.getPackageCbits().link(link);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var entry = new Entry(language, state.cbits().finalizerLabel("first").finalizerFunction());
                var target = entry.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                // Same public compile prerequisite as EntryValue; no guest warmup or retry.
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                byte[] bytes = {0}; target.call(ManagedAddress.fromByteArray(bytes));
                assertArrayEquals(new byte[]{1}, bytes, "the first and only invocation runs C once");
                assertEquals(1, entry.compiledCalls, "the first invocation entered installed guest code");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
}
