// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.lang.ref.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class PinnedPointerCellsTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private record Row(long input, long pointer, long array, long order, long char8, long byte8, long halfwordRead,
        long halfwordWrite, long mutableContents, long touchLazy, long nonOverlappingCopy, List<Long> wideBytes,
        List<Long> wideReads) {}
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var targets = new ArrayList<RootCallTarget>();
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
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
                if (call.getCurrentCallTarget() instanceof RootCallTarget next
                    && next.getRootNode() instanceof GuestRoot)
                    visit(next, seen, targets);
        targets.add(target);
    }
    private Context strictContext(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "installed target");
        // Match EntryValue.compile: restore the shared entry stub without
        // executing a settling guest call or replacing invalid target code.
        var runtime = Truffle.getRuntime();
        runtime.getClass()
            .getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
        valid(target, "restored entry boundary");
    }
    private void released(Language language) {
        var pools = language.getHandoffState().get();
        assertEquals(0, pools.getArguments().getDepth());
        assertEquals(0, pools.getResults().getDepth());
        assertEquals(0, pools.getArguments().retainedReferences());
        assertEquals(0, pools.getResults().retainedReferences());
    }
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (String name : List.of("PinnedPointerCellsAudit.json", "THC.InterfaceClosure.json"))
            modules.add(json("build/pinned-pointer-cells/" + stage + "/core/" + name));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> source, String backend) {
        return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
    }
    private List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>();
        for (String line : Files.readAllLines(root.resolve("build/pinned-pointer-cells/oracle.tsv"))) {
            var fields = List.of(line.split("\t", -1));
            assertEquals(13, fields.size());
            var scalars = fields.subList(0, 11).stream().map(Long::valueOf).toList();
            rows.add(new Row(scalars.get(0), scalars.get(1), scalars.get(2), scalars.get(3), scalars.get(4),
                scalars.get(5), scalars.get(6), scalars.get(7), scalars.get(8), scalars.get(9), scalars.get(10),
                Arrays.stream(fields.get(11).split(",", -1)).map(Long::valueOf).toList(),
                Arrays.stream(fields.get(12).split(",", -1)).map(Long::valueOf).toList()));
        }
        assertEquals(List.of(0L, 1L, 17L, 127L, 255L, 256L, 32767L, 32768L, 65535L, 4294967297L, 81985529216486895L,
                         -1L, -32768L),
            rows.stream().map(Row::input).toList());
        for (var row : rows) {
            long unsignedByte = row.input & 255L;
            assertEquals(1000000L + unsignedByte * 256L + (unsignedByte ^ 90L), row.mutableContents);
            assertEquals(row.input + 37L, row.touchLazy);
            assertEquals(1000000L + 257L * (row.input & 255L), row.nonOverlappingCopy);
        }
        return rows;
    }
    private void provenance() throws Exception {
        var manifest = json("build/pinned-pointer-cells/manifest.json");
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        var inputs = new HashSet<>(Set.of("compiler/test-fixtures/PinnedPointerCellsAudit.hs",
            "compiler/test-fixtures/PinnedPointerCellsNative.hs", "test/haskell-fixtures/Main.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
            "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py", "thc.cabal",
            "cabal.project"));
        try (var files = Files.list(root.resolve("compiler/THC"))) {
            files.filter(it -> it.toString().endsWith(".hs"))
                .map(it -> "compiler/THC/" + it.getFileName())
                .forEach(inputs::add);
        }
        try (var files = Files.list(root.resolve("scripts"))) {
            files.filter(it -> it.getFileName().toString().startsWith("core_") && it.toString().endsWith(".py"))
                .map(it -> "scripts/" + it.getFileName())
                .forEach(inputs::add);
        }
        var artifacts = new HashSet<>(Set.of("build/pinned-pointer-cells/oracle.tsv"));
        for (String stage : List.of("pre", "post"))
            for (String name :
                List.of("audit.json", "core/PinnedPointerCellsAudit.json", "core/THC.InterfaceClosure.json"))
                artifacts.add("build/pinned-pointer-cells/" + stage + "/" + name);
        for (var item : List.of(Map.entry("inputHashes", inputs), Map.entry("artifactHashes", artifacts))) {
            var hashes = (Map<String, String>) manifest.get(item.getKey());
            assertEquals(item.getValue(), hashes.keySet(), item.getKey());
            for (var hash : hashes.entrySet()) {
                var file = root.resolve(hash.getKey());
                assertTrue(
                    file.toFile().getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath()),
                    hash.getKey());
                String actual =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                assertEquals(
                    hash.getValue(), actual, "Stale pinned pointer-cell " + item.getKey() + ": " + hash.getKey());
            }
        }
    }
    @Test
    public void pointerArrayCellsRequireAnOwnerAndRejectInvalidIndicesBeforeMutation() {
        var nullAddress = ManagedAddress.Companion.nullAddress();
        byte[] raw = new byte[32];
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(raw, 0, nullAddress));
        assertThrows(RuntimeFault.class, () -> PinnedMemory.readAddressArray(raw, 0));
        var pinned = PinnedMemory.allocate(32, 1);
        PinnedMemory.writeAddressArray(pinned, 1, nullAddress);
        assertSame(nullAddress, PinnedMemory.readAddressArray(pinned, 1));
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(pinned, -1, nullAddress));
        assertThrows(RuntimeFault.class, () -> PinnedMemory.writeAddressArray(pinned, Long.MAX_VALUE, nullAddress));
        assertSame(nullAddress, PinnedMemory.readAddressArray(pinned, 1));
        var base = ManagedAddress.Companion.fromAllocation(pinned);
        var target = base.plus(24);
        base.writeAddressElementIndex(1, target);
        base.writeWord8(16, 0x1e9);
        assertEquals(0xe9L, base.readWord8(16));
        assertDoesNotThrow(() -> ManagedAddressRead.WORD16.readInt(base, 8));
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD16.readInt(base, 4));
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.INT16.readInt(base, Long.MAX_VALUE));
        assertThrows(RuntimeFault.class, () -> base.writeWord8(8, 0x41));
        assertSame(target, base.readAddressElementIndex(1));
    }
    @Test
    public void orderedAddressesRequireOneAllocationAndPreserveCheckedOffsets() {
        var nullAddress = ManagedAddress.Companion.nullAddress();
        assertEquals(0, nullAddress.compareWithinAllocation(nullAddress));
        byte[] bytes = new byte[16];
        var base = ManagedAddress.Companion.fromByteArray(bytes);
        var alias = ManagedAddress.Companion.fromByteArray(bytes);
        assertEquals(0, base.compareWithinAllocation(alias));
        assertTrue(base.compareWithinAllocation(alias.plus(1)) < 0);
        assertTrue(base.plus(16).compareWithinAllocation(alias) > 0); // one past
        assertThrows(RuntimeFault.class, () -> base.plus(Long.MAX_VALUE).readWord8(0));
        assertTrue(base.plus(-1).compareWithinAllocation(alias) < 0);
        assertThrows(RuntimeFault.class, () -> base.plus(-1).readWord8(0));
        assertThrows(RuntimeFault.class, () -> base.compareWithinAllocation(nullAddress));
        assertThrows(RuntimeFault.class, () -> nullAddress.compareWithinAllocation(base));
        assertThrows(RuntimeFault.class,
            () -> base.compareWithinAllocation(ManagedAddress.Companion.fromByteArray(bytes.clone())));
        var pinned = ManagedAddress.Companion.fromAllocation(PinnedMemory.allocate(16, 1));
        assertTrue(pinned.compareWithinAllocation(pinned.plus(16)) < 0);
        assertThrows(RuntimeFault.class, () -> base.compareWithinAllocation(pinned));
    }
    private List<List<Object>> primitiveCalls(Object value) {
        var result = new ArrayList<List<Object>>();if(value instanceof Map<?,?> map){
            for (var child : map.values()) result.addAll(primitiveCalls(child));
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0)) && list.size() > 1 && list.get(1) instanceof List<?> head
                && !head.isEmpty() && "prim".equals(head.get(0)))
                result.add((List<Object>) list);
            for (var child : list) result.addAll(primitiveCalls(child));
        }
        return result;
    }
    private Map<String, Object> rep(List<Object> expr) {
        return (Map<String, Object>) Objects.requireNonNull(CoreRepresentations.INSTANCE.metadata(expr)).get("rep");
    }
    private Map<String, Object> proof(String kind, List<String> reps, boolean evaluated) {
        return Map.of("kind", kind, "primReps", reps, "evaluated", evaluated);
    }
    private Map<String, Object> plus(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }
    private Object replace(Object value, Object target, Object replacement) {
        if (value == target)
            return replacement;
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            for (var child : list) result.add(replace(child, target, replacement));
            return result;
        }if(value instanceof Map<?,?> map){
            var result = new LinkedHashMap<Object, Object>();
            for (var entry : map.entrySet()) result.put(entry.getKey(), replace(entry.getValue(), target, replacement));
            return result;
        }
        return value;
    }
    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return list.getFirst();
    }
    @Test
    public void mutableContentsAndTouchRetainExactOriginalProofsAndRejectMutations() throws Exception {
        provenance();
        for (String stage : List.of("pre", "post")) {
            var source = module(stage);
            // The copy root also uses mutable contents and touch. Keep these
            // original ABI controls scoped to their two original entry roots.
            var mutable = CoreModules.reachable(source, "mutableContentsRoundtrip");
            var lazy = CoreModules.reachable(source, "touchLazyPayload");
            var calls = new ArrayList<>(primitiveCalls(mutable));
            calls.addAll(primitiveCalls(lazy));
            var contents = single(
                calls.stream().filter(it -> "mutableByteArrayContents#".equals(((List<?>) it.get(1)).get(1))).toList());
            var touches = calls.stream().filter(it -> "touch#".equals(((List<?>) it.get(1)).get(1))).toList();
            assertEquals(2, touches.size());
            var owner = proof("object", List.of("BoxedRep (Just Unlifted)"), true);
            var state = proof("void", List.of(), true);
            assertEquals(List.of(false), contents.get(3));
            assertEquals(List.of(owner), ((List<List<Object>>) contents.get(2)).stream().map(this::rep).toList());
            assertEquals(proof("address", List.of("AddrRep"), false), rep(contents));
            for (var touch : touches) {
                var flags = (List<Boolean>) touch.get(3);
                assertEquals(2, flags.size());
                assertEquals(false, flags.get(1));
                var args = (List<List<Object>>) touch.get(2);
                assertEquals(
                    List.of(flags.get(0) ? proof("data", List.of("BoxedRep (Just Lifted)"), false) : owner, state),
                    args.stream().map(this::rep).toList());
                assertEquals(proof("void", List.of(), false), rep(touch));
            } // Not (# State# #).
            assertEquals(Set.of(List.of(true, false), List.of(false, false)),
                touches.stream().map(it -> it.get(3)).collect(Collectors.toSet()));
            assertFalse(primitiveCalls(mutable).stream().anyMatch(
                it -> "unsafeFreezeByteArray#".equals(((List<?>) it.get(1)).get(1))));
            assertEquals(1,
                (int) primitiveCalls(lazy)
                    .stream()
                    .filter(it -> "raise#".equals(((List<?>) it.get(1)).get(1)))
                    .count());
            var audit = json("build/pinned-pointer-cells/" + stage + "/audit.json");
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitives = new LinkedHashMap<Object, Map<String, Object>>();
            for (var primitive : (List<Map<String, Object>>) audit.get("primitives"))
                primitives.put(primitive.get("name"), primitive);
            for (var item :
                Map.of("mutableByteArrayContents#", Set.of("mutableContentsAt", "nonOverlappingCopy"), "touch#",
                       Set.of("mutableContentsRoundtrip", "touchLazyPayload", "nonOverlappingCopy"))
                    .entrySet()) {
                var uses = (List<Map<String, Object>>) primitives.get(item.getKey()).get("uses");
                assertEquals(item.getValue()
                                 .stream()
                                 .map(it -> "main:PinnedPointerCellsAudit." + it)
                                 .collect(Collectors.toSet()),
                    uses.stream().map(it -> it.get("owner")).collect(Collectors.toSet()));
            }
            // Mutate the retained authentic applications, not a substitute synthetic ABI.
            var mutations = new ArrayList<Map.Entry<Object, Object>>();
            var apps = new ArrayList<>(List.of(contents));
            apps.addAll(touches);
            for (var app : apps) {
                var copy = new ArrayList<>(app);
                var flags = new ArrayList<>((List<Boolean>) app.get(3));
                flags.set(0, !flags.get(0));
                copy.set(3, flags);
                mutations.add(Map.entry(app, copy));
                copy = new ArrayList<>(app);
                copy.set(6, plus((Map<String, Object>) app.get(6), "rep", proof("long", List.of("IntRep"), false)));
                mutations.add(Map.entry(app, copy));
            }
            for (var touch : touches) {
                var args = (List<List<Object>>) touch.get(2);
                var badState = new ArrayList<>(args.get(1));
                badState.set(
                    2, plus((Map<String, Object>) badState.get(2), "rep", proof("long", List.of("IntRep"), true)));
                var copy = new ArrayList<>(touch);
                copy.set(2, List.of(args.get(0), badState));
                mutations.add(Map.entry(touch, copy));
                copy = new ArrayList<>(touch);
                copy.set(6,
                    plus((Map<String, Object>) copy.get(6), "rep",
                        Map.of("kind", "unknown", "primReps", List.of(), "evaluated", false, "aggregate",
                            "unboxed-tuple", "components", List.of(state))));
                mutations.add(Map.entry(touch, copy));
            }
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        assertDoesNotThrow(() -> program(language, source, backend));
                        for (var mutation : mutations)
                            assertThrows(RuntimeFault.class,
                                ()
                                    -> program(language,
                                        (Map<String, Object>) replace(source, mutation.getKey(), mutation.getValue()),
                                        backend),
                                stage + "/" + backend + " malformed authentic app");
                    } finally {
                        context.leave();
                    }
                }
        }
    }
    @Test
    public void mutableContentsAndLazyTouchMatchNativeOnEveryInstalledCall() throws Exception {
        provenance();
        var rows = rows();
        for (String stage : List.of("pre", "post"))
            for (String backend : List.of("ast", "bytecode"))
                for (boolean inlining : new boolean[] {false, true}) try (var context = strictContext(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            for (String entry : List.of("mutableContentsRoundtrip", "touchLazyPayload")) {
                                var source = plus(CoreModules.reachable(module(stage), entry), "instrument", true);
                                var evidence = new ArrayCoreEvidence(source, entry);
                                assertEquals(2, evidence.getBindings().size());
                                assertEquals(2, evidence.guestLambdas(evidence.getRoot().get("expr")).size());
                                assertEquals(1, evidence.loweredStateLambdas(evidence.getRoot().get("expr")).size());
                                String helperName =
                                    entry.equals("mutableContentsRoundtrip") ? "mutableContentsAt" : "opaqueBottom";
                                var helper = single(evidence.getBindings()
                                        .stream()
                                        .filter(it -> helperName.equals(it.get("name")))
                                        .toList());
                                assertEquals(1, evidence.guestLambdas(helper.get("expr")).size());
                                assertEquals(Collections.nCopies(
                                                 entry.equals("mutableContentsRoundtrip") ? 2 : 1, helper.get("id")),
                                    evidence.globalReferences(evidence.getRoot().get("expr")));
                                int expectedTargets = entry.equals("mutableContentsRoundtrip") ? 2 : 1;
                                long expectedCalls = entry.equals("mutableContentsRoundtrip") ? 3L : 1L;
                                var program = program(language, source, backend);
                                var target = program.entryTarget(entry);
                                java.util.function.Consumer<Row> check = row -> {
                                    assertEquals(
                                        entry.equals("mutableContentsRoundtrip") ? row.mutableContents : row.touchLazy,
                                        Calls.target(target, new Object[] {0L, row.input}),
                                        stage + "/" + backend + "/" + entry + "/" + row.input
                                            + "/inlining=" + inlining);
                                    released(language);
                                };
                                for (int i = 0; i < 3; i++) rows.forEach(check);
                                // The mutable helper runs twice; touch# must leave the
                                // opaque bottom unevaluated. Retain real helper boundaries.
                                var targets = activeTargets(target);
                                assertEquals(expectedTargets, targets.size());
                                for (var t : targets) {
                                    compile(t);
                                    valid(t,
                                        stage + "/" + backend + "/" + entry + "/" + t.getRootNode().getName()
                                            + " installation");
                                }
                                for (var t : targets)
                                    valid(t,
                                        stage + "/" + backend + "/" + entry + "/" + t.getRootNode().getName()
                                            + " installed graph");
                                var measured = new ArrayList<>(rows.reversed());
                                measured.addAll(rows);
                                for (var row : measured) {
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    check.accept(
                                        row); // First installed invocation is checked without settling/recompilation.
                                    assertEquals(expectedCalls,
                                        ((Number) program.diagnostics().get("compiledEntries")).longValue() - before);
                                    for (var t : targets)
                                        valid(t,
                                            stage + "/" + backend + "/" + entry + "/" + t.getRootNode().getName() + "/"
                                                + row.input + "/inlining=" + inlining);
                                }
                                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                            }
                        } finally {
                            context.leave();
                        }
                    }
    }
    @Test
    public void originalMutableContentsHelperPreservesAliasesLifetimeAndCheckedBounds() throws Exception {
        provenance();
        for (String stage : List.of("pre", "post"))
            for (String backend : List.of("ast", "bytecode"))
                for (boolean inlining : new boolean[] {false, true}) try (var context = strictContext(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var program = program(language,
                                plus(CoreModules.reachable(module(stage), "mutableContentsAt"), "instrument", true),
                                backend);
                            var target = program.entryTarget("mutableContentsAt");
                            record Alias(ManagedAddress retained, WeakReference<ManagedAllocation> owner) {}
                            class Check {
                                boolean compiled = false;
                                ManagedAddress address(ManagedAllocation owner, long offset) throws Exception {
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    try {
                                        return (ManagedAddress) Calls.target(target, new Object[] {0L, owner, offset});
                                    } finally {
                                        if (compiled) {
                                            assertTrue(
                                                ((Number) program.diagnostics().get("compiledEntries")).longValue()
                                                > before);
                                            valid(target,
                                                stage + "/" + backend + "/mutableContentsAt/" + offset
                                                    + "/inlining=" + inlining);
                                        }
                                        released(language);
                                    }
                                }
                                Alias aliases() throws Exception {
                                    var owner = PinnedMemory.allocate(32, 1);
                                    var base = address(owner, 0);
                                    assertTrue(base.sameLocation(address(owner, 0)));
                                    for (long offset : new long[] {0, 1, 31}) {
                                        var interior = address(owner, offset);
                                        assertTrue(base.plus(offset).sameLocation(interior));
                                        owner.writeByte(offset, offset + 70L);
                                        assertEquals(offset + 70L, interior.readWord8(0));
                                        interior.writeWord8(0, offset + 100L);
                                        assertEquals(offset + 100L, owner.readByte(offset));
                                    }
                                    assertTrue(base.plus(32).sameLocation(address(owner, 32)));
                                    return new Alias(base, new WeakReference<>(owner));
                                }
                            }
                            var check = new Check();
                            for (int i = 0; i < 3; i++) check.aliases();
                            var boundsOwner = PinnedMemory.allocate(32, 1);
                            for (long offset : new long[] {-1, 33, Long.MIN_VALUE, Long.MAX_VALUE})
                                // plusAddr# may form before-start/end sentinels; the
                                // memory access, not pointer arithmetic, checks bounds.
                                assertThrows(RuntimeFault.class, () -> check.address(boundsOwner, offset).readWord8(0));
                            assertThrows(RuntimeFault.class, () -> check.address(boundsOwner, 32).readWord8(0));
                            compile(target);
                            valid(target, "mutableContentsAt installation");
                            check.compiled = true;
                            var alias = check.aliases();
                            var retained = alias.retained();
                            var owner = alias.owner();
                            for (int i = 0; i < 3; i++) System.gc();
                            assertNotNull(owner.get(), "An escaped Addr# must retain its pinned allocation");
                            assertEquals(131L, retained.readWord8(31));
                            retained.writeWord8(31, 211);
                            assertEquals(211L, Objects.requireNonNull(owner.get()).readByte(31));
                            Reference.reachabilityFence(retained);
                            check.aliases();
                        } finally {
                            context.leave();
                        }
                    }
    }
    @Test
    public void halfwordStoresCheckWholeElementAndPreserveDisjointPointerCells() {
        var base = ManagedAddress.Companion.fromAllocation(PinnedMemory.allocate(32, 1));
        var target = base.plus(24);
        base.writeAddressElementIndex(1, target);
        base.writeWord16(8, 0x12345);
        var expected = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort((short) 0x2345).array();
        assertEquals(expected[0] & 255L, base.readWord8(16));
        assertEquals(expected[1] & 255L, base.readWord8(17));
        assertSame(target, base.readAddressElementIndex(1));
        for (long index : new long[] {4, 7, 16, Long.MAX_VALUE, Long.MIN_VALUE})
            assertThrows(RuntimeFault.class, () -> base.writeWord16(index, 0x55aa));
        assertSame(target, base.readAddressElementIndex(1));
        assertEquals(expected[0] & 255L, base.readWord8(16));
        assertThrows(RuntimeFault.class, () -> ManagedAddress.Companion.fromHex("0000").writeWord16(0, 1));
        base.plus(2).writeWord16(-1, -1);
        assertEquals(255L, base.readWord8(0));
        assertEquals(255L, base.readWord8(1));
    }
    @Test
    public void wideScalarStoresCheckWholeElementsAndPointerOverlap() {
        var base = ManagedAddress.Companion.fromAllocation(PinnedMemory.allocate(64, 1));
        var target = base.plus(56);
        base.writeAddressElementIndex(1, target);
        base.writeNativeScalar(4, 4, 0x12345678);
        base.writeNativeScalar(5, 8, 0x1020304050607080L);
        assertSame(target, base.readAddressElementIndex(1));
        for (int width : new int[] {4, 8})
            for (long index : new long[] {-1, Long.MIN_VALUE, Long.MAX_VALUE})
                assertThrows(RuntimeFault.class, () -> base.writeNativeScalar(index, width, -1));
        assertThrows(RuntimeFault.class, () -> base.plus(2).writeNativeScalar(1, 8, -1));
        assertSame(target, base.readAddressElementIndex(1));
        var expected = ByteBuffer.allocate(64)
                           .order(ByteOrder.nativeOrder())
                           .putInt(16, 0x12345678)
                           .putLong(40, 0x1020304050607080L)
                           .array();
        for (int index : IntStream.concat(IntStream.rangeClosed(16, 19), IntStream.rangeClosed(40, 47)).toArray())
            assertEquals(expected[index] & 255L, base.readWord8(index));
        base.writeNativeScalar(1, 8, -1);
        assertThrows(RuntimeFault.class, () -> base.readAddressElementIndex(1));
        for (int index = 8; index <= 15; index++) assertEquals(255L, base.readWord8(index));
    }
    private List<Long> wideStoreModel(long input) {
        var bytes = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        bytes.putInt(16, (int) input);
        bytes.putInt(20, (int) (input + 17));
        bytes.putLong(24, input);
        bytes.putLong(32, input + 33);
        bytes.putLong(40, input);
        bytes.putLong(48, input + 49);
        return IntStream.rangeClosed(16, 55).mapToObj(it -> bytes.get(it) & 255L).toList();
    }
    @Test
    public void wideIndexesUseElementOffsetsAndRejectPointerOverlap() {
        var base = ManagedAddress.Companion.fromAllocation(PinnedMemory.allocate(64, 1));
        base.writeNativeScalar(4, 4, -1);
        base.writeNativeScalar(5, 8, 0x123456789abcdefL);
        var derived = base.plus(24);
        assertEquals(-1L, (long) ManagedAddressRead.INT32.readInt(derived, -2));
        assertEquals(0xffffffffL, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(derived, -2)));
        assertEquals(0x123456789abcdefL, ManagedAddressRead.INT64.read(derived, 2));
        assertEquals(0x123456789abcdefL, ManagedAddressRead.WORD64.read(derived, 2));
        assertEquals(0x123456789abcdefL, ManagedAddressRead.INT64.read(base.plus(48), -1));
        for (var operation : List.of(ManagedAddressRead.INT32, ManagedAddressRead.WORD32, ManagedAddressRead.INT,
                 ManagedAddressRead.WORD, ManagedAddressRead.INT64, ManagedAddressRead.WORD64))
            for (long index : new long[] {Long.MIN_VALUE, Long.MAX_VALUE})
                assertThrows(RuntimeFault.class, () -> {
                    if (operation.isInt())
                        operation.readInt(derived, index);
                    else
                        operation.read(derived, index);
                });
        var target = base.plus(56);
        base.writeAddressElementIndex(1, target);
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD64.read(base, 1));
        assertSame(target, base.readAddressElementIndex(1));
    }
    private List<Long> wideReadModel(long input) {
        return List.of(
            (long) (int) input, (input + 17) & 0xffffffffL, input, input + 33, input, input + 49, input, input + 49);
    }
    private long halfwordModel(long input) {
        var bytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        bytes.putShort(24, (short) input);
        bytes.putShort(16, (short) (input + 32768));
        long packed = 0;
        for (int offset : new int[] {16, 17, 24, 25}) packed = (packed << 8) | (bytes.get(offset) & 255L);
        return (1L << 32) + packed;
    }
    @Test
    public void nonOverlappingAddressCopyPreservesPointerCellsAndRejectsInvalidRegions() {
        var base = ManagedAddress.Companion.fromAllocation(PinnedMemory.allocate(48, 1));
        var target = base.plus(16);
        base.writeWord8(16, 203);
        base.writeAddressElementIndex(1, target);
        base.plus(8).copyNonOverlappingTo(base.plus(24), 16);
        base.writeAddressElementIndex(1, base.plus(40));
        assertSame(target, base.readAddressElementIndex(3));
        assertEquals(203L, base.readWord8(32));
        long intact = base.readWord8(32);
        for (long[] region : new long[][] {{8, 16, 16}, // overlapping regions
                 {25, 40, 7}, // partial source pointer cell
                 {40, 25, 7}, // partial destination pointer cell
                 {8, 24, -1}, {8, 40, Long.MAX_VALUE}}) {
            var source = base.plus(region[0]);
            var destination = base.plus(region[1]);
            long length = region[2];
            assertThrows(RuntimeFault.class, () -> source.copyNonOverlappingTo(destination, length));
            assertSame(target, base.readAddressElementIndex(3));
            assertEquals(intact, base.readWord8(32));
        }
        assertThrows(RuntimeFault.class,
            () -> base.plus(24).copyNonOverlappingTo(ManagedAddress.Companion.fromByteArray(new byte[8]), 8));
        assertThrows(RuntimeFault.class,
            () -> base.plus(32).copyNonOverlappingTo(ManagedAddress.Companion.fromHex("0000000000000000"), 8));
        base.plus(48).copyNonOverlappingTo(base.plus(48), 0);
        // An exposed raw alias still names the same allocation. Never infer
        // disjointness merely from the distinct managed carrier classes.
        var rawOwner = ManagedAllocation.mutable(32, 8);
        var owned = ManagedAddress.Companion.fromAllocation(rawOwner);
        var rawAlias = ManagedAddress.Companion.fromByteArray(rawOwner.rawBytesIfPointerFree());
        assertThrows(RuntimeFault.class, () -> owned.plus(8).copyNonOverlappingTo(rawAlias.plus(12), 8));
        owned.plus(8).copyNonOverlappingTo(rawAlias.plus(16), 8);
    }
    private CoreRepresentation changeRep(CoreRepresentation proof, CoreKind kind, List<String> reps) {
        return proof.copy(kind, proof.getEvaluated(), proof.getPresent(), reps, proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }
    @Test
    public void genuineNonOverlappingCopyMatchesNativeAndCompilesForEveryInput() throws Exception {
        provenance();
        var rows = rows();
        for (String stage : List.of("pre", "post")) {
            var audit = json("build/pinned-pointer-cells/" + stage + "/audit.json");
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var uses = (List<Map<String, Object>>) single(
                ((List<Map<String, Object>>) audit.get("primitives"))
                    .stream()
                    .filter(it -> "copyAddrToAddrNonOverlapping#".equals(it.get("name")))
                    .toList())
                           .get("uses");
            assertEquals(Set.of("main:PinnedPointerCellsAudit.nonOverlappingCopy"),
                uses.stream().map(it -> it.get("owner")).collect(Collectors.toSet()));
            var source = plus(CoreModules.reachable(module(stage), "nonOverlappingCopy"), "instrument", true);
            // Preserve the exported state lambda while proving its exact
            // immediate application lowers within the public root.
            var evidence = new ArrayCoreEvidence(source, "nonOverlappingCopy");
            assertEquals(2, evidence.immediateStateCalls(), stage + " original source lambda inventory");
            long expectedEntries = evidence.loweredImmediateStateCalls();
            assertEquals(1L, expectedEntries, stage + " lowered public root");
            var expectedLabels =
                evidence.loweredStateLambdas(evidence.getRoot().get("expr"))
                    .stream()
                    .map(expression -> "lambda " + single((List<Map<String, Object>>) expression.get(1)).get("name"))
                    .collect(Collectors.toSet());
            assertEquals(1, expectedLabels.size(), stage + " lowered root label");
            var calls = primitiveCalls(source)
                            .stream()
                            .filter(it -> "copyAddrToAddrNonOverlapping#".equals(((List<?>) it.get(1)).get(1)))
                            .toList();
            assertEquals(1, calls.size());
            var call = single(calls);
            var arguments =
                ((List<List<Object>>) call.get(2)).stream().map(CoreRepresentations.INSTANCE::expression).toList();
            var flags = (List<?>) call.get(3);
            var result = CoreRepresentations.INSTANCE.expression(call);
            var operation = PinnedMemoryOp.COPY_ADDR_NON_OVERLAPPING;
            operation.validate(arguments, flags, result);
            assertThrows(
                RuntimeFault.class, () -> operation.validate(arguments, List.of(true, false, false, false), result));
            // The selected primop interprets a lowered Long; lexical WordRep
            // versus IntRep is the exporter's concern, not a carrier difference.
            var wordArguments = new ArrayList<>(arguments);
            wordArguments.set(2, changeRep(wordArguments.get(2), wordArguments.get(2).getKind(), List.of("WordRep")));
            operation.validate(wordArguments, flags, result);
            var doubleArguments = new ArrayList<>(arguments);
            doubleArguments.set(2, changeRep(doubleArguments.get(2), CoreKind.DOUBLE, List.of("DoubleRep")));
            assertThrows(RuntimeFault.class, () -> operation.validate(doubleArguments, flags, result));
            assertThrows(RuntimeFault.class,
                () -> operation.validate(arguments, flags, changeRep(result, CoreKind.LONG, List.of("IntRep"))));
            for (String backend : List.of("ast", "bytecode"))
                for (boolean inlining : new boolean[] {false, true}) try (var context = strictContext(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            String label = stage + "/" + backend + "/inlining=" + inlining;
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var program = program(language, source, backend);
                            var function = context.asValue(new EntryValue(program, "nonOverlappingCopy", 1));
                            var host = program.hostEntryTarget(1);
                            var original = program.entryTarget("nonOverlappingCopy");
                            class Check {
                                List<RootCallTarget> measuredTargets() {
                                    var entry =
                                        single(NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)
                                                .stream()
                                                .filter(it -> it.getCallTarget() == original)
                                                .map(it -> (RootCallTarget) it.getCurrentCallTarget())
                                                .distinct()
                                                .toList());
                                    return activeTargets(entry);
                                }
                                void row(Row row) {
                                    assertEquals(row.nonOverlappingCopy, function.execute(row.input).asLong(),
                                        label + "/" + row.input);
                                }
                            }
                            var check = new Check();
                            for (var row : rows) check.row(row);
                            var targets = check.measuredTargets();
                            assertEquals((int) expectedEntries, targets.size(), label + " active guest roots");
                            assertEquals(expectedLabels,
                                targets.stream().map(it -> it.getRootNode().getName()).collect(Collectors.toSet()),
                                label + " guest root labels");
                            // Compile callees before callers so residual state calls are
                            // measured too. Public compilation then installs the bridge
                            // and restores its shared entry prerequisite without a call.
                            for (var target : targets) {
                                compile(target);
                                valid(target, label + "/" + target.getRootNode().getName() + " installation");
                            }
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " compile");
                            assertEquals(targets, check.measuredTargets(), label + " installed active targets");
                            var allTargets = new ArrayList<>(targets);
                            allTargets.add(host);
                            for (var target : allTargets)
                                valid(target, label + "/" + target.getRootNode().getName() + " installed target");
                            for (var row : rows.reversed()) {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                check.row(row);
                                assertEquals(before + expectedEntries,
                                    ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                                    label + "/" + row.input + " exact entry and state-lambda compiled entries");
                                assertEquals(targets, check.measuredTargets(), label + " active targets");
                                for (var target : allTargets)
                                    valid(target, label + "/" + target.getRootNode().getName() + " retained target");
                            }
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                            released(language);
                        } finally {
                            context.leave();
                        }
                    }
        }
    }
    @Test
    public void originalPinnedFreezeContentsAndKeepAliveMatchNativeInBothBackends() throws Exception {
        provenance();
        var rows = rows();
        for (var row : rows) {
            assertEquals(1009L + 17L * (row.input & 255L), row.pointer);
            assertEquals(1L, row.array);
            assertEquals((row.input & 7L) == 0 ? 122L : 127L, row.order);
            assertEquals(0x01010101L * (row.input & 255L), row.char8);
            long unsigned = row.input & 255L, signed = (byte) unsigned;
            assertEquals(((signed + 128L) << 24) + ((signed + 128L) << 16) + (unsigned << 8) + unsigned, row.byte8);
            long halfword = row.input & 65535L, signedHalfword = (short) halfword;
            assertEquals(
                ((signedHalfword + 32768L) << 48) + ((signedHalfword + 32768L) << 32) + (halfword << 16) + halfword,
                row.halfwordRead);
            assertEquals(halfwordModel(row.input), row.halfwordWrite);
            assertEquals(wideStoreModel(row.input), row.wideBytes);
            assertEquals(wideReadModel(row.input), row.wideReads);
        }
        for (String stage : List.of("pre", "post")) {
            var audit = json("build/pinned-pointer-cells/" + stage + "/audit.json");
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitiveEvidence = (List<Map<String, Object>>) audit.get("primitives");
            var primitives = primitiveEvidence.stream().map(it -> it.get("name")).collect(Collectors.toSet());
            assertTrue(primitives.containsAll(Set.of("newPinnedByteArray#", "unsafeFreezeByteArray#",
                "byteArrayContents#", "keepAlive#", "writeAddrOffAddr#", "readAddrOffAddr#", "indexAddrOffAddr#",
                "writeAddrArray#", "readAddrArray#", "indexAddrArray#", "ltAddr#", "leAddr#", "gtAddr#", "geAddr#",
                "readCharOffAddr#", "writeCharOffAddr#", "indexCharOffAddr#", "readCharArray#", "writeCharArray#",
                "indexCharArray#", "readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#", "indexWord8OffAddr#",
                "readInt16OffAddr#", "readWord16OffAddr#", "indexInt16OffAddr#", "indexWord16OffAddr#",
                "writeInt16OffAddr#", "writeWord16OffAddr#", "writeInt32OffAddr#", "writeWord32OffAddr#",
                "writeIntOffAddr#", "writeWordOffAddr#", "writeInt64OffAddr#", "writeWord64OffAddr#",
                "indexInt32OffAddr#", "indexWord32OffAddr#", "indexIntOffAddr#", "indexWordOffAddr#",
                "indexInt64OffAddr#", "indexWord64OffAddr#", "readInt64OffAddr#", "readWord64OffAddr#",
                "readWord8OffAddr#", "indexWord8Array#")));
            for (String primitive :
                Set.of("indexInt32OffAddr#", "indexWord32OffAddr#", "indexIntOffAddr#", "indexWordOffAddr#",
                    "indexInt64OffAddr#", "indexWord64OffAddr#", "readInt64OffAddr#", "readWord64OffAddr#")) {
                var evidence =
                    single(primitiveEvidence.stream().filter(it -> primitive.equals(it.get("name"))).toList());
                var owners = ((List<Map<String, Object>>) evidence.get("uses"))
                                 .stream()
                                 .map(it -> it.get("owner"))
                                 .collect(Collectors.toSet());
                assertEquals(Set.of("main:PinnedPointerCellsAudit.wideReadSelector"), owners, stage + "/" + primitive);
            }
            for (String primitive : Set.of("writeInt32OffAddr#", "writeWord32OffAddr#", "writeIntOffAddr#",
                     "writeWordOffAddr#", "writeInt64OffAddr#", "writeWord64OffAddr#")) {
                var evidence =
                    single(primitiveEvidence.stream().filter(it -> primitive.equals(it.get("name"))).toList());
                var owners = ((List<Map<String, Object>>) evidence.get("uses"))
                                 .stream()
                                 .map(it -> it.get("owner"))
                                 .collect(Collectors.toSet());
                assertEquals(Set.of("main:PinnedPointerCellsAudit.wideStoreByte",
                                 "main:PinnedPointerCellsAudit.wideReadSelector"),
                    owners, stage + "/" + primitive);
            }
            for (String primitive : Set.of("readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#",
                     "indexWord8OffAddr#", "writeInt16OffAddr#", "writeWord16OffAddr#")) {
                var evidence =
                    single(primitiveEvidence.stream().filter(it -> primitive.equals(it.get("name"))).toList());
                var owners = ((List<Map<String, Object>>) evidence.get("uses"))
                                 .stream()
                                 .map(it -> it.get("owner"))
                                 .collect(Collectors.toSet());
                String owner = primitive.endsWith("16OffAddr#") ? "halfwordWriteRoundtrip" : "byte8Roundtrip";
                assertEquals(Set.of("main:PinnedPointerCellsAudit." + owner), owners, stage + "/" + primitive);
            }
            for (String primitive :
                Set.of("readInt16OffAddr#", "readWord16OffAddr#", "indexInt16OffAddr#", "indexWord16OffAddr#")) {
                var evidence =
                    single(primitiveEvidence.stream().filter(it -> primitive.equals(it.get("name"))).toList());
                var owners = ((List<Map<String, Object>>) evidence.get("uses"))
                                 .stream()
                                 .map(it -> it.get("owner"))
                                 .collect(Collectors.toSet());
                assertEquals(
                    Set.of("main:PinnedPointerCellsAudit.halfwordReadRoundtrip"), owners, stage + "/" + primitive);
            }
            var merged = module(stage);
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (String entry :
                            List.of("pointerRoundtrip", "pointerArrayRoundtrip", "pointerOrder", "char8Roundtrip",
                                "byte8Roundtrip", "halfwordReadRoundtrip", "halfwordWriteRoundtrip")) {
                            var source = plus(CoreModules.reachable(merged, entry), "instrument", true);
                            var program = program(language, source, backend);
                            var function = context.asValue(new EntryValue(program, entry, 1));
                            class Check {
                                void run(long input, long expected) {
                                    assertEquals(expected, function.execute(input).asLong(),
                                        stage + "/" + backend + "/" + entry + "/" + input);
                                    assertEquals(
                                        0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                                }
                                long expected(Row row) {
                                    return switch (entry) {
                                        case "pointerRoundtrip" -> row.pointer;
                                        case "pointerArrayRoundtrip" -> row.array;
                                        case "pointerOrder" -> row.order;
                                        case "char8Roundtrip" -> row.char8;
                                        case "byte8Roundtrip" -> row.byte8;
                                        case "halfwordReadRoundtrip" -> row.halfwordRead;
                                        default -> row.halfwordWrite;
                                    };
                                }
                            }
                            var check = new Check();
                            for (var row : rows) check.run(row.input, check.expected(row));
                            // EntryValue compiles the active DirectCallNode target (which may
                            // be a split clone) and the public host root, checking both tiers.
                            assertTrue(function.invokeMember("compile").asBoolean(),
                                stage + "/" + backend + "/" + entry + " compilation");
                            valid(program.hostEntryTarget(1),
                                stage + "/" + backend + "/" + entry + " host target after compilation");
                            for (var row : rows.reversed()) {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                check.run(row.input, check.expected(row));
                                long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                if (after == before) {
                                    // A dependency can retire the public call-boundary stub;
                                    // require a bounded fresh last-tier execution for this row.
                                    assertTrue(function.invokeMember("compile").asBoolean(),
                                        stage + "/" + backend + "/" + entry + " recompilation");
                                    check.run(row.input, check.expected(row));
                                    after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                }
                                assertTrue(after > before,
                                    stage + "/" + backend + "/" + entry + "/" + row.input + ": " + before + "->"
                                        + after);
                            }
                        }
                        String entry = "wideStoreByte";
                        var source = plus(CoreModules.reachable(merged, entry), "instrument", true);
                        var program = program(language, source, backend);
                        var function = context.asValue(new EntryValue(program, entry, 2));
                        class Wide {
                            void check(Row row, boolean reverse) {
                                for (int selector = reverse ? 39 : 0; reverse ? selector >= 0 : selector <= 39;
                                    selector += reverse ? -1 : 1)
                                    assertEquals(row.wideBytes.get(selector).longValue(),
                                        function.execute(row.input, selector).asLong(),
                                        stage + "/" + backend + "/" + entry + "/" + row.input + "/" + selector);
                                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                            }
                        }
                        var wide = new Wide();
                        for (var row : rows) wide.check(row, false);
                        assertTrue(function.invokeMember("compile").asBoolean(),
                            stage + "/" + backend + "/" + entry + " compilation");
                        valid(program.hostEntryTarget(2),
                            stage + "/" + backend + "/" + entry + " host target after compilation");
                        for (var row : rows.reversed()) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            wide.check(row, true);
                            long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            if (after == before) {
                                assertTrue(function.invokeMember("compile").asBoolean(),
                                    stage + "/" + backend + "/" + entry + " recompilation");
                                wide.check(row, true);
                                after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            }
                            assertTrue(after > before,
                                stage + "/" + backend + "/" + entry + "/" + row.input + ": " + before + "->" + after);
                        }
                        String readEntry = "wideReadSelector";
                        var readSource = plus(CoreModules.reachable(merged, readEntry), "instrument", true);
                        var readProgram = program(language, readSource, backend);
                        var readFunction = context.asValue(new EntryValue(readProgram, readEntry, 2));
                        class Read {
                            void check(Row row, boolean reverse) {
                                for (int selector = reverse ? 7 : 0; reverse ? selector >= 0 : selector <= 7;
                                    selector += reverse ? -1 : 1)
                                    assertEquals(row.wideReads.get(selector).longValue(),
                                        readFunction.execute(row.input, selector).asLong(),
                                        stage + "/" + backend + "/" + readEntry + "/" + row.input + "/" + selector);
                                assertEquals(
                                    0L, ((Number) readProgram.diagnostics().get("unsupportedTraps")).longValue());
                            }
                        }
                        var read = new Read();
                        for (var row : rows) read.check(row, false);
                        assertTrue(readFunction.invokeMember("compile").asBoolean(),
                            stage + "/" + backend + "/" + readEntry + " compilation");
                        valid(readProgram.hostEntryTarget(2),
                            stage + "/" + backend + "/" + readEntry + " host target after compilation");
                        for (var row : rows.reversed()) {
                            long before = ((Number) readProgram.diagnostics().get("compiledEntries")).longValue();
                            read.check(row, true);
                            long after = ((Number) readProgram.diagnostics().get("compiledEntries")).longValue();
                            if (after == before) {
                                assertTrue(readFunction.invokeMember("compile").asBoolean(),
                                    stage + "/" + backend + "/" + readEntry + " recompilation");
                                read.check(row, true);
                                after = ((Number) readProgram.diagnostics().get("compiledEntries")).longValue();
                            }
                            assertTrue(after > before,
                                stage + "/" + backend + "/" + readEntry + "/" + row.input + ": " + before + "->"
                                    + after);
                        }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
}
