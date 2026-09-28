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

/** Descriptors copied unchanged from original GHC.Internal.System.Posix.Internals
 * core/235.json, sha256 9e77ab9a3dbb5c507f9ec6a8c16e37b6b9f29eaf677520f1c532e6657ac30ce9. */
@SuppressWarnings("unchecked")
class OriginalStringRtsTest {
    private final Map<String,Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private Object resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) { return Json.parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)); }
    }
    private Map<String,Map<String,Object>> descriptors() throws Exception { return (Map<String,Map<String,Object>>) resource("/core/original-string-rts-descriptors.json"); }
    private Map<String,Object> module(String symbol) throws Exception { var declaration = descriptors().get(symbol); return module(symbol, declaration, declaration); }
    private Map<String,Object> module(String symbol, Map<String,Object> declaration) throws Exception { return module(symbol, declaration, descriptors().get(symbol)); }
    private Map<String,Object> module(String symbol, Map<String,Object> declaration, Map<String,Object> canonical) {
        var result = (Map<String,Object>) canonical.get("resultRep"); var fields = (List<Map<String,Object>>) result.get("components");
        var formals = new ArrayList<Map<String,Object>>(); var reps = (List<Map<String,Object>>) canonical.get("argumentReps");
        for (int i = 0; i < reps.size(); i++) formals.add(map("id", "arg" + i, "lifted", false, "rep", with(reps.get(i), "evaluated", true)));
        var args = new ArrayList<Object>(); for (var formal : formals) args.add(list("var", formal.get("id"), map("rep", formal.get("rep"))));
        var call = list("app", list("var", "original-" + symbol, map("rep", closure)), args, Collections.nCopies(formals.size(), false), false, false, map("rep", result, "foreignCall", declaration));
        var resultLong = fields.getLast(); var binders = new ArrayList<Object>();
        for (int i = 0; i < fields.size(); i++) binders.add(map("id", i == 0 ? "result-state" : "result-long", "lifted", false, "rep", fields.get(i)));
        var body = list("case", call, "result-tuple", list(list("data", "tuple2", list("result-state", "result-long"), list("var", "result-long", map("rep", resultLong)), map("binders", binders))),
            map("rep", resultLong, "binder", map("id", "result-tuple", "lifted", false, "rep", with(result, "evaluated", true))));
        var binding = map("id", symbol, "name", symbol, "arity", formals.size(), "lifted", true, "rep", closure, "expr", list("lam", formals, body, map("rep", closure, "resultRep", resultLong)));
        return map("schema", 1, "module", "SyntheticOriginalStringRts", "unit", "test", "ghc", "9.14.1", "instrument", true, "bindings", list(binding),
            "constructors", list(map("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private Context context() { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private ExecutableProgram program(Language language, String backend, Map<String,Object> source) { return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source); }
    private static long length(RootCallTarget target, ManagedAddress address) { return (Long) Calls.target(target, new Object[]{0L, address, kotlin.Unit.INSTANCE}); }
    @Test void originalByteStringSizeTDeclarationUsesTheSameCheckedCStringStorage() throws Exception {
        // Original unix System.Posix.PosixPath.FilePath module SHA-256
        // e341553bb7289341df45e43917d9dc17a3f7e67074c9afbd315326480175a26b.
        var original = (Map<String,Object>) resource("/core/original-bytestring-strlen-descriptor.json"); var originalTarget = (Map<String,Object>) original.get("target");
        for (var unit : List.of("bytestring-0.12.2.0-inplace", "bytestring-0.12.2.0", "bytestring-0.12.2.0-319833abde312f"))
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var declaration = with(original, "target", with(originalTarget, "unit", unit));
                var guest = program(language, backend, module("strlen", declaration, original)); var target = guest.entryTarget("strlen");
                var text = ManagedAddress.fromByteArray(new byte[]{65, -50, -69, 0, 66});
                assertEquals(3L, length(target, text)); assertEquals(0L, length(target, text.plus(3)));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); assertEquals(2L, length(target, text.plus(1)));
                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                assertThrows(RuntimeFault.class, () -> length(target, ManagedAddress.fromByteArray(new byte[]{65})));
                var wrong = with(declaration, "resultRep", descriptors().get("strlen").get("resultRep"));
                assertThrows(RuntimeFault.class, () -> program(language, backend, module("strlen", wrong, original)));
                assertEquals(unit, ((Map<?,?>) declaration.get("target")).get("unit"));
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var unit : list(null, list("bytestring-0.12.2.0"), "ghc-internal", "foreign", "bytestring-0.12.1.0", "bytestring-0.12.2.0-", "bytestring-0.12.2.0-hash-extra", "bytestring-0.12.2.0 hash", "bytestring-0.12.2.0:hash", "bytestring-0.12.2.0\n")) {
                    var wrong = with(original, "target", with(originalTarget, "unit", unit));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, module("strlen", wrong, original)));
                }
            } finally { context.leave(); }
        }
    }
    @Test void originalStrlenScansManagedAndOwnedNativeMemoryThroughFirstNulOnCompiledEntry() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var guest = program(language, backend, module("strlen")); var target = guest.entryTarget("strlen");
                var managed = ManagedAddress.fromAllocation(ManagedAllocation.mutable(12, 8));
                long[] bytes = {65, 0xce, 0xbb, 0, 80}; for (int i = 0; i < bytes.length; i++) managed.writeWord8(i, bytes[i]);
                assertEquals(3L, length(target, managed)); assertEquals(2L, length(target, managed.plus(1))); assertEquals(0L, length(target, managed.plus(3)));
                var raw = ManagedAddress.fromByteArray(new byte[]{1,2,0,4}); assertEquals(2L, length(target, raw));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); assertEquals(3L, length(target, managed));
                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                if (System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) {
                    var nativeAddress = Language.currentState().getNativeAllocations().malloc(8);
                    long[] values = {5,6,0}; for (int i = 0; i < values.length; i++) nativeAddress.writeWord8(i, values[i]);
                    long nativeBefore = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); var alias = nativeAddress.plus(1);
                    assertEquals(1L, length(target, alias)); assertEquals(nativeBefore + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                    Language.currentState().getNativeAllocations().free(nativeAddress); assertThrows(RuntimeFault.class, () -> length(target, alias));
                }
                assertThrows(RuntimeFault.class, () -> length(target, ManagedAddress.fromByteArray(new byte[]{1,2})));
                assertThrows(RuntimeFault.class, () -> length(target, managed.plus(12))); assertThrows(RuntimeFault.class, () -> length(target, ManagedAddress.nullAddress()));
                assertEquals(0L, length(target, ManagedAddress.fromHex("0000000000000000")));
                assertThrows(RuntimeFault.class, () -> length(target, ManagedAddress.unownedNumeric(0x1234L)));
                var pointers = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16,8)); pointers.writeAddressElementIndex(0, managed);
                assertThrows(RuntimeFault.class, () -> length(target, pointers));
            } finally { context.leave(); }
        }
    }
    @Test void originalRtsWayQueryUsesNonthreadedFdContractOnCompiledEntry() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var guest = program(language, backend, module("rts_isThreaded")); var target = guest.entryTarget("rts_isThreaded");
                assertEquals(0L, Calls.target(target, new Object[]{0L, kotlin.Unit.INSTANCE}));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, Calls.target(target, new Object[]{0L, kotlin.Unit.INSTANCE}));
                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
            } finally { context.leave(); }
        }
    }
    @Test void alteredOriginalDeclarationsRejectBeforeExecution() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var symbol : List.of("strlen", "rts_isThreaded")) {
                    assertThrows(RuntimeFault.class, () -> CoreStringRtsForeign.validateHead(list("var", "forged", map("rep", closure)), true));
                    for (var backend : List.of("ast", "bytecode")) {
                        class Control { void reject(Consumer<Map<String,Object>> change) throws Exception {
                            var malformed = new LinkedHashMap<>(descriptors().get(symbol)); change.accept(malformed);
                            assertThrows(RuntimeFault.class, () -> program(language, backend, module(symbol, malformed)));
                        }}
                        var control = new Control(); control.reject(it -> it.put("safety", "safe")); control.reject(it -> it.put("arity", 3L));
                        control.reject(it -> it.put("argumentReps", List.of()));
                        control.reject(it -> it.put("resultRep", map("kind", "long", "primReps", list("IntRep"), "evaluated", false)));
                        control.reject(it -> it.put("target", with((Map<?,?>) it.get("target"), "unit", "foreign")));
                    }
                }
            } finally { context.leave(); }
        }
    }
}
