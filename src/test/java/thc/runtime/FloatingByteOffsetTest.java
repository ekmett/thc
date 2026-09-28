// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
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
import static thc.runtime.ByteArrayOp.expression;

class FloatingByteOffsetTest {
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
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
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
        targets.add(target);
    }
    @Test
    void unalignedStorageBoundsPointerCellsAndStateBeforeMutation() {
        var bytes = new byte[32];
        long floatBits = 0x7fc12345L, doubleBits = 0x7ff8000000001234L;
        ManagedByteArray.writeFloatByteOffsetGuest(bytes, 1, Float.intBitsToFloat((int) floatBits));
        ManagedByteArray.writeDoubleByteOffsetGuest(bytes, 9, Double.longBitsToDouble(doubleBits));
        assertEquals(floatBits,
            Integer.toUnsignedLong(Float.floatToRawIntBits(ManagedByteArray.readFloatByteOffsetGuest(bytes, 1))));
        assertEquals(doubleBits, Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(bytes, 9)));
        var model = ByteBuffer.allocate(32)
                        .order(ByteOrder.nativeOrder())
                        .putInt(1, (int) floatBits)
                        .putLong(9, doubleBits)
                        .array();
        assertArrayEquals(model, bytes);
        for (long offset : new long[] {-1L, 29L, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readFloatByteOffsetGuest(bytes, offset));
        for (long offset : new long[] {-1L, 25L, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeDoubleByteOffsetGuest(bytes, offset, 1.0));
        var edge = new byte[12];
        ManagedByteArray.writeFloatByteOffsetGuest(edge, 8, -0.0f);
        assertEquals(0x80000000L,
            Integer.toUnsignedLong(Float.floatToRawIntBits(ManagedByteArray.readFloatByteOffsetGuest(edge, 8))));
        ManagedByteArray.writeDoubleByteOffsetGuest(edge, 4, -0.0);
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(edge, 4)));
        var owner = ManagedAllocation.mutable(32, 8);
        var target = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
        owner.writeAddressByteOffset(16, target);
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.readDoubleByteOffsetGuest(owner, 12));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeFloatByteOffsetGuest(owner, 19, 1.0f));
        assertSame(target, owner.readAddressByteOffset(16));
        ManagedByteArray.writeDoubleByteOffsetGuest(owner, 16, -0.0);
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(owner, 16)));
        assertThrows(RuntimeFault.class, () -> owner.readAddressByteOffset(16));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var write = expression(ByteArrayOp.WRITE_WORD8_AS_FLOAT, CoreRepresentation.UNKNOWN,
            new Expr[] {operand(bytes), operand(1L), operand(2.0f), operand("invalid state")});
        assertThrows(RuntimeFault.class, () -> write.execute(frame));
        assertThrows(
            RuntimeFault.class, () -> BytecodeRoot.WriteDoubleArray.write(true, bytes, 9L, 2.0, "invalid state"));
        assertArrayEquals(model, bytes);
    }
    private CoreRepresentation proof(CoreKind kind, String rep) {
        return new CoreRepresentation(kind, false, false, List.of(rep), null, null, null, null, null);
    }
    @Test
    void primitiveMetadataRejectsWrongOffsetsRepsAndFlags() {
        var array = proof(CoreKind.OBJECT, "BoxedRep (Just Unlifted)");
        var offset = proof(CoreKind.LONG, "IntRep");
        var wrongOffset = proof(CoreKind.DOUBLE, "DoubleRep");
        var floating = proof(CoreKind.FLOAT, "FloatRep");
        var doubleValue = proof(CoreKind.DOUBLE, "DoubleRep");
        ByteArrayOp.INDEX_WORD8_AS_FLOAT.validate(List.of(array, offset), List.of(false, false), floating);
        ByteArrayOp.INDEX_WORD8_AS_DOUBLE.validate(List.of(array, offset), List.of(false, false), doubleValue);
        assertThrows(RuntimeFault.class,
            ()
                -> ByteArrayOp.INDEX_WORD8_AS_FLOAT.validate(
                    List.of(array, wrongOffset), List.of(false, false), floating));
        assertThrows(RuntimeFault.class,
            () -> ByteArrayOp.INDEX_WORD8_AS_DOUBLE.validate(List.of(array, offset), List.of(false, false), floating));
        assertThrows(RuntimeFault.class,
            () -> ByteArrayOp.INDEX_WORD8_AS_FLOAT.validate(List.of(array, offset), List.of(false, true), floating));
    }
    private void check(Row row, boolean reverse, Value function, ExecutableProgram program, String label) {
        for (int i = 0; i < 4; i++) {
            int selector = reverse ? 3 - i : i;
            assertEquals(row.actual().get(selector).longValue(),
                function.execute(row.floatBits(), row.doubleBits(), selector).asLong(), label + "/" + selector);
        }
        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
    }
    @SuppressWarnings("unchecked")
    @Test
    void originalGhcFloatingByteOffsetCompositeMatchesNativeInBothBackends() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/floating-byte-offset/manifest.json").toPath()));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(new File(root, item.getKey()).toPath())));
                assertEquals(item.getValue(), digest, "Stale floating byte-offset " + kind + ": " + item.getKey());
            }
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(root, "build/floating-byte-offset/oracle.tsv").toPath())) {
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
                            .putInt(1, (int) row.floatBits())
                            .putLong(9, row.doubleBits());
            assertEquals(List.of(Integer.toUnsignedLong(model.getInt(1)), Integer.toUnsignedLong(model.getInt(1)),
                             model.getLong(9), model.getLong(9)),
                row.actual());
        }
        for (var stage : List.of("pre", "post")) {
            var directory = new File(root, "build/floating-byte-offset/" + stage);
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "audit.json").toPath()));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var required = Set.of("indexWord8ArrayAsFloat#", "readWord8ArrayAsFloat#", "writeWord8ArrayAsFloat#",
                "indexWord8ArrayAsDouble#", "readWord8ArrayAsDouble#", "writeWord8ArrayAsDouble#");
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
                assertEquals(
                    Set.of("main:FloatingByteOffsetAudit.floatingByteOffsetBits"), owners, stage + "/" + primitive);
            }
            var module = (Map<String, Object>) Json.parse(
                Files.readString(new File(directory, "core/FloatingByteOffsetAudit.json").toPath()));
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var entry = "floatingByteOffsetBits";
                        var source = new LinkedHashMap<>(CoreModules.reachable(module, entry));
                        source.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source)
                                                                          : new BytecodeProgram(language, source);
                        var function = context.asValue(new EntryValue(program, entry, 3));
                        var guest = program.entryTarget(entry);
                        var host = program.hostEntryTarget(3);
                        var stageBackend = stage + "/" + backend;
                        for (var row : rows) check(row, false, function, program, stageBackend);
                        var targets = activeTargets(host);
                        assertSame(host, targets.getLast(), stageBackend + " host root");
                        assertTrue(targets.size() > 1, stageBackend + " reachable guest roots");
                        int guestCount = targets.size() - 1;
                        for (int i = 0; i < targets.size() - 1; i++) {
                            var target = targets.get(i);
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            valid(target, stageBackend + " guest installation");
                        }
                        assertTrue(function.invokeMember("compile").asBoolean(), stageBackend);
                        valid(host, stageBackend + "/host installation");
                        for (int i = rows.size() - 1; i >= 0; i--) {
                            var row = rows.get(i);
                            for (int selector = 3; selector >= 0; selector--) {
                                var label =
                                    stageBackend + "/" + selector + "/" + row.floatBits() + "/" + row.doubleBits();
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(row.actual().get(selector).longValue(),
                                    function.execute(row.floatBits(), row.doubleBits(), selector).asLong(), label);
                                long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(before + guestCount, after, label + " exact compiled guest entries");
                                assertSame(guest, program.entryTarget(entry), label + " guest identity");
                                assertSame(host, program.hostEntryTarget(3), label + " host identity");
                                var active = activeTargets(host);
                                assertEquals(targets.size(), active.size(), label + " active root count");
                                boolean same = true;
                                for (int j = 0; j < Math.min(targets.size(), active.size()); j++)
                                    same &= targets.get(j) == active.get(j);
                                assertTrue(same, label + " active root identities");
                                for (var target : targets) valid(target, label + " active root valid");
                            }
                        }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
}
