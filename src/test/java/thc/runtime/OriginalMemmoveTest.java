// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Actual ghc-internal FCallId descriptor from installed Core module
 * sha256 2e57cef6400ed7113800a3e3f607b356b3a5380e5eb50f1e0116abc4338998c0,
 * executed through a small synthetic caller. */
@SuppressWarnings("unchecked")
class OriginalMemmoveTest {
    private final Map<String,Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String,Object> address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
    private Map<String,Object> descriptor() throws Exception {
        try (var stream = getClass().getResourceAsStream("/core/original-memmove-descriptor.json")) {
            return (Map<String,Object>) Json.parse(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private Map<String,Object> module() throws Exception { return module(descriptor()); }
    private Map<String,Object> module(Map<String,Object> declaration) throws Exception {
        // Build the caller from the genuine shape even when only the declaration changes.
        var canonical = descriptor();
        var tuple = (Map<String,Object>) canonical.get("resultRep");
        var fields = (List<Map<String,Object>>) tuple.get("components");
        var formals = new ArrayList<Map<String,Object>>();
        var reps = (List<Map<String,Object>>) canonical.get("argumentReps");
        for (int i = 0; i < reps.size(); i++) formals.add(map("id", "arg" + i, "lifted", false, "rep", with(reps.get(i), "evaluated", true)));
        var args = new ArrayList<Object>();
        for (var formal : formals) args.add(list("var", formal.get("id"), map("rep", formal.get("rep"))));
        var call = list("app", list("var", "original-memmove-id", map("rep", closure)), args,
            list(false, false, false, false), false, false, map("rep", tuple, "foreignCall", declaration));
        var binders = new ArrayList<Object>();
        for (int i = 0; i < fields.size(); i++) binders.add(map("id", i == 0 ? "result-state" : "result-address", "lifted", false, "rep", fields.get(i)));
        var body = list("case", call, "result-tuple", list(list("data", "tuple2", list("result-state", "result-address"),
            list("var", "result-address", map("rep", address)), map("binders", binders))),
            map("rep", address, "binder", map("id", "result-tuple", "lifted", false, "rep", with(tuple, "evaluated", true))));
        var binding = map("id", "move", "name", "move", "arity", 4, "lifted", true, "rep", closure,
            "expr", list("lam", formals, body, map("rep", closure, "resultRep", address)));
        return map("schema", 1, "module", "SyntheticOriginalMemmove", "unit", "test", "ghc", "9.14.1", "instrument", true,
            "bindings", list(binding), "constructors", list(map("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private ExecutableProgram program(Language language, String backend, Map<String,Object> source) {
        return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
    }
    @Test void originalDescriptorMovesOverlappingManagedAndNativeRegionsOnFirstCompiledCall() throws Exception {
        for (String backend : new String[]{"ast", "bytecode"}) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var guest = program(language, backend, module()); var target = guest.entryTarget("move");
                class Exercise {
                    ManagedAddress move(ManagedAddress destination, ManagedAddress source, long count) {
                        return (ManagedAddress) Calls.target(target, new Object[]{0L, destination, source, count, kotlin.Unit.INSTANCE});
                    }
                    List<Long> contents(ManagedAddress base) {
                        var values = new ArrayList<Long>(); for (long i = 0; i < 16; i++) values.add(base.readWord8(i)); return values;
                    }
                    void run(ManagedAddress base, boolean errors) {
                        for (long i = 0; i < 16; i++) base.writeWord8(i, i);
                        var right = base.plus(4); assertSame(right, move(right, base, 8));
                        assertEquals(list(0L,1L,2L,3L,0L,1L,2L,3L,4L,5L,6L,7L,12L,13L,14L,15L), contents(base));
                        for (long i = 0; i < 16; i++) base.writeWord8(i, i);
                        assertSame(base, move(base, right, 8));
                        assertEquals(list(4L,5L,6L,7L,8L,9L,10L,11L,8L,9L,10L,11L,12L,13L,14L,15L), contents(base));
                        var end = base.plus(16); assertSame(end, move(end, end, 0));
                        if (!errors) return;
                        var before = contents(base);
                        for (long count : new long[]{-1L, 17L, Long.MAX_VALUE}) {
                            assertThrows(RuntimeFault.class, () -> move(base, right, count)); assertEquals(before, contents(base));
                        }
                        assertThrows(RuntimeFault.class, () -> move(ManagedAddress.fromHex("0000000000000000"), base, 8));
                        assertEquals(before, contents(base));
                    }
                }
                var exercise = new Exercise();
                exercise.run(ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)), true);
                for (int i = 0; i < 2; i++) exercise.run(ManagedAddress.fromByteArray(new byte[16]), false);
                var nativeAddress = System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))
                    ? Language.currentState().getNativeAllocations().malloc(16) : null;
                if (nativeAddress != null) exercise.run(nativeAddress, false);
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                exercise.run(ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)), false);
                assertEquals(before + 3, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                if (nativeAddress != null) {
                    long beforeNative = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); exercise.run(nativeAddress, false);
                    assertEquals(beforeNative + 3, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                    var alias = nativeAddress.plus(4); Language.currentState().getNativeAllocations().free(nativeAddress);
                    assertThrows(RuntimeFault.class, () -> exercise.move(alias, alias, 0));
                }
            } finally { context.leave(); }
        }
    }
    @Test void pointerCellsRelocateWithoutFabricatingAddressBits() {
        var owner = ManagedAllocation.mutable(40, 8); var base = ManagedAddress.fromAllocation(owner);
        var payload = ManagedAddress.fromByteArray(new byte[8]);
        base.writeAddressElementIndex(0, payload); base.writeAddressElementIndex(1, base.plus(32));
        var destination = base.plus(8); assertSame(destination, base.moveTo(destination, 16));
        assertSame(payload, base.readAddressElementIndex(1)); assertTrue(base.readAddressElementIndex(2).sameLocation(base.plus(32)));
        assertThrows(RuntimeFault.class, () -> base.plus(1).moveTo(base.plus(16), 8));
    }
    @Test void malformedOriginalDescriptorAndForgedHeadRejectBeforeExecution() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                assertThrows(RuntimeFault.class, () -> CoreMemoryCopyForeign.MEMMOVE.validateHead(list("var", "forged-defined-head", map("rep", closure)), true));
                for (String backend : new String[]{"ast", "bytecode"}) {
                    class Control { void reject(Consumer<Map<String,Object>> change) throws Exception {
                        var malformed = new LinkedHashMap<>(descriptor()); change.accept(malformed);
                        assertThrows(RuntimeFault.class, () -> program(language, backend, module(malformed)));
                    }}
                    var control = new Control();
                    control.reject(it -> it.put("safety", "safe")); control.reject(it -> it.put("arity", 3L));
                    control.reject(it -> it.put("argumentReps", list(address))); control.reject(it -> it.put("resultRep", address));
                    control.reject(it -> it.put("target", with((Map<?,?>) it.get("target"), "unit", "foreign")));
                }
            } finally { context.leave(); }
        }
    }
}
