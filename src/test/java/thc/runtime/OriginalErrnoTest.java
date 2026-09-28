// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs({OS.LINUX, OS.MAC})
@SuppressWarnings("unchecked")
class OriginalErrnoTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-errno";
    private final List<Long> values = List.of((long) Integer.MIN_VALUE, -1L, 0L, 1L, (long) Integer.MAX_VALUE);
    private Object json(String path) throws Exception { return Json.parse(Files.readString(new File(root, path).toPath())); }
    private Context context() { return Context.newBuilder("thc").allowIO(IOAccess.NONE).out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream())
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private static <T> T entered(Context context, Callable<T> action) throws Exception {
        context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    private ExecutableProgram program(Language language, String backend, Map<String,Object> module) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private Map<String,Object> source(String stage) throws Exception {
        var modules = new ArrayList<Map<String,Object>>(); for (var name : List.of("OriginalErrnoAudit", "THC.InterfaceClosure")) modules.add((Map<String,Object>) json(prefix + "/" + stage + "/core/" + name + ".json"));
        return CoreModules.merge(modules);
    }
    private List<Object> original(Map<String,Object> module, String symbol) {
        return single(foreignCalls(module), call -> Objects.equals(((Map<?,?>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target")).get("symbol"), symbol));
    }
    /** The checked immediate State lambda lowers into the only consumer root. */
    private void checkConsumer(Map<String,Object> module) {
        var binding = single((List<Map<String,Object>>) module.get("bindings"), item -> Objects.equals(item.get("name"), "originalResetErrno"));
        assertEquals(1L, binding.get("arity")); var body = (List<?>) binding.get("expr"); assertEquals("lam", body.get(0));
        int lambdas = 0; for (var node : nodes(body)) if (!node.isEmpty() && Objects.equals(node.get(0), "lam")) lambdas++; assertEquals(2, lambdas);
        var run = (List<?>) body.get(2); assertEquals("app", run.get(0)); assertEquals(list(false), run.get(3));
        var lambda = (List<?>) run.get(1); assertEquals("lam", lambda.get(0));
        var state = single((List<Map<String,Object>>) lambda.get(1), ignored -> true);
        assertEquals("State# RealWorld", state.get("type")); assertEquals(OriginalStdioFixtures.scalar(null), state.get("rep"));
        assertEquals(false, state.get("lifted")); assertEquals(false, state.get("coercion"));
        var argument = single((List<List<?>>) run.get(2), ignored -> true); assertEquals("void", argument.get(0));
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences()); assertNull(state.getPending());
    }
    private List<Map<String,Object>> fixture() throws Exception {
        var manifest = (Map<String,Object>) json(prefix + "/manifest.json");
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(true, manifest.get("strictAccepted"));
        hashes(root, manifest.get("inputHashes"), Set.of("test/fixtures/compiler/OriginalErrnoAudit.hs", "test/fixtures/compiler/OriginalErrnoNative.hs", "test/haskell-fixtures/OriginalStdioFixtures.hs", "bin/core_original_foreign.py"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre", "post")) {
            artifacts.add(prefix + "/" + stage + "/core/OriginalErrnoAudit.json"); artifacts.add(prefix + "/" + stage + "/core/THC.InterfaceClosure.json"); artifacts.add(prefix + "/" + stage + "/originalResetErrno.audit.json");
        }
        hashes(root, manifest.get("artifactHashes"), artifacts, prefix + "/");
        var rows = (List<Map<String,Object>>) json(prefix + "/oracle.json"); var actualValues = new ArrayList<Object>(); for (var row : rows) actualValues.add(row.get("value")); assertEquals(values, actualValues);
        for (var row : rows) {
            var value = row.get("value"); assertEquals(value, row.get("roundTrip")); assertEquals(0L, row.get("successResult"));
            assertEquals(value, row.get("successErrno")); assertEquals(-1L, row.get("failureResult")); assertEquals(StdioHostAbi.load().error(4), row.get("failureErrno")); assertEquals(0L, row.get("resetErrno"));
        }
        return rows;
    }
    @Test void genuineSetterAndGetterMatchNativeOnFirstCompiledCalls() throws Exception {
        var rows = fixture();
        for (var stage : List.of("pre", "post")) {
            var source = source(stage); checkConsumer(source); var setter = original(source, "__hscore_set_errno"); var getter = original(source, "__hscore_get_errno");
            assertEquals(2, foreignCalls(source).size()); audit((Map<String,Object>) json(prefix + "/" + stage + "/originalResetErrno.audit.json"), "main:OriginalErrnoAudit.originalResetErrno", List.of("__hscore_set_errno", "__hscore_get_errno"));
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) { entered(context, () -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var stdio = Language.currentState().getStdio();
                var set = program(language, backend, rawModule(setter, source)); var get = program(language, backend, rawModule(getter, source));
                var reset = program(language, backend, with(CoreModules.reachable(source, "originalResetErrno"), "instrument", true));
                var setTarget = set.entryTarget("entry"); var getTarget = get.entryTarget("entry"); var resetTarget = reset.entryTarget("originalResetErrno");
                var targets = List.of(setTarget, getTarget, resetTarget);
                class Exercise {
                    Object invoke(ExecutableProgram guest, RootCallTarget target, Object[] args, boolean compiled) throws Exception {
                        long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); var result = callScalarTestTarget(target, args);
                        if (compiled) { assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue(), stage + "/" + backend + " enters the one scalar consumer root"); valid(target); }
                        released(language); return result;
                    }
                    void run(boolean compiled) throws Exception {
                        for (var row : rows) {
                            assertEquals(0L, invoke(set, setTarget, new Object[]{0L, ((Number) row.get("value")).intValue(), thc.runtime.Unit.INSTANCE}, compiled));
                            assertEquals(((Number) row.get("roundTrip")).intValue(), invoke(get, getTarget, new Object[]{0L, thc.runtime.Unit.INSTANCE}, compiled));
                            assertEquals(row.get("successResult"), stdio.write(1L, ManagedAddress.fromByteArray(new byte[0]), 0L));
                            assertEquals(((Number) row.get("successErrno")).intValue(), invoke(get, getTarget, new Object[]{0L, thc.runtime.Unit.INSTANCE}, compiled));
                            assertEquals(row.get("failureResult"), stdio.write(-1L, ManagedAddress.fromByteArray(new byte[0]), 0L));
                            assertEquals(((Number) row.get("failureErrno")).intValue(), invoke(get, getTarget, new Object[]{0L, thc.runtime.Unit.INSTANCE}, compiled));
                            assertEquals(row.get("resetErrno"), invoke(reset, resetTarget, new Object[]{0L, 0L}, compiled));
                            assertEquals(0, invoke(get, getTarget, new Object[]{0L, thc.runtime.Unit.INSTANCE}, compiled));
                        }
                    }
                }
                var exercise = new Exercise(); exercise.run(false);
                for (var target : targets) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); } exercise.run(true); return null;
            }); }
        }
    }
    @Test void invalidCarriersAndProofsRejectBeforeChangingErrno() throws Exception {
        var source = source("pre"); var setter = original(source, "__hscore_set_errno");
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) { entered(context, () -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var stdio = Language.currentState().getStdio();
            var target = program(language, backend, rawModule(setter, source)).entryTarget("entry");
            for (Object[] args : new Object[][]{{0L,1L,9L}, {0L,1L,null}, {0L,null,thc.runtime.Unit.INSTANCE}, {0L,true,thc.runtime.Unit.INSTANCE},
                    {0L,ManagedAddress.nullAddress(),thc.runtime.Unit.INSTANCE}, {0L,(long) Integer.MAX_VALUE + 1,thc.runtime.Unit.INSTANCE},
                    {0L,(long) Integer.MIN_VALUE - 1,thc.runtime.Unit.INSTANCE}, {0L,Long.MIN_VALUE,thc.runtime.Unit.INSTANCE}, {0L,Long.MAX_VALUE,thc.runtime.Unit.INSTANCE}}) {
                stdio.setErrno(-17L); assertThrows(Throwable.class, () -> Calls.target(target, args)); assertEquals(-17L, stdio.errno()); released(language);
            }
            for (int i = 0; i <= 1; i++) { int index = i; assertThrows(RuntimeFault.class, () -> program(language, backend, rawModule(setter, source, index))); }
            var scalarModule = rawModule(setter, source); var scalarState = single(foreignCalls(scalarModule), ignored -> true);
            var metadata = (Map<String,Object>) scalarState.get(6); metadata.put("rep", OriginalStdioFixtures.scalar(null));
            ((Map<String,Object>) metadata.get("foreignCall")).put("resultRep", OriginalStdioFixtures.scalar(null, false));
            assertThrows(RuntimeFault.class, () -> program(language, backend, scalarModule));
            for (boolean lowered : new boolean[]{false, true}) {
                var module = (Map<String,Object>) Json.parse(Json.stringify(rawModule(setter, source))); var call = single(foreignCalls(module), ignored -> true);
                if (lowered) ((List<Object>) call.get(2)).set(1, list("lit", "int", "1", map("rep", OriginalStdioFixtures.scalar(null))));
                else call.set(1, list("var", "p0", map("rep", OriginalStdioFixtures.closure())));
                assertThrows(RuntimeFault.class, () -> program(language, backend, module));
            }
            return null;
        }); }
    }
    @Test void errnoIsIsolatedByContextAndCarrierAndSurvivesReentry() throws Exception {
        try (var first = context(); var second = context()) {
            entered(first, () -> { Language.currentState().getStdio().setErrno(-3L); return null; });
            entered(second, () -> { var stdio = Language.currentState().getStdio(); assertEquals(0L, stdio.errno()); stdio.setErrno(7L); return null; });
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> entered(first, () -> { var stdio = Language.currentState().getStdio(); assertEquals(0L, stdio.errno()); stdio.setErrno(Integer.MIN_VALUE); return null; })).get();
                entered(first, () -> { assertEquals(-3L, Language.currentState().getStdio().errno()); return null; });
                worker.submit(() -> entered(first, () -> { assertEquals((long) Integer.MIN_VALUE, Language.currentState().getStdio().errno()); return null; })).get();
            }
            entered(second, () -> { assertEquals(7L, Language.currentState().getStdio().errno()); return null; });
            entered(first, () -> { var stdio = Language.currentState().getStdio(); assertEquals(-3L, stdio.errno()); stdio.setErrno(0L); assertEquals(0L, stdio.errno());
                stdio.setErrno(-1L); stdio.nativeError(0L); assertEquals(-1L, stdio.errno()); return null; });
        }
    }
}
