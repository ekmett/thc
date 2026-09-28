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
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class AlignedScalarMemoryTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> types = List.of("WideChar", "StablePtr");
    private final Set<String> names =
        types.stream()
            .flatMap(type
                -> List.of("Array", "OffAddr")
                    .stream()
                    .flatMap(
                        domain -> List.of("index", "read", "write").stream().map(verb -> verb + type + domain + "#")))
            .collect(Collectors.toSet());
    private Context context() {
        return context(false);
    }
    private Context context(boolean nativeAccess) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .allowNativeAccess(nativeAccess)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "100000")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private long model(String type, long raw) {
        if (type.equals("StablePtr"))
            return 1;
        var bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
        bytes.putInt(0, (int) (raw & 0x10ffffL));
        return bytes.getInt(0) & 0xffffffffL;
    }
    private record Row(String type, long raw, long offset, List<Long> expected) {}
    private Map<String, Object> json(Path path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(path));
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
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
        if (root instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var cached = argument.asCachedNode();
                        if (cached != null)
                            nodes.add(cached);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget child
                    && child.getRootNode() instanceof GuestRoot)
                    visit(child, seen, targets);
        targets.add(target);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target, String label) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, label + " installation");
        // Match EntryValue.compile and the Int16 retired-boundary regression:
        // restore the shared call boundary without executing any guest entry.
        var runtime = Truffle.getRuntime();
        runtime.getClass()
            .getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
        valid(target, label + " restored boundary");
    }
    private void released(Language language, String label) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth(), label + " argument depth");
        assertEquals(0, state.getArguments().retainedReferences(), label + " retained arguments");
        assertEquals(0, state.getResults().getDepth(), label + " result depth");
        assertEquals(0, state.getResults().retainedReferences(), label + " retained results");
        assertNull(state.getPending(), label + " pending argument loan");
    }
    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return list.getFirst();
    }
    @Test
    public void allPinnedScalarNamesMatchNativeAndIndependentModelOnFirstInstalledEntry() throws Exception {
        var directory = root.resolve("build/aligned-scalar-memory");
        var manifest = json(directory.resolve("manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, new HashSet<>((List<String>) manifest.get("primitives")));
        assertEquals(12, names.size());
        for (String key : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(key)).entrySet()) {
                var digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), digest, "Stale aligned scalar memory " + hash.getKey());
            }
        var rows = new ArrayList<Row>();
        for (String line : Files.readAllLines(directory.resolve("oracle.tsv"))) {
            var columns = List.of(line.split("\t", -1));
            assertEquals(9, columns.size());
            rows.add(new Row(columns.get(0), Long.parseLong(columns.get(1)), Long.parseLong(columns.get(2)),
                columns.subList(3, columns.size()).stream().map(Long::valueOf).toList()));
        }
        assertEquals(128L, manifest.get("nativeRows"));
        assertEquals(128, rows.size());
        assertEquals(new HashSet<>(types), rows.stream().map(Row::type).collect(Collectors.toSet()));
        for (var row : rows) {
            long replacement = ~row.raw;
            assertEquals(List.of(model(row.type, row.raw), model(row.type, row.raw), model(row.type, replacement),
                             model(row.type, replacement), 165L, 165L),
                row.expected, "independent native-endian model: " + row);
        }
        for (String stage : List.of("pre", "post")) {
            var audit = json(directory.resolve(stage + "/audit.json"));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("issues"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitives = (List<Map<String, Object>>) audit.get("primitives");
            for (String name : names) {
                var uses = (List<Map<String, Object>>) single(
                    primitives.stream().filter(it -> name.equals(it.get("name"))).toList())
                               .get("uses");
                var type = name.contains("StablePtr") ? "StablePtr" : "WideChar";
                assertEquals(Set.of("main:AlignedScalarMemoryAudit.aligned" + type),
                    uses.stream().map(it -> it.get("owner")).collect(Collectors.toSet()), stage + "/" + name);
            }
            var module = json(directory.resolve(stage + "/core/AlignedScalarMemoryAudit.json"));
            var expectedLabels = new LinkedHashMap<String, Set<String>>();
            for (String type : types) {
                String entry = "aligned" + type;
                var evidence = new ArrayCoreEvidence(module, entry);
                assertEquals(1, evidence.getBindings().size(), stage + "/" + entry + " original Core closure");
                assertTrue(evidence.globalReferences(evidence.getRoot().get("expr")).isEmpty());
                var outer = (List<?>) evidence.getRoot().get("expr");
                assertEquals("lam", outer.get(0));
                var stateCall = (List<?>) outer.get(2);
                assertEquals("app", stateCall.get(0), entry + " immediate runRW state call");
                var state = (List<?>) stateCall.get(1);
                assertEquals("lam", state.get(0));
                var stateFormal = single((List<Map<String, Object>>) state.get(1));
                assertEquals("State# RealWorld", stateFormal.get("type"));assertEquals("void",((Map<?,?>)stateFormal.get("rep")).get("kind"));
                var stateArgument = single((List<List<?>>) stateCall.get(2));
                assertEquals("void", stateArgument.get(0));
                var lambdas = evidence.guestLambdas(outer);
                // This fixed proof comes from original Core, never from the
                // runtime's discovered targets or its compiled-entry counter.
                assertEquals(2, lambdas.size(), entry + " public and runRW state roots");
                assertSame(outer, lambdas.get(0));
                assertSame(state, lambdas.get(1));
                assertSame(state, evidence.immediateStateLambda(stateCall));
                var labels = evidence.loweredGuestLambdas(outer)
                                 .stream()
                                 .map(expression
                                     -> "lambda "
                                         + ((List<Map<String, Object>>) expression.get(1))
                                             .stream()
                                             .map(it -> String.valueOf(it.get("name")))
                                             .collect(Collectors.joining(", ")))
                                 .collect(Collectors.toSet());
                assertEquals(1, labels.size(), entry + " public root after in-frame State# lowering");
                expectedLabels.put(type, labels);
            }
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var source = new LinkedHashMap<>(module);
                        source.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source)
                                                                          : new BytecodeProgram(language, source);

                        var stable = Language.currentState().getStablePointers();
                        var firstStable = stable.make(17L);
                        var secondStable = stable.make(29L);
                        try {
                            for (String type : types) {
                                String entry = "aligned" + type;
                                var target = program.entryTarget(entry);
                                var inputs = rows.stream().filter(row -> row.type.equals(type)).toList();
                                String entryLabel = stage + "/" + backend + "/" + entry;
                                class CallsForType {
                                    long count() {
                                        return ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    }
                                    Object call(Row row, int selector) {
                                        Object[] arguments = switch (type) {
                                            case "StablePtr" ->
                                                new Object[] {
                                                    0L, firstStable, secondStable, row.offset, (long) selector};
                                            default -> new Object[] {0L, row.raw, row.offset, (long) selector};
                                        };
                                        try {
                                            return Calls.target(target, arguments);
                                        } finally {
                                            released(language, entryLabel + "/" + row + "/" + selector);
                                        }
                                    }
                                }
                                var calls = new CallsForType();
                                for (var row : inputs)
                                    for (int selector = 0; selector <= 5; selector++)
                                        assertEquals(row.expected.get(selector), calls.call(row, selector),
                                            stage + "/" + backend + "/" + row);
                                var targets = activeTargets(target);
                                assertEquals(
                                    1, targets.size(), entryLabel + " active public root; State# body stays in-frame");
                                assertEquals(expectedLabels.get(type),
                                    targets.stream().map(it -> it.getRootNode().getName()).collect(Collectors.toSet()),
                                    entryLabel + " original Core root labels");
                                var callCount = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                                                    .getMethod("getCallCount");
                                class Counts {
                                    List<Integer> interpretedCalls() throws Exception {
                                        var result = new ArrayList<Integer>();
                                        for (var t : targets) result.add((Integer) callCount.invoke(t));
                                        return result;
                                    }
                                }
                                var counts = new Counts();
                                long entriesBeforeSetup = calls.count();
                                var callsBeforeSetup = counts.interpretedCalls();
                                var handoff = language.getHandoffState().get();
                                long argumentAllocations = handoff.getArguments().getAllocations(),
                                     resultAllocations = handoff.getResults().getAllocations();
                                for (var t : targets) compile(t, entryLabel);
                                assertEquals(entriesBeforeSetup, calls.count(),
                                    entryLabel + " setup executes no compiled guest code");
                                assertEquals(callsBeforeSetup, counts.interpretedCalls(),
                                    entryLabel + " setup executes no interpreted guest code");
                                released(language, entryLabel + " installation");
                                // No invocation is allowed between installation and the counted first call.
                                for (var row : inputs.reversed())
                                    for (int selector = 5; selector >= 0; selector--) {
                                        String label = stage + "/" + backend + "/" + row + "/" + selector;
                                        long before = calls.count();
                                        assertEquals(row.expected.get(selector), calls.call(row, selector), label);
                                        assertEquals(
                                            1L, calls.count() - before, label + " exact compiled public entry");
                                        assertEquals(callsBeforeSetup, counts.interpretedCalls(),
                                            label + " no interpreted guest entries");
                                        assertEquals(argumentAllocations, handoff.getArguments().getAllocations(),
                                            label + " pooled arguments reused");
                                        assertEquals(resultAllocations, handoff.getResults().getAllocations(),
                                            label + " pooled results reused");
                                        assertSame(target, program.entryTarget(entry), label);
                                        var after = activeTargets(target);
                                        assertEquals(1, after.size(), label);
                                        boolean identical = true;
                                        for (int i = 0; i < Math.min(targets.size(), after.size()); i++)
                                            identical &= targets.get(i) == after.get(i);
                                        assertTrue(identical, label);
                                        for (var t : targets) valid(t, label);
                                    }
                                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                            }
                        } finally {
                            stable.free(firstStable);
                            stable.free(secondStable);
                        }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
    @Test
    public void wideCharArrayIndicesUseFourBytesAndKeepUnsignedPayloads() {
        for (boolean allocationOwned : new boolean[] {false, true})
            for (long index : new long[] {0, 1, 3, 7})
                for (long value : new long[] {0, 255, 256, 65535, 65536, 1114111, 0x80000000L, 0xffffffffL, -1,
                         Long.MIN_VALUE, Long.MAX_VALUE}) {
                    var owner = ManagedAllocation.mutable(32, 8);
                    owner.fill(0, 32, 0xa5);
                    byte[] raw = new byte[32];
                    Arrays.fill(raw, (byte) 0xa5);
                    Object array = allocationOwned ? owner : raw;
                    ManagedByteArray.writeInt32Guest(array, index, (int) value);
                    byte[] expected = new byte[32];
                    Arrays.fill(expected, (byte) 0xa5);
                    ByteBuffer.wrap(expected).order(ByteOrder.nativeOrder()).putInt((int) index * 4, (int) value);
                    assertArrayEquals(expected, allocationOwned ? owner.copyBytesOut(0, 32) : raw);
                    assertEquals(value & 0xffffffffL,
                        Integer.toUnsignedLong(ManagedByteArray.readInt32Guest(array, index, true)));
                    var address = ManagedAddress.fromGuestByteArray(array);
                    assertEquals(value & 0xffffffffL, ManagedAddressRead.WIDE_CHAR.read(address.plus(32), index - 8));
                    address.plus(32).writeNativeScalar(index - 8, 4, ~value);
                    assertEquals(~value & 0xffffffffL,
                        Integer.toUnsignedLong(ManagedByteArray.readInt32Guest(array, index, true)));
                    for (long invalid : new long[] {-1, 8, Long.MIN_VALUE, Long.MAX_VALUE}) {
                        var before = allocationOwned ? owner.copyBytesOut(0, 32) : raw.clone();
                        assertThrows(RuntimeFault.class,
                            () -> Integer.toUnsignedLong(ManagedByteArray.readInt32Guest(array, invalid, true)));
                        assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32Guest(array, invalid, 3));
                        assertArrayEquals(before, allocationOwned ? owner.copyBytesOut(0, 32) : raw);
                    }
                }
        assertThrows(
            RuntimeFault.class, () -> Integer.toUnsignedLong(ManagedByteArray.readInt32Guest(new byte[0], 0, true)));
        var immutable = ManagedAddress.fromHex("00010203");
        assertThrows(RuntimeFault.class, () -> immutable.writeNativeScalar(0, 4, 1));
    }
    @Test
    public void stablePointerCellsRetainOpaqueHandlesWithoutExtendingTheirRegistryLifetime() {
        var cells = ManagedAllocation.mutable(24, 8);
        var base = ManagedAddress.fromAllocation(cells);
        ManagedAddress retained;
        try (var first = context()) {
            first.initialize("thc");
            first.enter();
            try {
                var registry = Language.currentState().getStablePointers();
                var referent = new Object();
                retained = registry.make(referent);
                var replacement = registry.make(29L);
                try {
                    PinnedMemory.writeAddressArray(cells, 1, retained);
                    assertSame(retained, base.plus(24).readAddressElementIndex(-2));
                    assertSame(referent, registry.dereference(PinnedMemory.readAddressArray(cells, 1)));
                    var copy = ManagedAllocation.mutable(24, 8);
                    copy.copyFrom(cells, 0, 0, 24);
                    assertSame(retained, PinnedMemory.readAddressArray(copy, 1));
                    // Partial byte writes must not tear a retained pointer cell.
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32Guest(cells, 2, 7));
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(cells, 1));
                    assertThrows(RuntimeFault.class, cells::rawBytesIfPointerFree);
                    assertThrows(RuntimeFault.class, cells::exposeToNative);
                    assertSame(retained, PinnedMemory.readAddressArray(cells, 1));
                    base.plus(24).writeAddressElementIndex(-2, replacement);
                    assertEquals(29L, registry.dereference(PinnedMemory.readAddressArray(cells, 1)));
                    assertSame(referent, registry.dereference(PinnedMemory.readAddressArray(copy, 1)));
                    try (var foreign = context()) {
                        foreign.initialize("thc");
                        foreign.enter();
                        try {
                            assertThrows(RuntimeFault.class,
                                ()
                                    -> Language.currentState().getStablePointers().dereference(
                                        PinnedMemory.readAddressArray(copy, 1)));
                            assertThrows(RuntimeFault.class,
                                () -> Language.currentState().getStablePointers().equal(retained, retained));
                        } finally {
                            foreign.leave();
                        }
                    }
                    registry.free(retained);
                    assertSame(retained, PinnedMemory.readAddressArray(copy, 1));
                    assertThrows(
                        RuntimeFault.class, () -> registry.dereference(PinnedMemory.readAddressArray(copy, 1)));
                    // A complete scalar overwrite removes, rather than fabricates, a reference cell.
                    ManagedByteArray.writeIntGuest(cells, 1, 0);
                    assertThrows(RuntimeFault.class, () -> PinnedMemory.readAddressArray(cells, 1));
                    assertEquals(0L, ManagedByteArray.readIntGuest(cells, 1));
                } finally {
                    registry.free(replacement);
                }
            } finally {
                first.leave();
            }
        }
        try (var later = context()) {
            later.initialize("thc");
            later.enter();
            try {
                assertThrows(
                    RuntimeFault.class, () -> Language.currentState().getStablePointers().dereference(retained));
            } finally {
                later.leave();
            }
        }
    }
    @Test
    public void pointerCellBoundsRemainCheckedAndNativeCellsTransportStableTokens() {
        var cells = ManagedAllocation.mutable(24, 8);
        var address = ManagedAddress.fromAllocation(cells);
        var pointer = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
        for (long index : new long[] {0, 1, 2}) {
            PinnedMemory.writeAddressArray(cells, index, pointer);
            assertSame(pointer, address.plus(24).readAddressElementIndex(index - 3));
        }
        for (long invalid : new long[] {-1, 3, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThrows(RuntimeFault.class, () -> PinnedMemory.readAddressArray(cells, invalid));
            assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(cells, invalid, pointer));
            assertThrows(RuntimeFault.class, () -> address.readAddressElementIndex(invalid));
            assertSame(pointer, PinnedMemory.readAddressArray(cells, 1));
        }
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(new byte[24], 1, pointer));
        var exposed = ManagedAllocation.mutable(24, 8);
        exposed.rawBytesIfPointerFree();
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(exposed, 1, pointer));
        assertThrows(RuntimeFault.class, () -> ManagedAddress.nullAddress().readAddressElementIndex(0));
        assertThrows(RuntimeFault.class,
            () -> ManagedAddress.unownedNumeric(123).readAddressElementIndex(0));
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = context(true)) {
            context.initialize("thc");
            context.enter();
            try {
                var state = Language.currentState();
                var nativeAddress = state.getNativeAllocations().malloc(24);
                var handle = state.getStablePointers().make(37L);
                try {
                    nativeAddress.writeAddressElementIndex(1, handle);
                    var recovered = nativeAddress.readAddressElementIndex(1);
                    assertTrue(state.getStablePointers().equal(handle, recovered));
                    assertEquals(37L, state.getStablePointers().dereference(recovered));
                    assertThrows(RuntimeFault.class, () -> nativeAddress.writeAddressElementIndex(3, handle));
                    assertThrows(RuntimeFault.class, () -> nativeAddress.readAddressElementIndex(3));
                } finally {
                    state.getStablePointers().free(handle);
                    state.getNativeAllocations().free(nativeAddress);
                }
                assertThrows(RuntimeFault.class, () -> nativeAddress.readAddressElementIndex(1));
            } finally {
                context.leave();
            }
        }
    }
    private void validate(String name, List<CoreRepresentation> args, List<?> flags, CoreRepresentation out) {
        var floating = FloatingAddressOp.named(name);
        var pinned = PinnedMemoryOp.named(name);
        if (floating != null)
            floating.validate(args, flags, out);
        else if (pinned != null)
            pinned.validate(args, flags, out);
        else
            Objects.requireNonNull(ByteArrayOp.named(name)).validate(args, flags, out);
    }
    private CoreRepresentation wrong(CoreKind kind) {
        return kind == CoreKind.LONG
            ? new CoreRepresentation(CoreKind.DOUBLE, false, false, List.of("DoubleRep"), null, null, null, null, null)
            : new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"), null, null, null, null, null);
    }
    @Test
    public void loweringChecksCarriersArityStateAndTupleOrderWithoutIntegralIdentityChecks() throws Exception {
        for (String stage : List.of("pre", "post")) {
            var module =
                json(root.resolve("build/aligned-scalar-memory/" + stage + "/core/AlignedScalarMemoryAudit.json"));
            var calls = new ArrayList<List<Object>>();
            for (String type : types) {
                var evidence = new ArrayCoreEvidence(module, "aligned" + type);
                for (var app : evidence.nodes(evidence.getRoot().get("expr")))
                    if (!app.isEmpty() && "app".equals(app.get(0)) && app.size() > 1
                        && app.get(1) instanceof List<?> function && !function.isEmpty()
                        && "prim".equals(function.get(0)) && function.size() > 1 && names.contains(function.get(1)))
                        calls.add(app);
            }
            assertEquals(names, calls.stream().map(it -> ((List<?>) it.get(1)).get(1)).collect(Collectors.toSet()));
            assertEquals(12, calls.size(), stage + " exact original memory applications");
            for (var call : calls) {
                String name = (String) ((List<?>) call.get(1)).get(1);
                var arguments =
                    ((List<List<Object>>) call.get(2)).stream().map(CoreRepresentations::expression).toList();
                var result = CoreRepresentations.expression(call);
                var flags = (List<?>) call.get(3);
                assertEquals(Collections.nCopies(arguments.size(), false), flags);
                validate(name, arguments, flags, result);
                var compatible = arguments.stream()
                                     .map(it
                                         -> it.getKind() == CoreKind.LONG
                                             ? it.copy(it.getKind(), it.getEvaluated(), it.getPresent(),
                                                   List.of("Word64Rep"), it.getComponents(), it.getVector(),
                                                   it.getAlternatives(), it.getTagSlot(), it.getAlternativeSlots())
                                             : it)
                                     .toList();
                assertDoesNotThrow(() -> validate(name, compatible, flags, result));
                assertThrows(RuntimeFault.class,
                    () -> validate(name, arguments.subList(0, arguments.size() - 1), flags, result));
                assertThrows(RuntimeFault.class,
                    () -> validate(name, arguments, Collections.nCopies(flags.size(), true), result));
                for (int index = 0; index < arguments.size(); index++) {
                    var changed = new ArrayList<>(arguments);
                    changed.set(index, wrong(arguments.get(index).getKind()));
                    assertThrows(RuntimeFault.class,
                        ()
                            -> validate(name, changed, flags, result),
                        stage + "/" + name + " argument " + index + " actual carrier");
                }
                if (result.isTuple())
                    assertThrows(RuntimeFault.class,
                        ()
                            -> validate(name, arguments, flags,
                                result.copy(result.getKind(), result.getEvaluated(), result.getPresent(),
                                    result.getPrimReps(), Objects.requireNonNull(result.getComponents()).reversed(),
                                    result.getVector(), result.getAlternatives(), result.getTagSlot(),
                                    result.getAlternativeSlots())));
                else {
                    var changed = wrong(result.getKind());
                    assertThrows(RuntimeFault.class, () -> validate(name, arguments, flags, changed));
                }
            }
        }
    }
}
