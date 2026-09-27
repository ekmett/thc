// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.BytecodeProgram;
import thc.runtime.Calls;
import thc.runtime.ExecutableProgram;
import thc.runtime.Program;

import static org.junit.jupiter.api.Assertions.*;

public final class CoreJsonDiagnosticsTest {
    // Preserve the original fixture's JSON field order: demand counters observe
    // source block traversal, so Map.of's unspecified iteration order is not suitable.
    private Map<String, Object> orderedMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
    private CoreJsonIndex source(Object value) {
        return CoreJsonIndex.Companion.fromBytes(Json.INSTANCE.stringify(value).getBytes(StandardCharsets.UTF_8));
    }
    private Map<String, Object> binding(String id, Object expression) {
        return orderedMap("id", id, "name", id, "arity", 1, "type", "Int# -> Int#", "lifted", true,
            "expr", List.of("lam", List.of(orderedMap("id", "x", "name", "x", "type", "Int#",
                "lifted", false, "coercion", false)), expression));
    }
    private long count(ExecutableProgram program, String key) {
        return ((Number) Objects.requireNonNull(program.diagnostics().get(key))).longValue();
    }

    @Test void counterHandlesHaveOnlyScalarFieldsAndKeepLazyHashAndCloseTotals() {
        for (var type : List.of(CoreJsonIndex.Counters.class, CoreJsonBindings.Counters.class)) {
            assertTrue(Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> field.getType().isPrimitive() || field.getType() == Long.class),
                "Detached handles must not capture source, adapter, arrays, maps or callbacks: " + type);
        }
        var input = source(Map.of("items", List.of("a", "b")));
        var totals = input.getCounters();
        var before = totals.statistics();
        assertEquals(0L, before.getSourceHashBytesScanned());
        assertEquals(0L, before.getSourceIdentityBytes());
        input.sha256();
        var hashed = totals.statistics();
        assertEquals((long) before.getSourceByteSize(), hashed.getSourceHashBytesScanned());
        assertEquals(32L, hashed.getSourceIdentityBytes());
        assertEquals(before.getIndexByteSize() + 32, hashed.getIndexByteSize());
        input.sha256();
        assertEquals(hashed, totals.statistics(), "second digest access adds no work or identity allocation");
        Objects.requireNonNull(input.getRoot().member("items")).elements().get(1).decode();
        var demanded = totals.statistics();
        assertEquals(1L, demanded.getDecodedSpanCount());
        assertTrue(demanded.getDecodedByteCount() > 0);
        assertTrue(demanded.getNavigationByteReads() > before.getNavigationByteReads());
        assertTrue(demanded.getRegeneratedSourceBytes() > before.getRegeneratedSourceBytes());
        assertEquals(demanded, input.statistics());
        input.close();
        assertThrows(IllegalStateException.class, input::statistics);
        assertEquals(demanded, totals.statistics(), "admission/work totals survive storage disposal");
    }

    private record Prepared(ExecutableProgram program, Object entry, List<WeakReference<?>> unused,
                            WeakReference<CoreJsonIndex> liveSource, long admittedBytes, long admittedIndexBytes) {}

    /** Separate stack frame: only the selected Program and weak ownership probes escape. */
    private Prepared prepare(Language language, String backend, boolean sharedAdapter) {
        var live = source(orderedMap("schema", 1, "ghc", "9.14.1", "unit", "test", "module", "Live",
            "constructors", List.of(), "bindings", List.of(
                binding("test:Live.entry", List.of("app", List.of("var", "test:Live.cold"), List.of(List.of("var", "x")), List.of(false))),
                binding("test:Live.cold", List.of("app", List.of("prim", "+#"), List.of(List.of("var", "x"),
                    List.of("lit", "int", "3")), List.of(false, false))))));
        // No source/constructor/foreign metadata legitimately retains this module.
        var unused = source(orderedMap("schema", 1, "ghc", "9.14.1", "unit", "test", "module", "Unused",
            "constructors", List.of(), "bindings", List.of(
                binding("test:Unused.never", List.of("unsupported", "unreachable ".repeat(1024))))));
        var adapter = new CoreJsonBindings(false);
        var unusedAdapter = sharedAdapter ? adapter : new CoreJsonBindings(false);
        var totals = new CoreJsonLoadingStatistics();
        totals.include(live, adapter);
        totals.include(unused, unusedAdapter);
        var merged = CoreModules.INSTANCE.merge(List.of(adapter.module(live.getRoot()), unusedAdapter.module(unused.getRoot())));
        Map<String, Object> linked = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(merged, "test:Live.entry", true));
        linked.put("instrument", true);
        linked.put("sourceNotesEnabled", false);
        linked.put("coreLoadingStatistics", totals);
        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, false, false)
            : new BytecodeProgram(language, linked, false);
        var entry = program.entryValue("test:Live.entry");
        List<WeakReference<?>> discarded = new ArrayList<>();
        discarded.add(new WeakReference<>(unused));
        if (!sharedAdapter) discarded.add(new WeakReference<>(unusedAdapter));
        return new Prepared(program, entry, discarded, new WeakReference<>(live),
            (long) live.statistics().getSourceByteSize() + unused.statistics().getSourceByteSize(),
            live.statistics().getIndexByteSize() + unused.statistics().getIndexByteSize());
    }

    private void reclaimed(List<WeakReference<?>> references) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) return;
            assertTrue(System.nanoTime() < deadline, "Diagnostics retained an unused module source/adapter");
            Thread.sleep(10);
        }
    }
    private Map<String, Object> jsonDiagnostics(ExecutableProgram program) {
        Map<String, Object> result = new LinkedHashMap<>();
        program.diagnostics().forEach((key, value) -> { if (key.startsWith("json")) result.put(key, value); });
        return result;
    }
    private Object call(Prepared prepared) {
        return Calls.target(prepared.program().hostEntryTarget(1), new Object[] {prepared.entry(), new Object[] {7L}});
    }

    @Test void unusedModuleIsCollectibleWhileColdCalleeDemandUpdatesCumulativeDiagnosticsOnce() throws InterruptedException {
        for (String backend : List.of("ast", "bytecode")) for (boolean sharedAdapter : new boolean[] {false, true}) {
            try (Context context = MainKt.executionContext(false)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var prepared = prepare(language, backend, sharedAdapter);
                    var program = prepared.program();
                    assertEquals(3L, count(program, "jsonBindingHeaders"), "one shared adapter is counted once");
                    assertEquals(1L, count(program, "jsonBodyMaterializations"));
                    assertEquals(1L, count(program, "loweredRootCount"));
                    var before = jsonDiagnostics(program);
                    reclaimed(prepared.unused());
                    assertNotNull(prepared.liveSource().get(), "reachable cold spans still own their immutable source");
                    assertEquals(before, jsonDiagnostics(program));
                    assertEquals(prepared.admittedBytes(), count(program, "jsonSourceBytes"));
                    assertEquals(prepared.admittedIndexBytes(), count(program, "jsonIndexPrimitiveBytes"));
                    assertEquals(10L, call(prepared));
                    assertEquals(2L, count(program, "jsonBodyMaterializations"));
                    assertEquals(2L, count(program, "loweredRootCount"));
                    for (String key : List.of("jsonDecodedSpanCount", "jsonDecodedByteCount", "jsonNavigationByteReads",
                            "jsonRegeneratedSourceBytes", "jsonExpressionViews", "jsonScalarDecodes")) {
                        assertTrue(count(program, key) > ((Number) Objects.requireNonNull(before.get(key))).longValue(), key);
                    }
                    var after = jsonDiagnostics(program);
                    assertEquals(10L, call(prepared));
                    assertEquals(after, jsonDiagnostics(program), "cached callee does not decode/materialize again");
                    Reference.reachabilityFence(prepared);
                } finally { context.leave(); }
            }
        }
    }
}
