// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import thc.runtime.Unit;
import static org.junit.jupiter.api.Assertions.*;

class FloatingAddressTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private record Row(long floatBits, long doubleBits, List<Long> actual) {}
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private Expr operand(Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        };
    }
    private FloatingAddressExpression node(FloatingAddressOp op, Expr... operands) {
        return new FloatingAddressExpression(op, CoreRepresentation.UNKNOWN, operands, false);
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var targets = new ArrayList<RootCallTarget>();
        visit(entry, Collections.newSetFromMap(new IdentityHashMap<>()), targets);
        return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target)) return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE
                            && argument.asCachedNode() != null) nodes.add(argument.asCachedNode());
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                        && active.getRootNode() instanceof GuestRoot) visit(active, seen, targets);
        targets.add(target);
    }
    private List<Object> callCounts(List<RootCallTarget> targets) throws Exception {
        var counts = new ArrayList<Object>();
        for (var target : targets) counts.add(target.getClass().getMethod("getCallCount").invoke(target));
        return counts;
    }

    @Test
    void wrongTypedSpecializationPreservesActualWidthAndTupleEffects() {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var base = ManagedAddress.fromAllocation(PinnedMemory.allocate(32, 1));
        FloatingAddresses.writeFloat(base, 2, -0.0f);
        FloatingAddresses.writeDouble(base, 2, -0.0);
        var floatIndex = node(FloatingAddressOp.INDEX_FLOAT, operand(base), operand(2L));
        var floatMiss = assertThrows(UnexpectedResultException.class, () -> floatIndex.executeDouble(frame));
        assertTrue(floatMiss.getResult() instanceof Float);
        assertEquals(0x80000000L, Integer.toUnsignedLong(Float.floatToRawIntBits((Float) floatMiss.getResult())));
        var doubleIndex = node(FloatingAddressOp.INDEX_DOUBLE, operand(base), operand(2L));
        var doubleMiss = assertThrows(UnexpectedResultException.class, () -> doubleIndex.executeFloat(frame));
        assertTrue(doubleMiss.getResult() instanceof Double);
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits((Double) doubleMiss.getResult()));
        int[] stateEvaluations = {0};
        var state = new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                stateEvaluations[0]++;
                return Unit.INSTANCE;
            }
        };
        var read = node(FloatingAddressOp.READ_FLOAT, operand(base), operand(2L), state);
        assertThrows(RuntimeFault.class, () -> read.execute(frame));
        assertThrows(RuntimeFault.class, () -> read.executeFloat(frame));
        assertEquals(0, stateEvaluations[0]);
        assertThrows(RuntimeFault.class, () -> floatIndex.executeTuple(frame, new int[] {0}, 0));
        var write = node(FloatingAddressOp.WRITE_FLOAT, operand(base), operand(2L), operand(1.0f), state);
        assertThrows(RuntimeFault.class, () -> write.executeDouble(frame));
        assertEquals(0, stateEvaluations[0]);
        assertEquals(
            0x80000000L, Integer.toUnsignedLong(Float.floatToRawIntBits(FloatingAddresses.readFloat(base, 2))));
    }

    @Test
    void nativeEndianFloatingStoragePreservesBitsAndChecksWholeElement() {
        var base = ManagedAddress.fromAllocation(PinnedMemory.allocate(64, 1));
        var target = base.plus(56);
        base.writeAddressElementIndex(1, target);
        long floatBits = 0x7fc01234L, doubleBits = 0x7ff8000000001234L;
        FloatingAddresses.writeFloat(base, 4, Float.intBitsToFloat((int) floatBits));
        FloatingAddresses.writeDouble(base, 3, Double.longBitsToDouble(doubleBits));
        assertEquals(
            floatBits, Integer.toUnsignedLong(Float.floatToRawIntBits(FloatingAddresses.readFloat(base.plus(20), -1))));
        assertEquals(doubleBits, Double.doubleToRawLongBits(FloatingAddresses.readDouble(base.plus(32), -1)));
        var expected = ByteBuffer.allocate(64)
                           .order(ByteOrder.nativeOrder())
                           .putInt(16, (int) floatBits)
                           .putLong(24, doubleBits)
                           .array();
        for (int index : new int[] {16, 17, 18, 19, 24, 25, 26, 27, 28, 29, 30, 31})
            assertEquals(expected[index] & 255L, base.readWord8(index));
        assertSame(target, base.readAddressElementIndex(1));
        for (long index : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThrows(RuntimeFault.class, () -> FloatingAddresses.writeFloat(base, index, 1f));
            assertThrows(RuntimeFault.class, () -> FloatingAddresses.readDouble(base, index));
        }
        assertThrows(RuntimeFault.class, () -> FloatingAddresses.writeFloat(base.plus(9), 0, 1f));
        assertThrows(RuntimeFault.class, () -> FloatingAddresses.readDouble(base, 1));
        assertSame(target, base.readAddressElementIndex(1));
    }
    private void check(Row row, boolean reverse, int expectedEntries, Value function,
            ExecutableProgram program, String label) {
        for (int i = 0; i < 4; i++) {
            int selector = reverse ? 3 - i : i;
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            assertEquals(new BigInteger(Long.toUnsignedString(row.actual().get(selector))),
                function.execute(new BigInteger(Long.toUnsignedString(row.floatBits())),
                    new BigInteger(Long.toUnsignedString(row.doubleBits())), selector).asBigInteger(),
                label + "/" + selector);
            if (expectedEntries != 0) assertEquals(before + expectedEntries,
                ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                label + "/" + selector + " exact first-installed Core entries");
        }
        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
    }
    @SuppressWarnings("unchecked")
    @Test
    void originalGhcFloatingAddrCompositeMatchesNativeInBothBackends() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/floating-address/manifest.json").toPath()));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(new File(root, item.getKey()).toPath())));
                assertEquals(item.getValue(), digest, "Stale floating Addr " + kind + ": " + item.getKey());
            }
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(root, "build/floating-address/oracle.tsv").toPath())) {
            var fields = line.split("\t", -1);
            assertEquals(6, fields.length);
            var actual = new ArrayList<Long>();
            for (int i = 2; i < fields.length; i++) actual.add(Long.parseUnsignedLong(fields[i]));
            rows.add(new Row(Long.parseLong(fields[0]), Long.parseUnsignedLong(fields[1]), actual));
        }
        assertEquals(10, rows.size());
        for (var row : rows) {
            var model = ByteBuffer.allocate(64)
                            .order(ByteOrder.nativeOrder())
                            .putInt(16, (int) row.floatBits())
                            .putLong(24, row.doubleBits());
            assertEquals(List.of(Integer.toUnsignedLong(model.getInt(16)), Integer.toUnsignedLong(model.getInt(16)),
                             model.getLong(24), model.getLong(24)),
                row.actual());
        }
        for (var stage : List.of("pre", "post")) {
            var directory = new File(root, "build/floating-address/" + stage);
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "audit.json").toPath()));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var required = Set.of("indexFloatOffAddr#", "readFloatOffAddr#", "writeFloatOffAddr#",
                "indexDoubleOffAddr#", "readDoubleOffAddr#", "writeDoubleOffAddr#");
            var primitives = (List<Map<String, Object>>) audit.get("primitives");
            for (var primitive : required) {
                Map<String, Object> evidence = null;
                for (var item : primitives)
                    if (primitive.equals(item.get("name"))) {
                        if (evidence != null)
                            throw new IllegalArgumentException("More than one matching primitive");
                        evidence = item;
                    }
                if (evidence == null)
                    throw new NoSuchElementException("No matching primitive");
                var owners = new HashSet<Object>();
                for (var use : (List<Map<String, Object>>) evidence.get("uses")) owners.add(use.get("owner"));
                assertEquals(Set.of("main:FloatingAddressAudit.floatingAddressBits"), owners, stage + "/" + primitive);
            }
            var module = thc.CoreCbdFixtures.read(new File(directory, "core/FloatingAddressAudit.cbd").toPath());
            var evidence = new ArrayCoreEvidence(module, "main:FloatingAddressAudit.floatingAddressBits");
            assertEquals(1, evidence.getBindings().size());
            var outer = (List<Object>) evidence.getRoot().get("expr");
            var state = evidence.immediateStateLambda(outer.get(2));
            var keepAlive = evidence.nodes(outer).stream().filter(node -> node.size() > 2
                && "app".equals(node.getFirst()) && node.get(1) instanceof List<?> head
                && head.size() > 1 && "prim".equals(head.getFirst()) && "keepAlive#".equals(head.get(1))).toList();
            assertEquals(1, keepAlive.size());
            var action = (List<Object>) ((List<?>) keepAlive.getFirst().get(2)).get(2);
            assertEquals("lam", action.getFirst());
            var formals = (List<Map<String, Object>>) action.get(1);
            assertEquals(1, formals.size());
            assertEquals("State# RealWorld", formals.getFirst().get("type"));
            assertEquals("void", ((Map<?, ?>) formals.getFirst().get("rep")).get("kind"));
            assertEquals(List.of(outer, state, action), evidence.guestLambdas(outer));
            var originals = evidence.loweredGuestLambdas(outer);
            assertEquals(List.of(outer, action), originals,
                "runRW stays in-frame, but the keepAlive callback is a real guest entry");
            var originalLabels = originals.stream().map(lambda -> "lambda " +
                ((List<Map<String, Object>>) lambda.get(1)).stream().map(formal -> String.valueOf(formal.get("name")))
                    .collect(java.util.stream.Collectors.joining(", "))).collect(java.util.stream.Collectors.toSet());
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var entry = "floatingAddressBits";
                        var source = new LinkedHashMap<>(CoreModules.reachable(module, "main:FloatingAddressAudit." + entry));
                        source.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source)
                                                                          : new BytecodeProgram(language, source);
                        var function = context.asValue(new EntryValue(program, "main:FloatingAddressAudit." + entry, 3));
                        var guest = program.entryTarget("main:FloatingAddressAudit." + entry);
                        var host = program.hostEntryTarget(3);
                        var label = stage + "/" + backend;
                        for (var row : rows) check(row, false, 0, function, program, label);
                        var targets = activeTargets(host);
                        assertSame(host, targets.getLast());
                        assertEquals(originalLabels, targets.stream().map(t -> t.getRootNode().getName())
                            .filter(name -> name.startsWith("lambda ")).collect(java.util.stream.Collectors.toSet()));
                        long beforeSetup = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var beforeCalls = callCounts(targets);
                        var runtime = Truffle.getRuntime();
                        var targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                        for (var target : targets) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            runtime.getClass().getMethod("bypassedInstalledCode", targetType).invoke(runtime, target);
                        }
                        assertTrue(function.invokeMember("compile").asBoolean(), label);
                        assertEquals(beforeSetup, ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                            label + " installation must not execute guest code");
                        assertEquals(beforeCalls, callCounts(targets),
                            label + " installation must not execute interpreted code");
                        assertEquals(true, guest.getClass().getMethod("isValidLastTier").invoke(guest));
                        assertEquals(true, host.getClass().getMethod("isValidLastTier").invoke(host));
                        for (int i = rows.size() - 1; i >= 0; i--) {
                            var row = rows.get(i);
                            check(row, true, originals.size(), function, program, label);
                            assertEquals(beforeCalls, callCounts(targets),
                                label + " no interpreted entries after installation");
                            assertSame(guest, program.entryTarget("main:FloatingAddressAudit." + entry));
                            assertSame(host, program.hostEntryTarget(3));
                            assertEquals(true, guest.getClass().getMethod("isValidLastTier").invoke(guest));
                            assertEquals(true, host.getClass().getMethod("isValidLastTier").invoke(host));
                            assertEquals(targets, activeTargets(host), label + " active target identities");
                            for (var target : targets)
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
}
