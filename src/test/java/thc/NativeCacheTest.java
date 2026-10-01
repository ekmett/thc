// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

class NativeCacheTest {
    @TempDir Path directory;

    @Test void cachedReturnRejectsLateInvalidationOfAnotherSavedTarget() throws Exception {
        cachedReturn("invalidate");
    }

    @Test void cachedReturnChecksLoansOnTheHostedGuestThread() throws Exception {
        for (String state : List.of("clean", "arguments", "results", "pending")) cachedReturn(state);
    }

    private Map<String, Object> identity(String name) {
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        return map("id", name, "name", name, "arity", 1, "lifted", true, "rep", closure,
            "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "rep", word)),
                list("var", "x"), map("rep", closure, "resultRep", word)));
    }

    private void cachedReturn(String fault) throws Exception {
        String oldCached = System.getProperty("thc.requireCachedCode"), oldCompiled = System.getProperty("thc.requireCompiledCode");
        var caller = Thread.currentThread();
        var guestThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var guestState = new java.util.concurrent.atomic.AtomicReference<HandoffState>();
        var cleanup = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("thc.ThreadHosting", "loom").option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            Language language;
            EntryValue entry;
            try {
                language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, map("schema", 1, "ghc", "9.14.1", "module", "CacheReturn",
                    "constructors", list(), "bindings", list(identity("read"), identity("other"))), List.of("read", "other"));
                var program = code.newInstance(language);
                var other = (com.oracle.truffle.runtime.OptimizedCallTarget) program.entryTarget("other");
                for (String name : List.of("read", "other")) {
                    var target = (com.oracle.truffle.runtime.OptimizedCallTarget) program.entryTarget(name);
                    assertFalse(target.wasExecuted());
                    assertTrue(target.prepareForAOT()); target.compile(true);
                    assertFalse(target.wasExecuted());
                }
                code.requireInstalledCode();
                var host = program.hostEntryTarget(1);
                var observed = new com.oracle.truffle.api.nodes.RootNode(language) {
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                        Object result = Calls.target(host, frame.getArguments());
                        guestThread.set(Thread.currentThread());
                        var state = language.getHandoffState().get(); guestState.set(state);
                        if (fault.equals("invalidate")) other.invalidate("late saved-target test");
                        else if (!fault.equals("clean")) {
                            var layout = language.getHandoffLayouts().intern(List.of("BoxedRep (Just Lifted)"));
                            if (fault.equals("pending")) {
                                state.setPending(layout.create()); cleanup.set(() -> state.setPending(null));
                            } else {
                                var loan = fault.equals("arguments") ? state.getArguments().acquire(layout) : state.getResults().acquire(layout);
                                layout.setObject(loan, 0, new Object());
                                cleanup.set(() -> { if (fault.equals("arguments")) state.getArguments().release(loan);
                                    else state.getResults().release(loan, layout); });
                            }
                        }
                        return result;
                    }
                }.getCallTarget();
                ExecutableProgram observedProgram = new ExecutableProgram() {
                    @Override public boolean getAsynchronousExceptions() { return false; }
                    @Override public com.oracle.truffle.api.RootCallTarget hostEntryTarget(int arity) { return observed; }
                    @Override public Object entryValue(String name) { return program.entryValue(name); }
                    @Override public com.oracle.truffle.api.RootCallTarget entryTarget(String name) { return program.entryTarget(name); }
                    @Override public DataLayout constructorLayout(String id) { return program.constructorLayout(id); }
                    @Override public Map<String, Object> diagnostics() { return program.diagnostics(); }
                };
                System.setProperty("thc.requireCachedCode", "true"); System.setProperty("thc.requireCompiledCode", "true");
                entry = new EntryValue(observedProgram, "read", 1, null, null, language, null, null, false, null, null, code);
            } finally { context.leave(); }
            try {
                if (fault.equals("clean")) assertEquals(42L, context.asValue(entry).execute(42L).asLong());
                else {
                    var failure = assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> context.asValue(entry).execute(42L));
                    assertTrue(failure.getMessage().contains(fault.equals("invalidate")
                        ? "Cached compiled target required" : "Cached guest returned with outstanding handoff state"), failure.getMessage());
                }
                assertNotSame(caller, guestThread.get(), "exercise the hosted guest-return thread");
                context.enter();
                try {
                    var callerState = language.getHandoffState().get();
                    assertNotSame(callerState, guestState.get());
                    assertNull(callerState.getPending()); assertEquals(0, callerState.getArguments().getDepth());
                    assertEquals(0, callerState.getResults().getDepth());
                } finally { context.leave(); }
            } finally { cleanup.get().run(); }
        } finally {
            if (oldCached == null) System.clearProperty("thc.requireCachedCode"); else System.setProperty("thc.requireCachedCode", oldCached);
            if (oldCompiled == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", oldCompiled);
        }
    }

    @Test void publicCachedSourceIdentityAndDynamicArgumentsUseFreshPrograms() throws Exception {
        String module = """
            {"schema":1,"ghc":"9.14.1","unit":"fixture","module":"CacheTest","boundary":"optimized-Core-after-Tidy-before-CorePrep","constructors":[],"bindings":[
              {"id":"plus","name":"plus","arity":2,"lifted":true,
               "rep":{"kind":"closure","evaluated":true,"primReps":["BoxedRep (Just Lifted)"]},
               "expr":["lam",[
                 {"id":"x","name":"x","type":"Int#","lifted":false,"coercion":false,"rep":{"kind":"long","evaluated":true,"primReps":["IntRep"]}},
                 {"id":"y","name":"y","type":"Int#","lifted":false,"coercion":false,"rep":{"kind":"long","evaluated":true,"primReps":["IntRep"]}}],
                 ["app",["prim","+#",{}],[["var","x",{}],["var","y",{}]],[false,false],false,false,{}],
                 {"rep":{"kind":"closure","evaluated":true,"primReps":["BoxedRep (Just Lifted)"]},
                  "resultRep":{"kind":"long","evaluated":true,"primReps":["IntRep"]}}]}]}
            """;
        Path file = CoreCbdFixtures.write(directory.resolve("module.cbd"), (Map<String,Object>) Json.parse(module));
        String request = NativeCache.request(List.of(file.toString()), "plus");
        Map<?, ?> document = (Map<?, ?>) Json.parse(request);
        assertEquals(true, document.get("prepareCode"));
        assertEquals(false, document.get("asyncExceptions"));
        assertEquals("ast", document.get("backend"));
        Source source = Source.newBuilder("thc", request, NativeCache.sourceName(request)).cached(true).buildLiteral();
        try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            assertThrows(IllegalStateException.class, () -> NativeCache.selectedSource(engine));
            try (Context preparation = Context.newBuilder("thc").engine(engine).build()) { preparation.parse(source); }
            assertEquals(source, NativeCache.selectedSource(engine));
            var artifact = Files.readAllBytes(file);
            CoreFileMappings.shared.evictIdleBelow(directory);
            Files.delete(file);
            for (int i = 0; i < 2; i++) {
                try (Context context = Context.newBuilder("thc").engine(engine).build()) {
                    var first = context.parse(NativeCache.selectedSource(engine)).execute();
                    var second = context.parse(NativeCache.selectedSource(engine)).execute();
                    assertEquals(47L, first.execute(40L, 7L).asLong());
                    assertEquals(-8L, second.execute(3L, -11L).asLong());
                    assertEquals(Long.MIN_VALUE, first.execute(Long.MAX_VALUE, 1L).asLong());
                    assertEquals(0L, ((Map<?, ?>) Json.parse(first.getMember("diagnostics").asString())).get("loweredRootCount"));
                }
            }
            // A second selected entry/source must not silently select an arbitrary cache member.
            Files.write(file, artifact);
            String secondRequest = request + " ";
            try (Context context = Context.newBuilder("thc").engine(engine).build()) {
                context.parse(Source.newBuilder("thc", secondRequest, NativeCache.sourceName(secondRequest)).cached(true).buildLiteral());
                assertThrows(IllegalStateException.class, () -> NativeCache.selectedSource(engine));
            }
        }
    }

    @Test void invalidSelectionAndArgumentsFailBeforeProviderUse() {
        assertThrows(IllegalArgumentException.class, () -> NativeCache.request(List.of(), "plus"));
        assertThrows(IllegalArgumentException.class, () -> NativeCache.request(List.of(""), "plus"));
        assertThrows(IllegalArgumentException.class, () -> NativeCache.main(new String[]{"store", "cache"}));
        assertNotEquals(NativeCache.sourceName("a"), NativeCache.sourceName("b"));
    }

    @Test void ioStoreOptionsReachTheImageRequirementWithoutGuestExecution() {
        var accepted = assertThrows(IllegalStateException.class, () -> NativeCache.main(new String[]{
            "store", "cache", "module.cbd", "main", "--io-main", "--shutdown-entry=shutdown", "--verify-artifacts"}));
        assertTrue(accepted.getMessage().contains("native-cache image"));
        assertThrows(IllegalArgumentException.class, () -> NativeCache.main(new String[]{
            "store", "cache", "module.cbd", "main", "--shutdown-entry=shutdown"}));
        assertThrows(IllegalArgumentException.class, () -> NativeCache.main(new String[]{
            "store", "cache", "module.cbd", "main", "--io-main", "--unknown"}));
    }

    @Test void commandArgumentsPreserveExplicitNumericCarriers() {
        assertEquals(Long.MIN_VALUE, NativeCache.argument("-9223372036854775808"));
        assertEquals(16777216f, NativeCache.argument("f:16777217"));
        assertEquals(16777217d, NativeCache.argument("d:16777217"));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((Float)NativeCache.argument("f:-0.0")));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits((Double)NativeCache.argument("d:-0.0")));
        assertEquals(Float.POSITIVE_INFINITY, NativeCache.argument("f:Infinity"));
        assertTrue(Double.isNaN((Double)NativeCache.argument("d:NaN")));
        for (String value : List.of("f:", "d:bad", "1.5", "9223372036854775808"))
            assertThrows(NumberFormatException.class, () -> NativeCache.argument(value));
    }

    @Test void compactFloatingLiteralsKeepRawBitsInBothReaders() throws Exception {
        var values = List.of(new thc.runtime.CoreFloatingLiteral.Single(0x80000000),
            new thc.runtime.CoreFloatingLiteral.Single(0x7fc01234), new thc.runtime.CoreFloatingLiteral.Single(1),
            new thc.runtime.CoreFloatingLiteral.Double(0x8000000000000000L),
            new thc.runtime.CoreFloatingLiteral.Double(0x7ff8000000005678L), new thc.runtime.CoreFloatingLiteral.Double(1));
        for (String backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc")
                .allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            for (var value : values) {
                boolean single = value instanceof thc.runtime.CoreFloatingLiteral.Single;
                var proof = map("kind", single ? "float" : "double", "evaluated", true,
                    "primReps", list(single ? "FloatRep" : "DoubleRep"));
                String bits = value instanceof thc.runtime.CoreFloatingLiteral.Single v ? Integer.toUnsignedString(v.bits())
                    : Long.toUnsignedString(((thc.runtime.CoreFloatingLiteral.Double) value).bits());
                var binding = map("id", "value", "name", "value", "arity", 0, "lifted", false, "rep", proof,
                    "expr", list("lit", single ? "float-bits" : "double-bits", bits, map("rep", proof)));
                var model = map("schema", 1, "ghc", "9.14.1", "unit", "fixture", "module", "Floating",
                    "boundary", "optimized-Core-after-Tidy-before-CorePrep", "bindings", list(binding), "constructors", list());
                Path file = CoreCbdFixtures.write(Files.createTempFile(directory, "floating-", ".cbd"), model);
                String request = backend.equals("ast") ? NativeCache.request(List.of(file.toString()), "value") :
                    CoreModules.request(List.of(file.toString()), "value", true, false, backend, false, false, null, false, false);
                var entry = context.eval("thc", request);
                if (value instanceof thc.runtime.CoreFloatingLiteral.Single expected)
                    assertEquals(expected.bits(), Float.floatToRawIntBits(entry.execute().asFloat()));
                else assertEquals(((thc.runtime.CoreFloatingLiteral.Double)value).bits(),
                    Double.doubleToRawLongBits(entry.execute().asDouble()));
            }
        }
    }
}
