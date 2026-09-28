// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.Unit.INSTANCE;

@SuppressWarnings("unchecked")
public class AtomicIntArrayTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot")),
                       directory = root.resolve("build/atomic-int-arrays");
    private final Map<String, AtomicIntArrayOp> named = named();
    private Map<String, AtomicIntArrayOp> named() {
        var result = new LinkedHashMap<String, AtomicIntArrayOp>();
        String[] names = {"fetchAddResult", "fetchSubResult", "fetchAndResult", "fetchNandResult", "fetchOrResult",
            "fetchXorResult", "casIntResult", "casInt8Result", "casInt16Result", "casInt32Result", "casInt64Result",
            "atomicLoadStore"};
        var operations =
            List.of(AtomicIntArrayOp.ADD, AtomicIntArrayOp.SUB, AtomicIntArrayOp.AND, AtomicIntArrayOp.NAND,
                AtomicIntArrayOp.OR, AtomicIntArrayOp.XOR, AtomicIntArrayOp.CAS, AtomicIntArrayOp.CAS8,
                AtomicIntArrayOp.CAS16, AtomicIntArrayOp.CAS32, AtomicIntArrayOp.CAS64, AtomicIntArrayOp.WRITE);
        for (int i = 0; i < names.length; i++) result.put(names[i], operations.get(i));
        return result;
    }
    private final List<Long> initials = List.of(Long.MIN_VALUE, -2147483649L, -32769L, -129L, -1L, 0L, 127L, 128L,
                                 32768L, 2147483648L, Long.MAX_VALUE),
                             replacements = List.of(Long.MIN_VALUE, -129L, 0L, 128L, Long.MAX_VALUE);
    private Map<String, Object> json(Path file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file));
    }
    private Context context() {
        return context(true);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "installed");
        var runtime = Truffle.getRuntime();
        runtime.getClass()
            .getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
    }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth());
        assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences());
        assertEquals(0, handoff.getResults().retainedReferences());
    }
    private long narrow(long value, int width) {
        return switch (width) {
            case 1 -> (byte) value;
            case 2 -> (short) value;
            case 4 -> (int) value;
            default -> value;
        };
    }
    private long model(AtomicIntArrayOp operation, long old, long operand) {
        return model(operation, old, operand, 0);
    }
    private long model(AtomicIntArrayOp operation, long old, long operand, long replacement) {
        return switch (operation) {
            case READ -> old;
            case WRITE -> operand;
            case ADD -> old + operand;
            case SUB -> old - operand;
            case AND -> old & operand;
            case NAND -> ~(old & operand);
            case OR -> old | operand;
            case XOR -> old ^ operand;
            default -> old == narrow(operand, operation.getWidth()) ? narrow(replacement, operation.getWidth()) : old;
        };
    }
    private record Row(String name, long initial, long operand, long replacement, long result) {}
    private List<Row> expectedRows() {
        var rows = new ArrayList<Row>();
        for (var entry : named.entrySet()) {
            var operation = entry.getValue();
            for (long initial : initials)
                for (long operand : new TreeSet<>(List.of(initial, initial + 1, initial + 256, 0L, -1L)))
                    for (long replacement : replacements) {
                        long old = narrow(initial, operation.getWidth()),
                             second = model(operation, old, operand, replacement),
                             last = operation.getOperands() == 2 ? model(operation, second, operand, initial)
                                                                 : model(operation, second, replacement);
                        rows.add(
                            new Row(entry.getKey(), initial, operand, replacement, old + 17L * second + 31L * last));
                    }
        }
        return rows;
    }
    @Test
    public void nativeFamilyWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void nativeFamilyAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }
    private CoreRepresentation proof(CoreKind kind, List<String> reps, List<CoreRepresentation> fields) {
        return new CoreRepresentation(kind, false, false, reps, fields, null, null, null, null);
    }
    private CoreRepresentation reps(CoreRepresentation p, List<String> reps) {
        return p.copy(p.getKind(), p.getEvaluated(), p.getPresent(), reps, p.getComponents(), p.getVector(),
            p.getAlternatives(), p.getTagSlot(), p.getAlternativeSlots());
    }
    private CoreRepresentation fields(CoreRepresentation p, List<CoreRepresentation> fields) {
        return p.copy(p.getKind(), p.getEvaluated(), p.getPresent(), p.getPrimReps(), fields, p.getVector(),
            p.getAlternatives(), p.getTagSlot(), p.getAlternativeSlots());
    }
    @Test
    public void loweredIntegralCarriersKeepArityStateAndTupleOrderChecks() {
        var owner = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"), null);
        var state = proof(CoreKind.VOID, List.of(), null);
        var integer = proof(CoreKind.LONG, List.of("IntRep"), null);
        for (var operation : AtomicIntArrayOp.values()) {
            var payload = reps(integer, List.of(switch (operation.getWidth()) {
                case 1 -> "Int8Rep";
                case 2 -> "Int16Rep";
                case 4 -> "Int32Rep";
                default -> "IntRep";
            }));
            var arguments = new ArrayList<>(List.of(owner, integer));
            arguments.addAll(Collections.nCopies(operation.getOperands(), payload));
            arguments.add(state);
            var tuple = proof(CoreKind.UNKNOWN, payload.getPrimReps(), List.of(state, payload));
            var result = operation.getTuple() ? tuple : state;
            var flags = Collections.nCopies(arguments.size(), false);
            for (var rep : List.of("IntRep", "WordRep", "Int8Rep", "Word16Rep", "Int32Rep", "Word64Rep")) {
                var relabelled =
                    arguments.stream().map(p -> p.getKind() == CoreKind.LONG ? reps(p, List.of(rep)) : p).toList();
                // Offsets are always machine Long; only payloads of narrow CAS use Int.
                if (relabelled.get(1).isLong() && (operation.getWidth() == 8 || operation.getOperands() == 0))
                    operation.validate(relabelled, flags, result);
                else
                    assertThrows(RuntimeFault.class, () -> operation.validate(relabelled, flags, result));
            }
            for (var rep : operation.getWidth() < 8
                    ? List.of("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep")
                    : List.of("IntRep", "WordRep", "Int64Rep", "Word64Rep")) {
                var relabelled = new ArrayList<CoreRepresentation>();
                for (int i = 0; i < arguments.size(); i++)
                    relabelled.add(
                        i >= 2 && i < arguments.size() - 1 ? reps(arguments.get(i), List.of(rep)) : arguments.get(i));
                operation.validate(relabelled, flags, result);
            }
            for (int i = 0; i < arguments.size(); i++) {
                var broken = new ArrayList<>(arguments);
                broken.set(i, proof(CoreKind.DOUBLE, List.of("DoubleRep"), null));
                assertThrows(RuntimeFault.class, () -> operation.validate(broken, flags, result));
            }
            assertThrows(RuntimeFault.class,
                () -> operation.validate(arguments.subList(0, arguments.size() - 1), flags, result));
            assertThrows(RuntimeFault.class,
                () -> operation.validate(arguments, Collections.nCopies(arguments.size(), true), result));
            for (var bad : List.of(integer, fields(tuple, List.of(integer, state)), fields(tuple, List.of(integer)),
                     fields(tuple, List.of())))
                assertThrows(RuntimeFault.class, () -> operation.validate(arguments, flags, bad));
        }
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = json(directory.resolve("manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(new ArrayList<>(named.keySet()), manifest.get("entries"));
        var inputs = new HashSet<>(
            List.of("compiler/test-fixtures/AtomicIntArrayAudit.hs", "test/haskell-fixtures/AtomicIntArrayFixtures.hs",
                "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
                "scripts/core-capabilities.json", "scripts/audit-core.py", "compiler/build.sh", "compiler/export.sh",
                "compiler/toolchain.sh", "compiler/plugin.py", "src/main/resources/thc/scalar-primop-signatures.json"));
        try (var files = Files.list(root.resolve("compiler/THC"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".hs"))
                .forEach(p -> inputs.add("compiler/THC/" + p.getFileName()));
        }
        try (var files = Files.list(root.resolve("scripts"))) {
            files
                .filter(
                    p -> p.getFileName().toString().startsWith("core_") && p.getFileName().toString().endsWith(".py"))
                .forEach(p -> inputs.add("scripts/" + p.getFileName()));
        }assertEquals(inputs,((Map<?,?>)manifest.get("inputHashes")).keySet());
        var artifactNames = new HashSet<>(List.of("NativeAtomicIntArrays.hs", "requests.tsv", "oracle.tsv"));
        var commands = new ArrayList<>(List.of("native-build", "native-oracle"));
        for (var stage : List.of("pre", "post")) {
            artifactNames.add(stage + "/core/AtomicIntArrayAudit.json");
            artifactNames.add(stage + "/core/THC.InterfaceClosure.json");
            commands.add(stage + "-export");
            for (var name : named.keySet()) {
                artifactNames.add(stage + "/" + name + ".audit.json");
                commands.add(stage + "-" + name + "-audit");
            }
        }
        for (var command : commands)
            for (var suffix : List.of("stdout", "stderr", "command.json"))
                artifactNames.add("commands/" + command + "." + suffix);assertEquals(new HashSet<>(artifactNames.stream().map(n->"build/atomic-int-arrays/"+n).toList()),((Map<?,?>)manifest.get("artifactHashes")).keySet(),"Closed native/Core provenance inventory");
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), digest, "Stale atomic-array " + kind + ": " + e.getKey());
            }
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(directory.resolve("oracle.tsv"))) {
            var fields = line.split(" ", -1);
            assertEquals(5, fields.length);
            rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3]),
                Long.parseLong(fields[4])));
        }
        assertEquals(expectedRows(), rows, "Native oracle must independently match signed old-value/wraparound model");
        assertEquals((long) rows.size(), manifest.get("nativeRows"));
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : stage.getValue()) modules.add(json(root.resolve(path)));
            var merged = CoreModules.merge(modules);
            for (var mapping : named.entrySet()) {
                var name = mapping.getKey();
                var operation = mapping.getValue();
                var audit = json(directory.resolve(stage.getKey() + "/" + name + ".audit.json"));
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
                var primops = new HashSet<>(
                    ((List<Map<String, Object>>) audit.get("primitives")).stream().map(p -> p.get("name")).toList());
                assertTrue(primops.contains(operation.getPrimitive()));
                if (name.equals("atomicLoadStore"))
                    assertTrue(primops.contains("atomicReadIntArray#"));
                var evidence = new ArrayCoreEvidence(merged, name);
                assertEquals(1, evidence.getBindings().size(), stage.getKey() + "/" + name + " closed original worker");
                assertEquals(
                    2, evidence.guestLambdas(evidence.getRoot().get("expr")).size(), "Export retains the state lambda");
                assertEquals(1, evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size(),
                    "Exact State# redex stays in-frame");
                var cases = rows.stream().filter(r -> r.name.equals(name)).toList();
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var program = program(
                                language, changed(CoreModules.reachable(merged, name), "instrument", true), backend);
                            var entry = program.entryTarget(name);
                            var label = stage.getKey() + "/" + backend + "/" + name + "/inlining=" + inlining;
                            for (var row : cases) call(row, entry, label, language);
                            var targets = activeTargets(entry);
                            assertEquals(1, targets.size(), label + " public entry with in-frame State# body");
                            for (var target : targets) compile(target);
                            long allocations = language.getHandoffState().get().getResults().getAllocations();
                            for (var row : cases.reversed()) {
                                long before = count(program, "compiledEntries");
                                call(row, entry, label, language);
                                assertEquals(1L, count(program, "compiledEntries") - before,
                                    label + " exact entry, including the first call after installation");
                                assertEquals(targets, activeTargets(entry), label + " target identities");
                                for (var target : targets) valid(target, label);
                            }
                            assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations(),
                                label + " result packet reuse");
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(program, counter), label);
                            System.out.println("AtomicIntArray PASS " + label + " rows=" + cases.size());
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private void call(Row row, RootCallTarget entry, String label, Language language) {
        assertEquals(row.result, Calls.target(entry, new Object[] {0L, row.initial, row.operand, row.replacement}),
            label + "/" + row);
        released(language);
    }
    private byte[] image(int width, long value) {
        var bytes = new byte[32];
        Arrays.fill(bytes, (byte) 53);
        store(bytes, width, value);
        return bytes;
    }
    private void store(byte[] bytes, int width, long value) {
        var view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        switch (width) {
            case 1 -> bytes[width] = (byte) value;
            case 2 -> view.putShort(width, (short) value);
            case 4 -> view.putInt(width, (int) value);
            default -> view.putLong(width, value);
        }
    }
    private ManagedAllocation owner(byte[] bytes) {
        var allocation = ManagedByteArray.allocateGuest(bytes.length);
        allocation.copyBytesIn(bytes, 0, 0, bytes.length);
        return allocation;
    }
    @Test
    public void everyWidthChecksOwnedMutableContainedPointerFreeStorageBeforeEffects() {
        for (var operation : AtomicIntArrayOp.values()) {
            var bytes = image(operation.getWidth(), -1);
            var owner = owner(bytes);
            for (long index : List.of(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 32, 32L / operation.getWidth())) {
                assertThrows(RuntimeFault.class, () -> operation.execute(owner, index, -1, 7));
                assertArrayEquals(bytes, owner.copyBytesOut(0, 32));
            }
            var shortOwner = ManagedByteArray.allocateGuest(2L * operation.getWidth() - 1);
            assertThrows(RuntimeFault.class, () -> operation.execute(shortOwner, 1, 0, 1));
            var empty = ManagedByteArray.allocateGuest(0);
            assertThrows(RuntimeFault.class, () -> operation.execute(empty, 0, 0, 1));
            for (var bad : Arrays.asList(null, new Object(), bytes)) {
                var failure = assertThrows(RuntimeFault.class, () -> operation.execute(bad, 1, -1, 1));
                assertEquals(operation.getPrimitive() + " requires an owned MutableByteArray#", failure.getMessage());
                assertArrayEquals(bytes, owner.copyBytesOut(0, 32));
            }
            assertThrows(RuntimeFault.class, () -> operation.execute(ManagedAllocation.immutable(bytes, 8), 1, -1, 1));
            var pointerOwner = ManagedByteArray.allocateGuest(16);
            var pointer = ManagedAddress.fromAllocation(pointerOwner);
            pointerOwner.writeAddressByteOffset(0, pointer);
            for (long index : List.of(0L, 8L / operation.getWidth() - 1)) {
                assertThrows(RuntimeFault.class, () -> operation.execute(pointerOwner, index, 0, 1));
                assertSame(pointer, pointerOwner.readAddressByteOffset(0));
            }
            operation.execute(pointerOwner, 8L / operation.getWidth(), 0, 1);
            assertSame(pointer, pointerOwner.readAddressByteOffset(0), "Disjoint atomic access preserves pointer cell");
            owner.shrink(operation.getWidth());
            assertThrows(RuntimeFault.class, () -> operation.execute(owner, 1, -1, 1));
            assertArrayEquals(Arrays.copyOf(bytes, operation.getWidth()), owner.copyBytesOut(0, operation.getWidth()));
        }
    }
    private Map<String, Object> applicationProof(List<?> expression) {return (Map<String,Object>)((Map<?,?>)expression.getLast()).get("rep");
    }
    private record Signature(List<Map<String, Object>> arguments, Map<String, Object> result) {}
    private Map<String, Object> synthetic(AtomicIntArrayOp operation) throws Exception {
        // Use the actual GHC application proofs, not the whole capability document:
        // unrelated unsigned bounds in that document need not fit JSON's Long carrier.
        var name = operation == AtomicIntArrayOp.READ
            ? "atomicLoadStore"
            : single(named.entrySet().stream().filter(e -> e.getValue() == operation).toList()).getKey();
        var evidence = new ArrayCoreEvidence(json(directory.resolve("pre/core/AtomicIntArrayAudit.json")), name);
        var calls = evidence.nodes(evidence.getRoot().get("expr"))
                        .stream()
                        .filter(n
                            -> !n.isEmpty() && "app".equals(n.getFirst()) && n.size() > 1
                                && n.get(1) instanceof List<?> f && !f.isEmpty() && "prim".equals(f.getFirst())
                                && f.size() > 1 && Objects.equals(f.get(1), operation.getPrimitive()))
                        .toList();
        assertEquals(List.of(AtomicIntArrayOp.READ, AtomicIntArrayOp.WRITE).contains(operation) ? 3 : 2, calls.size());
        var signatures = new ArrayList<Signature>();
        for (var call : calls) {
            var arguments = (List<List<Object>>) call.get(2);
            assertEquals(Collections.nCopies(arguments.size(), false), call.get(3));
            var signature =
                new Signature(arguments.stream().map(this::applicationProof).toList(), applicationProof(call));
            if (!signatures.contains(signature))
                signatures.add(signature);
        }
        assertEquals(1, signatures.size(), "Original " + operation.getPrimitive() + " application proofs must agree");
        var signature = single(signatures);
        var proofs = signature.arguments;
        var result = signature.result;
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var parameters = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < proofs.size(); i++)
            parameters.add(Map.of("id", "p" + i, "lifted", false, "rep", proofs.get(i)));
        var app = List.of("app", List.of("prim", operation.getPrimitive()),
            parameters.stream().map(p -> List.of("var", p.get("id"), Map.of("rep", p.get("rep")))).toList(),
            Collections.nCopies(parameters.size(), false), false, false, Map.of("rep", result));
        List<Map<String, Object>> constructors;
        List<?> body;
        if (operation.getTuple()) {
            var components = (List<Map<String, Object>>) result.get("components");
            var fields = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < components.size(); i++)
                fields.add(Map.of("id", "f" + i, "lifted", false, "rep", components.get(i)));
            constructors = List.of(Map.of("id", "Tuple2", "kind", "unboxed-tuple", "arity", 2, "fieldReps",
                components.stream().map(c -> c.get("primReps")).toList(), "fieldLifted", List.of(false, false),
                "strictFields", List.of(false, false)));
            body = List.of("case", app, "tuple",
                List.of(List.of("data", "Tuple2", List.of("f0", "f1"),
                    List.of("var", "f1", Map.of("rep", components.get(1))), Map.of("binders", fields))),
                Map.of("rep", components.get(1), "binder", Map.of("id", "tuple", "lifted", false, "rep", result)));
        } else {
            constructors = List.of();
            body = List.of("case", app, "state",
                List.of(Arrays.asList("default", null, List.of(), List.of("lit", "int", "19", Map.of("rep", integer)))),
                Map.of("rep", integer, "binder", Map.of("id", "state", "lifted", false, "rep", result)));
        }
        return Map.of("instrument", true, "constructors", constructors, "bindings",
            List.of(Map.of("id", "atomic", "name", "atomic", "arity", parameters.size(), "lifted", true, "rep", closure,
                "expr",
                List.of("lam", parameters, body,
                    Map.of("rep", closure, "resultRep",
                        operation.getTuple() ? ((List<?>) result.get("components")).get(1) : integer)))));
    }
    private Object call(AtomicIntArrayOp operation, RootCallTarget entry, Object owner, long index, long operand,
        long replacement, Object state) {
        var args = new ArrayList<Object>(Arrays.asList(0L, owner, index));
        if (operation.getOperands() > 0) {
            if (operation.getWidth() < 8)
                args.add((int) operand);
            else
                args.add(operand);
        }
        if (operation.getOperands() == 2) {
            if (operation.getWidth() < 8)
                args.add((int) replacement);
            else
                args.add(replacement);
        }
        args.add(state);
        return ScalarTestCalls.callScalarTestTarget(entry, args.toArray());
    }
    private void positives(boolean compiled, AtomicIntArrayOp operation, RootCallTarget entry,
        ExecutableProgram program, Language language, String backend) throws Exception {
        for (long initial : initials)
            for (long operand : List.of(initial, initial + 256, ~initial)) {
                var expected = image(operation.getWidth(), initial);
                var owner = owner(expected);
                long old = narrow(initial, operation.getWidth());
                store(expected, operation.getWidth(), model(operation, old, operand, 128));
                long before = count(program, "compiledEntries");
                Object expectedOld;
                if (operation.getWidth() < 8)
                    expectedOld = (int) old;
                else
                    expectedOld = old;
                assertEquals(
                    operation.getTuple() ? expectedOld : 19L, call(operation, entry, owner, 1, operand, 128, INSTANCE));
                assertArrayEquals(expected, owner.copyBytesOut(0, 32), backend + "/" + operation);
                if (compiled) {
                    assertEquals(before + 1, count(program, "compiledEntries"));
                    valid(entry, backend + "/" + operation);
                }
                released(language);
            }
    }
    @Test
    public void typedBackendsReturnOldSignedValuesAndRejectStateBeforeMutation() throws Exception {
        for (var operation : AtomicIntArrayOp.values())
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = program(language, synthetic(operation), backend);
                        var entry = program.entryTarget("atomic");
                        positives(false, operation, entry, program, language, backend);
                        compile(entry);
                        positives(true, operation, entry, program, language, backend);
                        var bytes = image(operation.getWidth(), -1);
                        var owner = owner(bytes);
                        var failure = assertThrows(
                            RuntimeFault.class, () -> call(operation, entry, owner, Long.MAX_VALUE, 0, 1, 17L));
                        assertTrue(Objects.toString(failure.getMessage(), "").contains("zero-width scalar carrier"));
                        for (long index : List.of(-1L, Long.MAX_VALUE, 32L / operation.getWidth()))
                            assertThrows(
                                RuntimeFault.class, () -> call(operation, entry, owner, index, -1, 1, INSTANCE));
                        assertThrows(RuntimeFault.class, () -> call(operation, entry, bytes, 1, -1, 1, INSTANCE));
                        assertArrayEquals(bytes, owner.copyBytesOut(0, 32));
                        released(language);
                    } finally {
                        context.leave();
                    }
                }
    }
    private void parallel(IntConsumer action) throws Exception {
        parallel(4, action);
    }
    private void parallel(int threads, IntConsumer action) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int thread = 0; thread < threads; thread++) {
                int index = thread;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    action.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
    private record Observation(long operand, long old) {}
    @Test
    public void concurrentFetchHistoriesAreLinearizable() throws Exception {
        for (var operation : List.of(AtomicIntArrayOp.ADD, AtomicIntArrayOp.SUB, AtomicIntArrayOp.AND,
                 AtomicIntArrayOp.NAND, AtomicIntArrayOp.OR, AtomicIntArrayOp.XOR)) {
            long initial = operation == AtomicIntArrayOp.AND ? -1L : 0L;
            var owner = owner(image(8, initial));
            var rows = Collections.synchronizedList(new ArrayList<Observation>());
            int perThread = List.of(AtomicIntArrayOp.AND, AtomicIntArrayOp.OR).contains(operation) ? 8 : 250;
            parallel(thread -> {
                for (int i = 0; i < perThread; i++) {
                    long operand = switch (operation) {
                        case AND -> ~(1L << (thread * perThread + i));
                        case OR -> 1L << (thread * perThread + i);
                        case XOR, NAND -> -1L;
                        default -> 1L;
                    };
                    rows.add(new Observation(operand, operation.execute(owner, 1, operand, 0)));
                }
            });
            long current = initial;
            var remaining = new ArrayList<>(rows);
            while (!remaining.isEmpty()) {
                int index = -1;
                for (int i = 0; i < remaining.size(); i++)
                    if (remaining.get(i).old == current) {
                        index = i;
                        break;
                    }
                assertTrue(index >= 0, operation + " cannot linearize old value " + current);
                current = model(operation, current, remaining.remove(index).operand);
            }
            assertEquals(current, AtomicIntArrayOp.READ.execute(owner, 1, 0, 0));
            assertEquals(53L, owner.readByte(7));
            assertEquals(53L, owner.readByte(16));
        }
    }
    @Test
    public void everyCasWidthHasOneWinnerAndLinearizableRetryLoops() throws Exception {
        for (var operation : AtomicIntArrayOp.values())
            if (operation.getOperands() == 2) {
                var owner = owner(image(operation.getWidth(), 0));
                var old = Collections.synchronizedList(new ArrayList<Long>());
                parallel(thread -> old.add(operation.execute(owner, 1, 0, 1)));
                assertEquals(List.of(0L, 1L, 1L, 1L), old.stream().sorted().toList(), operation + " single winner");
                operation.execute(owner, 1, 1, 0);
                parallel(thread -> {
                    for (int i = 0; i < 100; i++) {
                        long expected = 0;
                        while (true) {
                            long observed = operation.execute(owner, 1, expected, expected + 1);
                            if (observed == expected)
                                break;
                            expected = observed;
                        }
                    }
                });
                assertEquals(narrow(400, operation.getWidth()), operation.execute(owner, 1, 0, 0),
                    operation + " retry increments");
            }
    }
    @Test
    public void overlappingCasWidthsAndExposedAliasesUseOneAtomicOrdering() throws Exception {
        var owner = owner(image(8, 0));
        long lowByteIndex = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 8L : 15L;
        parallel(thread -> {
            for (int i = 0; i < 250; i++) {
                if (thread < 2)
                    AtomicIntArrayOp.ADD.execute(owner, 1, 256, 0);
                else {
                    long expected = 0;
                    while (true) {
                        long observed = AtomicIntArrayOp.CAS8.execute(owner, lowByteIndex, expected, expected + 1);
                        if (observed == expected)
                            break;
                        expected = observed;
                    }
                }
            }
        });
        assertEquals(500L * 256 + (500L & 255), AtomicIntArrayOp.READ.execute(owner, 1, 0, 0));
        AtomicIntArrayOp.WRITE.execute(owner, 1, 0, 0);
        var raw = ManagedAddress.fromByteArray(owner.rawBytesIfPointerFree()).plus(8);
        var ownedAddress = ManagedAddress.fromAllocation(owner).plus(8);
        var observations = Collections.synchronizedList(new ArrayList<Long>());
        parallel(thread -> {
            for (int i = 0; i < 250; i++) {
                long old;
                switch (thread) {
                    case 0 -> old = AtomicAddressOp.ADD.numeric(raw, 1, 0);
                    case 1 -> {
                        long expected = AtomicAddressOp.READ.numeric(ownedAddress, 0, 0);
                        while (true) {
                            long observed = AtomicAddressOp.CAS.numeric(ownedAddress, expected, expected + 1);
                            if (observed == expected)
                                break;
                            expected = observed;
                        }
                        old = expected;
                    }
                    default -> old = AtomicIntArrayOp.ADD.execute(owner, 1, 1, 0);
                }
                observations.add(old);
            }
        });
        var expected = new ArrayList<Long>();
        for (long i = 0; i < 1000; i++) expected.add(i);
        assertEquals(expected, observations.stream().sorted().toList());
        assertEquals(1000L, AtomicIntArrayOp.READ.execute(owner, 1, 0, 0));
    }
    private void waitFor(ManagedAllocation flag, long value, long deadline) {
        while (AtomicIntArrayOp.READ.execute(flag, 0, 0, 0) != value) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("Atomic publication timed out");
            Thread.onSpinWait();
        }
    }
    @Test
    public void atomicReadAndWritePublishPayloadBetweenAllocations() throws Exception {
        var flag = ManagedByteArray.allocateGuest(8);
        var payload = new long[1];
        parallel(2, thread -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            if (thread == 0)
                for (int i = 0; i < 1000; i++) {
                    waitFor(flag, 0, deadline);
                    payload[0] = i;
                    AtomicIntArrayOp.WRITE.execute(flag, 0, 1, 0);
                }
            else
                for (int i = 0; i < 1000; i++) {
                    waitFor(flag, 1, deadline);
                    assertEquals((long) i, payload[0]);
                    AtomicIntArrayOp.WRITE.execute(flag, 0, 0, 0);
                }
        });
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var targets = new ArrayList<RootCallTarget>();
        visit(entry, seen, targets);
        return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target))
            return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(root);
        // Bytecode DSL operation caches are not ordinary @Children fields.
        // Its public instruction API exposes the actual adopted cached nodes.
        if (root instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, targets);
        targets.add(target); // Install callees before their callers.
    }

    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected a single element");
        return list.getFirst();
    }
    private static Map<String, Object> changed(Map<String, Object> module, String key, Object value) {
        var result = new LinkedHashMap<>(module);
        result.put(key, value);
        return result;
    }
    private static long count(ExecutableProgram program, String key) {
        return ((Number) program.diagnostics().get(key)).longValue();
    }
}
