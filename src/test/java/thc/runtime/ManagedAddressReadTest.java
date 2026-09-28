// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import thc.runtime.Unit;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ManagedAddressReadTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final Map<String, Integer> nativeEntries = entries();
    private Map<String, Integer> entries() {
        var result = new LinkedHashMap<String, Integer>();
        result.put("word32Read", 4);
        result.put("wordRead", 8);
        result.put("int32Read", 4);
        result.put("intRead", 8);
        return result;
    }
    private final List<Long> nativeSeeds = List.of(Long.MIN_VALUE, -4294967296L, -2147483649L, -2147483648L, -1L, 0L,
        1L, 127L, 128L, 255L, 256L, 2147483647L, 2147483648L, 4294967295L, Long.MAX_VALUE);
    private long nativeRead(String name, ByteBuffer bytes, int start) {
        return switch (name) {
            case "word32Read" -> bytes.getInt(start) & 0xffffffffL;
            case "wordRead", "intRead" -> bytes.getLong(start);
            case "int32Read" -> bytes.getInt(start);
            default -> throw new IllegalStateException("Unknown native address read " + name);
        };
    }
    private long nativeModel(List<String> row) {
        var name = row.get(0);
        long raw = Long.parseLong(row.get(1));
        int width = nativeEntries.get(name),
            start = Integer.parseInt(row.get(2)) + Integer.parseInt(row.get(3)) * width;
        var bytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        for (long word : new long[] {raw, raw ^ 0x0123456789abcdefL, ~raw, raw ^ 0xaaaaaaaaaaaaaaaaL})
            bytes.putLong(word);
        long before = nativeRead(name, bytes, start);
        bytes.put(start, (byte) (raw + 173));
        return 3 * before + 5 * nativeRead(name, bytes, start);
    }
    private final List<PinnedMemoryOp> operations = List.of(PinnedMemoryOp.READ_WORD16, PinnedMemoryOp.READ_INT16,
        PinnedMemoryOp.READ_WORD32, PinnedMemoryOp.READ_WORD, PinnedMemoryOp.READ_INT32, PinnedMemoryOp.READ_INT);
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private List<List<String>> lines(String path) throws Exception {
        var result = new ArrayList<List<String>>();
        for (var line : Files.readAllLines(new File(root, path).toPath()))
            result.add(Arrays.asList(line.split("\t", -1)));
        return result;
    }
    private void checkNative(List<String> row, Value function, Language language, String label) {
        assertEquals(Long.parseLong(row.getLast()),
            function.execute(Long.parseLong(row.get(1)), Long.parseLong(row.get(2)), Long.parseLong(row.get(3)))
                .asLong(),
            label + "/" + row);
        released(language);
    }
    @Test
    void genuineGhcNativeOracleMatchesBothBackends() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/managed-address-reads/manifest.json").toPath()));
        assertEquals(true, manifest.get("strictAccepted"));
        assertEquals(8L, manifest.get("strictAudits"));
        assertEquals(64L, manifest.get("wordBits"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(new File(root, item.getKey()).toPath())));
                assertEquals(item.getValue(), actual, "Stale address-read evidence: " + item.getKey());
            }
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var row : lines("build/managed-address-reads/oracle.tsv"))
            rows.computeIfAbsent(row.getFirst(), key -> new ArrayList<>()).add(row);
        assertEquals(nativeEntries.keySet(), new HashSet<>((List<String>) manifest.get("entries")));
        assertEquals(nativeEntries.keySet(), rows.keySet());
        int count = 0;
        for (var cases : rows.values()) count += cases.size();
        assertEquals(1800, count);
        assertEquals(1800L, manifest.get("nativeRows"));
        var requests = new ArrayList<List<String>>();
        for (var entry : nativeEntries.entrySet())
            for (long raw : nativeSeeds)
                for (int base = 0; base <= 32; base += 8)
                    for (int start = 0; start <= 32 - entry.getValue(); start += entry.getValue())
                        requests.add(List.of(entry.getKey(), Long.toString(raw), Integer.toString(base),
                            Integer.toString((start - base) / entry.getValue())));
        assertEquals(requests, lines("build/managed-address-reads/requests.tsv"));
        var orderedRows = lines("build/managed-address-reads/oracle.tsv");
        for (int i = 0; i < Math.min(requests.size(), orderedRows.size()); i++) {
            var request = requests.get(i);
            var row = orderedRows.get(i);
            assertEquals(request, row.subList(0, Math.min(4, row.size())));
            assertEquals(nativeModel(request), Long.parseLong(row.get(4)), "Native address read " + row);
        }
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var paths = stageEntry.getValue();
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : paths)
                modules.add((Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())));
            var source = CoreModules.merge(modules);
            Map<String, Object> fixture = null;
            for (var path : paths) {
                var candidate = (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath()));
                if ("ManagedAddressReadAudit".equals(candidate.get("module"))) {
                    if (fixture != null)
                        throw new IllegalArgumentException("Multiple matching fixtures");
                    fixture = candidate;
                }
            }
            if (fixture == null)
                throw new NoSuchElementException("Missing fixture");
            assertEquals(
                stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep",
                fixture.get("boundary"));
            var primitives = List.of("readWord32OffAddr#", "readWordOffAddr#", "readInt32OffAddr#", "readIntOffAddr#");
            int i = 0;
            for (var name : nativeEntries.keySet()) {
                var primitive = primitives.get(i++);
                var report = (Map<String, Object>) Json.parse(Files.readString(
                    new File(root, "build/managed-address-reads/" + stage + "-" + name + ".audit.json").toPath()));
                assertEquals(true, report.get("accepted"), stage + "/" + name);
                boolean present = false;
                for (var p : (List<Map<String, Object>>) report.get("primitives"))
                    present |= primitive.equals(p.get("name"));
                assertTrue(present, stage + "/" + name);
            }
            for (var rowEntry : rows.entrySet())
                for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                        var name = rowEntry.getKey();
                        var cases = rowEntry.getValue();
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var module = new LinkedHashMap<>(CoreModules.reachable(source, name));
                            module.put("instrument", true);
                            var runtime = program(language, module, backend);
                            var function = context.asValue(new EntryValue(runtime, name, 3));
                            var label = stage + "/" + backend;
                            for (var row : cases) checkNative(row, function, language, label);
                            assertTrue(function.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = ((Number) runtime.diagnostics().get("compiledEntries")).longValue();
                                checkNative(row, function, language, label);
                                assertTrue(
                                    ((Number) runtime.diagnostics().get("compiledEntries")).longValue() > before);
                                valid(runtime.hostEntryTarget(3));
                                valid(runtime.entryTarget(name));
                            }
                            assertEquals(0L, ((Number) runtime.diagnostics().get("unsupportedTraps")).longValue());
                            System.out.println(
                                "ManagedAddressRead PASS " + label + "/" + name + " rows=" + cases.size());
                        } finally {
                            context.leave();
                        }
                    }
        }
    }
    private long expected(ManagedAddressRead operation, byte[] bytes, int start) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        return switch (operation) {
            case CHAR -> buffer.get(start) & 255L;
            case WORD16 -> buffer.getShort(start) & 0xffffL;
            case INT16 -> buffer.getShort(start);
            case WORD32, WIDE_CHAR -> buffer.getInt(start) & 0xffffffffL;
            case INT32 -> buffer.getInt(start);
            case WORD, INT, WORD64, INT64 -> buffer.getLong(start);
        };
    }
    private long observed(ManagedAddressRead operation, Object value) {
        if (operation.isInt())
            return operation == ManagedAddressRead.WORD32 ? Integer.toUnsignedLong((Integer) value)
                                                          : ((Integer) value).longValue();
        return (Long) value;
    }
    private long read(ManagedAddressRead operation, ManagedAddress address, long offset) {
        if (operation.isInt())
            return observed(operation, operation.readInt(address, offset));
        return observed(operation, operation.read(address, offset));
    }
    @Test
    void fullWidthBoundsNegativeDerivedOffsetsAndOverflow() {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37 + 129);
        for (var operation : ManagedAddressRead.values()) {
            int width = operation.getWidth();
            for (int base = 0; base <= bytes.length; base++) {
                var address = ManagedAddress.fromByteArray(bytes).plus(base);
                for (long o = -9; o <= 9; o++) {
                    final long offset = o;
                    long start = base + offset * width;
                    if (start >= 0 && start + width <= bytes.length)
                        assertEquals(expected(operation, bytes, (int) start), read(operation, address, offset),
                            operation + "/" + base + "/" + offset);
                    else
                        assertThrows(RuntimeFault.class, () -> read(operation, address, offset));
                }
                for (long offset : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE / width - 1,
                         Long.MAX_VALUE / width + 1, 1L << 32, -(1L << 32)})
                    assertThrows(RuntimeFault.class, () -> read(operation, address, offset));
            }
            assertThrows(
                RuntimeFault.class, () -> read(operation, ManagedAddress.fromByteArray(new byte[width - 1]), 0));
            assertThrows(RuntimeFault.class, () -> read(operation, ManagedAddress.fromByteArray(new byte[0]), 0));
        }
        var allOnes = ManagedAddress.fromHex("ffffffffffffffff");
        assertEquals(65535L, read(ManagedAddressRead.WORD16, allOnes, 0));
        assertEquals(-1L, read(ManagedAddressRead.INT16, allOnes, 0));
        assertEquals(4294967295L, read(ManagedAddressRead.WORD32, allOnes, 0));
        assertEquals(4294967295L, ManagedAddressRead.WIDE_CHAR.read(allOnes, 0));
        assertEquals(-1L, read(ManagedAddressRead.INT32, allOnes, 0));
        assertEquals(-1L, ManagedAddressRead.WORD.read(allOnes, 0));
        assertEquals(-1L, ManagedAddressRead.INT.read(allOnes, 0));
    }
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
    private long payload(ManagedAddressRead read, VirtualFrame frame)
        throws com.oracle.truffle.api.frame.FrameSlotTypeException {
        if (read.isInt())
            return observed(read, frame.getInt(1));
        return observed(read, frame.getLong(1));
    }
    private Expr operand(String name, Object value, List<String> events) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return value;
            }
        };
    }
    @Test
    void stateValidationPrecedesReadsAndFailedReadsDoNotPublish() throws Exception {
        for (var operation : operations)
            for (var mode : List.of("ok", "state-throws", "bad-state", "bounds")) {
                var read = Objects.requireNonNull(operation.getAddressRead());
                var builder = FrameDescriptor.newBuilder();
                builder.addSlot(FrameSlotKind.Long, null, null);
                builder.addSlot(read.isInt() ? FrameSlotKind.Int : FrameSlotKind.Long, null, null);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
                var bytes = new byte[16];
                Arrays.fill(bytes, (byte) 127);
                var events = new ArrayList<String>();
                FrameAccess.writeLong(frame, 0, 17L);
                if (read.isInt())
                    FrameAccess.writeInt(frame, 1, 91);
                else
                    FrameAccess.writeLong(frame, 1, 91L);
                var token = new Expr() {
                    @Override
                    public Object execute(VirtualFrame current) {
                        events.add("state");
                        try {
                            assertEquals(91L, payload(read, frame));
                        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException failure) {
                            throw rethrow(failure);
                        }
                        if (mode.equals("state-throws"))
                            throw new RuntimeFault("failed state");
                        bytes[0] = 0;
                        return mode.equals("bad-state") ? 1L : Unit.INSTANCE;
                    }
                };
                var expression = new PinnedMemoryExpression(operation, CoreRepresentation.UNKNOWN,
                    new Expr[] {operand("address", ManagedAddress.fromByteArray(bytes), events),
                        operand("offset", mode.equals("bounds") ? 17L : 0L, events), token},
                    false);
                if (mode.equals("ok")) {
                    expression.executeTuple(frame, new int[] {0, 1}, 1);
                    assertEquals(expected(read, bytes, 0), payload(read, frame));
                } else {
                    assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[] {0, 1}, 1));
                    assertEquals(91L, payload(read, frame));
                }
                assertEquals(17L, frame.getLong(0));
                assertEquals(List.of("address", "offset", "state"), events);
            }
    }
    private Map<String, Object> scalar(String kind, String... reps) {
        return Map.of("kind", kind, "primReps", Arrays.asList(reps), "evaluated", true);
    }
    private final Map<String, Object> address = scalar("address", "AddrRep"), integer = scalar("long", "IntRep"),
                                      state = scalar("void"), closure = scalar("closure", "BoxedRep (Just Lifted)");
    private Map<String, Object> synthetic(PinnedMemoryOp operation) {
        var payload = scalar("long", Objects.requireNonNull(operation.getAddressRead()).getPayload());
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true, "primReps",
            payload.get("primReps"), "components", List.of(state, payload));
        var parameters = new ArrayList<Map<String, Object>>();
        var proofs = List.of(address, integer, state);
        for (int i = 0; i < proofs.size(); i++)
            parameters.add(Map.of("id", "p" + i, "lifted", false, "rep", proofs.get(i)));
        var args = new ArrayList<List<Object>>();
        for (var p : parameters) args.add(List.of("var", p.get("id"), Map.of("rep", p.get("rep"))));
        var call = List.of("app", List.of("prim", operation.getPrimitive()), args, List.of(false, false, false), false,
            false, Map.of("rep", tuple));
        var fields = List.of(
            Map.of("id", "s", "lifted", false, "rep", state), Map.of("id", "n", "lifted", false, "rep", payload));
        var body = List.of("case", call, "pair",
            List.of(List.of("data", "T2", List.of("s", "n"), List.of("var", "n", Map.of("rep", payload)),
                Map.of("binders", fields))),
            Map.of("rep", payload, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        return Map.of("instrument", true, "constructors",
            List.of(Map.of("id", "T2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)), "bindings",
            List.of(Map.of("id", "read", "name", "read", "arity", 3, "lifted", true, "rep", closure, "expr",
                List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", payload)))));
    }
    private long call(PinnedMemoryOp operation, RootCallTarget target, Object value, Object offset, Object token) {
        return observed(Objects.requireNonNull(operation.getAddressRead()),
            Calls.target(target, new Object[] {0L, value, offset, token}));
    }
    @Test
    void compiledReadsObserveMutationsAndRejectWrongCarriersInBothBackends() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
            for (var operation : operations) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var runtime = program(language, synthetic(operation), backend);
                        var target = runtime.entryTarget("read");
                        var read = Objects.requireNonNull(operation.getAddressRead());
                        var bytes = new byte[16];
                        var derived = ManagedAddress.fromByteArray(bytes).plus(8);
                        for (int v = 0; v <= 255; v++) {
                            bytes[0] = (byte) v;
                            call(operation, target, derived, -8L / read.getWidth(), Unit.INSTANCE);
                        }
                        compile(target);
                        for (int v = 255; v >= 0; v--) {
                            Arrays.fill(bytes, (byte) v);
                            assertEquals(expected(read, bytes, 0),
                                call(operation, target, derived, -8L / read.getWidth(), Unit.INSTANCE));
                            valid(target);
                            released(language);
                        }
                        for (var bad : Arrays.asList(null, 0L, new Object(), bytes))
                            assertThrows(RuntimeFault.class, () -> call(operation, target, bad, 0L, Unit.INSTANCE));
                        for (var bad : Arrays.asList(null, 0L, new Object()))
                            assertThrows(RuntimeFault.class, () -> call(operation, target, derived, 0L, bad));
                        for (long offset :
                            new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 1L << 32, -8L / read.getWidth() - 1})
                            assertThrows(
                                RuntimeFault.class, () -> call(operation, target, derived, offset, Unit.INSTANCE));
                        assertEquals(expected(read, bytes, 8), call(operation, target, derived, 0L, Unit.INSTANCE));
                        released(language);
                    } finally {
                        context.leave();
                    }
                }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var child : list) result.addAll(applications(child));}else if(value instanceof Map<?,?> map)
            for (var child : map.values()) result.addAll(applications(child));
        return result;
    }
    @Test
    void bothLoadersRejectForgedArgumentsAndTuplePayloads() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 7; mutation++)
                            for (boolean diagnostic : new boolean[] {false, true}) {
                                var module = (Map<String, Object>) Json.parse(Json.stringify(synthetic(operation)));
                                var applications = applications(module);
                                if (applications.size() != 1)
                                    throw new IllegalArgumentException("Expected single application");
                                var call = applications.getFirst();
                                var args = (List<List<Object>>) call.get(2);
                                var flags = (List<Object>) call.get(3);
                                var metadata = (Map<String, Object>) call.getLast();
                                switch (mutation) {
                                    case 0, 1, 2 ->
                                        ((Map<String, Object>) args.get(mutation).getLast())
                                            .put("rep", scalar("double", "DoubleRep"));
                                    case 3 -> flags.set(0, true);
                                    case 4 -> {
                                        args.remove(2);
                                        flags.remove(2);
                                    }
                                    case 5 -> metadata.put("rep", integer);
                                    case 6 -> {
                                        var tuple = (Map<String, Object>) metadata.get("rep");
                                        ((List<Object>) tuple.get("components")).set(1, scalar("double", "DoubleRep"));
                                        tuple.put("primReps", List.of("DoubleRep"));
                                    }
                                    case 7 -> {
                                        var tuple = (Map<String, Object>) metadata.get("rep");
                                        ((List<Object>) tuple.get("components")).set(0, integer);
                                        tuple.put("primReps",
                                            List.of("IntRep",
                                                Objects.requireNonNull(operation.getAddressRead()).getPayload()));
                                    }
                                }
                                var changed = new LinkedHashMap<>(module);
                                changed.put("diagnosticUnsupported", diagnostic);
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(language, changed, backend),
                                    backend + "/" + operation + "/" + mutation);
                            }
                } finally {
                    context.leave();
                }
            }
    }
}
