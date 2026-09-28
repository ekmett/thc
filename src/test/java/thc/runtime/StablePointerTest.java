// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class StablePointerTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/stable-pointers");
    private final List<String> entries = List.of("stableComposite", "lazyStable", "sharedEventManagerStore", "sharedSignalHandlerStore");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath())); }
    private String digest(File file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))); }
    private Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000").option("engine.CompilationFailureAction", "Throw").option("compiler.CompilationTimeout", "30").build();
    }
    private Map<String, Object> merge(List<String> paths) throws Exception { var modules = new ArrayList<Map<String, Object>>(); for (var path : paths) modules.add(json(new File(root, path))); return CoreModules.merge(modules); }
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private record Row(long input, long expected) {}

    @Test public void nativeOracleAndInstalledAstBytecodePreserveIdentityAndLazyReferents() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(entries, manifest.get("entries"));
        for (var key : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(key)).entrySet())
            assertEquals(hash.getValue(), digest(new File(root, hash.getKey())), "Stale StablePtr fixture: " + hash.getKey());
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) { var fields = line.split("\t", -1); rows.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(fields); }
        assertEquals(new HashSet<>(entries), rows.keySet()); int rowCount = 0; for (var group : rows.values()) rowCount += group.size();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var genuine = merge(stage.getValue()); var synthetic = merge(((Map<String, List<String>>) manifest.get("sharedStages")).get(stage.getKey()));
            for (var name : entries) {
                boolean shared = name.startsWith("shared"); var merged = shared ? synthetic : genuine;
                var audit = json(new File(directory, stage.getKey() + "/" + name + ".audit.json"));
                assertEquals(true, audit.get("accepted"), stage.getKey() + "/" + name); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
                var primitives = new HashSet<Object>(); for (var primitive : (List<Map<String, Object>>) audit.get("primitives")) primitives.add(primitive.get("name"));
                var wanted = new HashSet<>(Set.of("makeStablePtr#")); if (!shared) { wanted.add("deRefStablePtr#"); wanted.add(name.equals("stableComposite") ? "eqStablePtr#" : "touch#"); }
                assertTrue(primitives.containsAll(wanted));
                var symbols = new HashSet<Object>(); for (var call : (List<Map<String, Object>>) audit.get("foreignCalls")) symbols.add(call.get("symbol"));
                var expectedSymbols = new HashSet<>(Set.of("hs_free_stable_ptr")); if (shared) expectedSymbols.add(name.equals("sharedEventManagerStore") ? SharedCAFStore.EVENT_MANAGER.getSymbol() : SharedCAFStore.SIGNAL_HANDLER.getSymbol());
                assertEquals(expectedSymbols, symbols);
                var cases = new ArrayList<Row>(); for (var row : rows.get(name)) cases.add(new Row(Long.parseLong(row[1]), Long.parseLong(row[2])));
                for (var row : cases) assertEquals(row.input + switch (name) { case "stableComposite" -> 48L; case "lazyStable" -> 73L; default -> 0L; }, row.expected, "Native " + name + "(" + row.input + ")");
                for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var linked = with(CoreModules.reachable(merged, name), "instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var host = program.hostEntryTarget(1); var function = context.asValue(new EntryValue(program, name, 1));
                        for (var row : cases) assertEquals(row.expected, function.execute(row.input).asLong(), stage.getKey() + "/" + backend + "/" + name + "(" + row.input + ")");
                        assertTrue(function.invokeMember("compile").asBoolean(), stage.getKey() + "/" + backend + "/" + name + " install");
                        for (var row : cases.reversed()) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.expected, function.execute(row.input).asLong(), stage.getKey() + "/" + backend + "/" + name + "(" + row.input + ") compiled");
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, stage.getKey() + "/" + backend + "/" + name + "(" + row.input + ") entered compiled code");
                            assertEquals(true, host.getClass().getMethod("isValidLastTier").invoke(host));
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally { context.leave(); }
                }
            }
        }
    }

    @Test public void opaqueHandlesAreContextOwnedAndNeverBecomeMemoryAddresses() {
        var firstContext = context(); firstContext.initialize("thc"); firstContext.enter(); var registry = Language.currentState().getStablePointers();
        var referent = new Object(); var first = registry.make(referent); var second = registry.make(referent);
        try {
            assertSame(referent, registry.dereference(first)); assertTrue(registry.equal(first, first)); assertFalse(registry.equal(first, second));
            assertTrue(first.sameLocation(first)); assertFalse(first.sameLocation(second)); assertFalse(first.sameLocation(ManagedAddress.nullAddress()));
            assertNotEquals(0L, first.stableHandle().getId()); assertNotEquals(first.stableHandle().getId(), second.stableHandle().getId());
            assertThrows(RuntimeFault.class, () -> first.plus(0)); assertThrows(RuntimeFault.class, () -> first.readWord8(0)); assertThrows(RuntimeFault.class, first::rawBacking);
            assertThrows(RuntimeFault.class, () -> first.compareWithinAllocation(first)); assertThrows(RuntimeFault.class, () -> registry.make(null));
        } finally { firstContext.leave(); }
        try (var other = context()) {
            other.initialize("thc"); other.enter();
            try {
                var foreign = Language.currentState().getStablePointers(); assertThrows(RuntimeFault.class, () -> foreign.dereference(first));
                assertThrows(RuntimeFault.class, () -> foreign.equal(first, first)); assertThrows(RuntimeFault.class, () -> first.sameLocation(first));
            } finally { other.leave(); }
        }
        firstContext.enter();
        try {
            registry.free(first); assertThrows(RuntimeFault.class, () -> registry.dereference(first)); assertThrows(RuntimeFault.class, () -> first.sameLocation(first));
            var third = registry.make(referent); assertTrue(third.stableHandle().getId() > second.stableHandle().getId()); registry.free(second); registry.free(third);
        } finally { firstContext.leave(); firstContext.close(); }
        assertThrows(RuntimeFault.class, () -> registry.dereference(first));
    }

    @Test public void rtsSharedCAFSlotsInstallOneLiveHandleAndReleaseRootsAtContextClose() throws Exception {
        var registry = new StablePointers(); var foreign = new StablePointers(); var event = SharedCAFStore.EVENT_MANAGER; var signal = SharedCAFStore.SIGNAL_HANDLER;
        var none = ManagedAddress.nullAddress(); var first = registry.make(new Object()); var second = registry.make(new Object());
        assertSame(none, registry.getOrSetSharedCAF(event, none)); var start = new CountDownLatch(1); var done = new CountDownLatch(2);
        var workers = Executors.newFixedThreadPool(2); var outcomes = new ManagedAddress[2];
        try {
            var candidates = List.of(first, second);
            for (int i = 0; i < candidates.size(); i++) {
                final int index = i; var candidate = candidates.get(i);
                workers.submit(() -> { start.await(); try { outcomes[index] = registry.getOrSetSharedCAF(event, candidate); } finally { done.countDown(); } return null; });
            }
            start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); var winner = registry.getOrSetSharedCAF(event, none); boolean firstWon = registry.equal(winner, first);
            assertTrue(firstWon || registry.equal(winner, second)); boolean allEqual = true;
            for (var outcome : outcomes) if (!registry.equal(Objects.requireNonNull(outcome), winner)) { allEqual = false; break; }
            assertTrue(allEqual);
            registry.free(firstWon ? second : first); var otherStore = registry.make(new Object());
            assertSame(none, registry.getOrSetSharedCAF(signal, none)); assertTrue(registry.equal(registry.getOrSetSharedCAF(signal, otherStore), otherStore)); assertFalse(registry.equal(winner, otherStore));
            var stale = registry.make(new Object()); registry.free(stale); assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(event, stale));
            assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(event, ManagedAddress.fromByteArray(new byte[] {1})));
            assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(event, foreign.make(new Object()))); assertThrows(RuntimeFault.class, () -> registry.free(winner));
            assertTrue(registry.equal(registry.getOrSetSharedCAF(event, none), winner)); registry.close();
            assertThrows(RuntimeFault.class, () -> registry.dereference(winner)); assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(event, none));
        } finally { workers.shutdownNow(); foreign.close(); registry.close(); }
    }
    private void nodes(Object value, List<List<?>> result) {
        if (value instanceof Map<?, ?> map) for (var child : map.values()) nodes(child, result);
        else if (value instanceof List<?> list) { result.add(list); for (var child : list) nodes(child, result); }
    }
    private Object symbol(List<?> app) {
        if (app.size() > 6 && app.get(6) instanceof Map<?, ?> metadata && metadata.get("foreignCall") instanceof Map<?, ?> call && call.get("target") instanceof Map<?, ?> target) return target.get("symbol");
        return null;
    }
    private List<Object> argumentReps(List<?> app) {
        var result = new ArrayList<Object>();
        for (var argument : (List<?>) app.get(2)) { var metadata = CoreRepresentations.metadata((List<?>) argument); result.add(metadata == null ? null : metadata.get("rep")); }
        return result;
    }

    @Test public void installedStablePointerAbiRejectsRetypedAndRelabeledCalls() throws Exception {
        for (var stage : List.of("pre", "post")) {
            var core = json(new File(directory, stage + "/core/StablePointerAudit.json")); var all = new ArrayList<List<?>>(); nodes(core.get("bindings"), all);
            var applications = new ArrayList<List<?>>(); for (var node : all) if (!node.isEmpty() && Objects.equals(node.getFirst(), "app")) applications.add(node);
            List<?> free = applications.stream().filter(app -> Objects.equals(symbol(app), "hs_free_stable_ptr")).findFirst().orElseThrow();
            var metadata = (Map<String, Object>) free.get(6); var arguments = argumentReps(free); var flags = (List<?>) free.get(3);
            assertTrue(CoreStablePointers.validate(metadata, arguments, flags, metadata.get("rep"))); var descriptor = (Map<String, Object>) metadata.get("foreignCall");
            assertThrows(RuntimeFault.class, () -> CoreStablePointers.validate(with(metadata, "foreignCall", with(descriptor, "safety", "safe")), arguments, flags, metadata.get("rep")));
            assertThrows(RuntimeFault.class, () -> CoreStablePointers.validate(metadata, arguments.reversed(), flags, metadata.get("rep")));
            var make = applications.stream().filter(app -> app.get(1) instanceof List<?> head && head.size() >= 2 && head.subList(0, 2).equals(List.of("prim", "makeStablePtr#"))).findFirst().orElseThrow();
            var operation = StablePointerOp.MAKE; var input = new ArrayList<CoreRepresentation>(); for (var arg : (List<?>) make.get(2)) input.add(CoreRepresentations.expression((List<?>) arg));
            var output = CoreRepresentations.expression(make); operation.validate(input, (List<?>) make.get(3), output);
            assertThrows(RuntimeFault.class, () -> operation.validate(input, List.of(false, false), output));
            var synthetic = json(new File(directory, stage + "/synthetic/SharedCAFNative.json")); var syntheticNodes = new ArrayList<List<?>>(); nodes(synthetic.get("bindings"), syntheticNodes);
            var shared = new ArrayList<List<?>>(); for (var app : syntheticNodes) if (SharedCAFStore.named(symbol(app)) != null) shared.add(app);
            var symbols = new HashSet<Object>(); for (var app : shared) symbols.add(symbol(app)); assertEquals(2, symbols.size());
            for (var app : shared) {
                var proof = (Map<String, Object>) app.get(6); var operands = argumentReps(app); var call = (Map<String, Object>) proof.get("foreignCall");
                assertNotNull(CoreSharedCAFStores.validate(proof, operands, (List<?>) app.get(3), proof.get("rep")));
                assertThrows(RuntimeFault.class, () -> CoreSharedCAFStores.validate(with(proof, "foreignCall", with(call, "safety", "safe")), operands, (List<?>) app.get(3), proof.get("rep")));
                var target = (Map<String, Object>) call.get("target");
                assertThrows(RuntimeFault.class, () -> CoreSharedCAFStores.validate(with(proof, "foreignCall", with(call, "target", with(target, "unit", "base"))), operands, (List<?>) app.get(3), proof.get("rep")));
            }
        }
    }
}
