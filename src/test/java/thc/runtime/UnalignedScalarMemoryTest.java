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
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class UnalignedScalarMemoryTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> types = List.of("Char", "WideChar", "Int", "Word", "Addr", "Float", "Double",
        "StablePtr", "Int16", "Int32", "Int64", "Word16", "Word32", "Word64");
    private final List<String> entryTypes = Stream.concat(types.stream(), Stream.of("Heap16", "Heap32")).toList();
    private final Set<String> names = types.stream()
                                          .flatMap(type
                                              -> List.of("Array", "OffAddr")
                                                  .stream()
                                                  .flatMap(domain
                                                      -> List.of("index", "read", "write")
                                                          .stream()
                                                          .map(verb -> verb + "Word8" + domain + "As" + type + "#")))
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
    private int width(String type) {
        return switch (type) {
            case "Char" -> 1;
            case "Int16", "Word16" -> 2;
            case "Int32", "Word32", "WideChar", "Float" -> 4;
            default -> 8;
        };
    }
    private long model(String type, long raw) {
        if (type.equals("Addr") || type.equals("StablePtr"))
            return 1;
        var bytes = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        long input = switch (type) {
            case "Char" -> raw & 255;
            case "WideChar" -> raw & 0x10ffff;
            default -> raw;
        };
        switch (width(type)) {
            case 1 -> bytes.put(0, (byte) input);
            case 2 -> bytes.putShort(0, (short) input);
            case 4 -> bytes.putInt(0, (int) input);
            default -> bytes.putLong(0, input);
        }
        return switch (type) {
            case "Char" -> bytes.get(0) & 255L;
            case "Int16" -> bytes.getShort(0);
            case "Word16" -> bytes.getShort(0) & 65535L;
            case "Int32" -> bytes.getInt(0);
            case "Word32", "WideChar", "Float" -> bytes.getInt(0) & 0xffffffffL;
            default -> bytes.getLong(0);
        };
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
    private Object call(RootCallTarget target, Object[] arguments) {
        var typed = ((GuestRoot) target.getRootNode()).getTypedInput();
        if (typed == null) return Calls.target(target, arguments);
        var input = typed.state().getArguments().acquire(typed.getPacket());
        input.setInputMode(1);
        try {
            typed.getPacket().copyIn(input, arguments);
            return Calls.target(target, new Object[]{input});
        } finally { typed.releaseChecked(input); }
    }
    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return list.getFirst();
    }
    @Test
    public void scalarStorageMatchesNativeAndIndependentModelOnFirstInstalledEntry() throws Exception {
        var directory = root.resolve("build/unaligned-scalar-memory");
        var manifest = json(directory.resolve("manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, new HashSet<>((List<String>) manifest.get("primitives")));
        for (String key : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(key)).entrySet()) {
                var digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), digest, "Stale unaligned scalar memory " + hash.getKey());
            }
        var rows = new ArrayList<Row>();
        for (String line : Files.readAllLines(directory.resolve("oracle.tsv"))) {
            var columns = List.of(line.split("\t", -1));
            assertEquals(9, columns.size());
            rows.add(new Row(columns.get(0), Long.parseLong(columns.get(1)), Long.parseLong(columns.get(2)),
                columns.subList(3, columns.size()).stream().map(Long::valueOf).toList()));
        }
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        assertEquals(new HashSet<>(entryTypes), rows.stream().map(Row::type).collect(Collectors.toSet()));
        for (var row : rows) {
            if (row.type.startsWith("Heap")) {
                String bits = row.type.substring(4);
                long signed = model("Int" + bits, row.raw), unsigned = model("Word" + bits, ~row.raw);
                assertEquals(List.of(signed, signed, unsigned, unsigned, 165L, 165L), row.expected,
                    "independent heap byte model: " + row);
                continue;
            }
            long replacement = switch (row.type) {
                case "Float" -> row.raw ^ 0x80000000L;
                case "Double" -> row.raw ^ Long.MIN_VALUE;
                default -> ~row.raw;
            };
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
                var type = name.substring(name.indexOf("As") + 2, name.length() - 1);
                assertTrue(uses.stream().anyMatch(it ->
                    ("main:UnalignedScalarMemoryAudit.unaligned" + type).equals(it.get("owner"))), stage + "/" + name);
            }
            var module = thc.CoreCbdFixtures.read(directory.resolve(stage + "/core/UnalignedScalarMemoryAudit.cbd"));
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var source = new LinkedHashMap<>(module);
                        source.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source)
                                                                          : new BytecodeProgram(language, source);
                        var first = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
                        var second = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
                        var stable = Language.currentState().getStablePointers();
                        var firstStable = stable.make(17L);
                        var secondStable = stable.make(29L);
                        try {
                            for (String type : entryTypes) {
                                String entry = "unaligned" + type;
                                var target = program.entryTarget("main:UnalignedScalarMemoryAudit." + entry);
                                var inputs = rows.stream().filter(row -> row.type.equals(type)).toList();
                                String entryLabel = stage + "/" + backend + "/" + entry;
                                class CallsForType {
                                    long count() {
                                        return ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    }
                                    Object call(Row row, int selector) {
                                        Object[] arguments = switch (type) {
                                            case "Addr" ->
                                                new Object[] {0L, first, second, row.offset, (long) selector};
                                            case "StablePtr" ->
                                                new Object[] {
                                                    0L, firstStable, secondStable, row.offset, (long) selector};
                                            default -> new Object[] {0L, row.raw, row.offset, (long) selector};
                                        };
                                        try {
                                            return UnalignedScalarMemoryTest.this.call(target, arguments);
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
                                long entriesBeforeSetup = calls.count();
                                for (var t : targets) compile(t, entryLabel);
                                assertEquals(entriesBeforeSetup, calls.count(),
                                    entryLabel + " setup executes no compiled guest code");
                                released(language, entryLabel + " installation");
                                // No invocation is allowed between installation and the counted first call.
                                for (var row : inputs.reversed())
                                    for (int selector = 5; selector >= 0; selector--) {
                                        String label = stage + "/" + backend + "/" + row + "/" + selector;
                                        long before = calls.count();
                                        assertEquals(row.expected.get(selector), calls.call(row, selector), label);
                                        assertTrue(calls.count() > before, label + " first installed guest execution");
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
    public void numericBoundariesSentinelsAliasesAndOwnerRestrictions() {
        for (int width : new int[] {1, 2, 4, 8}) {
            var operation = switch (width) {
                case 1 -> ManagedAddressRead.CHAR;
                case 2 -> ManagedAddressRead.WORD16;
                case 4 -> ManagedAddressRead.WORD32;
                default -> ManagedAddressRead.WORD64;
            };
            for (long offset : new long[] {0, 1, 3, 16L - width})
                for (long bits : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789abcdefL}) {
                    byte[] bytes = new byte[16];
                    Arrays.fill(bytes, (byte) 0xa5);
                    var owner = ManagedAllocation.mutable(16, 8);
                    owner.fill(0, 16, 0xa5);
                    var address = ManagedAddress.fromAllocation(owner);
                    if (width == 1)
                        address.plus(16).writeWord8(offset - 16, bits);
                    else
                        address.plus(16).writeNativeScalar(offset - 16, width, bits, true);
                    var expected = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
                    switch (width) {
                        case 1 -> expected.put((int) offset, (byte) bits);
                        case 2 -> expected.putShort((int) offset, (short) bits);
                        case 4 -> expected.putInt((int) offset, (int) bits);
                        default -> expected.putLong((int) offset, bits);
                    }
                    assertArrayEquals(bytes, owner.copyBytesOut(0, 16));
                    long mask = switch (width) {
                        case 1 -> 255L;
                        case 2 -> 65535L;
                        case 4 -> 0xffffffffL;
                        default -> -1L;
                    };
                    long addressValue = operation.isInt()
                        ? Integer.toUnsignedLong(operation.readInt(address.plus(16), offset - 16, true))
                        : operation.read(address.plus(16), offset - 16, true);
                    assertEquals(bits & mask, addressValue);
                    long arrayValue = switch (width) {
                        case 1 -> ManagedByteArray.readGuest(owner, offset, true);
                        case 2 -> ManagedByteArray.readInt16ByteOffsetGuest(owner, offset, true);
                        case 4 ->
                            Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(owner, offset, true));
                        default -> ManagedByteArray.readIntGuest(owner, offset, true);
                    };
                    assertEquals(bits & mask, arrayValue);
                }
            var owner = ManagedAllocation.mutable(16, 8);
            var address = ManagedAddress.fromAllocation(owner);
            for (long offset : new long[] {-1, 17L - width, Long.MIN_VALUE, Long.MAX_VALUE}) {
                var before = owner.copyBytesOut(0, 16);
                assertThrows(RuntimeFault.class, () -> {
                    if (operation.isInt())
                        operation.readInt(address, offset, true);
                    else
                        operation.read(address, offset, true);
                });
                assertThrows(RuntimeFault.class, () -> {
                    if (width == 1)
                        address.writeWord8(offset, 1);
                    else
                        address.writeNativeScalar(offset, width, 1, true);
                });
                assertArrayEquals(before, owner.copyBytesOut(0, 16));
            }
        }
        byte[] raw = new byte[8];
        ManagedByteArray.writeIntGuest(raw, 0, Long.MIN_VALUE, true);
        assertEquals(Long.MIN_VALUE, ManagedByteArray.readIntGuest(raw, 0, true));
        byte[] fifteen = new byte[15];
        Arrays.fill(fifteen, (byte) 37);
        for (byte[] bytes : List.of(new byte[0], raw, fifteen))
            for (long offset : new long[] {Long.MIN_VALUE, -1, bytes.length - 7L, Long.MAX_VALUE}) {
                var before = bytes.clone();
                assertEquals("ByteArray# scalar byte offset outside its backing storage",
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(bytes, offset, true))
                        .getMessage());
                assertEquals("ByteArray# scalar byte offset outside its backing storage",
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeIntGuest(bytes, offset, 1, true))
                        .getMessage());
                assertArrayEquals(before, bytes);
            }
        var literal = ManagedAddress.fromHex("0001020304050607");
        assertThrows(RuntimeFault.class, () -> literal.writeNativeScalar(1, 4, 1, true));
        assertThrows(RuntimeFault.class, () -> ManagedAddress.nullAddress().readWord8(0));
        assertThrows(RuntimeFault.class,
            () -> ManagedAddress.unownedNumeric(123).writeNativeScalar(0, 4, 1, true));
    }
    @Test
    public void pointerCellsRetainReferencesAndRejectPartialOrRawOverwrites() {
        var owner = ManagedAllocation.mutable(24, 8);
        var base = ManagedAddress.fromAllocation(owner);
        var first = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
        first.writeWord8(3, 211);
        PinnedMemory.writeAddressArray(owner, 1, first.plus(3), true);
        assertEquals(211L, base.plus(24).readAddressElementIndex(-23, true).readWord8(0));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(owner, 1, true));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeIntGuest(owner, 2, 0, true));
        assertThrows(RuntimeFault.class, () -> base.writeAddressElementIndex(2, first, true));
        assertThrows(RuntimeFault.class, owner::exposeToNative);
        assertThrows(RuntimeFault.class, owner::rawBytesIfPointerFree);
        assertEquals(211L, PinnedMemory.readAddressArray(owner, 1, true).readWord8(0));
        var copied = ManagedAllocation.mutable(24, 8);
        copied.copyFrom(owner, 0, 0, 24);
        assertEquals(211L, copied.readAddressByteOffset(1).readWord8(0));
        ManagedByteArray.writeIntGuest(owner, 1, 0, true);
        assertThrows(RuntimeFault.class, () -> PinnedMemory.readAddressArray(owner, 1, true));
        assertEquals(0L, ManagedByteArray.readIntGuest(owner, 1, true));
        var exposed = ManagedAllocation.mutable(16, 8);
        exposed.rawBytesIfPointerFree();
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(exposed, 1, first, true));
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(new byte[16], 1, first, true));
        for (long offset : new long[] {-1, 17, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(owner, offset, first, true));
    }
    @Test
    public void stableOwnersRejectForeignContextsAndReleasedLifetimes() {
        var pointerCell = ManagedAllocation.mutable(16, 8);
        try (var first = context(true)) {
            first.initialize("thc");
            first.enter();
            try {
                var state = Language.currentState();
                var stable = state.getStablePointers().make(37L);
                pointerCell.writeAddressByteOffset(1, stable);
                assertEquals(37L, state.getStablePointers().dereference(pointerCell.readAddressByteOffset(1)));
                try (var second = context(true)) {
                    second.initialize("thc");
                    second.enter();
                    try {
                        assertThrows(RuntimeFault.class,
                            ()
                                -> Language.currentState().getStablePointers().dereference(
                                    pointerCell.readAddressByteOffset(1)));
                    } finally {
                        second.leave();
                    }
                }
                state.getStablePointers().free(stable);
                assertThrows(RuntimeFault.class,
                    () -> state.getStablePointers().dereference(pointerCell.readAddressByteOffset(1)));
            } finally {
                first.leave();
            }
        }
    }
    @Test
    public void ownedNativeNumericAliasesCheckLifetimesAndContext() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var first = context(true)) {
            first.initialize("thc");
            first.enter();
            try {
                var state = Language.currentState();
                var nativeAddress = state.getNativeAllocations().malloc(16);
                try {
                    for (long offset : new long[] {0, 1, 7, 8}) {
                        nativeAddress.plus(16).writeNativeScalar(offset - 16, 8, Long.MIN_VALUE, true);
                        assertEquals(Long.MIN_VALUE, ManagedAddressRead.INT64.read(nativeAddress, offset, true));
                        FloatingAddresses.writeFloat(nativeAddress, offset, Float.intBitsToFloat(0x7fc12345), true);
                        assertEquals(0x7fc12345,
                            Float.floatToRawIntBits(FloatingAddresses.readFloat(nativeAddress, offset, true)));
                    }
                    assertThrows(RuntimeFault.class, () -> nativeAddress.writeNativeScalar(9, 8, 7, true));
                    // Owned native pointer cells transport actual address bits,
                    // including unaligned cells; managed references cannot escape.
                    nativeAddress.writeAddressElementIndex(1, nativeAddress.plus(8), true);
                    assertTrue(nativeAddress.plus(8).sameLocation(nativeAddress.readAddressElementIndex(1, true)));
                    var stablePointers = state.getStablePointers();
                    var handle = stablePointers.make(37L);
                    ManagedAddress recovered;
                    try {
                        nativeAddress.writeAddressElementIndex(1, handle);
                        recovered = nativeAddress.readAddressElementIndex(1);
                        assertTrue(stablePointers.equal(handle, recovered));
                        assertEquals(37L, stablePointers.dereference(recovered));
                        assertThrows(RuntimeFault.class, () -> nativeAddress.writeAddressElementIndex(2, handle));
                        assertThrows(RuntimeFault.class, () -> nativeAddress.readAddressElementIndex(2));
                    } finally {
                        stablePointers.free(handle);
                    }
                    assertThrows(RuntimeFault.class, () -> stablePointers.dereference(recovered));
                    nativeAddress.writeAddressElementIndex(1, nativeAddress.plus(8), true);
                    var managed = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8));
                    assertThrows(RuntimeFault.class, () -> nativeAddress.writeAddressElementIndex(1, managed, true));
                    assertTrue(nativeAddress.plus(8).sameLocation(nativeAddress.readAddressElementIndex(1, true)));
                    assertThrows(
                        RuntimeFault.class, () -> nativeAddress.writeAddressElementIndex(9, nativeAddress, true));
                    try (var second = context(true)) {
                        second.initialize("thc");
                        second.enter();
                        try {
                            assertThrows(
                                RuntimeFault.class, () -> ManagedAddressRead.INT64.read(nativeAddress, 1, true));
                        } finally {
                            second.leave();
                        }
                    }
                } finally {
                    state.getNativeAllocations().free(nativeAddress);
                }
                assertThrows(RuntimeFault.class, () -> ManagedAddressRead.INT64.read(nativeAddress, 1, true));
            } finally {
                first.leave();
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
                thc.CoreCbdFixtures.read(root.resolve("build/unaligned-scalar-memory/" + stage + "/core/UnalignedScalarMemoryAudit.cbd"));
            var calls = new ArrayList<List<Object>>();
            for (String type : types) {
                var evidence = new ArrayCoreEvidence(module, "main:UnalignedScalarMemoryAudit." + "unaligned" + type);
                for (var app : evidence.nodes(evidence.getRoot().get("expr")))
                    if (!app.isEmpty() && "app".equals(app.get(0)) && app.size() > 1
                        && app.get(1) instanceof List<?> function && !function.isEmpty()
                        && "prim".equals(function.get(0)) && function.size() > 1 && names.contains(function.get(1)))
                        calls.add(app);
            }
            assertEquals(names, calls.stream().map(it -> ((List<?>) it.get(1)).get(1)).collect(Collectors.toSet()));
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
                                                   List.of(it.isInt() ? "Word32Rep" : "Word64Rep"), it.getComponents(), it.getVector(),
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
        for (String type : List.of("Int8", "Word8", "Int8X16", "Word8X16", "Bogus"))
            for (String verb : List.of("index", "read", "write")) {
                String array = verb + "Word8ArrayAs" + type + "#", addr = verb + "Word8OffAddrAs" + type + "#";
                assertNull(ByteArrayOp.named(array));
                assertNull(PinnedMemoryOp.named(addr));
                assertNull(FloatingAddressOp.named(addr));
            }
    }
}
