// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** THC-owned compiler state and live ABI controls. Inputs are typed Core and
 * context-owned objects; no generated fixtures are consumed or produced. */
class CompilerRtsTest {
    private Context context() {
        var context = Context.newBuilder("thc").build(); context.initialize("thc"); return context;
    }
    private final CoreRepresentation proof = new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null);
    @Test void uniqueCellsRetainAliasesAndRejectForeignOrDisposedContexts() {
        var first = context(); var second = context();
        ManagedAddress counter, alias;
        first.enter();
        try {
            counter = CoreDataLabels.fromCore("ghc_unique_counter64", proof, null);
            alias = counter.plus(8).plus(-8);
            var step = CoreDataLabels.fromCore("ghc_unique_inc", proof, null);
            assertEquals(0L, AtomicAddressOp.READ.numeric(counter, 0, 0));
            assertEquals(1L, AtomicAddressOp.READ.numeric(step, 0, 0));
            assertTrue(counter.sameLocation(alias)); assertFalse(counter.sameLocation(step));
            AtomicAddressOp.WRITE.numeric(counter, -2, 0);
            assertEquals(-2L, AtomicAddressOp.ADD.numeric(alias, 1, 0));
            assertEquals(-1L, AtomicAddressOp.ADD.numeric(counter, 1, 0));
            assertEquals(0L, AtomicAddressOp.CAS.numeric(alias, 0, 73));
            assertEquals(73L, AtomicAddressOp.READ.numeric(counter, 0, 0));
            assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(counter.plus(1), 0, 0));
            var unevaluated = new CoreRepresentation(CoreKind.ADDRESS, false, true, List.of("AddrRep"), null, null, null, null, null);
            assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore("ghc_unique_counter64", unevaluated, null));
        } finally { first.leave(); }
        second.enter();
        try {
            assertEquals(0L, AtomicAddressOp.READ.numeric(CoreDataLabels.fromCore("ghc_unique_counter64", proof, null), 0, 0));
            assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(counter, 0, 0));
            assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
            assertThrows(RuntimeFault.class, () -> counter.sameLocation(counter));
        } finally { second.leave(); second.close(); first.close(); }
        assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(alias, 0, 0));
    }
    @Test void originalUniqueCellFetchAddIsAtomicAcrossGuestCarriers() throws Exception {
        try (var context = context()) {
            context.enter();
            ManagedAddress address;
            try { address = CoreDataLabels.fromCore("ghc_unique_counter64", proof, null); }
            finally { context.leave(); }
            var pool = Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<Callable<List<Long>>>();
                for (int i = 0; i < 4; i++) tasks.add(() -> {
                    context.enter();
                    try {
                        var values = new ArrayList<Long>();
                        for (int j = 0; j < 1000; j++) values.add(AtomicAddressOp.ADD.numeric(address, 1, 0));
                        return values;
                    } finally { context.leave(); }
                });
                var values = new ArrayList<Long>();
                for (var task : pool.invokeAll(tasks)) values.addAll(task.get(20, TimeUnit.SECONDS));
                assertEquals(new HashSet<>(LongStream.range(0, 4000).boxed().toList()), new HashSet<>(values));
                context.enter();
                try { assertEquals(4000L, AtomicAddressOp.READ.numeric(address, 0, 0)); }
                finally { context.leave(); }
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void fastStringSlotKeepsWinnerAliveAndSeparateFromOtherRtsSlots() {
        var registry = new StablePointers(); var foreign = new StablePointers();
        var value = new Object(); var winner = registry.make(value); var loser = registry.make(new Object());
        var none = ManagedAddress.nullAddress(); var slot = SharedCAFStore.FAST_STRING;
        assertSame(none, registry.getOrSetSharedCAF(slot, none));
        assertSame(winner, registry.getOrSetSharedCAF(slot, winner));
        assertTrue(registry.equal(winner, registry.getOrSetSharedCAF(slot, loser)));
        registry.free(loser);
        assertSame(value, registry.dereference(registry.getOrSetSharedCAF(slot, none)));
        assertSame(none, registry.getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER, none));
        assertThrows(RuntimeFault.class, () -> registry.free(winner));
        assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(slot, foreign.make(new Object())));
        registry.close();
        assertThrows(RuntimeFault.class, () -> registry.dereference(winner));
        foreign.close();
    }
    @Test void fastStringNativeTokenRemainsRootedUntilContextDisposal() {
        var context = Context.newBuilder("thc").allowNativeAccess(true).build();
        StablePointerToken token;
        context.initialize("thc"); context.enter();
        try {
            var registry = Language.currentState(null).getStablePointers();
            var winner = registry.make(new Object());
            registry.getOrSetSharedCAF(SharedCAFStore.FAST_STRING, winner);
            token = registry.nativeTransport(winner);
            assertTrue(registry.equal(winner, registry.recoverToken(token.getBits())));
            assertThrows(RuntimeFault.class, () -> registry.free(winner));
            assertTrue(token.isPointer());
            assertSame(token, registry.nativeTransport(winner));
        } finally { context.leave(); context.close(); }
        assertFalse(token.isPointer());
    }
    @Test void everySharedSlotHasOneConcurrentWinnerAndIndependentLifetime() throws Exception {
        var registry = new StablePointers(); var foreign = new StablePointers();
        var pool = Executors.newFixedThreadPool(4);
        var none = ManagedAddress.nullAddress(); var winners = new ArrayList<ManagedAddress>();
        try {
            for (var slot : SharedCAFStore.values()) {
                assertSame(none, registry.getOrSetSharedCAF(slot, none));
                var candidates = new ArrayList<ManagedAddress>();
                for (int i = 0; i < 16; i++) candidates.add(registry.make(new Object()));
                var tasks = new ArrayList<Callable<ManagedAddress>>();
                for (var candidate : candidates) tasks.add(() -> registry.getOrSetSharedCAF(slot, candidate));
                var values = new ArrayList<ManagedAddress>();
                for (var task : pool.invokeAll(tasks)) values.add(task.get(20, TimeUnit.SECONDS));
                var winner = values.getFirst();
                assertTrue(values.stream().allMatch(value -> registry.equal(winner, value)));
                assertTrue(winners.stream().noneMatch(value -> registry.equal(winner, value)));
                winners.add(winner);
                for (var candidate : candidates) {
                    if (registry.equal(candidate, winner)) assertThrows(RuntimeFault.class, () -> registry.free(candidate));
                    else registry.free(candidate);
                }
                assertNotNull(registry.dereference(registry.getOrSetSharedCAF(slot, none)));
                assertSame(none, foreign.getOrSetSharedCAF(slot, none));
                assertThrows(RuntimeFault.class, () -> foreign.getOrSetSharedCAF(slot, winner));
                assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(slot, foreign.make(new Object())));
            }
            registry.close();
            for (var winner : winners) assertThrows(RuntimeFault.class, () -> registry.dereference(winner));
            for (var slot : SharedCAFStore.values()) assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(slot, none));
        } finally { pool.shutdownNow(); registry.close(); foreign.close(); }
    }

    private static Map<String, Object> scalar(String kind, String rep) {
        return Map.of("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", true);
    }
    private final Map<String, Object> state = scalar("void", null), integer = scalar("long", "IntRep"),
        word = scalar("long", "WordRep"), address = scalar("address", "AddrRep"),
        closure = scalar("closure", "BoxedRep (Just Lifted)");
    private static Map<String, Object> changed(Map<String, Object> source, String key, Object value) {
        var copy = new LinkedHashMap<>(source); copy.put(key, value); return copy;
    }
    private Map<String, Object> pair(Map<String, Object> value) {
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(state, value),
            "primReps", value.get("primReps"), "evaluated", true);
    }
    private Map<String, Object> binder(String id, Map<String, Object> rep) {
        return Map.of("id", id, "lifted", false, "rep", rep);
    }
    private static List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private static List<Object> literal(String kind, String value, Map<String, Object> rep) { return List.of("lit", kind, value, Map.of("rep", rep)); }
    private static List<Object> apply(List<Object> head, List<List<Object>> args, Map<String, Object> rep, Map<String, Object> extra) {
        var metadata = new LinkedHashMap<>(extra); metadata.put("rep", rep);
        return List.of("app", head, args, java.util.Collections.nCopies(args.size(), false), false, false, metadata);
    }
    @SafeVarargs private static List<Object> prim(String name, Map<String, Object> rep, List<Object>... args) {
        return apply(List.of("prim", name), List.of(args), rep, Map.of());
    }
    private List<Object> unpack(List<Object> call, String token, String value, Map<String, Object> rep, List<Object> body) {
        return List.of("case", call, "pair-" + token,
            List.of(List.of("data", "tuple2", List.of(token, value), body,
                Map.of("binders", List.of(binder(token, state), binder(value, rep))))),
            Map.of("rep", integer, "binder", binder("pair-" + token, pair(rep))));
    }
    private Map<String, Object> declaration(String symbol, boolean keep) {
        var args = keep ? List.of(state) : List.of(address, state);
        return Map.of("schema", 1, "target", Map.of("kind", "static", "symbol", symbol, "unit", "ghc-9.14.1-inplace", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", args.size(), "suppliedArity", args.size(),
            "argumentReps", args.stream().map(rep -> changed(rep, "evaluated", false)).toList(),
            "resultRep", changed(pair(keep ? integer : address), "evaluated", false));
    }
    private List<Object> foreign(Map<String, Object> declaration, List<List<Object>> args, boolean keep) {
        return apply(variable("foreign", closure), args, pair(keep ? integer : address), Map.of("foreignCall", declaration));
    }
    private final List<SharedCAFStore> compilerSlots = List.of(SharedCAFStore.FAST_STRING, SharedCAFStore.PPR_DEBUG,
        SharedCAFStore.NO_DEBUG_OUTPUT, SharedCAFStore.NO_STATE_HACK);
    private Map<String, Object> binding(String name, List<Map<String, Object>> args, List<Object> body) {
        return Map.of("id", name, "name", name, "arity", args.size(), "lifted", true, "rep", closure,
            "expr", List.of("lam", args, body, Map.of("rep", closure, "resultRep", integer)));
    }
    private Map<String, Object> core() {
        List<Object> slots = literal("int", "0", integer);
        // Each install is followed by a query; sum the four pointer identities.
        for (int i = compilerSlots.size() - 1; i >= 0; i--) {
            var decl = declaration(compilerSlots.get(i).getSymbol(), false);
            var installed = "installed" + i; var found = "found" + i;
            slots = unpack(foreign(decl, List.of(variable("candidate", address), variable("s" + i, state)), false),
                "query" + i, installed, address,
                unpack(foreign(decl, List.of(literal("null-addr", "0", address), variable("query" + i, state)), false),
                    "s" + (i + 1), found, address,
                    prim("+#", integer, prim("eqAddr#", integer, variable(installed, address), variable(found, address)), slots)));
        }
        var keep = unpack(foreign(declaration("keepCAFsForGHCi", true), List.of(variable("s", state)), true),
            "kept", "answer", integer, variable("answer", integer));
        var counter = literal("data-addr", "ghc_unique_counter64", address);
        var increment = literal("data-addr", "ghc_unique_inc", address);
        var unique = unpack(prim("readIntOffAddr#", pair(integer), increment, literal("int", "0", integer), variable("s", state)),
            "u1", "step", integer,
            unpack(prim("fetchAddWordAddr#", pair(word), counter, literal("word", "3", word), variable("u1", state)),
                "u2", "before", word,
                unpack(prim("atomicReadWordAddr#", pair(word), counter, variable("u2", state)), "u3", "after", word,
                    prim("+#", integer, variable("step", integer),
                        prim("word2Int#", integer, prim("minusWord#", word, variable("after", word), variable("before", word)))))));
        return Map.of("schema", 1, "ghc", "9.14.1", "instrument", true,
            "bindings", List.of(binding("slots", List.of(binder("candidate", address), binder("s0", state)), slots),
                binding("keep", List.of(binder("s", state)), keep), binding("unique", List.of(binder("s", state)), unique)),
            "constructors", List.of(Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private ExecutableProgram coreProgram(Language language, String backend) {
        return backend.equals("ast") ? new Program(language, core()) : new BytecodeProgram(language, core());
    }
    @Test void compilerRoutesPreserveIdentityAndUniqueArithmeticOnFirstCompiledCalls() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc")
                .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = coreProgram(language, backend);
                var registry = Language.currentState(null).getStablePointers();
                var winner = registry.make(new Object()); var loser = registry.make(new Object());
                var counter = CoreDataLabels.fromCore("ghc_unique_counter64", proof, null);
                for (var name : List.of("slots", "keep", "unique")) {
                    var target = program.entryTarget(name);
                    var args = name.equals("slots") ? new Object[]{0L, winner, Unit.INSTANCE} : new Object[]{0L, Unit.INSTANCE};
                    long expected = name.equals("keep") ? 1L : 4L;
                    assertEquals(expected, ScalarTestCalls.callScalarTestTarget(target, args), backend + "/" + name);
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    if (name.equals("slots")) args[1] = loser;
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(expected, ScalarTestCalls.callScalarTestTarget(target, args), backend + "/" + name + " first compiled call");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                }
                assertEquals(6L, AtomicAddressOp.READ.numeric(counter, 0, 0), "Two calls each advance the counter by three");
                for (var slot : compilerSlots) assertTrue(registry.equal(winner, registry.getOrSetSharedCAF(slot, ManagedAddress.nullAddress())));
                registry.free(loser);
            } finally { context.leave(); }
        }
    }
    @SuppressWarnings("unchecked")
    @Test void compilerDeclarationsRejectWrongOwnerSafetyArityAndDataTargets() {
        var symbols = new ArrayList<>(compilerSlots.stream().map(SharedCAFStore::getSymbol).toList());
        symbols.add("keepCAFsForGHCi");
        for (var symbol : symbols) {
            boolean keep = symbol.equals("keepCAFsForGHCi");
            var decl = declaration(symbol, keep); var target = (Map<String, Object>) decl.get("target");
            var operands = keep ? List.of(state) : List.of(address, state);
            var flags = java.util.Collections.nCopies(operands.size(), false); var result = pair(keep ? integer : address);
            assertEquals(keep ? CoreForeignOverride.STRING_RTS : CoreForeignOverride.SHARED_CAF,
                CoreForeignOverride.select(Map.of("foreignCall", decl)));
            for (var variant : List.of(decl, changed(decl, "target", changed(target, "unit", "ghc-internal")),
                    changed(decl, "safety", "safe"), changed(decl, "arity", 99),
                    changed(decl, "target", changed(target, "isFunction", false)))) {
                var metadata = Map.of("foreignCall", variant, "rep", result);
                if (variant == decl) {
                    if (keep) assertEquals(StringRtsOp.KEEP_CAFS, CoreStringRtsForeign.validate(metadata, operands, flags, result));
                    else assertEquals(SharedCAFStore.named(symbol), CoreSharedCAFStores.validate(metadata, operands, flags, result));
                } else assertThrows(RuntimeFault.class, () -> {
                    if (keep) CoreStringRtsForeign.validate(metadata, operands, flags, result);
                    else CoreSharedCAFStores.validate(metadata, operands, flags, result);
                }, symbol);
            }
        }
    }
}
