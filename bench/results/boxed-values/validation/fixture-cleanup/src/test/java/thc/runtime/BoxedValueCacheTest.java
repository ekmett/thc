// Later Java translation; original measured source is identified in bench/HISTORICAL-SOURCE-PORTS.md.
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class BoxedValueCacheTest {
    private record Builtin(String id, String name, String rep, long minimum, long maximum) {}
    private final List<Builtin> builtins = List.of(new Builtin("ghc-internal:GHC.Internal.Types.I#", "I#", "IntRep", -16, 255), new Builtin("ghc-internal:GHC.Internal.Types.C#", "C#", "WordRep", 0, 255));
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface LanguageAction { void run(Language language) throws Exception; }
    private void property(String name, String value, Action action) throws Exception { String before = System.getProperty(name); try { if (value == null) System.clearProperty(name); else System.setProperty(name, value); action.run(); } finally { if (before == null) System.clearProperty(name); else System.setProperty(name, before); } }
    private void cache(boolean enabled, Action action) throws Exception { property("thc.boxedValueCache", enabled ? "true" : null, action); }
    private void context(String strategy, LanguageAction action) throws Exception { try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) { context.initialize("thc"); context.enter(); try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); } } }
    private DataLayout layout(Language language, Builtin builtin) { return new DataLayout(language, builtin.id, builtin.name, new String[] {builtin.rep}); }
    private void compile(RootCallTarget target) throws Exception { var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); type.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, type.getMethod("isValidLastTier").invoke(target)); }
    private Map<String, Object> exported(String path, String id) throws Exception { var module = (Map<String, Object>) Json.parse(Files.readString(new File(System.getProperty("thc.projectRoot"), path).toPath())); Map<String, Object> result = null; for (var constructor : (List<Map<String, Object>>) module.get("constructors")) if (Objects.equals(constructor.get("id"), id)) { if (result != null) throw new IllegalArgumentException("Duplicate constructor"); result = constructor; } return Objects.requireNonNull(result); }
    private Map<String, Object> metadata(Builtin builtin) throws Exception { return exported("build/core/CbvAudit.json", builtin.id); }
    @Test public void wiredConstructorIdentitiesAndPrimitiveRepresentationsMatchActualPinnedExports() throws Exception { for (var builtin : builtins) { var constructor = metadata(builtin); assertEquals(builtin.name, constructor.get("name")); assertEquals(1, ((Number) constructor.get("arity")).intValue()); assertEquals("boxed", constructor.get("kind")); assertEquals(List.of(List.of(builtin.rep)), constructor.get("fieldReps")); assertEquals(List.of(false), constructor.get("fieldLifted")); } }
    @Test public void exactGhcRangesReuseImmutableValuesWhileEveryOtherPayloadStaysFullWidth() throws Exception { cache(true, () -> { for (var strategy : List.of("field-based", "array-based")) context(strategy, language -> { for (var builtin : builtins) {
        var layout = layout(language, builtin); assertTrue(layout.getHasBoxedValueCache()); var cached = new ArrayList<DataValue>(); for (long n = builtin.minimum; n <= builtin.maximum; n++) cached.add(layout.createLong(n));
        for (long n = builtin.minimum; n <= builtin.maximum; n++) { int index = (int) (n - builtin.minimum); assertSame(cached.get(index), layout.create(new Object[] {n}), strategy + " " + builtin.name + " " + n); assertEquals(n, layout.readLong(cached.get(index), 0)); if (index > 0) assertNotSame(cached.get(index - 1), cached.get(index)); }
        // Historical raw WordRep semantics include invalid Unicode and all machine bits.
        for (long n : new long[] {builtin.minimum - 1, builtin.maximum + 1, 0x10000L, 0x10ffffL, 3000000017L, Long.MIN_VALUE, Long.MAX_VALUE}) { var first = layout.createLong(n); assertNotSame(first, layout.create(new Object[] {n})); assertEquals(n, layout.readLong(first, 0)); }
        assertThrows(RuntimeFault.class, () -> layout.create(new Object[] {1})); assertThrows(RuntimeFault.class, () -> layout.create(new Object[0])); var fresh = layout.allocate(); layout.initializeLong(fresh, 0, 7); assertNotSame(fresh, layout.createLong(7)); assertEquals(7L, layout.readLong(fresh, 0));
    } }); }); }
    @Test public void defaultOffAndNonBuiltinConstructorsKeepOrdinaryAllocation() throws Exception { cache(false, () -> { for (var strategy : List.of("field-based", "array-based")) context(strategy, language -> { for (var builtin : builtins) { var off = layout(language, builtin); assertFalse(off.getHasBoxedValueCache()); assertNotSame(off.createLong(7), off.createLong(7)); cache(true, () -> { assertFalse(off.getHasBoxedValueCache()); assertNotSame(off.createLong(7), off.createLong(7)); for (var other : List.of(new DataLayout(language, "user:" + builtin.id, builtin.name, new String[] {builtin.rep}), new DataLayout(language, builtin.id, "Other", new String[] {builtin.rep}), new DataLayout(language, builtin.id, builtin.name, new String[] {"Int64Rep"}), new DataLayout(language, builtin.id, builtin.name, new String[] {"LiftedRep"}))) { assertFalse(other.getHasBoxedValueCache()); assertNotSame(other.create(new Object[] {7L}), other.create(new Object[] {7L})); } }); } }); }); }
    @Test public void cachesRemainOwnedByTheirLayoutAcrossSharedArrayCarriersAndContexts() throws Exception { cache(true, () -> property("thc.constructorClassIdentity", "true", () -> { for (var strategy : List.of("field-based", "array-based")) { var first = new DataLayout[1]; var value = new DataValue[1]; context(strategy, language -> { first[0] = layout(language, builtins.getFirst()); value[0] = first[0].createLong(7); var second = layout(language, builtins.getFirst()); var other = second.createLong(7); assertNotSame(value[0], other); assertTrue(first[0].matches(value[0])); assertFalse(first[0].matches(other)); assertTrue(second.matches(other)); assertFalse(second.matches(value[0])); assertThrows(RuntimeFault.class, () -> second.readLong(value[0], 0)); if (strategy.equals("array-based")) assertSame(value[0].getClass(), other.getClass()); }); context(strategy, language -> { var another = layout(language, builtins.getFirst()); var other = another.createLong(7); assertNotSame(value[0], other); assertFalse(first[0].matches(other)); assertFalse(another.matches(value[0])); assertEquals(7L, first[0].readLong(value[0], 0)); assertEquals(7L, another.readLong(other, 0)); }); } })); }
    private Map<String, Object> map(Object... values) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private Map<String, Object> module(Builtin builtin) throws Exception {
        var proof = map("kind", "long", "primReps", List.of(builtin.rep), "evaluated", true); var parameter = map("id", "x", "name", "x", "lifted", false, "coercion", false, "rep", proof);
        // The historical primitive body is retained verbatim in meaning, not modernized.
        var operand = List.of("app", List.of("prim", "+#"), List.of(List.of("var", "x"), List.of("lit", "int", "1")), List.of(false, false), true, true); var construct = List.of("app", List.of("con", builtin.id, 1), List.of(operand), List.of(false), true, true);
        return map("instrument", true, "constructors", List.of(metadata(builtin)), "bindings", List.of(map("id", "make", "name", "make", "lifted", true, "expr", List.of("lam", List.of(parameter), construct))));
    }
    private DataValue call(ExecutableProgram program, long n) { return (DataValue) Calls.target(program.hostEntryTarget(1), new Object[] {program.entryValue("make"), new Object[] {n}}); }
    private void check(ExecutableProgram program, long n, String label) { var value = call(program, n); assertEquals(n + 1, value.getLayout().readLong(value, 0), label); }
    @Test public void bothBackendsSwitchBetweenCachedAndFullWidthConstructorsAfterCompilation() throws Exception { for (boolean enabled : new boolean[] {false, true}) cache(enabled, () -> { for (var strategy : List.of("field-based", "array-based")) context(strategy, language -> { for (var backend : List.of("ast", "bytecode")) for (var builtin : builtins) {
        ExecutableProgram program = backend.equals("ast") ? new Program(language, module(builtin)) : new BytecodeProgram(language, module(builtin)); var anchor = call(program, 6); for (int i = 0; i < 30; i++) { var value = call(program, i); assertEquals(i + 1L, value.getLayout().readLong(value, 0)); } compile(program.entryTarget("make")); String label = enabled + " " + strategy + " " + backend + " " + builtin.name;
        for (long n : new long[] {builtin.minimum - 2, builtin.minimum - 1, builtin.maximum - 1, builtin.maximum, 0x10ffffL - 1, 3000000017L, Long.MIN_VALUE, Long.MAX_VALUE}) check(program, n, label); compile(program.entryTarget("make")); check(program, Long.MAX_VALUE - 1, label); if (enabled) assertSame(anchor, call(program, 6)); else assertNotSame(anchor, call(program, 6)); assertEquals(7L, anchor.getLayout().readLong(anchor, 0), "Cold large writes must not mutate cached values");
    } }); }); }
}
