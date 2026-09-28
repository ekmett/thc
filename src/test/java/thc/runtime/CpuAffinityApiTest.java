// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kotlin.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.NarrowIntegerCarrierTestKt.callScalarTestTarget;

/** Exact retained GHC foreign declarations, with explicit synthetic callers. */
@Timeout(30)
@SuppressWarnings("unchecked")
class CpuAffinityApiTest {
    private final Map<String, Object> state = scalar("void", null);
    private final Map<String, Object> integer = scalar("long", "IntRep");
    private final Map<String, Object> cInt = scalar("long", "Int32Rep");
    private final Map<String, Object> closure = scalar("closure", "BoxedRep (Just Lifted)");
    private final Map<String, Object> boxed = scalar("data", "BoxedRep (Just Lifted)");
    private final Map<String, Object> reference = scalar("object", "BoxedRep (Just Unlifted)");
    private static Map<String, Object> scalar(String kind, String rep) {
        return Map.of("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", true);
    }
    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(fields),
            "primReps", Arrays.stream(fields).flatMap(field -> ((List<?>) field.get("primReps")).stream()).toList(), "evaluated", true);
    }
    private static List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private Map<String, Object> binder(String id, Map<String, Object> rep) {
        return Map.of("id", id, "lifted", rep.equals(closure) || rep.equals(boxed), "rep", rep);
    }
    private static List<Object> application(List<Object> head, List<List<Object>> arguments, List<Boolean> flags,
            Map<String, Object> rep, Map<String, Object> foreign) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("rep", rep);
        if (foreign != null) metadata.put("foreignCall", foreign);
        return List.of("app", head, arguments, flags, false, false, metadata);
    }
    private Map<String, Object> binding(String name, List<Map<String, Object>> args, List<Object> body, Map<String, Object> rep) {
        return Map.of("id", name, "name", name, "arity", args.size(), "lifted", true, "rep", closure,
            "expr", List.of("lam", args, body, Map.of("rep", closure, "resultRep", rep)));
    }
    private List<Object> tupleCase(List<Object> value, String first, String second, Map<String, Object> secondRep,
            List<Object> body, Map<String, Object> result) {
        return List.of("case", value, "pair-" + first, List.of(List.of("data", "tuple2", List.of(first, second), body,
            Map.of("binders", List.of(binder(first, state), binder(second, secondRep))))),
            Map.of("rep", result, "binder", binder("pair-" + first, tuple(state, secondRep))));
    }
    private Map<String, Object> descriptor(boolean applied) throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/cpu-affinity-descriptors.json"))) {
            return (Map<String, Object>) ((Map<?, ?>) Json.INSTANCE.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)))
                .get(applied ? CoreCpuAffinity.APPLIED : CoreCpuAffinity.SUPPORT);
        }
    }
    private List<Object> query(boolean applied) throws IOException { return query(applied, descriptor(applied)); }
    private List<Object> query(boolean applied, Map<String, Object> declaration) {
        return application(variable("foreign-query", closure), List.of(variable("s", state)), List.of(false), tuple(state, cInt), declaration);
    }
    private Map<String, Object> module() throws IOException { return module(null); }
    private Map<String, Object> module(Map<String, Object> declaration) throws IOException {
        var support = tupleCase(query(false, declaration == null ? descriptor(false) : declaration), "s1", "n", cInt, variable("n", cInt), cInt);
        var applied = tupleCase(query(true), "s1", "n", cInt, variable("n", cInt), cInt);
        List<Object> unit = List.of("con", "unit", 0, Map.of("rep", boxed));
        var completed = application(List.of("con", "tuple2", 2, Map.of("rep", closure)),
            List.of(variable("done", state), unit), List.of(false, true), tuple(state, boxed), null);
        var write = application(List.of("prim", "writeInt32Array#"), List.of(variable("buffer", reference),
            List.of("lit", "int", "0", Map.of("rep", integer)), variable("n", cInt), variable("s1", state)),
            List.of(false, false, false, false), state, null);
        List<Object> afterWrite = List.of("case", write, "done", List.of(Arrays.asList("default", null, List.of(), completed)),
            Map.of("rep", tuple(state, boxed), "binder", binder("done", state)));
        var childBody = tupleCase(query(true), "s1", "n", cInt, afterWrite, tuple(state, boxed));
        List<Object> action = List.of("lam", List.of(binder("s", state)), childBody,
            Map.of("rep", closure, "resultRep", tuple(state, boxed)));
        var fork = application(List.of("prim", "forkOn#"), List.of(variable("cap", integer), action, variable("parent-state", state)),
            List.of(false, true, false), tuple(state, reference), null);
        var forkBody = tupleCase(fork, "parent-next", "child", reference, variable("child", reference), reference);
        return Map.of("schema", 1, "ghc", "9.14.1", "unit", "test", "module", "CpuAffinitySynthetic",
            "instrument", true, "bindings", List.of(
                binding("support", List.of(binder("s", state)), support, cInt),
                binding("applied", List.of(binder("s", state)), applied, cInt),
                binding("fork", List.of(binder("cap", integer), binder("buffer", reference), binder("parent-state", state)), forkBody, reference)),
            "constructors", List.of(
                Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1),
                Map.of("id", "unit", "name", "()", "kind", "boxed", "arity", 0, "tag", 1,
                    "fieldReps", List.of(), "strictFields", List.of(), "fieldLifted", List.of())));
    }
    private static Context context(boolean nativeAccess) {
        return Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(nativeAccess).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build();
    }
    private ExecutableProgram program(Language language, String backend) throws IOException { return program(language, backend, module()); }
    private static ExecutableProgram program(Language language, String backend, Map<String, Object> source) {
        return backend.equals("ast") ? new Program(language, source, false, false) : new BytecodeProgram(language, source, true);
    }

    @Test void queriesUseCurrentContextAndFirstInstalledEntriesOnBothBackends() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean nativeAccess : new boolean[]{false, true})
            try (var context = context(nativeAccess)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var threads = Language.currentState(null).getThreads$org_intelligence_thc();
                    var guest = program(language, backend);
                    threads.enterCurrent(null, false, true, null);
                    try {
                        for (var entry : List.of(Map.entry("support", threads.getCpuAffinity().getMode().ordinal()), Map.entry("applied", 0))) {
                            var target = guest.entryTarget(entry.getKey());
                            assertEquals(entry.getValue(), callScalarTestTarget(target, new Object[]{0L, Unit.INSTANCE}));
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(entry.getValue(), callScalarTestTarget(target, new Object[]{0L, Unit.INSTANCE}));
                            assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue());
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        }
                        if (!nativeAccess) assertEquals(CpuAffinityMode.UNAVAILABLE, threads.getCpuAffinity().getMode());
                        assertThrows(RuntimeFault.class, () -> callScalarTestTarget(guest.entryTarget("support"), new Object[]{0L, 1L}));
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { context.leave(); }
            }
    }

    @Test void actualForkQueriesItsOwnPublishedAcceptanceBeforeRunningChildBody() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean nativeAccess : new boolean[]{false, true})
            try (var context = context(nativeAccess)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var guest = program(language, backend);
                    var threads = Language.currentState(null).getThreads$org_intelligence_thc();
                    threads.enterCurrent(null, false, true, null);
                    try {
                        for (long capability : new long[]{0L, -1L, Long.MAX_VALUE}) {
                            var buffer = new byte[]{-1, -1, -1, -1};
                            var child = (GuestThreadId) Calls.target(guest.entryTarget("fork"), new Object[]{0L, capability, buffer, Unit.INSTANCE});
                            var carrier = child.getCarrier$org_intelligence_thc().get();
                            if (carrier != null) { carrier.join(5000); assertFalse(carrier.isAlive()); }
                            assertEquals(GuestThreadStatus.FINISHED, threads.status(child));
                            assertEquals(child.getAffinityApplied() ? 1 : 0, ManagedByteArray.readInt32(buffer, 0));
                            if (!nativeAccess) assertFalse(child.getAffinityApplied());
                            assertFalse(threads.currentIdentity().getAffinityApplied(), "Child acceptance does not leak to parent");
                        }
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { context.leave(); }
            }
    }

    private static Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        var result = new LinkedHashMap<>(original);
        result.put(key, value);
        return result;
    }

    @Test void exactForeignContractRejectsChangesAndShadowedIdentifiers() throws Exception {
        var valid = descriptor(false);
        var badResult = tuple(state, integer);
        var variants = List.of(changed(valid, "safety", "safe"), changed(valid, "convention", "prim"),
            changed(valid, "arity", 2), changed(valid, "resultRep", badResult),
            changed(valid, "argumentReps", List.of(integer)),
            changed(valid, "target", changed((Map<String, Object>) valid.get("target"), "isFunction", false)));
        for (var backend : List.of("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var bad : variants) assertThrows(RuntimeFault.class, () -> program(language, backend, module(bad)));
                assertThrows(RuntimeFault.class, () -> CoreCpuAffinity.validate(query(false), true));
                assertThrows(RuntimeFault.class, () -> {
                    var call = new ArrayList<>(query(false));
                    call.set(3, List.of(true));
                    CoreCpuAffinity.validate(call, false);
                });
            } finally { context.leave(); }
        }
    }
}
