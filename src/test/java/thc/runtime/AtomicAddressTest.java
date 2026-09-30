// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.frame.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SuppressWarnings("unchecked")
public class AtomicAddressTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<AtomicAddressOp> numeric = List.of(AtomicAddressOp.READ, AtomicAddressOp.WRITE,
        AtomicAddressOp.EXCHANGE, AtomicAddressOp.CAS, AtomicAddressOp.CAS8, AtomicAddressOp.CAS16,
        AtomicAddressOp.CAS32, AtomicAddressOp.CAS64, AtomicAddressOp.ADD, AtomicAddressOp.SUB, AtomicAddressOp.AND,
        AtomicAddressOp.NAND, AtomicAddressOp.OR, AtomicAddressOp.XOR);
    private final long prefix = 0x0123456789abcdefL, suffix = 0xfedcba9876543210L;
    private record Row(String kind, int operation, long initial, long operand, long desired, List<Long> answers) {}
    private Context context(boolean nativeAccess, boolean inline) {
        return Context.newBuilder("thc")
            .allowNativeAccess(nativeAccess)
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inline))
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private static <T> T single(List<T> values) {
        if (values.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return values.getFirst();
    }
    private Map<String, Object> json(Path path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(path));
    }
    private void stateLambda(List<Object> lambda, Map<String, Object> voidRep) {
        assertEquals("lam", lambda.get(0));
        var formal = single((List<Map<String, Object>>) lambda.get(1));
        assertEquals("State# RealWorld", formal.get("type"));
        assertEquals(voidRep, formal.get("rep"));
        assertEquals(false, formal.get("lifted"));
    }
    /** Derive executed roots from the original Core and its checked State# redex. */
    private Set<String> originalGuestLabels(Map<String, Object> module, String name) {
        var evidence = new ArrayCoreEvidence(module, "main:AtomicAddressAudit." + name);
        assertEquals(1, evidence.getBindings().size(), name + " closed original binding");
        assertEquals(List.of(), evidence.globalReferences(evidence.getRoot().get("expr")));
        var outer = (List<Object>) evidence.getRoot().get("expr");
        assertEquals("lam", outer.get(0));
        assertEquals(5, ((List<?>) outer.get(1)).size());
        var stateCall = (List<Object>) outer.get(2);
        assertEquals("app", stateCall.get(0));
        var state = (List<Object>) stateCall.get(1);
        Map<String, Object> voidRep = Map.of("primReps", List.of(), "kind", "void", "evaluated", true);
        stateLambda(state, voidRep);
        var argument = single((List<List<Object>>) stateCall.get(2));
        assertEquals("void", argument.get(0));assertEquals(voidRep,((Map<?,?>)argument.getLast()).get("rep"));
        assertEquals(List.of(List.of(false), false, false), stateCall.subList(3, 6));
        var allocation = (List<Object>) state.get(2);
        assertEquals("case", allocation.get(0));
        var allocate = (List<Object>) allocation.get(1);
        assertEquals(List.of("prim", "newPinnedByteArray#"), ((List<?>) allocate.get(1)).subList(0, 2));
        var allocated = single((List<List<Object>>) allocation.get(3));
        var resultCase = (List<Object>) allocated.get(3);
        assertEquals("case", resultCase.get(0));
        var keepAlive = (List<Object>) resultCase.get(1);
        assertEquals("app", keepAlive.get(0));
        assertEquals(List.of("prim", "keepAlive#"), ((List<?>) keepAlive.get(1)).subList(0, 2));
        var action = ((List<List<Object>>) keepAlive.get(2)).get(2);
        stateLambda(action, voidRep);
        var nodes = evidence.nodes(outer);
        var joins = nodes.stream()
                        .filter(it -> !it.isEmpty() && "let".equals(it.get(0)))
                        .flatMap(it -> ((List<Map<String, Object>>) it.get(2)).stream())
                        .filter(it -> it.containsKey("joinValueArity"))
                        .toList();
        var join = single(joins);
        assertEquals(2L, ((Number) join.get("joinValueArity")).longValue());
        var joinLambda = (List<Object>) join.get("expr");
        assertEquals("lam", joinLambda.get(0));
        assertEquals(2, ((List<?>) joinLambda.get(1)).size());
        var joinResult = (Map<String, Object>) join.get("joinResultRep");
        assertEquals("unboxed-tuple", joinResult.get("aggregate"));
        var components = (List<Map<String, Object>>) joinResult.get("components");
        assertEquals(2, components.size());
        assertEquals(voidRep, components.get(0));
        assertEquals("long", components.get(1).get("kind"));assertEquals(joinResult,((Map<?,?>)joinLambda.getLast()).get("resultRep"));
        // A saturated local join is control flow, not a fourth guest root.
        var lambdas = nodes.stream().filter(it -> !it.isEmpty() && "lam".equals(it.get(0))).toList();
        assertEquals(4, lambdas.size());
        assertTrue(lambdas.get(0) == outer && lambdas.get(1) == state && lambdas.get(2) == action
            && lambdas.get(3) == joinLambda);
        // The checked immediate runRW State# application lowers in-frame;
        // the keepAlive argument remains a guest call, and the join is control flow.
        assertSame(state, evidence.immediateStateLambda(stateCall));
        var lowered = lambdas.stream().filter(it -> it != state && it != joinLambda).toList();
        assertEquals(2, lowered.size(), name + " lowered guest-root inventory");
        assertSame(outer, lowered.get(0));
        assertSame(action, lowered.get(1));
        return lowered.stream().map(lambda -> "lambda " + ((List<Map<String, Object>>) lambda.get(1))
            .stream().map(formal -> String.valueOf(formal.get("name"))).collect(Collectors.joining(", ")))
            .collect(Collectors.toSet());
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
        var node = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(node);
        if (node instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var cached = argument.asCachedNode();
                        if (cached != null)
                            nodes.add(cached);
                    }
        for (var child : nodes)
            for (var call : NodeUtil.findAllNodeInstances(child, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, targets);
        targets.add(target);
    }
    /** Independent byte-buffer model; never calls the runtime operation enum. */
    private List<Long> model(Row row) {
        if (row.kind.equals("pointer"))
            return List.of(row.initial, row.operation == 0 || row.initial == row.operand ? row.desired : row.initial);
        int width = switch (row.operation) {
            case 4 -> 1;
            case 5 -> 2;
            case 6 -> 4;
            default -> 8;
        };
        var memory = ByteBuffer.allocate(24)
                         .order(ByteOrder.nativeOrder())
                         .putLong(0, prefix)
                         .putLong(8, row.initial)
                         .putLong(16, suffix);
        long mask = width == 8 ? -1L : (1L << (width * 8)) - 1;
        long old = switch (width) {
            case 1 -> memory.get(8) & mask;
            case 2 -> memory.getShort(8) & mask;
            case 4 -> memory.getInt(8) & mask;
            default -> memory.getLong(8);
        };
        long next = switch (row.operation) {
            case 0 -> old;
            case 1, 2 -> row.operand;
            case 3, 4, 5, 6, 7 -> old == (row.operand & mask) ? row.desired& mask : old;
            case 8 -> old + row.operand;
            case 9 -> old - row.operand;
            case 10 -> old & row.operand;
            case 11 -> ~(old & row.operand);
            case 12 -> old | row.operand;
            case 13 -> old ^ row.operand;
            default -> throw new IllegalStateException("Unknown model operation");
        };
        switch (width) {
            case 1 -> memory.put(8, (byte) next);
            case 2 -> memory.putShort(8, (short) next);
            case 4 -> memory.putInt(8, (int) next);
            default -> memory.putLong(8, next);
        }
        return List.of(row.operation == 1 ? 0L : old, memory.getLong(8), prefix, suffix);
    }
    private List<Row> rows(String text) {
        var result = new ArrayList<Row>();
        for (String line : text.split("\\R", -1)) {
            if (line.isEmpty())
                continue;
            var f = line.split("\t", -1);
            if (!Set.of("numeric", "pointer").contains(f[0]))
                throw new IllegalArgumentException("Failed requirement.");
            if (f.length != (f[0].equals("numeric") ? 9 : 7))
                throw new IllegalArgumentException("Failed requirement.");
            java.util.function.Function<String, Long> number =
                f[0].equals("numeric") ? Long::parseUnsignedLong : Long::parseLong;
            var row = new Row(f[0], Integer.parseInt(f[1]), number.apply(f[2]), number.apply(f[3]), number.apply(f[4]),
                Arrays.stream(f).skip(5).map(number).toList());
            if (!row.answers.equals(model(row)))
                throw new IllegalArgumentException("Native/model atomic mismatch: " + line);
            result.add(row);
        }
        if (result.size() != 108)
            throw new IllegalArgumentException("Failed requirement.");
        var numericCounts = result.stream()
                                .filter(it -> it.kind.equals("numeric"))
                                .collect(Collectors.groupingBy(Row::operation, Collectors.counting()));
        var pointerCounts = result.stream()
                                .filter(it -> it.kind.equals("pointer"))
                                .collect(Collectors.groupingBy(Row::operation, Collectors.counting()));
        var expectedNumeric = new HashMap<Integer, Long>();
        for (int i = 0; i <= 13; i++) expectedNumeric.put(i, 7L);
        if (!numericCounts.equals(expectedNumeric) || !pointerCounts.equals(Map.of(0, 5L, 1, 5L)))
            throw new IllegalArgumentException("Failed requirement.");
        return result;
    }
    private List<Object> callCounts(List<RootCallTarget> targets) throws Exception {
        var result = new ArrayList<Object>();
        for (var target : targets) result.add(target.getClass().getMethod("getCallCount").invoke(target));
        return result;
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
    @Test
    public void nativeOracleMatchesIndependentModelAndBothFirstCompiledEntries() throws Exception {
        var directory = root.resolve("build/atomic-address");
        var manifest = json(directory.resolve("manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        for (String kind : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), actual, "Stale atomic-address " + kind + ": " + hash.getKey());
            }
        String text = Files.readString(directory.resolve("oracle.tsv"));
        var corpus = rows(text);
        assertThrows(IllegalArgumentException.class,
            () -> rows(Arrays.stream(text.split("\\R", -1)).skip(1).collect(Collectors.joining("\n"))));
        assertThrows(IllegalArgumentException.class, () -> rows(text.replaceFirst("\t81985529216486895\t", "\t1\t")));
        for (String stage : List.of("pre", "post")) {
            var audit = json(directory.resolve(stage + "/audit.json"));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitives = (List<Map<String, Object>>) audit.get("primitives");
            for (var operation : AtomicAddressOp.values()) {
                var evidence =
                    single(primitives.stream().filter(it -> operation.getPrimitive().equals(it.get("name"))).toList());
                var owners = ((List<Map<String, Object>>) evidence.get("uses"))
                                 .stream()
                                 .map(it -> it.get("owner"))
                                 .collect(Collectors.toSet());
                String name = operation.getPointer() ? "atomicAddressPointer" : "atomicAddressNumeric";
                assertEquals(
                    Set.of("main:AtomicAddressAudit." + name, "main:AtomicAddressAudit." + name + "At"), owners);
            }
            var module = thc.CoreCbdFixtures.read(directory.resolve(stage + "/core/AtomicAddressAudit.cbd"));
            var expectedLabels = new LinkedHashMap<String, Set<String>>();
            for (String name : List.of("atomicAddressNumeric", "atomicAddressPointer"))
                expectedLabels.put(name, originalGuestLabels(module, name));
            for (String backend : List.of("ast", "bytecode"))
                for (boolean inline : new boolean[] {false, true}) try (var context = context(false, inline)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = new LinkedHashMap<>(
                                CoreModules.reachable(module, List.of("atomicAddressNumeric", "atomicAddressPointer").stream().map(name -> "main:AtomicAddressAudit." + name).toList()));
                            linked.put("instrument", true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked)
                                                                              : new BytecodeProgram(language, linked);
                            var targets = new LinkedHashMap<String, RootCallTarget>();
                            for (String name : List.of("atomicAddressNumeric", "atomicAddressPointer"))
                                targets.put(name, program.entryTarget("main:AtomicAddressAudit." + name));
                            var handoff = language.getHandoffState().get();
                            class Check {
                                boolean compiled = false;
                                Map<String, List<RootCallTarget>> compiledTargets = Map.of();
                                long argumentAllocations = 0, resultAllocations = 0;
                                long count() {
                                    return ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                }
                                void run(Row row) throws Exception {
                                    String entry =
                                        row.kind.equals("numeric") ? "atomicAddressNumeric" : "atomicAddressPointer";
                                    var target = targets.get(entry);
                                    for (int selector = 0; selector < row.answers.size(); selector++) {
                                        long before = count();
                                        String label =
                                            stage + "/" + backend + "/" + inline + "/" + entry + "/" + selector;
                                        try {
                                            var actual = Calls.target(target,
                                                new Object[] {0L, (long) row.operation, row.initial, row.operand,
                                                    row.desired, (long) selector});
                                            assertEquals(row.answers.get(selector), actual, label + "/" + row);
                                            if (compiled) {
                                                assertEquals((long) expectedLabels.get(entry).size(), count() - before,
                                                    "Exact original guest entries, including first installed call: "
                                                        + label);
                                                assertEquals(compiledTargets.get(entry), activeTargets(target),
                                                    label + " target identities");
                                                for (var active : compiledTargets.get(entry))
                                                    assertEquals(true,
                                                        active.getClass().getMethod("isValidLastTier").invoke(active),
                                                        label);
                                                assertEquals(argumentAllocations,
                                                    handoff.getArguments().getAllocations(), label);
                                                assertEquals(
                                                    resultAllocations, handoff.getResults().getAllocations(), label);
                                            }
                                        } finally {
                                            assertEquals(0, handoff.getArguments().getDepth(), label);
                                            assertEquals(0, handoff.getResults().getDepth(), label);
                                            assertEquals(0, handoff.getArguments().retainedReferences(), label);
                                            assertEquals(0, handoff.getResults().retainedReferences(), label);
                                            assertNull(handoff.getPending(), label);
                                        }
                                    }
                                }
                            }
                            var check = new Check();
                            for (var row : corpus) check.run(row);
                            var compiledTargets = new LinkedHashMap<String, List<RootCallTarget>>();
                            for (var entry : targets.entrySet()) {
                                var active = activeTargets(entry.getValue());
                                // A prepublished join-body recovery target is not an
                                // additional Core lambda on the original inline path.
                                var originals = active.stream().map(t -> t.getRootNode().getName())
                                    .filter(name -> name.startsWith("lambda ")).toList();
                                assertEquals(expectedLabels.get(entry.getKey()), new HashSet<>(originals),
                                    stage + "/" + backend + "/" + entry.getKey() + " original guest roots");
                                assertEquals(expectedLabels.get(entry.getKey()).size(), originals.size());
                                for (var target : active)
                                    if (!target.getRootNode().getName().startsWith("lambda ")) {
                                        assertEquals("bytecode", backend);
                                        assertTrue(target.getRootNode().getName().startsWith("join body "));
                                    }
                                compiledTargets.put(entry.getKey(), active);
                            }
                            check.compiledTargets = compiledTargets;
                            var allTargets = compiledTargets.values().stream().flatMap(List::stream).toList();
                            long beforeSetup = check.count();
                            assertEquals(0L, beforeSetup);
                            var beforeCalls = callCounts(allTargets);
                            check.argumentAllocations = handoff.getArguments().getAllocations();
                            check.resultAllocations = handoff.getResults().getAllocations();
                            var runtime = Truffle.getRuntime();
                            var targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                            for (var target : allTargets) {
                                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target),
                                    stage + "/" + backend + "/" + inline + "/" + target.getRootNode().getName()
                                        + " installation");
                                runtime.getClass()
                                    .getMethod("bypassedInstalledCode", targetType)
                                    .invoke(runtime, target);
                            }
                            assertEquals(beforeSetup, check.count(), "Compilation must not enter guest code");
                            assertEquals(beforeCalls, callCounts(allTargets));
                            check.compiled = true;
                            for (var row : corpus.reversed()) check.run(row);
                            assertEquals(
                                beforeCalls, callCounts(allTargets), "No interpreted guest entries after installation");
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                        } finally {
                            context.leave();
                        }
                    }
        }
    }
    private void exerciseStorage(ManagedAddress base) {
        for (int op = 0; op < numeric.size(); op++)
            for (long[] args :
                new long[][] {{-1, -1, 0}, {0x123456789abcdef0L, 0x123456789abcdef0L, -1}, {42, 99, 17}}) {
                long x = args[0], y = args[1], z = args[2];
                base.writeNativeScalar(0, 8, prefix);
                base.writeNativeScalar(1, 8, x);
                base.writeNativeScalar(2, 8, suffix);
                var row = new Row("numeric", op, x, y, z, List.of());
                long result = numeric.get(op).numeric(base.plus(8), y, z);
                var expected = model(row);
                if (op != 1)
                    assertEquals(expected.get(0).longValue(), result);
                assertEquals(expected.get(1).longValue(), ManagedAddressRead.WORD.read(base, 1));
                assertEquals(prefix, ManagedAddressRead.WORD.read(base, 0));
                assertEquals(suffix, ManagedAddressRead.WORD.read(base, 2));
            }
        for (var operation : numeric) {
            assertThrows(RuntimeFault.class, () -> operation.numeric(base.plus(24), 0, 0));
            if (operation.getWidth() > 1)
                assertThrows(RuntimeFault.class, () -> operation.numeric(base.plus(1), 0, 0));
        }
    }
    @Test
    public void originalAddressConsumersKeepCompiledNativeManagedAndRawPaths() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        for (String stage : List.of("pre", "post"))
            for (String backend : List.of("ast", "bytecode")) try (var context = context(true, false)) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var module =
                            thc.CoreCbdFixtures.read(root.resolve("build/atomic-address/" + stage + "/core/AtomicAddressAudit.cbd"));
                        var linked = new LinkedHashMap<>(
                            CoreModules.reachable(module, List.of("atomicAddressNumericAt", "atomicAddressPointerAt").stream().map(name -> "main:AtomicAddressAudit." + name).toList()));
                        linked.put("instrument", true);
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked)
                                                                          : new BytecodeProgram(language, linked);
                        var targets = new LinkedHashMap<String, RootCallTarget>();
                        for (String name : List.of("atomicAddressNumericAt", "atomicAddressPointerAt"))
                            targets.put(name, program.entryTarget("main:AtomicAddressAudit." + name));
                        var registry = ManagedNativeAllocations.current(null);
                        var nativeAddress = registry.malloc(24);
                        var managed = ManagedAddress.fromAllocation(PinnedMemory.allocate(24, 8));
                        var raw = ManagedAddress.fromByteArray(new byte[24]);
                        class Exercise {
                            boolean compiled = false;
                            Object call(String entry, Object... arguments) throws Exception {
                                var target = targets.get(entry);
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                var args = new Object[arguments.length + 1];
                                args[0] = 0L;
                                System.arraycopy(arguments, 0, args, 1, arguments.length);
                                Object answer;
                                try { answer = AtomicAddressTest.this.call(target, args); }
                                finally {
                                    var handoff = language.getHandoffState().get();
                                    assertEquals(0, handoff.getArguments().getDepth());
                                    assertEquals(0, handoff.getResults().getDepth());
                                    assertEquals(0, handoff.getArguments().retainedReferences());
                                    assertEquals(0, handoff.getResults().retainedReferences());
                                    assertNull(handoff.getPending());
                                }
                                if (compiled) {
                                    assertEquals(before + 1,
                                        ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                                        stage + "/" + backend + "/" + entry + " exact installed entry");
                                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                                }
                                return answer;
                            }
                            void run() throws Exception {
                                for (var base : List.of(managed, raw, nativeAddress))
                                    for (int op = 0; op < numeric.size(); op++)
                                        for (long[] args : new long[][] {{-1, -1, 7}, {23, 17, 99}}) {
                                            long initial = args[0], operand = args[1], desired = args[2];
                                            base.writeNativeScalar(0, 8, prefix);
                                            base.writeNativeScalar(1, 8, initial);
                                            base.writeNativeScalar(2, 8, suffix);
                                            var expected =
                                                model(new Row("numeric", op, initial, operand, desired, List.of()));
                                            assertEquals(expected.get(0),
                                                call("atomicAddressNumericAt", base.plus(8), (long) op, operand,
                                                    desired));
                                            assertEquals(
                                                expected.get(1).longValue(), ManagedAddressRead.WORD.read(base, 1));
                                            assertEquals(prefix, ManagedAddressRead.WORD.read(base, 0));
                                            assertEquals(suffix, ManagedAddressRead.WORD.read(base, 2));
                                        }
                                for (var base : List.of(managed, nativeAddress))
                                    for (long op = 0; op <= 1; op++)
                                        for (boolean success : new boolean[] {false, true}) {
                                            var initial = base.plus(16);
                                            var desired = base.plus(24);
                                            var expected =
                                                success ? base.plus(16) : ManagedAddress.nullAddress();
                                            if (base == nativeAddress)
                                                AtomicAddressOp.WRITE.numeric(base.plus(8), initial.toNativeBits(), 0);
                                            else
                                                base.writeAddressElementIndex(1, initial);
                                            var old = (ManagedAddress) call(
                                                "atomicAddressPointerAt", base.plus(8), op, expected, desired);
                                            assertTrue(old.sameLocation(initial));
                                            long actual = base == nativeAddress
                                                ? AtomicAddressOp.READ.numeric(base.plus(8), 0, 0)
                                                : 0;
                                            var finalAddress = op == 0 || success ? desired : initial;
                                            if (base == nativeAddress)
                                                assertEquals(finalAddress.toNativeBits(), actual);
                                            else
                                                assertTrue(base.readAddressElementIndex(1).sameLocation(finalAddress));
                                        }
                                // Erase the entire pointer cell before the next numeric pass.
                                managed.writeNativeScalar(1, 8, 0);
                            }
                        }
                        var exercise = new Exercise();
                        try {
                            for (int i = 0; i < 2; i++) exercise.run();
                            var runtime = Truffle.getRuntime();
                            var targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                            for (var target : targets.values()) {
                                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                                runtime.getClass()
                                    .getMethod("bypassedInstalledCode", targetType)
                                    .invoke(runtime, target);
                            }
                            exercise.compiled = true;
                            exercise.run();
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                        } finally {
                            registry.free(nativeAddress);
                        }
                    } finally {
                        context.leave();
                    }
                }
    }
    @Test
    public void managedAndRawAliasesHaveExactWidthsSentinelsAndFailureAtomicity() {
        exerciseStorage(ManagedAddress.fromAllocation(PinnedMemory.allocate(24, 8)));
        exerciseStorage(ManagedAddress.fromByteArray(new byte[24]));
        var allocation = PinnedMemory.allocate(24, 8);
        var base = ManagedAddress.fromAllocation(allocation);
        var target = base.plus(24);
        base.writeAddressElementIndex(1, target);
        for (var operation : numeric) assertThrows(RuntimeFault.class, () -> operation.numeric(base.plus(8), 0, 0));
        assertSame(target, base.readAddressElementIndex(1));
        assertSame(target, AtomicAddressOp.CAS_ADDR.address(base.plus(8), base.plus(24), base.plus(16)));
        assertTrue(base.readAddressElementIndex(1).sameLocation(base.plus(16)));
        assertTrue(AtomicAddressOp.CAS_ADDR.address(base.plus(8), base.plus(24), target).sameLocation(base.plus(16)));
        assertTrue(AtomicAddressOp.EXCHANGE_ADDR.address(base.plus(8), ManagedAddress.nullAddress(), null)
                .sameLocation(base.plus(16)));
        assertSame(ManagedAddress.nullAddress(), base.readAddressElementIndex(1));
        var literal = ManagedAddress.fromHex("0102030405060708");
        AtomicAddressOp.READ.numeric(literal, 0, 0);
        for (var operation : numeric)
            if (operation != AtomicAddressOp.READ)
                assertThrows(RuntimeFault.class, () -> operation.numeric(literal, 0, 0));
        for (var bad : List.of(ManagedAddress.nullAddress(),
                 ManagedAddress.unownedNumeric(1234)))
            for (var operation : numeric) assertThrows(RuntimeFault.class, () -> operation.numeric(bad, 0, 0));
        allocation.shrink(8);
        assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(base.plus(8), 0, 0));
    }
    private CoreRepresentation proof(CoreKind kind, List<String> reps, List<CoreRepresentation> components) {
        return new CoreRepresentation(kind, false, false, reps, components, null, null, null, null);
    }
    private Expr literal(Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        };
    }
    @Test
    public void loweredLongCarriersStayValidButShapeAndStateErrorsHaveNoEffects() {
        var address = proof(CoreKind.ADDRESS, List.of("AddrRep"), null);
        var integer = proof(CoreKind.LONG, List.of("Int8Rep"), null);
        var state = proof(CoreKind.VOID, List.of(), null);
        var tuple = proof(CoreKind.UNKNOWN, null, List.of(state, integer));
        AtomicAddressOp.CAS16.validate(List.of(address, integer, integer, state), Collections.nCopies(4, false), tuple);
        for (var bad : List.of(proof(CoreKind.UNKNOWN, null, List.of(integer, state)),
                 proof(CoreKind.UNKNOWN, null, List.of(state, address)), integer))
            assertThrows(RuntimeFault.class,
                ()
                    -> AtomicAddressOp.CAS16.validate(
                        List.of(address, integer, integer, state), Collections.nCopies(4, false), bad));
        assertThrows(RuntimeFault.class,
            ()
                -> AtomicAddressOp.CAS16.validate(
                    List.of(integer, integer, integer, state), Collections.nCopies(4, false), tuple));
        var descriptor = FrameDescriptor.newBuilder();
        descriptor.addSlot(FrameSlotKind.Long, null, null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
        var base = ManagedAddress.fromByteArray(new byte[8]);
        AtomicAddressOp.WRITE.numeric(base, 37, 0);
        int[] stateCalls = {0};
        var invalidState = new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                stateCalls[0]++;
                return 0L;
            }
        };
        var expression = new AtomicAddressExpression(
            AtomicAddressOp.ADD, tuple, new Expr[] {literal(base), literal(5L), invalidState});
        assertThrows(RuntimeFault.class, () -> expression.execute(frame));
        assertEquals(0, stateCalls[0]);
        assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[] {0}, 0));
        assertEquals(1, stateCalls[0]);
        assertEquals(37L, AtomicAddressOp.READ.numeric(base, 0, 0));
    }
    @Test
    public void pointerCasRecognizesRawAliasesInBothDirections() {
        // This case specifically checks owner/JVM-byte-array alias identity.
        var target = ManagedAllocation.mutable(64, 8);
        var owned = ManagedAddress.fromAllocation(target);
        var raw = ManagedAddress.fromByteArray(target.rawBytesIfPointerFree());
        var location = ManagedAddress.fromAllocation(PinnedMemory.allocate(8, 8));
        for (var pair : List.of(List.of(owned.plus(16), raw.plus(16)), List.of(raw.plus(16), owned.plus(16)))) {
            var stored = pair.get(0);
            var expected = pair.get(1);
            assertTrue(stored.sameLocation(expected));
            assertTrue(expected.sameLocation(stored));
            location.writeAddressElementIndex(0, stored);
            assertSame(stored,
                AtomicAddressOp.CAS_ADDR.address(location, expected.plus(8), ManagedAddress.nullAddress()));
            assertSame(stored, location.readAddressElementIndex(0));
            assertSame(
                stored, AtomicAddressOp.CAS_ADDR.address(location, expected, ManagedAddress.nullAddress()));
            assertSame(ManagedAddress.nullAddress(), location.readAddressElementIndex(0));
        }
    }
    private void awaitQueued(AtomicReference<Thread> thread, ReentrantReadWriteLock lifetime, String role) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.get() == null || !lifetime.hasQueuedThread(thread.get())) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException(role + " native lifetime waiter did not queue");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
    @Test
    public void pointerValidationDoesNotHoldCellMonitorBehindQueuedNativeFree() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = context(true, false)) {
            context.initialize("thc");
            context.enter();
            try {
                var registry = ManagedNativeAllocations.current(null);
                for (String role : List.of("old", "expected", "desired")) {
                    var referent = registry.malloc(8);
                    var nativeOwner = Objects.requireNonNull(referent.nativeAllocation());
                    // Observe the existing fair lifetime lock to establish an
                    // actual queued writer/reader, without timing assumptions.
                    var field = nativeOwner.getClass().getDeclaredField("lifetime");
                    field.setAccessible(true);
                    var lifetime = (ReentrantReadWriteLock) field.get(nativeOwner);
                    var cell = PinnedMemory.allocate(8, 8);
                    var location = ManagedAddress.fromAllocation(cell);
                    var old = role.equals("old") ? referent : ManagedAddress.nullAddress();
                    var expected = role.equals("expected") ? referent : ManagedAddress.nullAddress();
                    var desired = role.equals("desired") ? referent : ManagedAddress.nullAddress();
                    location.writeAddressElementIndex(0, old);
                    var pool = Executors.newFixedThreadPool(3);
                    var freeingThread = new AtomicReference<Thread>();
                    var comparingThread = new AtomicReference<Thread>();
                    Future<?> freeing = null, comparing = null;
                    var borrow = nativeOwner.borrow();
                    try {
                        freeing = pool.submit(() -> {
                            context.enter();
                            try {
                                freeingThread.set(Thread.currentThread());
                                registry.free(referent);
                            } finally {
                                context.leave();
                            }
                        });
                        awaitQueued(freeingThread, lifetime, role);
                        comparing = pool.submit(() -> {
                            context.enter();
                            try {
                                comparingThread.set(Thread.currentThread());
                                assertThrows(RuntimeFault.class,
                                    () -> AtomicAddressOp.CAS_ADDR.address(location, expected, desired));
                            } finally {
                                context.leave();
                            }
                        });
                        awaitQueued(comparingThread, lifetime, role);
                        // The old implementation holds cell while blocked on
                        // the fair native lock. A native-to-managed copy would
                        // then deadlock with free. This probe is bounded, and
                        // finally releases the borrow even on regression.
        var available=pool.submit(()->{synchronized(cell){return true;
                    }
                });
                assertTrue(available.get(5, TimeUnit.SECONDS), role);
            } finally {
                borrow.close();
                try {
                    if (freeing != null)
                        freeing.get(10, TimeUnit.SECONDS);
                    if (comparing != null)
                        comparing.get(10, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                }
            }
            assertSame(old, location.readAddressElementIndex(0), "Validation failure must not mutate the cell");
        }
    }
    finally {
        context.leave();
    }
}
}
private void contend(ManagedAddress first, ManagedAddress second) throws Exception {
    contend(first, second, () -> {}, () -> {});
}
private void contend(ManagedAddress first, ManagedAddress second, Runnable enter, Runnable leave) throws Exception {
    enter.run();
    try {
        AtomicAddressOp.WRITE.numeric(first, 0, 0);
    } finally {
        leave.run();
    }
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(4);
    try {
        var jobs = new ArrayList<Future<List<Long>>>();
        for (int t = 0; t < 4; t++) {
            final int thread = t;
            jobs.add(pool.submit(() -> {
                enter.run();
                try {
                    start.await();
                    var alias = thread % 2 == 0 ? first : second;
                    var answers = new ArrayList<Long>();
                    for (int i = 0; i < 500; i++) {
                        if (thread % 2 == 0)
                            answers.add(AtomicAddressOp.ADD.numeric(alias, 1, 0));
                        else {
                            long old = AtomicAddressOp.READ.numeric(alias, 0, 0);
                            while (true) {
                                long observed = AtomicAddressOp.CAS.numeric(alias, old, old + 1);
                                if (observed == old)
                                    break;
                                old = observed;
                            }
                            answers.add(old);
                        }
                    }
                    return answers;
                } finally {
                    leave.run();
                }
            }));
        }
        start.countDown();
        var actual = new ArrayList<Long>();
        for (var job : jobs) actual.addAll(job.get(30, TimeUnit.SECONDS));
        actual.sort(Long::compare);
        assertEquals(LongStream.range(0, 2000).boxed().toList(), actual);
        enter.run();
        try {
            assertEquals(2000L, AtomicAddressOp.READ.numeric(first, 0, 0));
        } finally {
            leave.run();
        }
    } finally {
        pool.shutdownNow();
    }
}
@Test
public void aliasesLinearizeAndPublishAcrossThreads() throws Exception {
    // Native pinned storage has no JVM-array alias; exercise the heap lock pair.
    var owner = ManagedAllocation.mutable(16, 8);
    var owned = ManagedAddress.fromAllocation(owner).plus(8);
    var exposed = ManagedAddress.fromByteArray(owner.rawBytesIfPointerFree()).plus(8);
    contend(owned, exposed);
    byte[] raw = new byte[16];
    contend(ManagedAddress.fromByteArray(raw).plus(8), ManagedAddress.fromByteArray(raw).plus(8));
    int[] data = new int[1];
    AtomicAddressOp.WRITE.numeric(owned, 0, 0);
    var pool = Executors.newSingleThreadExecutor();
    try {
        var reader = pool.submit(() -> {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (AtomicAddressOp.READ.numeric(exposed, 0, 0) == 0) {
                if (System.nanoTime() >= until)
                    throw new IllegalStateException("Atomic publication timed out");
                Thread.onSpinWait();
            }
            return data[0];
        });
        data[0] = 123456;
        AtomicAddressOp.WRITE.numeric(owned, 1, 0);
        assertEquals(123456, reader.get(15, TimeUnit.SECONDS));
    } finally {
        pool.shutdownNow();
    }
}
@Test
public void nativeStorageUsesTrueAtomicsAndRejectsLifetimeAndContextViolations() throws Exception {
    assumeTrue(System.getProperty("os.name").equals("Linux")
        && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
    try (var context = context(true, false)) {
        context.initialize("thc");
        context.enter();
        var registry = ManagedNativeAllocations.current(null);
        var base = registry.malloc(24);
        try {
            exerciseStorage(base);
            // Tiny final elements forbid implementing narrow CAS using a wider load.
            for (int width : new int[] {1, 2}) {
                var operation = width == 1 ? AtomicAddressOp.CAS8 : AtomicAddressOp.CAS16;
                var tail = registry.malloc(width);
                try {
                    for (int i = 0; i < width; i++) tail.writeWord8(i, 255);
                    assertEquals(width == 1 ? 255L : 65535L, operation.numeric(tail, -1, 7));
                    assertEquals(7L, tail.readWord8(0));
                } finally {
                    registry.free(tail);
                }
            }
            AtomicAddressOp.WRITE.numeric(base, base.plus(16).toNativeBits(), 0);
            var old = AtomicAddressOp.CAS_ADDR.address(base, base.plus(16), base.plus(24));
            assertTrue(old.sameLocation(base.plus(16)));
            assertTrue(AtomicAddressOp.EXCHANGE_ADDR.address(base, ManagedAddress.nullAddress(), null)
                    .sameLocation(base.plus(24)));
            assertSame(ManagedAddress.nullAddress(),
                AtomicAddressOp.CAS_ADDR.address(base, ManagedAddress.nullAddress(), base.plus(8)));
            assertThrows(RuntimeFault.class,
                ()
                    -> AtomicAddressOp.EXCHANGE_ADDR.address(
                        base, ManagedAddress.fromByteArray(new byte[8]), null));
            var first = base.plus(8);
            var second = base.plus(8);
            context.leave();
            try {
                contend(first, second, context::enter, context::leave);
            } finally {
                context.enter();
            }
            try (var other = context(true, false)) {
                context.leave();
                other.initialize("thc");
                other.enter();
                try {
                    assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(base, 0, 0));
                } finally {
                    other.leave();
                    context.enter();
                }
            }
            registry.free(base);
            assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(base, 0, 0));
        } finally {
            context.leave();
        }
    }
}
}
