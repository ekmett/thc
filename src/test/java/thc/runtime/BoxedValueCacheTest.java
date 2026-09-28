// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class BoxedValueCacheTest {
    private record Builtin(String id, String name, String rep, long minimum, long maximum) {}
    private final List<Builtin> builtins = list(new Builtin(DataValuesKt.BOXED_INT_CONSTRUCTOR_ID, "I#", "IntRep", -16, 255), new Builtin(DataValuesKt.BOXED_CHAR_CONSTRUCTOR_ID, "C#", "WordRep", 0, 255));
    private void property(String name, String value, CheckedRunnable action) throws Exception {
        var before = System.getProperty(name);
        try { if (value == null) System.clearProperty(name); else System.setProperty(name, value); action.run(); }
        finally { if (before == null) System.clearProperty(name); else System.setProperty(name, before); }
    }
    private void cache(boolean enabled, CheckedRunnable action) throws Exception { property(DataValuesKt.BOXED_VALUE_CACHE_PROPERTY, enabled ? "true" : null, action); }
    private void context(String strategy, CheckedConsumer<Language> action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private DataLayout layout(Language language, Builtin builtin) { return new DataLayout(language, builtin.id(), builtin.name(), new String[]{builtin.rep()}); }
    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }
    private Map<String, Object> exported(String path, String id) throws Exception {
        var module = object(Json.parse(Files.readString(Path.of(System.getProperty("thc.projectRoot"), path))));
        var matches = objects(module.get("constructors")).stream().filter(c -> id.equals(c.get("id"))).toList();
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    private Map<String, Object> metadata(Builtin builtin) throws Exception { return exported("build/core/CBVAudit.json", builtin.id()); }
    @Test void wiredConstructorIdentitiesAndPrimitiveRepresentationsMatchActualPinnedExports() throws Exception {
        for (var builtin : builtins) {
            var constructor = metadata(builtin); assertEquals(builtin.name(), constructor.get("name")); assertEquals(1, ((Number) constructor.get("arity")).intValue());
            assertEquals("boxed", constructor.get("kind")); assertEquals(list(list(builtin.rep())), constructor.get("fieldReps")); assertEquals(list(false), constructor.get("fieldLifted"));
        }
    }
    @Test void exactGhcRangesReuseImmutableValuesWhileEveryOtherPayloadStaysFullWidth() throws Exception {
        cache(true, () -> {
            for (var strategy : list("field-based", "array-based")) context(strategy, language -> {
                for (var builtin : builtins) {
                    var layout = layout(language, builtin); assertTrue(layout.getHasBoxedValueCache());
                    var cached = new ArrayList<DataValue>();
                    for (long n = builtin.minimum(); n <= builtin.maximum(); n++) cached.add(layout.createLong(n));
                    for (long n = builtin.minimum(); n <= builtin.maximum(); n++) {
                        int index = (int) (n - builtin.minimum());
                        assertSame(cached.get(index), layout.create(new Object[]{n}), strategy + " " + builtin.name() + " " + n);
                        assertEquals(n, layout.readLong(cached.get(index), 0)); if (index > 0) assertNotSame(cached.get(index - 1), cached.get(index));
                    }
                    // Raw WordRep payloads include Unicode and full machine-width values.
                    for (long n : new long[]{builtin.minimum() - 1, builtin.maximum() + 1, 0x10000L, 0x10ffffL, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                        var first = layout.createLong(n); assertNotSame(first, layout.create(new Object[]{n})); assertEquals(n, layout.readLong(first, 0));
                    }
                    assertThrows(RuntimeFault.class, () -> layout.create(new Object[]{1})); assertThrows(RuntimeFault.class, () -> layout.create(new Object[0]));
                    // Initialization must use a fresh allocation, never a cached immutable value.
                    var fresh = layout.allocate(); layout.initializeLong(fresh, 0, 7);
                    assertNotSame(fresh, layout.createLong(7)); assertEquals(7L, layout.readLong(fresh, 0));
                }
            });
        });
    }
    @Test void defaultOffAndNonBuiltinConstructorsKeepOrdinaryAllocation() throws Exception {
        cache(false, () -> {
            for (var strategy : list("field-based", "array-based")) context(strategy, language -> {
                for (var builtin : builtins) {
                    var off = layout(language, builtin); assertFalse(off.getHasBoxedValueCache()); assertNotSame(off.createLong(7), off.createLong(7));
                    cache(true, () -> {
                        // Options are fixed at construction, never polled in hot code.
                        assertFalse(off.getHasBoxedValueCache()); assertNotSame(off.createLong(7), off.createLong(7));
                        for (var other : list(new DataLayout(language, "user:" + builtin.id(), builtin.name(), new String[]{builtin.rep()}),
                                new DataLayout(language, builtin.id(), "Other", new String[]{builtin.rep()}), new DataLayout(language, builtin.id(), builtin.name(), new String[]{"Int64Rep"}),
                                new DataLayout(language, builtin.id(), builtin.name(), new String[]{"LiftedRep"}))) {
                            assertFalse(other.getHasBoxedValueCache()); assertNotSame(other.create(new Object[]{7L}), other.create(new Object[]{7L}));
                        }
                    });
                }
            });
        });
    }
    private record Retained(DataLayout layout, DataValue value) {}
    @Test void cachesRemainOwnedByTheirLayoutAcrossSharedArrayCarriersAndContexts() throws Exception {
        cache(true, () -> property(ConstructorClassIdentityKt.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY, "true", () -> {
            for (var strategy : list("field-based", "array-based")) {
                var saved = new Retained[1];
                context(strategy, language -> {
                    var first = layout(language, builtins.getFirst()); var value = first.createLong(7); saved[0] = new Retained(first, value);
                    var second = layout(language, builtins.getFirst()); var other = second.createLong(7);
                    assertNotSame(value, other); assertTrue(first.matches(value)); assertFalse(first.matches(other)); assertTrue(second.matches(other)); assertFalse(second.matches(value));
                    assertThrows(RuntimeFault.class, () -> second.readLong(value, 0));
                    if (strategy.equals("array-based")) {
                        if (value instanceof LayoutDataValue) assertSame(value.getClass(), other.getClass());
                        else { assertTrue(other instanceof LayoutDataValue); assertNotSame(value.getClass(), other.getClass()); }
                    }
                });
                context(strategy, language -> {
                    var first = saved[0].layout(); var value = saved[0].value(); var another = layout(language, builtins.getFirst()); var other = another.createLong(7);
                    assertNotSame(value, other); assertFalse(first.matches(other)); assertFalse(another.matches(value)); assertEquals(7L, first.readLong(value, 0)); assertEquals(7L, another.readLong(other, 0));
                });
            }
        }));
    }
    private Map<String, Object> module(Builtin builtin) throws Exception {
        var proof = map("kind", "long", "primReps", list(builtin.rep()), "evaluated", true);
        var parameter = map("id", "x", "name", "x", "lifted", false, "coercion", false, "rep", proof); boolean word = builtin.rep().equals("WordRep");
        // The field expression executes before cache lookup in both backends.
        var operand = list("app", list("prim", word ? "plusWord#" : "+#"), list(list("var", "x", map("rep", proof)), list("lit", word ? "word" : "int", "1", map("rep", proof))), list(false, false), true, true, map("rep", proof));
        var construct = list("app", list("con", builtin.id(), 1), list(operand), list(false), true, true);
        return map("instrument", true, "constructors", list(metadata(builtin)), "bindings", list(map("id", "make", "name", "make", "lifted", true, "expr", list("lam", list(parameter), construct))));
    }
    private DataValue call(ExecutableProgram program, long n) { return (DataValue) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("make"), new Object[]{n}}); }
    private void check(ExecutableProgram program, long n, String label) { var value = call(program, n); assertEquals(n + 1, value.getLayout().readLong(value, 0), label); }
    @Test void bothBackendsSwitchBetweenCachedAndFullWidthConstructorsAfterCompilation() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) cache(enabled, () -> {
            for (var strategy : list("field-based", "array-based")) context(strategy, language -> {
                for (var backend : list("ast", "bytecode")) for (var builtin : builtins) {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module(builtin)) : new BytecodeProgram(language, module(builtin));
                    var anchor = call(program, 6);
                    for (int i = 0; i < 30; i++) { var value = call(program, i); assertEquals((long) i + 1, value.getLayout().readLong(value, 0)); }
                    compile(program.entryTarget("make")); var label = enabled + " " + strategy + " " + backend + " " + builtin.name();
                    for (long n : new long[]{builtin.minimum() - 2, builtin.minimum() - 1, builtin.maximum() - 1, builtin.maximum(), 0x10ffffL - 1, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE}) check(program, n, label);
                    compile(program.entryTarget("make")); check(program, Long.MAX_VALUE - 1, label);
                    if (enabled) assertSame(anchor, call(program, 6)); else assertNotSame(anchor, call(program, 6));
                    assertEquals(7L, anchor.getLayout().readLong(anchor, 0), "Cold large writes must not mutate cached values");
                }
            });
        });
    }
}
