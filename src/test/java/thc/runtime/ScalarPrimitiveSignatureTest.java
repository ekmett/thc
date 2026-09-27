// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public final class ScalarPrimitiveSignatureTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> unknown() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "unknown");
        result.put("primReps", null);
        result.put("evaluated", false);
        return result;
    }
    private Map<String, Object> proof(String rep) {
        return new LinkedHashMap<>(Map.of("kind", "long", "primReps", List.of(rep), "evaluated", true));
    }
    private Map<String, Object> module(String path) throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.resolve(path)));
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
    }
    @FunctionalInterface private interface Visit { void accept(Language language, String backend) throws Exception; }
    private void visit(Visit action) throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc");
            context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); }
            finally { context.leave(); }
        }
    }
    private List<Object> app(List<?> left, List<?> right, Map<String, Object> rep) {
        List<Object> result = new ArrayList<>(List.of("app", List.of("prim", "plusInt64#"), List.of(left, right), List.of(false, false)));
        if (rep != null) result.addAll(List.of(false, false, Map.of("rep", rep)));
        return result;
    }
    @Test void missingUnknownAndCorrectLegacyMetadataRemainCompatible() throws Exception {
        visit((language, backend) -> {
            for (var rep : Arrays.asList(null, unknown(), proof("Int64Rep"))) {
                Map<String, Object> binder = new LinkedHashMap<>(Map.of("id", "x", "lifted", false));
                if (rep != null) binder.put("rep", rep);
                List<Object> variable = new ArrayList<>(List.of("var", "x"));
                if (rep != null) variable.add(Map.of("rep", rep));
                // Unknown primitive metadata establishes a Long carrier, not an exact register proof.
                var body = app(app(variable, variable, rep), variable, rep);
                Map<String, Object> input = Map.of("schema", 1, "ghc", "9.14.1", "module", "LegacyScalarSignature",
                    "constructors", List.of(), "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", 1,
                        "lifted", true, "expr", List.of("lam", List.of(binder), body))));
                var program = program(language, input, backend);
                for (long x : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE})
                    assertEquals(x + x + x, Calls.target(program.hostEntryTarget(1), new Object[] {program.entryValue("entry"), new Object[] {x}}));
            }
        });
    }
    private void compile(RootCallTarget target) throws Exception {
        Class<?> type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private void check(RootCallTarget host, Object entry, int arity, long x) {
        assertEquals(arity == 2 ? x + 1 : x, Calls.target(host,
            new Object[] {entry, arity == 2 ? new Object[] {x, 1L} : new Object[] {x}}));
    }
    @Test void nativeNewtypeCastsAroundScalarArithmeticAndConversionKeepCompiledEntries() throws Exception {
        visit((language, backend) -> {
            for (String stage : List.of("core", "cbv-post-core")) for (String name : List.of("addRaw", "rawToWord")) {
                var input = CoreModules.INSTANCE.reachable(module("build/" + stage + "/CBVCoercionAudit.json"), name, false);
                var program = program(language, input, backend);
                int arity = name.equals("addRaw") ? 2 : 1;
                RootCallTarget host = program.hostEntryTarget(arity);
                Object entry = program.entryValue(name);
                List<Long> values = List.of(Long.MIN_VALUE, -4097L, -1L, 0L, 1L, 4097L, Long.MAX_VALUE);
                for (long x : values) check(host, entry, arity, x);
                RootCallTarget target = program.entryTarget(name);
                compile(target);
                for (long x : values.reversed()) {
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    check(host, entry, arity, x);
                    assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                }
                assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target));
            }
        });
    }
}
