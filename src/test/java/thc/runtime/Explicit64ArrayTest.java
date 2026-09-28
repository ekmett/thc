// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class Explicit64ArrayTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private record Row(long bits, List<Long> results) {}
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private static <T> T single(List<T> values) {
        if (values.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return values.getFirst();
    }
    private Map<String, Object> json(Path file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file));
    }

    @Test
    void fullWidthStorageChecksBoundsAndPointerCellsBeforeMutation() {
        byte[] raw = new byte[24];
        ManagedByteArray.writeIntGuest(raw, 1, Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, ManagedByteArray.readIntGuest(raw, 1));
        byte[] expected = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder()).putLong(8, Long.MIN_VALUE).array();
        assertArrayEquals(expected, raw);
        for (long index : List.of(-1L, 3L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(raw, index));
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeIntGuest(raw, index, 1));
            assertArrayEquals(expected, raw);
        }
        var owner = PinnedMemory.allocate(32, 1);
        var base = ManagedAddress.Companion.fromAllocation(owner);
        var retained = base.plus(24);
        base.writeAddressElementIndex(0, retained);
        ManagedByteArray.writeIntGuest(owner, 2, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, ManagedByteArray.readIntGuest(owner, 2));
        assertSame(retained, base.readAddressElementIndex(0));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(owner, 0));
        assertSame(retained, base.readAddressElementIndex(0));
        ManagedByteArray.writeIntGuest(owner, 0, -1);
        assertEquals(-1L, ManagedByteArray.readIntGuest(owner, 0));
        assertEquals(Long.MAX_VALUE, ManagedByteArray.readIntGuest(owner, 2));
    }

    @Test
    void originalGhcExplicit64ArrayCompositeMatchesNativeAndCompiledBackends() throws Exception {
        var base = root.resolve("build/explicit64-arrays");
        var manifest = json(base.resolve("manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(10L, manifest.get("nativeRows"));
        for (String kind : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), digest, "Stale explicit64 array " + kind + ": " + hash.getKey());
            }
        var rows = Files.readAllLines(base.resolve("oracle.tsv"))
                       .stream()
                       .map(line -> {
                           var fields = line.split("\t", -1);
                           assertEquals(5, fields.length);
                           return new Row(
                               Long.parseLong(fields[0]), Arrays.stream(fields).skip(1).map(Long::parseLong).toList());
                       })
                       .toList();
        assertEquals(10, rows.size());
        for (var row : rows) {
            var memory =
                ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()).putLong(8, row.bits).putLong(16, row.bits);
            assertEquals(
                List.of(memory.getLong(8), memory.getLong(16), memory.getLong(8), memory.getLong(16)), row.results);
        }
        var required = Map.of("readInt64Array#", 3L, "readWord64Array#", 3L, "writeInt64Array#", 4L,
            "writeWord64Array#", 4L, "indexInt64Array#", 2L, "indexWord64Array#", 2L);
        for (String stage : List.of("pre", "post")) {
            var directory = base.resolve(stage);
            var audit = json(directory.resolve("audit.json"));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitives = (List<Map<String, Object>>) audit.get("primitives");
            for (var primitive : required.entrySet()) {
                var proof =
                    single(primitives.stream().filter(value -> primitive.getKey().equals(value.get("name"))).toList());
                assertEquals(primitive.getValue(), proof.get("expectedArity"));
                var uses = (List<Map<String, Object>>) proof.get("uses");
                assertEquals(1, uses.size());
                assertEquals("main:Explicit64ArrayAudit.explicit64ArrayBits", single(uses).get("owner"));
                assertEquals(primitive.getValue(), single(uses).get("arity"));
            }
            var module = json(directory.resolve("core/Explicit64ArrayAudit.json"));
            var evidence = new ArrayCoreEvidence(module, "explicit64ArrayBits");
            var outer = (List<Object>) evidence.getRoot().get("expr");
            assertEquals(1, evidence.getBindings().size());
            assertTrue(evidence.globalReferences(outer).isEmpty());
            assertEquals("lam", outer.get(0));
            assertEquals(List.of("IntRep", "IntRep"), ((List<Map<String, Object>>) outer.get(1)).stream()
                .map(value -> single((List<?>) ((Map<?, ?>) value.get("rep")).get("primReps"))).toList());
            var state = evidence.immediateStateLambda(outer.get(2));
            assertEquals(List.of(outer, state), evidence.guestLambdas(outer),
                "the exported entry retains exactly its immediate State# lambda");
            var lowered = evidence.loweredGuestLambdas(outer);
            assertEquals(List.of(outer), lowered, "the exact State# beta-redex executes in-frame");
            int executedRoots = lowered.size();
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        String entry = "explicit64ArrayBits";
                        var source = new LinkedHashMap<>(CoreModules.reachable(module, entry));
                        source.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source)
                                                                          : new BytecodeProgram(language, source);
                        var function = context.asValue(new EntryValue(program, entry, 2));
                        for (var row : rows) {
                            for (int selector = 0; selector <= 3; selector++)
                                assertEquals(row.results.get(selector), function.execute(row.bits, selector).asLong(),
                                    stage + "/" + backend + "/" + selector);
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertTrue(function.invokeMember("compile").asBoolean(), stage + "/" + backend);
                        var host = program.hostEntryTarget(2);
                        var original = program.entryTarget(entry);
                        var guestCall = single(NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)
                                .stream()
                                .filter(call -> call.getCallTarget() == original)
                                .toList());
                        var active = (RootCallTarget) guestCall.getCurrentCallTarget();
                        class Valid {
                            boolean check(RootCallTarget target) throws Exception {
                                return Boolean.TRUE.equals(
                                    target.getClass().getMethod("isValidLastTier").invoke(target));
                            }
                        }
                        var valid = new Valid();
                        assertTrue(valid.check(host), stage + "/" + backend + " host installed");
                        assertTrue(valid.check(active), stage + "/" + backend + " active guest installed");
                        for (var row : rows.reversed())
                            for (int selector = 3; selector >= 0; selector--) {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(row.results.get(selector), function.execute(row.bits, selector).asLong(),
                                    stage + "/" + backend + "/" + selector);
                                long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                // Count the exact lowered Core roots, retaining the separate
                                // exported-lambda proof above and every first-installed call.
                                assertEquals(before + executedRoots, after,
                                    stage + "/" + backend + "/" + selector
                                        + " must enter every lowered root in compiled code");
                                assertTrue(valid.check(host), stage + "/" + backend + " host remains installed");
                                assertTrue(
                                    valid.check(active), stage + "/" + backend + " active guest remains installed");
                                assertSame(active, guestCall.getCurrentCallTarget(),
                                    stage + "/" + backend + " active guest remains the call target");
                                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                            }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
}