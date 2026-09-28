// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.*;
import kotlin.Unit;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

@SuppressWarnings("unchecked")
class ByteArrayTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("shortBytes", "orderedBytes", "shortUncons", "copiedBytes");
    private final List<ByteArrayOp> byteOperations = List.of(
        ByteArrayOp.NEW, ByteArrayOp.WRITE, ByteArrayOp.COPY, ByteArrayOp.FREEZE, ByteArrayOp.SIZE, ByteArrayOp.INDEX);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/bytearray/manifest.json").toPath()));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths)
            modules.add((Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private BigInteger octet(BigInteger value) {
        return value.mod(BigInteger.valueOf(256));
    }
    private long mathematical(String name, long seed) {
        if (!names.contains(name))
            throw new IllegalArgumentException("Unknown byte-array entry");
        var x = BigInteger.valueOf(seed);
        if (name.equals("orderedBytes"))
            return BigInteger.valueOf(3)
                .add(octet(x))
                .add(octet(x.add(BigInteger.valueOf(17))).multiply(BigInteger.valueOf(257)))
                .add(octet(x.add(BigInteger.TWO)).multiply(BigInteger.valueOf(65537)))
                .add(octet(x.add(BigInteger.valueOf(71))).multiply(BigInteger.valueOf(16777259)))
                .longValue();
        if (name.equals("copiedBytes")) {
            var source =
                List.of(octet(x), octet(x.add(BigInteger.valueOf(17))), BigInteger.ZERO, BigInteger.valueOf(255));
            var destination = new ArrayList<BigInteger>();
            for (long value : new long[] {11, 22, 33, 44, 55, 66}) destination.add(BigInteger.valueOf(value));
            int key = x.mod(BigInteger.valueOf(1024)).intValue(), start = key % 5, end = key / 5 % 7,
                count = Math.min(key / 35 % 5, Math.min(4 - start, 6 - end));
            for (int i = 0; i < count; i++) destination.set(end + i, source.get(start + i));
            var both = new ArrayList<>(source);
            both.addAll(destination);
            var sum = BigInteger.ZERO;
            for (int i = 0; i < both.size(); i++) sum = sum.add(both.get(i).multiply(BigInteger.valueOf(257).pow(i)));
            return BigInteger.TEN.add(sum).longValue();
        }
        int size = x.abs().mod(BigInteger.valueOf(33)).intValue();
        if (name.equals("shortUncons")) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < size; i++)
                sum = sum.add(octet(x.add(BigInteger.valueOf(17L * i))).multiply(BigInteger.valueOf(33).pow(i)));
            return sum.longValue();
        }
        var value = BigInteger.ZERO;
        for (int index = 0; index < size; index++)
            value = value.multiply(BigInteger.valueOf(33)).add(octet(x.add(BigInteger.valueOf(17L * index))));
        return value.add(BigInteger.valueOf(size)).longValue();
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true,
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target),
            label);
    }
    private long compiled(ExecutableProgram p) {
        return ((Number) p.diagnostics().get("compiledEntries")).longValue();
    }
    @Test
    void installedShortByteStringAndArrayEffectsMatchNativeAndIndependentModel() throws Exception {
        var manifest = manifest();
        ByteArrayFixtureEvidence.verify(root, "bytearray", manifest);
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var row : checkedRows(Files.readString(new File(root, "build/bytearray/oracle.tsv").toPath())))
            rows.computeIfAbsent(row.getFirst(), k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), rows.keySet());
        int rowCount = 0;
        for (var list : rows.values()) rowCount += list.size();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var module = merged(stage.getValue());
            for (var name : names) {
                var cases = new ArrayList<long[]>();
                for (var row : Objects.requireNonNull(rows.get(name)))
                    cases.add(new long[] {Long.parseLong(row.get(1)), Long.parseLong(row.get(2))});
                for (var row : cases)
                    assertEquals(mathematical(name, row[0]), row[1], "Native " + name + "(" + row[0] + ")");
                for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var label = stage.getKey() + "/" + backend + "/" + name;
                            var linked = new LinkedHashMap<>(CoreModules.reachable(module, name));
                            linked.put("instrument", true);
                            var p = program(language, linked, backend);
                            var host = p.hostEntryTarget(1);
                            var function = context.asValue(new EntryValue(p, name, 1));
                            for (var row : cases)
                                assertEquals(row[1], function.execute(row[0]).asLong(), label + "(" + row[0] + ")");
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                            var original = p.entryTarget(name);
                            var active = new ArrayList<RootCallTarget>();
                            for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
                                if (call.getCallTarget() == original)
                                    active.add((RootCallTarget) call.getCurrentCallTarget());
                            if (active.isEmpty())
                                active.add(original);
                            for (var row : cases.reversed()) {
                                long before = compiled(p);
                                assertEquals(row[1], function.execute(row[0]).asLong(), label + "(" + row[0] + ")");
                                assertTrue(
                                    compiled(p) > before, label + "(" + row[0] + ") must enter installed guest code");
                                valid(host, label + " host remains installed");
                                for (var target : active) valid(target, label + " active target remains installed");
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(
                                    0L, ((Number) p.diagnostics().get(counter)).longValue(), label + "/" + counter);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth(),
                                label + " releases tuple results");
                            if (List.of("orderedBytes", "copiedBytes").contains(name))
                                assertEquals(0L, language.getHandoffState().get().getResults().getAllocations(),
                                    label + " saturated primitives write directly into locals");
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private final List<Long> inputs = inputs();
    private List<Long> inputs() {
        var result = new ArrayList<Long>();
        for (long i = -512; i <= 512; i++) result.add(i);
        result.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        result.sort(Long::compare);
        return result;
    }
    private Long longOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            return null;
        }
    }
    private void require(boolean condition) {
        if (!condition)
            throw new IllegalArgumentException("Failed requirement.");
    }
    private List<List<String>> checkedRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        require(lines.size() == 4116);
        var result = new ArrayList<List<String>>();
        for (int index = 0; index < lines.size(); index++) {
            var fields = Arrays.asList(lines.get(index).split("\t", -1));
            require(fields.size() == 3);
            var name = names.get(index / inputs.size());
            long raw = inputs.get(index % inputs.size());
            if (!fields.get(0).equals(name) || !Objects.equals(longOrNull(fields.get(1)), raw)
                || !Objects.equals(longOrNull(fields.get(2)), mathematical(name, raw)))
                throw new IllegalArgumentException(
                    "Missing, duplicate, reordered or mismatched byte-array row " + index);
            result.add(fields);
        }
        return result;
    }
    private List<String> replaced(List<String> lines, String first) {
        var result = new ArrayList<>(lines);
        result.set(0, first);
        return result;
    }
    @Test
    void originalSourceEvidenceAndCompleteCorpusFailClosed() throws Exception {
        ByteArrayFixtureEvidence.rejectionControls(root, "bytearray");
        var text = Files.readString(new File(root, "build/bytearray/oracle.tsv").toPath());
        checkedRows(text);
        var lines = new ArrayList<String>();
        for (var line : text.split("\\r\\n|\\n|\\r", -1))
            if (!line.isEmpty())
                lines.add(line);
        var duplicate = new ArrayList<>(lines);
        duplicate.add(lines.getFirst());
        var blank = new ArrayList<>(lines);
        blank.add("");
        for (var bad :
            List.of(lines.subList(1, lines.size()), duplicate, lines.reversed(), replaced(lines, lines.get(1)),
                replaced(lines, "unknown\t0\t0"), replaced(lines, "shortBytes\t9223372036854775808\t0"),
                replaced(lines, lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t999"), blank))
            assertThrows(IllegalArgumentException.class, () -> checkedRows(String.join("\n", bad) + "\n"));
    }
    @Test
    void managedStorageHasExactSizeUnsignedBytesIdentityAndGuardedDomain() {
        for (long size : new long[] {0, 1, 7, 8, 9, 256}) {
            var array = ManagedByteArray.allocate(size);
            assertEquals(byte[].class, array.getClass());
            assertEquals(byte.class, array.getClass().getComponentType());
            assertEquals(size, ManagedByteArray.size(array));
            assertSame(array, ManagedByteArray.freeze(array));
            assertNotSame(array, ManagedByteArray.allocate(size));
            for (long index = 0; index < size; index++) ManagedByteArray.write(array, index, (int) (index - 128));
            for (long index = 0; index < size; index++)
                assertEquals((index - 128) & 255, (long) ManagedByteArray.read(array, index));
            for (long index : new long[] {Long.MIN_VALUE, -1, size, Long.MAX_VALUE}) {
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.read(array, index));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.write(array, index, 1));
            }
        }
        for (long size : new long[] {Long.MIN_VALUE, -1, Integer.MAX_VALUE + 1L, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.allocate(size));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.require(new Object()));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.requireState(0L));
    }
    @Test
    void ownedGuestStorageRejectsFullWidthInvalidSizesAndIndicesBeforeWriting() {
        // Guest arrays use an owner so shrink preserves aliases. Exercise that
        // path separately from the raw host ByteArray controls above.
        for (long size : new long[] {Long.MIN_VALUE, -1, Integer.MAX_VALUE + 1L, 1L << 32, Long.MAX_VALUE}) {
            var failure = assertThrows(RuntimeFault.class, () -> ManagedByteArray.allocateGuest(size));
            assertEquals("Managed allocation size outside JVM domain", failure.getMessage(), "size=" + size);
        }
        for (long size : new long[] {0, 1, 3}) {
            var array = ManagedByteArray.allocateGuest(size);
            for (long index = 0; index < size; index++) ManagedByteArray.writeGuest(array, index, (int) (129 + index));
            var expected = new ArrayList<Long>();
            for (long i = 0; i < size; i++) expected.add(129 + i);
            for (long index : new long[] {Long.MIN_VALUE, -1, size, 1L << 32, Long.MAX_VALUE}) {
                var read = assertThrows(RuntimeFault.class, () -> ManagedByteArray.readGuest(array, index, true));
                var write = assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeGuest(array, index, 7));
                for (var failure : List.of(read, write))
                    assertEquals("Managed allocation range outside its backing storage", failure.getMessage(),
                        "size=" + size + "/index=" + index);
                assertEquals(size, ManagedByteArray.sizeGuest(array));
                var actual = new ArrayList<Long>();
                for (long i = 0; i < size; i++) actual.add((long) ManagedByteArray.readGuest(array, i, true));
                assertEquals(expected, actual);
            }
        }
    }
    private Expr operand(String name, Supplier<Object> action, List<String> events) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return action.get();
            }
        };
    }
    @Test
    void effectsEvaluateTheStateOperandBeforeWritingAndFailWithoutPublishing() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "destination", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        var events = new ArrayList<String>();
        var array = ManagedByteArray.allocate(1);
        ManagedByteArray.write(array, 0, 7);
        var write = ByteArrayOp.expression(ByteArrayOp.WRITE, CoreRepresentation.UNKNOWN,
            new Expr[] {operand("array", () -> array, events), operand("index", () -> 0L, events),
                operand("byte", () -> 129L, events), operand("state", () -> {
                    assertEquals(7L, (long) ManagedByteArray.read(array, 0));
                    return Unit.INSTANCE;
                }, events)});
        assertSame(Unit.INSTANCE, write.execute(frame));
        assertEquals(List.of("array", "index", "byte", "state"), events);
        assertEquals(129L, (long) ManagedByteArray.read(array, 0));
        var marker = new Object();
        frame.setObject(slot, marker);
        var failing = ByteArrayOp.expression(ByteArrayOp.NEW, CoreRepresentation.UNKNOWN,
            new Expr[] {operand("size", () -> 1L, events),
                operand("bad-state", () -> { throw new RuntimeFault("state failed"); }, events)});
        assertThrows(RuntimeFault.class, () -> failing.executeTuple(frame, new int[] {slot}, 0));
        assertSame(marker, frame.getObject(slot));
    }
    @Test
    void copyRangesMatchAnIndependentModelAndRejectInvalidDomainsWithoutWriting() {
        for (int sourceSize = 0; sourceSize <= 6; sourceSize++)
            for (int destinationSize = 0; destinationSize <= 6; destinationSize++) {
                var source = new byte[sourceSize];
                for (int i = 0; i < sourceSize; i++) source[i] = (byte) (i * 49 + 128);
                for (int from = 0; from <= sourceSize; from++)
                    for (int to = 0; to <= destinationSize; to++)
                        for (int count = 0; count <= Math.min(sourceSize - from, destinationSize - to); count++) {
                            var destination = new byte[destinationSize];
                            for (int i = 0; i < destinationSize; i++) destination[i] = (byte) (i + 17);
                            var expected = destination.clone();
                            for (int i = 0; i < count; i++) expected[to + i] = source[from + i];
                            ManagedByteArray.copy(source, from, destination, to, count);
                            assertArrayEquals(expected, destination);
                            var original = new byte[sourceSize];
                            for (int i = 0; i < sourceSize; i++) original[i] = (byte) (i * 49 + 128);
                            assertArrayEquals(original, source);
                        }
            }
        var source = new byte[] {0, -1, 17, 33};
        var destination = new byte[] {1, 2, 3, 4, 5, 6};
        for (var range : new long[][] {{-1, 0, 1}, {5, 0, 0}, {Long.MAX_VALUE, 0, 1}, {0, -1, 1}, {0, 7, 0},
                 {0, Long.MAX_VALUE, 1}, {0, 0, -1}, {0, 0, 5}, {0, 3, 4}, {1, 1, Long.MAX_VALUE},
                 {0, 0, Long.MIN_VALUE}, {Integer.MAX_VALUE + 1L, 0, 0}, {0, Integer.MAX_VALUE + 1L, 0}}) {
            var before = destination.clone();
            assertThrows(
                RuntimeFault.class, () -> ManagedByteArray.copy(source, range[0], destination, range[1], range[2]));
            assertArrayEquals(before, destination, "No partial write for " + Arrays.toString(range));
        }
        // GHC forbids the same array in different states, even disjoint/empty ranges.
        for (long count : new long[] {0, 1})
            assertThrows(
                RuntimeFault.class, () -> ManagedByteArray.copy(source, 0, ManagedByteArray.freeze(source), 2, count));
        var owned = ManagedByteArray.allocateGuest(4);
        for (long index = 0; index <= 3; index++) ManagedByteArray.writeGuest(owned, index, (int) (17 + index));
        for (long count : new long[] {0, 1}) {
            var failure = assertThrows(RuntimeFault.class,
                () -> ManagedByteArray.copyGuest(owned, 0, ManagedByteArray.freezeGuest(owned), 2, count, false));
            assertEquals("copyByteArray# requires distinct source and destination arrays", failure.getMessage());
            var actual = new ArrayList<Long>();
            for (long i = 0; i <= 3; i++) actual.add((long) ManagedByteArray.readGuest(owned, i, true));
            assertEquals(List.of(17L, 18L, 19L, 20L), actual);
        }
    }
    private Expr copyExpression(boolean fail, byte[] source, byte[] destination, List<String> events) {
        return ByteArrayOp.expression(ByteArrayOp.COPY, CoreRepresentation.UNKNOWN,
            new Expr[] {operand("source", () -> source, events), operand("sourceOffset", () -> 0L, events),
                operand("destination", () -> destination, events), operand("destinationOffset", () -> 1L, events),
                operand("count", () -> 2L, events), operand("state", () -> {
                    assertArrayEquals(new byte[] {7, 8, 9}, destination);
                    if (fail)
                        throw new RuntimeFault("state failed");
                    return Unit.INSTANCE;
                }, events)});
    }
    @Test
    void copyEvaluatesAllSixOperandsBeforeTheEffectAndStateFailureDoesNotWrite() {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var source = new byte[] {0, -1};
        var destination = new byte[] {7, 8, 9};
        var events = new ArrayList<String>();
        assertThrows(RuntimeFault.class, () -> copyExpression(true, source, destination, events).execute(frame));
        assertArrayEquals(new byte[] {7, 8, 9}, destination);
        assertEquals(List.of("source", "sourceOffset", "destination", "destinationOffset", "count", "state"), events);
        events.clear();
        assertSame(Unit.INSTANCE, copyExpression(false, source, destination, events).execute(frame));
        assertArrayEquals(new byte[] {7, 0, -1}, destination);
        assertEquals(List.of("source", "sourceOffset", "destination", "destinationOffset", "count", "state"), events);
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var item : list) result.addAll(applications(item));}else if(value instanceof Map<?,?> map)
            for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private List<Object> application(Object module, String primitive) {
        for (var app : applications(module)) {
            var function = (List<?>) app.get(1);
            if (function.subList(0, Math.min(2, function.size())).equals(List.of("prim", primitive)))
                return app;
        }
        throw new NoSuchElementException();
    }
    private Map<String, Object> configured(Map<String, Object> module, boolean diagnostic) {
        var result = new LinkedHashMap<>(module);
        result.put("diagnosticUnsupported", diagnostic);
        return result;
    }
    @Test
    void copiedRangesAndAllOperandProofsAreCheckedInBothBackends() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var mutation : new long[][] {{1, -1}, {1, 5}, {1, Long.MAX_VALUE}, {3, -1}, {3, 7},
                             {3, Long.MAX_VALUE}, {4, -1}, {4, 5}, {4, Long.MAX_VALUE}}) {
                        int argument = (int) mutation[0];
                        long value = mutation[1];
                        var module = CoreModules.reachable(merged(paths), "copiedBytes");
                        var args = (List<Object>) application(module, "copyByteArray#").get(2);
                        args.set(argument,
                            List.of("lit", "int", Long.toString(value),
                                CoreRepresentations.metadata((List<Object>) args.get(argument))));
                        var function =
                            context.asValue(new EntryValue(program(language, module, backend), "copiedBytes", 1));
                        var failure = assertThrows(PolyglotException.class, () -> function.execute(0L));
                        assertTrue(Objects.toString(failure.getMessage(), "").contains("ByteArray# copy range"),
                            backend + "/" + argument + "/" + value + ": " + failure);
                    }
                    for (long count : new long[] {0, 1})
                        for (boolean alias : new boolean[] {false, true}) {
                            var module = CoreModules.reachable(merged(paths), "copiedBytes");
                            var args = (List<Object>) application(module, "copyByteArray#").get(2);
                            // Either copy in the fixture may be visited first. Keep both
                            // ranges within the four-byte source when aliasing its destination.
                            for (var row : new long[][] {{1, 0}, {3, 2}, {4, count}}) {
                                int argument = (int) row[0];
                                args.set(argument,
                                    List.of("lit", "int", Long.toString(row[1]),
                                        CoreRepresentations.metadata((List<Object>) args.get(argument))));
                            }
                            if (alias)
                                args.set(2, args.getFirst());
                            var function =
                                context.asValue(new EntryValue(program(language, module, backend), "copiedBytes", 1));
                            if (alias) {
                                var failure = assertThrows(PolyglotException.class, () -> function.execute(0L));
                                assertTrue(Objects.toString(failure.getMessage(), "")
                                               .contains("requires distinct source and destination"),
                                    backend + "/" + count + ": " + failure);
                            } else {
                                var expected =
                                    BigInteger.valueOf(mathematical("copiedBytes", 0))
                                        .subtract(
                                            BigInteger.valueOf(33 * count).multiply(BigInteger.valueOf(257).pow(6)));
                                assertEquals(expected.longValue(), function.execute(0L).asLong(),
                                    backend + "/" + count + " distinct arrays");
                            }
                        }
                    for (boolean diagnostic : new boolean[] {false, true}) {
                        var module = CoreModules.reachable(merged(paths), "copiedBytes");
                        var proof =
                            (Map<String, Object>) CoreRepresentations.metadata(application(module, "copyByteArray#"))
                                .get("rep");
                        proof.put("kind", "unknown");
                        proof.put("aggregate", "unboxed-tuple");
                        proof.put("components", List.of());
                        assertThrows(
                            RuntimeFault.class, () -> program(language, configured(module, diagnostic), backend));
                    }
                    for (int argument = 0; argument <= 5; argument++)
                        for (boolean diagnostic : new boolean[] {false, true}) {
                            var module = CoreModules.reachable(merged(paths), "copiedBytes");
                            var args = (List<Object>) application(module, "copyByteArray#").get(2);
                            var proof =
                                (Map<String, Object>) CoreRepresentations.metadata((List<Object>) args.get(argument))
                                    .get("rep");
                            proof.put("primReps",
                                List.of(argument == 0 || argument == 2 ? "BoxedRep (Just Lifted)" : "DoubleRep"));
                            if (argument != 0 && argument != 2)
                                proof.put("kind", "double");
                            assertThrows(RuntimeFault.class,
                                ()
                                    -> program(language, configured(module, diagnostic), backend),
                                backend + "/copy argument " + argument + "/" + diagnostic);
                        }
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    void invalidSizesAndIndicesAreGuardedOnBothBackendsWithoutNativeUndefinedInputs() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        record Mutation(String primitive, int operand, long[] values) {}
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var mutation : List.of(
                             new Mutation("newByteArray#", 0, new long[] {-1, Integer.MAX_VALUE + 1L, Long.MAX_VALUE}),
                             new Mutation("writeWord8Array#", 1, new long[] {-1, 3, Long.MAX_VALUE}),
                             new Mutation("indexWord8Array#", 1, new long[] {-1, 3, Long.MAX_VALUE})))
                        for (long value : mutation.values()) {
                            var module = CoreModules.reachable(merged(paths), "orderedBytes");
                            var app = application(module, mutation.primitive());
                            var args = (List<Object>) app.get(2);
                            var old = (List<Object>) args.get(mutation.operand());
                            args.set(mutation.operand(),
                                List.of("lit", "int", Long.toString(value), CoreRepresentations.metadata(old)));
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, "orderedBytes", 1));
                            var failure = assertThrows(PolyglotException.class, () -> function.execute(5L));
                            var guard = mutation.primitive().equals("newByteArray#")
                                ? "Managed allocation size outside JVM domain"
                                : "Managed allocation range outside its backing storage";
                            assertEquals(RuntimeFault.class.getName() + ": " + guard, failure.getMessage(),
                                backend + "/" + mutation.primitive() + "/" + value);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                        }
                    var good = context.asValue(
                        new EntryValue(program(language, CoreModules.reachable(merged(paths), "orderedBytes"), backend),
                            "orderedBytes", 1));
                    assertEquals(mathematical("orderedBytes", 5), good.execute(5L).asLong());
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    void exactShapesArityAndSaturationAreRequiredInBothLoadModes() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : byteOperations)
                        for (int mutation = 0; mutation <= 6; mutation++)
                            for (boolean diagnostic : new boolean[] {false, true}) {
                                var module = CoreModules.reachable(
                                    merged(paths), operation == ByteArrayOp.COPY ? "copiedBytes" : "orderedBytes");
                                var app = application(module, operation.getPrimitive());
                                var args = (List<Object>) app.get(2);
                                var flags = (List<Object>) app.get(3);
                                var metadata = CoreRepresentations.metadata(app);
                                switch (mutation) {
                                    case 0 -> {
                                        args.removeLast();
                                        flags.removeLast();
                                        metadata.remove("callDemand");
                                    }
                                    case 1 -> {
                                        args.add(args.getFirst());
                                        flags.add(false);
                                        metadata.remove("callDemand");
                                    }
                                    case 2 -> metadata.remove("rep");
                                    case 3 -> {
                                        var proof = (Map<String, Object>) metadata.get("rep");
                                        proof.put("primReps", List.of("DoubleRep"));
                                        proof.put("kind", "double");
                                        proof.remove("aggregate");
                                        proof.remove("components");
                                    }
                                    case 4 -> flags.set(0, true);
                                    case 5 ->
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.getFirst())
                                                .get("rep"))
                                            .put("kind", "unknown");
                                    case 6 -> {
                                        if (operation.getTuple()) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            var children = (List<Object>) proof.get("components");
                                            children.set(0,
                                                Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
                                                    List.of(), "primReps", List.of(), "evaluated", true));
                                        } else
                                            ((Map<String, Object>) CoreRepresentations
                                                    .metadata((List<Object>) args.getFirst())
                                                    .get("rep"))
                                                .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(language, configured(module, diagnostic), backend),
                                    backend + "/" + operation.getPrimitive() + "/mutation" + mutation + "/"
                                        + diagnostic);
                            }
                    for (var operation : byteOperations) {
                        var module = CoreModules.reachable(
                            merged(paths), operation == ByteArrayOp.COPY ? "copiedBytes" : "orderedBytes");
                        var app = application(module, operation.getPrimitive());
                        var primitive = new ArrayList<>((List<?>) app.get(1));
                        app.clear();
                        app.addAll(primitive);
                        assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                    }
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    void lexicalLiftedReferenceCannotBecomeByteArrayByRelabellingAnOccurrence() {
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var unlifted = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var lifted = new LinkedHashMap<>(unlifted);
        lifted.put("primReps", List.of("BoxedRep (Just Lifted)"));
        var number = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var body = List.of("app", List.of("prim", "sizeofByteArray#"),
            List.of(List.of("var", "array", Map.of("rep", unlifted))), List.of(false), false, false,
            Map.of("rep", number));
        var lambda = List.of("lam", List.of(Map.of("id", "array", "name", "array", "lifted", false, "rep", lifted)),
            body, Map.of("rep", closure, "resultRep", number));
        Map<String, Object> module = Map.of("schema", 1, "ghc", "9.14.1", "constructors", List.of(), "bindings",
            List.of(Map.of("id", "root", "name", "root", "lifted", true, "arity", 1, "expr", lambda)));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (boolean diagnostic : new boolean[] {false, true}) {
                        var failure = assertThrows(
                            RuntimeFault.class, () -> program(language, configured(module, diagnostic), backend));
                        assertTrue(
                            Objects.toString(failure.getMessage(), "").contains("Conflicting Core boxed levity proofs"),
                            failure.getMessage());
                    }
                } finally {
                    context.leave();
                }
            }
    }
}
