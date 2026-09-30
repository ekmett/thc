// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class FetchAddIntArrayTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/fetch-add-int-array");
    private Map<String, Object> json(Path file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file));
    }
    private String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private record Row(long initial, long first, long second, long result) {}
    private long model(long initial, long first, long second) {
        long old2 = initial + first, last = old2 + second;
        return initial + 17L * old2 + 31L * last;
    }
    private List<Row> rows() throws Exception {
        var expected = new ArrayList<Row>();
        for (long initial : List.of(Long.MIN_VALUE, -7L, 0L, 1L, Long.MAX_VALUE))
            for (long first : List.of(-2L, 0L, 1L, Long.MAX_VALUE))
                for (long second : List.of(-1L, 0L, 3L))
                    expected.add(new Row(initial, first, second, model(initial, first, second)));
        var actual = Files.readAllLines(directory.resolve("oracle.tsv"))
                         .stream()
                         .map(line -> {
                             var fields = line.split("\t", -1);
                             assertEquals(4, fields.length);
                             return new Row(Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                                 Long.parseLong(fields[2]), Long.parseLong(fields[3]));
                         })
                         .toList();
        assertEquals(expected, actual, "Native old-value/wraparound oracle");
        return actual;
    }

    @Test
    void nativeFetchAddRunsInCompiledAstAndBytecode() throws Exception {
        var manifest = json(directory.resolve("manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(List.of("fetchComposite"), manifest.get("entries"));
        for (String kind : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(kind)).entrySet())
                assertEquals(hash.getValue(), digest(root.resolve(hash.getKey())),
                    "Stale fetch-add " + kind + ": " + hash.getKey());
        var cases = rows();
        assertEquals((long) cases.size(), ((Number) manifest.get("nativeRows")).longValue());
        for (var stagePaths : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            String stage = stagePaths.getKey();
            var modules = new ArrayList<Map<String, Object>>();
            for (String path : stagePaths.getValue()) modules.add(thc.CoreCbdFixtures.read(root.resolve(path)));
            var merged = CoreModules.merge(modules);
            var audit = json(directory.resolve(stage + "/fetchComposite.audit.json"));
            assertEquals(true, audit.get("accepted"), stage);
            assertEquals(List.of(), audit.get("issues"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            assertTrue(((List<Map<String, Object>>) audit.get("primitives"))
                    .stream()
                    .map(primitive -> primitive.get("name"))
                    .toList()
                    .contains("fetchAddIntArray#"));
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var linked = new LinkedHashMap<>(CoreModules.reachable(merged, "main:FetchAddIntArrayAudit.fetchComposite"));
                        linked.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked)
                                                                          : new BytecodeProgram(language, linked);
                        var host = program.hostEntryTarget(3);
                        var entry = context.asValue(new EntryValue(program, "main:FetchAddIntArrayAudit.fetchComposite", 3));
                        for (var row : cases)
                            assertEquals(row.result, entry.execute(row.initial, row.first, row.second).asLong(),
                                stage + "/" + backend + "/" + row);
                        assertTrue(entry.invokeMember("compile").asBoolean(), stage + "/" + backend + " install");
                        for (var row : cases.reversed()) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.result, entry.execute(row.initial, row.first, row.second).asLong(),
                                stage + "/" + backend + " compiled " + row);
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                                stage + "/" + backend + " " + row + " compiled");
                            assertEquals(true, host.getClass().getMethod("isValidLastTier").invoke(host));
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally {
                        context.leave();
                    }
                }
        }
    }

    @Test
    void concurrentUpdatesHaveDistinctOldValuesAndFullBounds() throws Exception {
        var owner = ManagedByteArray.allocateGuest(16);
        ManagedByteArray.writeIntGuest(owner, 0, 313);
        ManagedByteArray.writeIntGuest(owner, 1, 0);
        int threads = 4, perThread = 500;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var results = new ArrayList<Future<List<Long>>>();
            for (int i = 0; i < threads; i++)
                results.add(pool.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var values = new ArrayList<Long>();
                    for (int j = 0; j < perThread; j++) values.add(AtomicIntArrayOp.ADD.execute(owner, 1, 1, 0));
                    return values;
                }));
            start.countDown();
            var oldValues = new ArrayList<Long>();
            for (var result : results) oldValues.addAll(result.get(10, TimeUnit.SECONDS));
            Collections.sort(oldValues);
            assertEquals(java.util.stream.LongStream.range(0, threads * perThread).boxed().toList(), oldValues);
            assertEquals((long) threads * perThread, ManagedByteArray.readIntGuest(owner, 1));
            assertEquals(313L, ManagedByteArray.readIntGuest(owner, 0));
        } finally {
            pool.shutdownNow();
        }
        owner.shrink(8);
        assertThrows(RuntimeFault.class, () -> AtomicIntArrayOp.ADD.execute(owner, 1, 1, 0));
        assertEquals(313L, ManagedByteArray.readIntGuest(owner, 0));
        assertThrows(RuntimeFault.class, () -> AtomicIntArrayOp.ADD.execute(new byte[8], 0, 1, 0));
        var pointerOwner = ManagedByteArray.allocateGuest(8);
        var address = ManagedAddress.fromAllocation(pointerOwner);
        pointerOwner.writeAddressByteOffset(0, address);
        assertThrows(RuntimeFault.class, () -> AtomicIntArrayOp.ADD.execute(pointerOwner, 0, 1, 0));
        assertSame(address, pointerOwner.readAddressByteOffset(0));
    }
}
