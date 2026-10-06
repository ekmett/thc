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
    private static final String SOURCE = """
        void first(unsigned char *p) { if (p) *p = *p * 10 + 1; }
        void second(unsigned char *p) { if (p) *p = *p * 10 + 2; }
        void environment(unsigned char *env, unsigned char *p) {
            if (env) *env += 3;
            if (p) *p = *p * 10 + (env ? *env : 7);
        }
        static unsigned long tokens;
        void token_object(void *p) { if (p) ++tokens; }
        void token_environment(void *env, void *p) { if (env && env == p) tokens += 10; }
        unsigned long token_count(void) { return tokens; }
        """;
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private PackageScalarLink library() throws Exception { return library("finalizer-test", "second"); }
    private PackageScalarLink library(String unit, String secondSymbol) throws Exception {
        Path source = directory.resolve(unit + ".c"), artifact = directory.resolve(unit + ".bc");
        Files.writeString(source, SOURCE);
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
            new PackageScalarSignature(secondSymbol, "second", List.of("AddrRep"), "void"),
            new PackageScalarSignature("environment", "environment", List.of("AddrRep", "AddrRep"), "void"),
            new PackageScalarSignature("token_object", "token_object", List.of("AddrRep"), "void"),
            new PackageScalarSignature("token_environment", "token_environment", List.of("AddrRep", "AddrRep"), "void"),
            new PackageScalarSignature("token_count", "token_count", List.of(), "WordRep"));
        return new PackageScalarLink(unit, "test-host", sha, sha, bytes, abi, "llvm-bitcode", Set.of("first", "second", "environment", "token_object", "token_environment"));
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
                assertEquals(1L, state.getWeaks().addCFinalizer(first, pointer, 0, ManagedAddress.nullAddress(), weak, provider));
                assertEquals(1L, state.getWeaks().addCFinalizer(second, pointer, 0, ManagedAddress.nullAddress(), weak, provider));
                assertEquals(1L, state.getWeaks().addCallback(weak, () ->
                    assertEquals(0L, state.getWeaks().dereference(weak).getFlag(), "DEAD is visible before C")));
                var result = state.getWeaks().finalize(weak);
                assertSame(action, result.getValue()); assertEquals(1L, result.getFlag());
                assertArrayEquals(new byte[]{71, 21, 93}, bytes, "C visits the actual interior pointer, newest first");
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                assertEquals(0L, state.getWeaks().addCFinalizer(first, pointer, 0, ManagedAddress.nullAddress(), weak, provider));
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
                        assertThrows(RuntimeFault.class, () -> other.getWeaks().addCFinalizer(callback, pointer, 0, ManagedAddress.nullAddress(), weak, other.cbits()));
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
                assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function, pointer, 1, ManagedAddress.nullAddress(), weak, provider));
                assertEquals(1L, state.getWeaks().addCFinalizer(function, pointer, 0, ManagedAddress.nullAddress(), weak, provider));
                java.lang.ref.Reference.reachabilityFence(key);
                state.getNativeAllocations().free(pointer);
                assertThrows(RuntimeFault.class, () -> state.getWeaks().finalize(weak));
                assertEquals(0L, state.getWeaks().dereference(weak).getFlag());
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag(), "failed callback is not replayed");
            } finally { context.leave(); }
        }
    }
    private ExecutableProgram finalizerProgram(Language language, String backend) {
        var address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
        var flag = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var weak = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("IntRep"),
            "evaluated", true, "components", List.of(state, flag));
        var names = List.of("function", "object", "flag", "environment", "weak", "state");
        var reps = List.of(address, address, flag, address, weak, state);
        var parameters = new ArrayList<Object>(); var operands = new ArrayList<Object>();
        for (int index = 0; index < names.size(); index++) {
            parameters.add(Map.of("id", names.get(index), "lifted", false, "rep", reps.get(index)));
            operands.add(List.of("var", names.get(index), Map.of("rep", reps.get(index))));
        }
        var primitive = List.of("app", List.of("prim", "addCFinalizerToWeak#"), operands,
            List.of(false, false, false, false, false, false), false, false, Map.of("rep", result));
        var body = List.of("case", primitive, "pair", List.of(List.of("data", "tuple2", List.of("s", "added"),
            List.of("var", "added", Map.of("rep", flag)), Map.of("binders", List.of(
                Map.of("id", "s", "lifted", false, "rep", state), Map.of("id", "added", "lifted", false, "rep", flag))))),
            Map.of("rep", flag, "binder", Map.of("id", "pair", "lifted", false, "rep", result)));
        var module = Map.<String,Object>of("schema", 1, "ghc", "9.14.1", "instrument", true,
            "constructors", List.of(Map.of("id", "tuple2", "name", "(#,#)", "arity", 2, "tag", 1, "kind", "unboxed-tuple")),
            "bindings", List.of(Map.of("id", "add", "name", "add", "arity", 6, "lifted", true, "rep", closure,
                "expr", List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", flag)))));
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }

    @Test void environmentCallbacksUseTheDeclaredABIThroughBothBackends() throws Exception {
        var link = library();
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); state.getPackageCbits().link(link);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = finalizerProgram(language, backend);
                for (long flag : new long[]{1, -7, Long.MIN_VALUE}) {
                    byte[] bytes = {91, 4, 2, 93}; var storage = ManagedAddress.fromByteArray(bytes);
                    var env = storage.plus(1); var object = storage.plus(2);
                    var key = new Object(); var weak = state.getWeaks().make(key, key, null);
                    var function = state.cbits().finalizerLabel("environment");
                    assertEquals(1L, ScalarTestCalls.callScalarTestTarget(program.entryTarget("add"),
                        new Object[]{0L, function, object, flag, env, weak, Unit.INSTANCE}));
                    assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                    assertArrayEquals(new byte[]{91, 7, 27, 93}, bytes, backend + "/flag=" + flag);
                    assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                    assertArrayEquals(new byte[]{91, 7, 27, 93}, bytes, "no replay");
                    java.lang.ref.Reference.reachabilityFence(key);
                }
                byte[] aliasBytes = {4}; var alias = ManagedAddress.fromByteArray(aliasBytes);
                var function = state.cbits().finalizerLabel("environment").finalizerFunction();
                function.invoke(alias, alias); assertArrayEquals(new byte[]{77}, aliasBytes);
                byte[] nullBytes = {2}; function.invoke(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(nullBytes));
                assertArrayEquals(new byte[]{27}, nullBytes); function.invoke(ManagedAddress.nullAddress(), ManagedAddress.nullAddress());
                var key = new Object(); var weak = state.getWeaks().make(key, key, null);
                assertThrows(RuntimeFault.class, () -> state.getWeaks().addCFinalizer(function.getAddress(), alias,
                    0, ManagedAddress.nullAddress(), weak, state.cbits()));
                assertEquals(1L, state.getWeaks().dereference(weak).getFlag(), "wrong arity cannot claim the weak");
                // Zero ignores even a disposed environment; only the object belongs to the one-pointer ABI.
                var ignored = state.getNativeAllocations().malloc(1); state.getNativeAllocations().free(ignored);
                assertEquals(1L, ScalarTestCalls.callScalarTestTarget(program.entryTarget("add"), new Object[]{0L,
                    state.cbits().finalizerLabel("first"), alias, 0L, ignored, weak, Unit.INSTANCE}));
                state.getWeaks().finalize(weak); assertArrayEquals(new byte[]{(byte) 771}, aliasBytes);
                java.lang.ref.Reference.reachabilityFence(key);
            } finally { context.leave(); }
        }
    }

    @Test void opaqueTokensUseTheSamePointerTransportForObjectAndEnvironment() throws Exception {
        var link = library();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); state.getPackageCbits().link(link);
                var token = state.getStablePointers().make(new Object()); var key = new Object();
                var weak = state.getWeaks().make(key, key, null);
                assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel("token_object"), token,
                    0L, ManagedAddress.nullAddress(), weak, state.cbits()));
                assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel("token_environment"), token,
                    -1L, token, weak, state.cbits()));
                state.getWeaks().finalize(weak);
                var signature = link.getAbi().stream().filter(s -> s.getSymbol().equals("token_count")).findFirst().orElseThrow();
                var getter = state.getPackageCbits().resolve(link, signature);
                assertEquals(11L, com.oracle.truffle.api.interop.InteropLibrary.getUncached().execute(getter.getReceiver()));
                // A stale opaque token must reject before C effects, through the same transport boundary.
                state.getStablePointers().free(token);
                assertThrows(RuntimeFault.class, () -> state.cbits().finalizerLabel("token_environment").finalizerFunction().invoke(token, token));
                assertEquals(11L, com.oracle.truffle.api.interop.InteropLibrary.getUncached().execute(getter.getReceiver()));
                java.lang.ref.Reference.reachabilityFence(key);
            } finally { context.leave(); }
        }
    }

    private static final class Entry extends RootNode {
        private final CFinalizerFunction function;
        int compiledCalls;
        Entry(Language language, CFinalizerFunction function) { super(language); this.function = function; }
        @Override public Object execute(VirtualFrame frame) {
            if (com.oracle.truffle.api.CompilerDirectives.inCompiledCode()) compiledCalls++;
            if (frame.getArguments().length == 1) function.invoke((ManagedAddress) frame.getArguments()[0]);
            else function.invoke((ManagedAddress) frame.getArguments()[0], (ManagedAddress) frame.getArguments()[1]);
            return Unit.INSTANCE;
        }
    }
    @Test void firstInstalledCallbackUsesTheLivePointerAndRetainsItsCode() throws Exception {
        var link = library();
        for (boolean hasEnvironment : new boolean[]{false, true}) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); state.getPackageCbits().link(link);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var entry = new Entry(language, state.cbits().finalizerLabel(hasEnvironment ? "environment" : "first").finalizerFunction());
                var target = entry.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                // Same public compile prerequisite as EntryValue; no guest warmup or retry.
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                byte[] environment = {4}, bytes = {2};
                if (hasEnvironment) target.call(ManagedAddress.fromByteArray(environment), ManagedAddress.fromByteArray(bytes));
                else target.call(ManagedAddress.fromByteArray(bytes));
                assertArrayEquals(new byte[]{hasEnvironment ? (byte) 7 : (byte) 4}, environment);
                assertArrayEquals(new byte[]{hasEnvironment ? (byte) 27 : (byte) 21}, bytes,
                    "the first and only invocation runs C once");
                assertEquals(1, entry.compiledCalls, "the first invocation entered installed guest code");
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            } finally { context.leave(); }
        }
    }
}
