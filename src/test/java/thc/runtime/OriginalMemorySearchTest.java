// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.CoreModules;
import thc.ForeignExceptionFixtureSupport;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import thc.runtime.Unit;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

@SuppressWarnings("unchecked")
class OriginalMemorySearchTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-memory-search";
    private Object json(String path) throws Exception {
        return Json.parse(Files.readString(new File(root, path).toPath()));
    }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var name : List.of("OriginalMemorySearchAudit", "THC.InterfaceClosure"))
            modules.add((Map<String, Object>) json(prefix + "/" + stage + "/core/" + name + ".json"));
        return CoreModules.merge(modules);
    }
    private List<Map<String, Object>> rows() throws Exception {
        var manifest = (Map<String, Object>) json(prefix + "/manifest.json");
        assertEquals(true, manifest.get("strictAccepted"));
        assertEquals(392L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"),
            Set.of("t/fixtures/compiler/OriginalMemorySearchAudit.hs",
                "t/fixtures/compiler/OriginalMemorySearchNative.hs", "t/haskell-fixtures/MemorySearchFixtures.hs",
                "bin/core_original_foreign.py"),
            null);
        var required = new HashSet<String>();
        required.add(prefix + "/oracle.json");
        for (var stage : List.of("pre", "post")) {
            required.add(prefix + "/" + stage + "/core/OriginalMemorySearchAudit.json");
            required.add(prefix + "/" + stage + "/core/THC.InterfaceClosure.json");
        }
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
        var rows = (List<Map<String, Object>>) json(prefix + "/oracle.json");
        var counts = new LinkedHashMap<Object, Integer>();
        for (var row : rows) counts.merge(row.get("entry"), 1, Integer::sum);
        assertEquals(Map.of("originalCompare", 264, "originalFind", 128), counts);
        for (var stage : List.of("pre", "post"))
            for (var entry : List.of("originalCompare", "originalFind")) {
                var audit = (Map<String, Object>) json(prefix + "/" + stage + "/" + entry + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
            }
        return rows;
    }
    private Context context() {
        return Context.newBuilder("thc")
            .allowNativeAccess(true)
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    @FunctionalInterface
    private interface Action {
        void run(Language language) throws Exception;
    }
    private void inside(Action action) throws Exception {
        try (var context = context()) {
            context.initialize("thc");
            context.enter();
            try {
                action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null));
            } finally {
                context.leave();
            }
        }
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> source) throws Exception {
        source = ForeignExceptionFixtureSupport.nativeModules(List.of(source));
        return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
    }
    private ManagedAddress address(Object values, int kind) {
        var items = (List<Long>) values;
        var bytes = new byte[items.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = items.get(i).byteValue();
        if (kind == 0)
            return ManagedAddress.fromByteArray(bytes);
        if (kind == 3)
            return ManagedAddress.fromHex(HexFormat.of().formatHex(bytes));
        var result = ManagedAddress.fromAllocation(kind == 4 ? ManagedAllocation.nativeMutable(bytes.length, 8)
            : ManagedAllocation.mutable(bytes.length, 8, kind == 2));
        for (int i = 0; i < bytes.length; i++) result.writeWord8(i, bytes[i]);
        return result;
    }
    private void exercise(boolean compiled, List<Map<String, Object>> examples, String entry, ExecutableProgram program,
        RootCallTarget target, Language language, String stage, String backend) throws Exception {
        // Match the native withArray oracle: strong-pinned, static, and ordinary native owners.
        for (int kind : new int[]{2, 3, 4})
            for (int index = 0; index < examples.size(); index++) {
                var row = examples.get(index);
                var base = address(row.get("left"), kind);
                var left = base.plus((Long) row.get("leftOffset"));
                var second = entry.equals("originalCompare")
                    ? address(row.get("right"), kind).plus((Long) row.get("rightOffset"))
                    : row.get("needle");
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var returned = callScalarTestTarget(target, new Object[] {0L, left, second, row.get("count")});
                var label =
                    stage + "/" + backend + "/" + entry + "/storage=" + kind + "/" + index + "/compiled=" + compiled;
                if (entry.equals("originalCompare"))
                    assertEquals(row.get("result"), (long) Long.compare((Long) returned, 0L), label);
                else {
                    var found = (ManagedAddress) returned;
                    long expected = (Long) row.get("result");
                    if (expected < 0)
                        assertSame(ManagedAddress.nullAddress(), found, label);
                    else {
                        assertTrue(found.sameLocation(base.plus(expected)), label);
                        assertEquals(expected, found.difference(base), label);
                        if (kind != 3) {
                            base.writeWord8(expected, 23);
                            assertEquals(23L, found.readWord8(0), label + " alias is original storage");
                        }
                    }
                }
                if (compiled) {
                    assertEquals(
                        before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label);
                    valid(target);
                }
                var handoff = language.getHandoffState().get();
                assertEquals(0, handoff.getArguments().getDepth());
                assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getResults().retainedReferences());
            }
    }
    @Test @Tag("foreign-exceptions-full-core")
    void originalByteStringCallsMatchNativeBeforeAndOnFirstCompiledCalls() throws Exception {
        var rows = rows();
        for (var stage : List.of("pre", "post")) {
            var source = module(stage);
            var calls = OriginalStdioChecks.foreignCalls(source);
            var operations = new HashSet<String>();
            for (var call : calls) {
                var descriptor = (Map<?, ?>) ((Map<?, ?>) call.get(6)).get("foreignCall");
                operations.add((String) ((Map<?, ?>) descriptor.get("target")).get("symbol"));
            }
            assertEquals(Set.of("memcmp", "memchr"), operations);
            for (var backend : List.of("ast", "bytecode"))
                inside(language -> {
                    var grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
                    for (var row : rows)
                        grouped.computeIfAbsent((String) row.get("entry"), key -> new ArrayList<>()).add(row);
                    for (var group : grouped.entrySet()) {
                        var entry = group.getKey();
                        var linked = new LinkedHashMap<>(CoreModules.reachable(source, entry));
                        linked.put("instrument", true);
                        var program = load(language, backend, linked);
                        var target = program.entryTarget(entry);
                        exercise(false, group.getValue(), entry, program, target, language, stage, backend);
                        compile(target);
                        exercise(true, group.getValue(), entry, program, target, language, stage, backend);
                    }
                });
        }
    }
    @Test
    void searchPreservesBoundsOpaqueCellsAndNativeOwnerLifetime() throws Exception {
        var nullAddress = ManagedAddress.nullAddress();
        assertEquals(0L, nullAddress.compareBytes(nullAddress, 0));
        assertSame(nullAddress, nullAddress.findByte(0, 0));
        var base = ManagedAddress.fromByteArray(new byte[] {0, -128, -1, 0, -1});
        assertTrue(base.findByte(-1, 5).sameLocation(base.plus(2)));
        assertTrue(base.findByte(511, 5).sameLocation(base.plus(2)));
        assertTrue(base.findByte(256, 5).sameLocation(base));
        assertTrue(base.plus(1).compareBytes(base, 1) > 0);
        for (long count : new long[] {-1L, 6L, Long.MAX_VALUE}) {
            assertThrows(RuntimeFault.class, () -> base.findByte(0, count));
            assertThrows(RuntimeFault.class, () -> base.compareBytes(base, count));
        }
        assertThrows(RuntimeFault.class, () -> nullAddress.findByte(0, 1));
        var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8));
        cells.writeAddressElementIndex(1, base);
        assertThrows(RuntimeFault.class, () -> cells.findByte(0, 16));
        assertThrows(RuntimeFault.class, () -> cells.compareBytes(cells, 16));
        var literal = ManagedAddress.fromHex("00ff00");
        assertThrows(RuntimeFault.class, () -> literal.findByte(255, 3).writeWord8(0, 7));
        if (System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) {
            ManagedAddress[] stale = {null};
            inside(language -> {
                var allocation = Language.currentState().getNativeAllocations().malloc(8);
                for (int i = 0; i < 8; i++) allocation.writeWord8(i, i);
                var found = allocation.findByte(5, 8);
                assertTrue(found.sameLocation(allocation.plus(5)));
                found.writeWord8(0, 44);
                assertEquals(44L, allocation.readWord8(5));
                assertEquals(
                    0L, allocation.compareBytes(ManagedAddress.fromByteArray(new byte[] {0, 1, 2, 3, 4, 44, 6, 7}), 8));
                stale[0] = found;
                inside(other -> assertThrows(RuntimeFault.class, () -> found.findByte(44, 1)));
                Language.currentState().getNativeAllocations().free(allocation);
                assertThrows(RuntimeFault.class, () -> found.findByte(44, 0));
                assertThrows(RuntimeFault.class, () -> found.compareBytes(base, 0));
            });
            inside(language -> assertThrows(RuntimeFault.class, () -> stale[0].readWord8(0)));
        }
    }
    private <T> T single(List<T> values) {
        if (values.isEmpty())
            throw new NoSuchElementException("List is empty.");
        if (values.size() != 1)
            throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    @Test @Tag("foreign-exceptions-full-core")
    void malformedOriginalABIsAndStateCarriersRejectBothBackends() throws Exception {
        var source = module("pre");
        for (var backend : List.of("ast", "bytecode"))
            inside(language -> {
                for (var original : OriginalStdioChecks.foreignCalls(source)) {
                    var raw = OriginalStdioChecks.rawModule(original, source, null);
                    for (int variant : new int[]{2, 4, 5, 6}) {
                        var bad = (Map<String, Object>) Json.parse(Json.stringify(raw));
                        var call = single(OriginalStdioChecks.foreignCalls(bad));var descriptor=(Map<String,Object>)((Map<?,?>)call.get(6)).get("foreignCall");
                        switch (variant) {
                            case 2 -> descriptor.put("arity", 3L);
                            case 4 -> ((List<Object>) call.get(3)).set(0, true);
                            case 5 -> ((List<Object>) call.get(1)).set(1, "entry");
                            case 6 ->
                                ((List<Object>) descriptor.get("argumentReps"))
                                    .set(2, OriginalStdioFixtures.scalar("IntRep", false));
                        }
                        assertThrows(RuntimeFault.class, () -> load(language, backend, bad), backend + "/" + variant);
                    }
                    var program = load(language, backend, raw);
                    var target = program.entryTarget("entry");var symbol=((Map<?,?>)((Map<?,?>)((Map<?,?>)original.get(6)).get("foreignCall")).get("target")).get("symbol");
                    var bytes = ManagedAddress.fromByteArray(new byte[] {1, 2});
                    Object second = "memcmp".equals(symbol) ? bytes : 1L;
                    assertThrows(
                        RuntimeFault.class, () -> callScalarTestTarget(target, new Object[] {0L, bytes, second, 2L, 17L}));
                    assertThrows(RuntimeFault.class,
                        ()
                            -> callScalarTestTarget(
                                target, new Object[] {0L, bytes, "memcmp".equals(symbol) ? bytes : 1, 2L, 17L}));
                }
            });
    }
    private Map<String, Object> shadowed(
        List<Object> original, Map<String, Object> source, boolean compare, boolean claimForeign) {
        var raw = (Map<String, Object>) Json.parse(
            Json.stringify(OriginalStdioChecks.rawModule(original, source, null)));
        var entry = single((List<Map<String, Object>>) raw.get("bindings"));
        var lambda = (List<Object>) entry.get("expr");
        var body = (List<Object>) lambda.get(2);
        var call = (List<Object>) body.get(1);
        var metadata = (Map<String, Object>) call.get(6);
        var tuple = (Map<String, Object>) metadata.get("rep");
        var fields = (List<Map<String, Object>>) tuple.get("components");
        var operands = (List<List<Object>>) call.get(2);
        var closure = OriginalStdioFixtures.closure();
        var formals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < operands.size(); i++)
            formals.add(Map.of("id", "join" + i, "lifted", false, "rep",
                Objects.requireNonNull(
                    Objects.requireNonNull(CoreRepresentations.metadata(operands.get(i))).get("rep"))));
        var value = compare ? List.of("lit", "int32", "37", Map.of("rep", fields.get(1)))
                            : List.of("var", "join0", Map.of("rep", fields.get(1)));
        var evaluated = new LinkedHashMap<>(tuple);
        evaluated.put("evaluated", true);
        var pair = List.of("app", List.of("con", "T2", 2, Map.of("rep", closure)),
            List.of(List.of("var", "join3", Map.of("rep", fields.get(0))), value), List.of(false, false), false, false,
            Map.of("rep", evaluated));
        Map<String, Object> join =
            Map.of("id", ((List<?>) call.get(1)).get(1), "name", "shadowedMemorySearch", "lifted", true, "rep", closure,
                "expr", List.of("lam", formals, pair, Map.of("rep", closure, "resultRep", tuple)), "joinValueArity", 4L,
                "joinResultRep", tuple, "info", Map.of("joinArity", 4L));
        if (!claimForeign) {
            var bare = new LinkedHashMap<>(metadata);
            bare.remove("foreignCall");
            call.set(6, bare);
        }
        CoreJoins.validate(List.of(join), call, false);
        body.set(1, List.of("let", false, List.of(join), call, Map.of("rep", tuple)));
        return raw;
    }
    @ParameterizedTest @Tag("foreign-exceptions-full-core")
    @ValueSource(strings = {"ast", "bytecode"})
    void lexicalJoinsCannotClaimOriginalMemorySearchAuthority(String backend) throws Exception {
        for (var stage : List.of("pre", "post")) {
            var source = module(stage);
            inside(language -> {
                for (var original : OriginalStdioChecks.foreignCalls(source)) {var descriptor=(Map<?,?>)((Map<?,?>)original.get(6)).get("foreignCall");boolean compare="memcmp".equals(((Map<?,?>)descriptor.get("target")).get("symbol"));
                    // The same well-formed lexical join works without claiming the original foreign declaration's
                    // authority.
                    var bytes = ManagedAddress.fromByteArray(new byte[] {1, 2});
                    Object second = compare ? ManagedAddress.fromByteArray(new byte[] {9, 8}) : 9;
                    var target =
                        load(language, backend, shadowed(original, source, compare, false)).entryTarget("entry");
                    var result = callScalarTestTarget(target, new Object[] {0L, bytes, second, 1L, Unit.INSTANCE});
                    if (compare)
                        assertEquals(37, result);
                    else
                        assertSame(bytes, result);
                    var failure = assertThrows(
                        RuntimeFault.class, () -> load(language, backend, shadowed(original, source, compare, true)));
                    assertTrue(
                        Objects.toString(failure.getMessage(), "").contains("unresolved declared foreign head"),
                        stage + "/" + backend + " rejects the shadowed head specifically: " + failure.getMessage());
                }
            });
        }
    }
}
