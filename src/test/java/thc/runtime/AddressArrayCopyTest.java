// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static thc.runtime.Unit.INSTANCE;

@SuppressWarnings("unchecked")
public class AddressArrayCopyTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String directory = "build/address-array-copy";
    private final List<String> names = List.of("addrToArray", "arrayToAddr", "mutableArrayToAddr");
    private final List<String> primitives =
        Arrays.stream(AddressArrayCopyOp.values()).map(AddressArrayCopyOp::getPrimitive).toList();
    private final List<Long> seeds = List.of(0L, 1L, 127L, 255L, -1L, Long.MAX_VALUE, Long.MIN_VALUE);
    private final List<List<Long>> ranges = ranges();
    private List<List<Long>> ranges() {
        var result = new ArrayList<List<Long>>();
        for (int from = 0; from <= 4; from++)
            for (int to = 0; to <= 4; to++)
                for (int count = 0; count <= Math.min(4 - from, 4 - to); count++)
                    result.add(List.of((long) from, (long) to, (long) count));
        result.addAll(List.of(List.of(0L, 0L, 8L), List.of(0L, 1L, 7L), List.of(1L, 0L, 7L), List.of(3L, 5L, 3L),
            List.of(5L, 3L, 3L), List.of(8L, 8L, 0L), List.of(8L, 0L, 0L), List.of(0L, 8L, 0L)));
        return result;
    }
    private record Row(String name, List<Long> arguments, List<Long> bytes) {}
    private record Request(String name, List<Long> arguments) {}
    private List<Row> rows(String text) {
        var rows = new ArrayList<Row>();
        for (String line : text.split("\\R", -1)) {
            if (line.isEmpty())
                continue;
            var fields = List.of(line.split("\t", -1));
            if (fields.size() != 21)
                throw new IllegalArgumentException("Failed requirement.");
            rows.add(new Row(fields.get(0), fields.subList(1, 5).stream().map(Long::valueOf).toList(),
                fields.subList(5, fields.size()).stream().map(Long::valueOf).toList()));
        }
        var requested = new ArrayList<Request>();
        for (String name : names)
            for (long seed : seeds)
                for (var range : ranges) {
                    var args = new ArrayList<>(List.of(seed));
                    args.addAll(range);
                    requested.add(new Request(name, args));
                }
        if (!rows.stream().map(it -> new Request(it.name, it.arguments)).toList().equals(requested))
            throw new IllegalArgumentException("Missing, repeated or reordered native copy rows");
        for (var row : rows) {
            long seed = row.arguments.get(0), from = row.arguments.get(1), to = row.arguments.get(2),
                 count = row.arguments.get(3);
            var source = LongStream.range(0, 8).map(it -> (seed + 17L * it) & 255).boxed().toList();
            var destination =
                new ArrayList<>(LongStream.range(0, 8).map(it -> (seed * 3 + 91 + 29L * it) & 255).boxed().toList());
            for (int i = 0; i < (int) count; i++) destination.set((int) to + i, source.get((int) from + i));
            var expected = new ArrayList<>(source);
            expected.addAll(destination);
            if (!expected.equals(row.bytes))
                throw new IllegalArgumentException("Native copy/model mismatch: " + row);
        }
        return rows;
    }
    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return list.getFirst();
    }
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private void evidence() throws Exception {
        var manifest = json(directory + "/manifest.json");
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(names, manifest.get("entries"));
        assertEquals(16L, manifest.get("bytesPerRow"));
        assertEquals((long) names.size() * seeds.size() * ranges.size(), manifest.get("nativeRows"));
        var sources = new HashSet<>(Set.of("t/fixtures/compiler/AddressArrayCopyAudit.hs",
            "t/fixtures/compiler/AddressArrayCopyNative.hs", "thc.cabal", "t/haskell-fixtures/Main.hs",
            "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/AddressArrayCopyFixtures.hs",
            "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
            "bin/audit-core.py", "bin/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json"));
        try (var files = Files.list(root.resolve("src/compiler/THC"))) {
            files.filter(it -> it.toString().endsWith(".hs"))
                .map(root::relativize)
                .map(Path::toString)
                .forEach(sources::add);
        }
        try (var files = Files.list(root.resolve("bin"))) {
            files.filter(it -> it.getFileName().toString().startsWith("core_") && it.toString().endsWith(".py"))
                .map(root::relativize)
                .map(Path::toString)
                .forEach(sources::add);
        }
        var commands = new ArrayList<>(List.of("native-build", "native-oracle"));
        for (String stage : List.of("pre", "post")) {
            commands.add(stage + "-export");
            for (String name : names) commands.add(stage + "-" + name + "-audit");
        }
        var artifacts =
            new HashSet<>(Set.of(directory + "/inputs.tsv", directory + "/oracle.tsv", directory + "/native/oracle"));
        for (String stage : List.of("pre", "post")) {
            artifacts.add(directory + "/" + stage + "-core/AddressArrayCopyAudit.cbd");
            for (String name : names) artifacts.add(directory + "/" + stage + "-" + name + "-audit.json");
        }
        for (String command : commands)
            for (String suffix : List.of("stdout", "stderr", "command.json"))
                artifacts.add(directory + "/commands/" + command + "." + suffix);
        for (var item : List.of(Map.entry("inputHashes", sources), Map.entry("artifactHashes", artifacts))) {
            var hashes = (Map<String, String>) manifest.get(item.getKey());
            assertEquals(item.getValue(), hashes.keySet(), item.getKey());
            for (var hash : hashes.entrySet()) {
                String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), actual, "Stale address/array copy evidence: " + hash.getKey());
            }
        }
        for (String stage : List.of("pre", "post"))
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                var binding = single(((List<Map<String, Object>>) module(stage).get("bindings"))
                        .stream()
                        .filter(it -> ("main:AddressArrayCopyAudit." + name).equals(it.get("id")))
                        .toList());
                assertEquals(5L, binding.get("arity"));
                var expression = (List<?>) binding.get("expr");
                assertEquals("lam", expression.get(0));
                assertEquals(5, ((List<?>) expression.get(1)).size());
                var calls = applications(expression);
                var stateCall =
                    single(calls.stream().filter(it -> !"prim".equals(((List<?>) it.get(1)).get(0))).toList());
                var lambda = (List<?>) stateCall.get(1);
                assertEquals("lam", lambda.get(0));
                var state = single((List<Map<String, Object>>) lambda.get(1));
                assertEquals(false, state.get("lifted"));assertEquals("void",((Map<?,?>)state.get("rep")).get("kind"));
                assertEquals(1, ((List<?>) stateCall.get(2)).size());
                var stateArgument = single((List<List<Object>>) stateCall.get(2));
                assertEquals("void", stateArgument.get(0));assertEquals(state.get("rep"),((Map<?,?>)stateArgument.getLast()).get("rep"));
                assertEquals(List.of(false), stateCall.get(3));
                var report = json(directory + "/" + stage + "-" + name + "-audit.json");
                assertEquals(true, report.get("accepted"));
                assertEquals(List.of(), report.get("issues"));
                assertEquals(List.of(), report.get("missingGlobals"));
                String primitiveName = primitives.get(index);
                var primitive = single(((List<Map<String, Object>>) report.get("primitives"))
                        .stream()
                        .filter(it -> primitiveName.equals(it.get("name")))
                        .toList());
                assertEquals(1, ((List<?>) primitive.get("uses")).size(), stage + "/" + name + " saturated copy");
            }
    }
    private Context context() {
        return context(false, false);
    }
    private Context context(boolean inlining) {
        return context(inlining, false);
    }
    private Context context(boolean inlining, boolean nativeAccess) {
        return Context.newBuilder("thc")
            .allowNativeAccess(nativeAccess)
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Map<String, Object> module(String stage) throws Exception {
        return thc.CoreCbdFixtures.read(root.resolve(directory + "/" + stage + "-core/AddressArrayCopyAudit.cbd"));
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private long count(ExecutableProgram program) {
        return ((Number) program.diagnostics().get("compiledEntries")).longValue();
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
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
        var runtime = Truffle.getRuntime();
        runtime.getClass()
            .getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
        valid(target);
    }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var result = new ArrayList<RootCallTarget>();
        visit(entry, seen, result);
        return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target))
            return;
        var body = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(body);
        if (body instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var cached = argument.asCachedNode();
                        if (cached != null)
                            nodes.add(cached);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, result);
        result.add(target);
    }
    private void released(Language language) {
        var pools = language.getHandoffState().get();
        assertEquals(0, pools.getArguments().getDepth());
        assertEquals(0, pools.getResults().getDepth());
        assertEquals(0, pools.getArguments().retainedReferences());
        assertEquals(0, pools.getResults().retainedReferences());
    }
    @Test
    public void nativeCopiesWithResidualCalls() throws Exception {
        nativeChecks(false);
    }
    @Test
    public void nativeCopiesWithInlining() throws Exception {
        nativeChecks(true);
    }
    private void nativeChecks(boolean inlining) throws Exception {
        evidence();
        var rows = rows(Files.readString(root.resolve(directory + "/oracle.tsv")))
                       .stream()
                       .collect(Collectors.groupingBy(Row::name));
        for (String stage : List.of("pre", "post"))
            for (String backend : List.of("ast", "bytecode"))
                for (String name : names) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var source = new LinkedHashMap<>(CoreModules.reachable(module(stage), "main:AddressArrayCopyAudit." + name));
                            source.put("instrument", true);
                            var p = program(language, source, backend);
                            var function = context.asValue(new EntryValue(p, "main:AddressArrayCopyAudit." + name, 5));
                            var entry = p.entryTarget("main:AddressArrayCopyAudit." + name);
                            var host = p.hostEntryTarget(5);
                            class Check {
                                List<RootCallTarget> active() {
                                    return targets((RootCallTarget) single(
                                        NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)
                                            .stream()
                                            .filter(it -> it.getCallTarget() == entry)
                                            .toList())
                                            .getCurrentCallTarget());
                                }
                                void row(Row row, boolean installed) {
                                    for (int field = 0; field < row.bytes.size(); field++) {
                                        long before = count(p);
                                        String label = stage + "/" + backend + "/" + name + "/" + row.arguments + "/"
                                            + field + "/inlining=" + inlining;
                                        var args = new ArrayList<>(row.arguments);
                                        args.add((long) field);
                                        assertEquals(row.bytes.get(field).longValue(),
                                            function.execute(args.toArray()).asLong(), label);
                                        // The independently checked runRW State# beta-redex stays
                                        // in the entry frame; it does not create a second guest root.
                                        if (installed)
                                            assertEquals(
                                                before + 1L, count(p), label + " exact entry after state lowering");
                                        released(language);
                                    }
                                }
                            }
                            var check = new Check();
                            for (var row : rows.get(name)) check.row(row, false);
                            var targets = check.active();
                            assertEquals(
                                1, targets.size(), stage + "/" + backend + "/" + name + " entry after state lowering");
                            for (var target : targets) compile(target);
                            assertTrue(function.invokeMember("compile").asBoolean());
                            assertEquals(targets, check.active());
                            for (var target : targets) valid(target);
                            valid(host);
                            for (var row : rows.get(name).reversed()) {
                                check.row(row, true);
                                assertEquals(targets, check.active());
                                for (var target : targets) valid(target);
                                valid(host);
                            }
                            assertEquals(0L, language.getHandoffState().get().getResults().getAllocations(),
                                "Copies return bare State");
                            for (String counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue());
                        } finally {
                            context.leave();
                        }
                    }
    }
    @Test
    public void missingReorderedAndCorruptNativeObservationsReject() throws Exception {
        evidence();
        var lines = Files.readAllLines(root.resolve(directory + "/oracle.tsv"));
        var repeated = new ArrayList<>(lines);
        repeated.add(lines.getFirst());
        var corrupt = new ArrayList<>(lines);
        corrupt.set(0, lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t999");
        for (var bad : List.of(lines.subList(1, lines.size()), lines.reversed(), repeated, corrupt))
            assertThrows(IllegalArgumentException.class, () -> rows(String.join("\n", bad)));
    }
    private byte[] array(int size, IntUnaryOperator initializer) {
        var bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) initializer.applyAsInt(i);
        return bytes;
    }
    private Object storage(byte[] bytes, boolean owned) {
        if (!owned)
            return bytes.clone();
        var result = ManagedAllocation.mutable(bytes.length, 8);
        result.copyBytesIn(bytes, 0, 0, bytes.length);
        return result;
    }
    private byte[] bytes(Object value) {
        return value instanceof ManagedAllocation allocation ? allocation.copyBytesOut(0, allocation.getSize())
                                                             : ((byte[]) value).clone();
    }
    @Test
    public void everyContainedRangePreservesSourcesSentinelsAndIdentity() {
        for (boolean sourceOwned : new boolean[] {false, true})
            for (boolean destinationOwned : new boolean[] {false, true})
                for (int from = 0; from <= 8; from++)
                    for (int to = 0; to <= 8; to++)
                        for (int count = 0; count <= Math.min(8 - from, 8 - to); count++)
                            for (boolean toArray : new boolean[] {false, true}) {
                                var original = array(8, it -> 17 * it + 127);
                                var initial = array(8, it -> 29 * it + 91);
                                var source = storage(original, sourceOwned);
                                var destination = storage(initial, destinationOwned);
                                var expected = initial.clone();
                                System.arraycopy(original, from, expected, to, count);
                                if (toArray)
                                    ManagedAddress.fromGuestByteArray(source).plus(from).copyToByteArray(
                                        destination, to, count);
                                else
                                    ManagedAddress.fromGuestByteArray(destination)
                                        .plus(to)
                                        .copyFromByteArray(source, from, count);
                                assertArrayEquals(original, bytes(source));
                                assertArrayEquals(expected, bytes(destination));
                                assertEquals(8, bytes(source).length);
                                assertEquals(8, bytes(destination).length);
                            }
    }
    @Test
    public void fullWidthBoundsMutabilityAndBackingAliasesRejectBeforeMutation() {
        for (boolean owned : new boolean[] {false, true}) {
            var source = storage(array(8, it -> it), owned);
            var target = storage(array(8, it -> 71), owned);
            var sourceAddress = ManagedAddress.fromGuestByteArray(source);
            var targetAddress = ManagedAddress.fromGuestByteArray(target);
            for (long bad : new long[] {-1, 9, (long) Integer.MAX_VALUE + 1, Long.MIN_VALUE, Long.MAX_VALUE}) {
                assertThrows(RuntimeFault.class, () -> sourceAddress.copyToByteArray(target, bad, 0));
                assertThrows(RuntimeFault.class, () -> sourceAddress.copyToByteArray(target, 0, bad));
                assertThrows(RuntimeFault.class, () -> targetAddress.copyFromByteArray(source, bad, 0));
                assertThrows(RuntimeFault.class, () -> targetAddress.copyFromByteArray(source, 0, bad));
                assertArrayEquals(array(8, it -> 71), bytes(target));
            }
            for (long[] args : new long[][] {{0, 4, 2}, {4, 0, 2}, {8, 8, 0}}) {
                long addressOffset = args[0], arrayOffset = args[1], count = args[2];
                assertThrows(RuntimeFault.class,
                    () -> sourceAddress.plus(addressOffset).copyToByteArray(source, arrayOffset, count));
                assertThrows(RuntimeFault.class,
                    () -> sourceAddress.plus(addressOffset).copyFromByteArray(source, arrayOffset, count));
            }
            assertArrayEquals(array(8, it -> it), bytes(source));
            assertThrows(RuntimeFault.class, () -> sourceAddress.plus(8).copyToByteArray(target, 0, 1));
            assertThrows(RuntimeFault.class, () -> targetAddress.plus(8).copyFromByteArray(source, 0, 1));
            assertThrows(RuntimeFault.class, () -> sourceAddress.copyToByteArray(new Object(), 0, 0));
            assertThrows(RuntimeFault.class, () -> targetAddress.copyFromByteArray(new Object(), 0, 0));
        }
        var owner = ManagedAllocation.mutable(8, 8);
        var raw = owner.rawBytesIfPointerFree();
        for (var pair : List.of(Map.entry(ManagedAddress.fromAllocation(owner), (Object) raw),
                 Map.entry(ManagedAddress.fromByteArray(raw), (Object) owner))) {
            var address = pair.getKey();
            var array = pair.getValue();
            assertThrows(RuntimeFault.class, () -> address.copyToByteArray(array, 8, 0));
            assertThrows(RuntimeFault.class, () -> address.copyFromByteArray(array, 4, 1));
        }
        var immutable = ManagedAllocation.immutable(new byte[] {1, 2, 3}, 8);
        var literal = ManagedAddress.fromHex("0041ff");
        byte[] destination = new byte[4];
        literal.copyToByteArray(destination, 0, 4);
        assertArrayEquals(new byte[] {0, 65, -1, 0}, destination);
        ManagedAddress.fromByteArray(destination).copyFromByteArray(immutable, 0, 3);
        for (long count : new long[] {0, 1}) {
            assertThrows(RuntimeFault.class, () -> literal.copyFromByteArray(destination, 0, count));
            assertThrows(RuntimeFault.class, () -> literal.copyToByteArray(immutable, 0, count));
            assertThrows(RuntimeFault.class,
                () -> ManagedAddress.fromAllocation(immutable).copyFromByteArray(destination, 0, count));
            for (var address : List.of(ManagedAddress.nullAddress(),
                     ManagedAddress.unownedNumeric(12345))) {
                if (count != 0 || address != ManagedAddress.nullAddress())
                    assertThrows(RuntimeFault.class, () -> address.copyToByteArray(destination, 0, count));
                assertThrows(RuntimeFault.class, () -> address.copyFromByteArray(destination, 0, count));
            }
        }
        owner.shrink(4);
        assertThrows(RuntimeFault.class,
            () -> ManagedAddress.fromAllocation(owner).copyToByteArray(destination, 0, 5));
        assertThrows(RuntimeFault.class,
            () -> ManagedAddress.fromByteArray(destination).copyFromByteArray(owner, 4, 1));
    }
    @Test
    public void emptyNullAddressSourceNeedsNoBackingButStillChecksItsDestination() {
        var source = ManagedAddress.nullAddress();
        for (boolean owned : new boolean[] {false, true})
            for (int size : new int[] {0, 8}) {
                var expected = array(size, it -> it + 71);
                var destination = storage(expected, owned);
                for (int offset = 0; offset <= size; offset++) source.copyToByteArray(destination, offset, 0);
                assertArrayEquals(expected, bytes(destination));
                for (long offset : new long[] {-1, size + 1L, Long.MIN_VALUE, Long.MAX_VALUE})
                    assertThrows(RuntimeFault.class, () -> source.copyToByteArray(destination, offset, 0));
                for (long count : new long[] {-1, 1, Long.MIN_VALUE, Long.MAX_VALUE})
                    assertThrows(RuntimeFault.class, () -> source.copyToByteArray(destination, 0, count));
                assertArrayEquals(expected, bytes(destination));
            }
        assertThrows(RuntimeFault.class, () -> source.copyToByteArray(new Object(), 0, 0));
        assertThrows(
            RuntimeFault.class, () -> source.copyToByteArray(ManagedAllocation.immutable(new byte[0], 8), 0, 0));
        assertThrows(RuntimeFault.class,
            ()
                -> ManagedAddress.unownedNumeric(12345).copyToByteArray(
                    new byte[0], 0, 0));
    }
    @Test
    public void pointerCellsStayReferencesAndPartialRawCopiesFailBeforeEffects() {
        var source = ManagedAllocation.mutable(32, 8);
        var target = ManagedAllocation.mutable(32, 8);
        var pointer = ManagedAddress.fromHex("abcd");
        source.writeAddressByteOffset(8, pointer);
        var from = ManagedAddress.fromAllocation(source).plus(8);
        var to = ManagedAddress.fromAllocation(target).plus(16);
        from.copyToByteArray(target, 16, 8);
        assertSame(pointer, target.readAddressByteOffset(16));
        target.fill(16, 8, 77);
        to.copyFromByteArray(source, 8, 8);
        assertSame(pointer, target.readAddressByteOffset(16));
        from.plus(1).copyToByteArray(target, 17, 0);
        to.plus(1).copyFromByteArray(source, 9, 0);
        assertSame(pointer, target.readAddressByteOffset(16));
        assertSame(pointer, source.readAddressByteOffset(8));
        for (long count : new long[] {1, 7}) {
            assertThrows(RuntimeFault.class, () -> from.copyToByteArray(target, 0, count));
            assertThrows(RuntimeFault.class, () -> to.copyFromByteArray(new byte[8], 0, count));
            assertSame(pointer, target.readAddressByteOffset(16));
            assertSame(pointer, source.readAddressByteOffset(8));
        }
        var raw = array(8, it -> 99);
        assertThrows(RuntimeFault.class, () -> from.copyToByteArray(raw, 0, 8));
        assertArrayEquals(array(8, it -> 99), raw);
        var exposed = ManagedAllocation.mutable(8, 8);
        exposed.rawBytesIfPointerFree();
        assertThrows(RuntimeFault.class, () -> from.copyToByteArray(exposed, 0, 8));
        assertArrayEquals(new byte[8], bytes(exposed));
        to.copyFromByteArray(raw, 0, 8);
        assertThrows(RuntimeFault.class, () -> target.readAddressByteOffset(16));
        assertEquals(99L, target.readByte(16));
    }
    @Test
    public void opposingManagedCopiesUseExistingOrderedOwnerLocks() throws Exception {
        var first = ManagedAllocation.mutable(16, 8);
        var second = ManagedAllocation.mutable(16, 8);
        var pointer = ManagedAddress.fromHex("41");
        first.writeAddressByteOffset(0, pointer);
        second.writeAddressByteOffset(0, pointer);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> {
                for (int i = 0; i < 1000; i++)
                    ManagedAddress.fromAllocation(first).copyToByteArray(second, 0, 16);
            });
            var b = executor.submit(() -> {
                for (int i = 0; i < 1000; i++)
                    ManagedAddress.fromAllocation(first).copyFromByteArray(second, 0, 16);
            });
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
            assertSame(pointer, first.readAddressByteOffset(0));
            assertSame(pointer, second.readAddressByteOffset(0));
        } finally {
            executor.shutdownNow();
        }
    }
    private Expr value(List<Integer> log, int index, Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                log.add(index);
                return value;
            }
            @Override
            public long executeLong(VirtualFrame frame) {
                log.add(index);
                return (Long) value;
            }
            @Override
            public ManagedAddress executeAddress(VirtualFrame frame) {
                log.add(index);
                return (ManagedAddress) value;
            }
        };
    }
    @Test
    public void fixedAstChildrenEvaluateStateBeforeStorageAndLeaveNoMutationOnFailure() {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var proof = new CoreRepresentation(CoreKind.VOID, false, true, null, null, null, null, null, null);
        for (boolean toArray : new boolean[] {false, true}) {
            var log = new ArrayList<Integer>();
            var source = array(8, it -> 3);
            var target = array(8, it -> 71);
            var state = new Expr() {
                @Override
                public Object execute(VirtualFrame frame) {
                    log.add(4);
                    throw new RuntimeFault("state failed");
                }
            };
            Expr expression = toArray
                ? new AddressToByteArrayExpression(proof, value(log, 0, ManagedAddress.fromByteArray(source)),
                      value(log, 1, target), value(log, 2, 0L), value(log, 3, 8L), state)
                : new ByteArrayToAddressExpression(proof, value(log, 0, source), value(log, 1, 0L),
                      value(log, 2, ManagedAddress.fromByteArray(target)), value(log, 3, 8L), state);
            assertThrows(RuntimeFault.class, () -> expression.execute(frame));
            assertEquals(List.of(0, 1, 2, 3, 4), log);
            assertArrayEquals(array(8, it -> 71), target);
        }
    }
    private static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }
    private Map<String, Object> rep(String kind, String primitive) {
        return map("kind", kind, "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", true);
    }
    private Map<String, Object> synthetic(AddressArrayCopyOp operation) {
        var longRep = rep("long", "IntRep");
        var address = rep("address", "AddrRep");
        var array = rep("object", "BoxedRep (Just Unlifted)");
        var state = rep("void", null);
        var closure = rep("closure", "BoxedRep (Just Lifted)");
        var proofs = operation.getToArray() ? List.of(address, array, longRep, longRep, state)
                                            : List.of(array, longRep, address, longRep, state);
        var args = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < proofs.size(); i++) args.add(map("id", "a" + i, "lifted", false, "rep", proofs.get(i)));
        var call = list("app", list("prim", operation.getPrimitive()),
            args.stream().map(it -> list("var", it.get("id"), map("rep", it.get("rep")))).toList(),
            Collections.nCopies(5, false), false, false, map("rep", state));
        var body = list("case", call, "state",
            list(list("default", null, List.of(), list("lit", "int", "23", map("rep", longRep)))),
            map("rep", longRep, "binder", map("id", "state", "lifted", false, "rep", state)));
        return map("module", "SyntheticAddressArrayCopy", "schema", 1, "instrument", true, "bindings",
            list(map("id", "copy", "name", "copy", "arity", 5, "lifted", true, "rep", closure, "expr",
                list("lam", args, body, map("rep", closure, "resultRep", longRep)))));
    }
    @Test
    public void ownedNativeCopiesExecuteOnFirstCompiledEntriesAndRejectStaleOrForeignOwners() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        for (String backend : List.of("ast", "bytecode"))
            for (boolean inlining : new boolean[] {false, true}) try (var context = context(inlining, true)) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var registry = Language.currentState().getNativeAllocations();
                        var base = registry.malloc(24);
                        try {
                            for (var operation : AddressArrayCopyOp.values()) {
                                var p = program(language, synthetic(operation), backend);
                                var target = p.entryTarget("copy");
                                class Call {
                                    Object run(Object array) {
                                        return run(array, INSTANCE);
                                    }
                                    Object run(Object array, Object state) {
                                        return call(target,
                                            operation.getToArray()
                                                ? new Object[] {0L, base.plus(4), array, 2L, 8L, state}
                                                : new Object[] {0L, array, 2L, base.plus(4), 8L, state});
                                    }
                                }
                                var call = new Call();
                                for (boolean installed : new boolean[] {false, true})
                                    for (boolean owned : new boolean[] {false, true}) {
                                        // The previous case deliberately trips a state
                                        // guard, which may invalidate installed code.
                                        // Install before this case's first measured call.
                                        if (installed)
                                            compile(target);
                                        for (long i = 0; i < 24; i++) base.writeWord8(i, i + 31);
                                        var array = storage(array(12, it -> it + 91), owned);
                                        long before = count(p);
                                        assertEquals(23L, call.run(array));
                                        if (installed) {
                                            assertEquals(
                                                before + 1, count(p), backend + "/" + operation + "/owned=" + owned);
                                            valid(target);
                                        }
                                        if (operation.getToArray())
                                            assertArrayEquals(
                                                array(12, it -> it >= 2 && it <= 9 ? it + 33 : it + 91), bytes(array));
                                        else
                                            assertEquals(LongStream.range(0, 24)
                                                             .map(it -> it >= 4 && it <= 11 ? it + 89 : it + 31)
                                                             .boxed()
                                                             .toList(),
                                                LongStream.range(0, 24).map(base::readWord8).boxed().toList());
                                        var old = bytes(array);
                                        var nativeOld = LongStream.range(0, 24).map(base::readWord8).boxed().toList();
                                        assertThrows(RuntimeFault.class, () -> call.run(array, 7L));
                                        assertArrayEquals(old, bytes(array));
                                        assertEquals(
                                            nativeOld, LongStream.range(0, 24).map(base::readWord8).boxed().toList());
                                        released(language);
                                    }
                            }
                            var pointers = ManagedAllocation.mutable(8, 8);
                            pointers.writeAddressByteOffset(0, ManagedAddress.fromHex("41"));
                            var before = LongStream.range(0, 24).map(base::readWord8).boxed().toList();
                            assertThrows(RuntimeFault.class, () -> base.copyFromByteArray(pointers, 0, 8));
                            assertEquals(before, LongStream.range(0, 24).map(base::readWord8).boxed().toList());
                            assertThrows(RuntimeFault.class, () -> base.copyToByteArray(pointers, 0, 1));
                            assertNotNull(pointers.readAddressByteOffset(0));
                            try (var other = context(true, true)) {
                                other.initialize("thc");
                                other.enter();
                                try {
                                    assertThrows(RuntimeFault.class, () -> base.copyToByteArray(new byte[8], 0, 0));
                                    assertThrows(RuntimeFault.class, () -> base.copyFromByteArray(new byte[8], 0, 0));
                                } finally {
                                    other.leave();
                                }
                            }
                        } finally {
                            registry.free(base);
                        }
                        assertThrows(RuntimeFault.class, () -> base.copyToByteArray(new byte[8], 0, 0));
                        assertThrows(RuntimeFault.class, () -> base.copyFromByteArray(new byte[8], 0, 0));
                        assertEquals(0, registry.liveCount());
                    } finally {
                        context.leave();
                    }
                }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0)))
                result.add((List<Object>) list);
            for (var child : list) result.addAll(applications(child));}else if(value instanceof Map<?,?> map)
            for (var child : map.values()) result.addAll(applications(child));
        return result;
    }
    @Test
    public void nativeOwnerRemainsBorrowedWhileWaitingForManagedCopyMonitor() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = context(false, true)) {
            var executor = Executors.newFixedThreadPool(2);
            context.initialize("thc");
            context.enter();
            try {
                var registry = Language.currentState().getNativeAllocations();
                for (boolean toArray : new boolean[] {false, true}) {
                    var base = registry.malloc(8);
                    var array = ManagedAllocation.mutable(8, 8);
                    for (long i = 0; i <= 7; i++) base.writeWord8(i, 73);
                    array.fill(0, 8, 91);
                    var copyingThread = new AtomicReference<Thread>();
                    var startedFree = new CountDownLatch(1);
                    Future<?> copy, free;
                    synchronized (array) {
                        copy = executor.submit(() -> {
                            context.enter();
                            try {
                                copyingThread.set(Thread.currentThread());
                                if (toArray)
                                    base.copyToByteArray(array, 0, 8);
                                else
                                    base.copyFromByteArray(array, 0, 8);
                            } finally {
                                context.leave();
                            }
                        });
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while ((copyingThread.get() == null || copyingThread.get().getState() != Thread.State.BLOCKED)
                            && System.nanoTime() < deadline)
                            Thread.sleep(1);
                        assertEquals(Thread.State.BLOCKED,
                            copyingThread.get() == null ? null : copyingThread.get().getState(),
                            "Copy must reach the managed owner monitor");
                        assertFalse(copy.isDone());
                        free = executor.submit(() -> {
                            context.enter();
                            try {
                                startedFree.countDown();
                                registry.free(base);
                            } finally {
                                context.leave();
                            }
                        });
                        assertTrue(startedFree.await(5, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> free.get(100, TimeUnit.MILLISECONDS));
                    }
                    copy.get(5, TimeUnit.SECONDS);
                    free.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(array(8, it -> toArray ? 73 : 91), bytes(array));
                    assertThrows(RuntimeFault.class, () -> base.copyToByteArray(array, 0, 0));
                    assertThrows(RuntimeFault.class, () -> base.copyFromByteArray(array, 0, 0));
                    assertEquals(0, registry.liveCount());
                }
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                    executor.shutdownNow();
                context.leave();
            }
        }
    }
    @Test
    public void genuineCoreCarrierArityAndScalarStateFailuresStayStrict(@TempDir Path temporary) throws Exception {
        evidence();
        for (String stage : List.of("pre", "post"))
            for (int index = 0; index < names.size(); index++)
                for (String mutation :
                    List.of("valid", "argument", "state", "result", "tuple", "partial", "over", "lifted", "bare")) {
                    String name = names.get(index), primitive = primitives.get(index);
                    var linked = CoreModules.reachable(module(stage), "main:AddressArrayCopyAudit." + name);
                    // Reachability adds a runtime selection field, not a module wire field.
                    assertNull(linked.remove("selectedForeignExceptionBridge"));
                    var app = single(applications(linked)
                            .stream()
                            .filter(it
                                -> it.get(1) instanceof List<?> function && function.size() >= 2
                                    && function.subList(0, 2).equals(List.of("prim", primitive)))
                            .toList());
                    var metadata = (Map<String, Object>) app.get(6);
                    var arguments = (List<Object>) app.get(2);
                    var longRep = map("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
                    switch (mutation) {
                        case "argument", "state" ->
                            ((Map<String, Object>) CoreRepresentations.metadata(
                                 (List<Object>) arguments.get(mutation.equals("state") ? 4 : 0)))
                                .put("rep", longRep);
                        case "result" -> metadata.put("rep", longRep);
                        case "tuple" ->
                            metadata.put("rep",
                                map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of(),
                                    "components", list(map("kind", "void", "primReps", List.of()))));
                        case "partial" -> {
                            arguments.removeLast();
                            ((List<?>) app.get(3)).removeLast();
                            metadata.remove("callDemand");
                        }
                        case "over" -> {
                            arguments.add(arguments.getLast());
                            ((List<Object>) app.get(3)).add(false);
                            metadata.remove("callDemand");
                        }
                        case "lifted" -> ((List<Object>) app.get(3)).set(0, true);
                        case "bare" -> {
                            var head = new ArrayList<>((List<?>) app.get(1));
                            app.clear();
                            app.addAll(head);
                        }
                    }
                    String label = stage + "-" + name + "-" + mutation;
                    var input = thc.CoreCbdFixtures.write(temporary.resolve(label + ".cbd"), linked);
                    var report = temporary.resolve(label + "-report.json");
                    var process = new ProcessBuilder("python3", "bin/audit-core.py", input.toString(), "--entry",
                        "main:AddressArrayCopyAudit." + name, "--output", report.toString())
                                      .directory(root.toFile())
                                      .redirectOutput(temporary.resolve(label + ".stdout").toFile())
                                      .redirectError(temporary.resolve(label + ".stderr").toFile())
                                      .start();
                    if (!process.waitFor(60, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor();
                        fail("Auditor timeout: " + label);
                    }
                    assertEquals(mutation.equals("valid") ? 0 : 1, process.exitValue(), label);assertEquals(mutation.equals("valid"),((Map<?,?>)Json.parse(Files.readString(report))).get("accepted"),label);
                    for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                            context.initialize("thc");
                            context.enter();
                            try {
                                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                                if (mutation.equals("valid"))
                                    program(language, linked, backend);
                                else
                                    for (boolean diagnostic : new boolean[] {false, true}) {
                                        var source = new LinkedHashMap<>(linked);
                                        source.put("diagnosticUnsupported", diagnostic);
                                        if (mutation.equals("bare") && diagnostic) {
                                            var loaded = program(language, source, backend);
                                            assertThrows(RuntimeException.class,
                                                ()
                                                    -> Calls.target(loaded.hostEntryTarget(5),
                                                        new Object[] {loaded.entryValue("main:AddressArrayCopyAudit." + name),
                                                            new Object[] {0L, 0L, 0L, 1L, 8L}}),
                                                backend + "/" + label + " deferred diagnostic trap");
                                        } else
                                            assertThrows(RuntimeException.class,
                                                () -> program(language, source, backend), backend + "/" + label);
                                    }
                            } finally {
                                context.leave();
                            }
                        }
                }
        for (var operation : AddressArrayCopyOp.values()) {
            var proofs = operation.getToArray()
                ? List.of(CoreKind.ADDRESS, CoreKind.OBJECT, CoreKind.LONG, CoreKind.LONG, CoreKind.VOID)
                : List.of(CoreKind.OBJECT, CoreKind.LONG, CoreKind.ADDRESS, CoreKind.LONG, CoreKind.VOID);
            for (String register : List.of("IntRep", "WordRep", "Int64Rep", "Word64Rep"))
                operation.validate(
                    proofs.stream()
                        .map(it
                            -> new CoreRepresentation(it, false, true,
                                it == CoreKind.LONG ? List.of(register) : List.of(), null, null, null, null, null))
                        .toList(),
                    Collections.nCopies(5, false),
                    new CoreRepresentation(CoreKind.VOID, false, false, null, null, null, null, null, null));
        }
    }
}
