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
import kotlin.Unit;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ByteArrayOp.expression;

class Int32ByteOffsetTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private record Row(long signed, long unsigned, List<Long> actual) {}
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build(); }
    private Expr operand(Object value) { return new Expr() { @Override public Object execute(VirtualFrame frame) { return value; } }; }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var targets = new ArrayList<RootCallTarget>();
        visit(entry, seen, targets); return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target)) return; var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var node = argument.asCachedNode(); if (node != null) nodes.add(node);
            }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot) visit(active, seen, targets);
        targets.add(target);
    }
    @Test void unalignedSignedUnsignedStorageChecksBoundsPointerCellsAndState() {
        var bytes = new byte[16]; ManagedByteArray.writeInt32ByteOffsetGuest(bytes, 1, -2147483648); ManagedByteArray.writeInt32ByteOffsetGuest(bytes, 9, 0x80000001);
        assertEquals(-2147483648L, (long) ManagedByteArray.readInt32ByteOffsetGuest(bytes, 1, false));
        assertEquals(0x80000001L, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(bytes, 9, true)));
        var model = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putInt(1, -2147483648).putInt(9, 0x80000001).array(); assertArrayEquals(model, bytes);
        var tail = new byte[16]; ManagedByteArray.writeInt32ByteOffsetGuest(tail, 12, -1); assertEquals(4294967295L, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(tail, 12, true)));
        for (long offset : new long[]{-1L, 13L, Long.MAX_VALUE}) {
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt32ByteOffsetGuest(bytes, offset, false));
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32ByteOffsetGuest(bytes, offset, 1));
        }
        var owner = ManagedAllocation.mutable(24, 8); var target = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8));
        owner.writeAddressByteOffset(8, target); ManagedByteArray.writeInt32ByteOffsetGuest(owner, 1, -1);
        assertEquals(-1L, (long) ManagedByteArray.readInt32ByteOffsetGuest(owner, 1, false));
        assertThrows(RuntimeFault.class, () -> Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(owner, 9, true)));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32ByteOffsetGuest(owner, 9, 1)); assertSame(target, owner.readAddressByteOffset(8));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var write = expression(ByteArrayOp.WRITE_WORD8_AS_INT32, CoreRepresentation.UNKNOWN, new Expr[]{operand(bytes), operand(1L), operand(7L), operand("invalid state")});
        assertThrows(RuntimeFault.class, () -> write.execute(frame));
        assertThrows(RuntimeFault.class, () -> BytecodeRoot.WriteInt32Array.write(true, bytes, 9L, 7, "invalid state"));
        assertEquals(-2147483648L, (long) ManagedByteArray.readInt32ByteOffsetGuest(bytes, 1, false)); assertEquals(0x80000001L, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(bytes, 9, true)));
    }
    private CoreRepresentation proof(CoreKind kind, String rep) { return new CoreRepresentation(kind, false, false, List.of(rep), null, null, null, null, null); }
    @Test void metadataRequiresScalarCarriersAndByteOffsets() {
        var array = proof(CoreKind.OBJECT, "BoxedRep (Just Unlifted)"); var offset = proof(CoreKind.LONG, "IntRep"); var wrongOffset = proof(CoreKind.DOUBLE, "DoubleRep");
        var signed = proof(CoreKind.LONG, "Int32Rep"); var unsigned = proof(CoreKind.LONG, "Word32Rep");
        ByteArrayOp.INDEX_WORD8_AS_INT32.validate(List.of(array, offset), List.of(false, false), signed);
        ByteArrayOp.INDEX_WORD8_AS_WORD32.validate(List.of(array, offset), List.of(false, false), unsigned);
        assertThrows(RuntimeFault.class, () -> ByteArrayOp.INDEX_WORD8_AS_INT32.validate(List.of(array, wrongOffset), List.of(false, false), signed));
        assertDoesNotThrow(() -> ByteArrayOp.INDEX_WORD8_AS_WORD32.validate(List.of(array, offset), List.of(false, false), signed));
        assertThrows(RuntimeFault.class, () -> ByteArrayOp.INDEX_WORD8_AS_INT32.validate(List.of(array, offset), List.of(false, true), signed));
    }
    private void check(Row row, boolean reverse, Value function, ExecutableProgram program, String label) {
        for (int i = 0; i < 4; i++) { int selector = reverse ? 3 - i : i;
            assertEquals(row.actual().get(selector).longValue(), function.execute(row.signed(), row.unsigned(), selector).asLong(), label + "/" + selector); }
        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
    }
    @SuppressWarnings("unchecked")
    @Test void originalGhcInt32ByteOffsetCompositeMatchesNativeInBothBackends() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/int32-byte-offset/manifest.json").toPath()));
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), digest, "Stale int32 byte-offset " + kind + ": " + item.getKey());
        }
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(root, "build/int32-byte-offset/oracle.tsv").toPath())) {
            var fields = line.split("\t", -1); assertEquals(6, fields.length); var actual = new ArrayList<Long>();
            for (int i = 2; i < fields.length; i++) actual.add(Long.parseLong(fields[i]));
            rows.add(new Row(Long.parseLong(fields[0]), Long.parseLong(fields[1]), actual));
        }
        assertEquals(10, rows.size());
        for (var row : rows) {
            var model = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder()).putInt(1, (int) row.signed()).putInt(9, (int) row.unsigned());
            assertEquals(List.of((long) model.getInt(1), (long) model.getInt(1), model.getInt(9) & 0xffffffffL, model.getInt(9) & 0xffffffffL), row.actual());
        }
        for (var stage : List.of("pre", "post")) {
            var directory = new File(root, "build/int32-byte-offset/" + stage);
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "audit.json").toPath()));
            assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("missingGlobals"));
            var required = Set.of("indexWord8ArrayAsInt32#", "readWord8ArrayAsInt32#", "writeWord8ArrayAsInt32#", "indexWord8ArrayAsWord32#", "readWord8ArrayAsWord32#", "writeWord8ArrayAsWord32#");
            var primitives = (List<Map<String, Object>>) audit.get("primitives");
            for (var primitive : required) {
                Map<String, Object> evidence = null;
                for (var item : primitives) if (primitive.equals(item.get("name"))) { if (evidence != null) throw new IllegalArgumentException("More than one matching primitive"); evidence = item; }
                if (evidence == null) throw new NoSuchElementException("No matching primitive");
                var owners = new HashSet<Object>(); for (var use : (List<Map<String, Object>>) evidence.get("uses")) owners.add(use.get("owner"));
                assertEquals(Set.of("main:Int32ByteOffsetAudit.int32ByteOffsetValues"), owners, stage + "/" + primitive);
            }
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "core/Int32ByteOffsetAudit.json").toPath()));
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var entry = "int32ByteOffsetValues";
                    var source = new LinkedHashMap<>(CoreModules.reachable(module, entry)); source.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                    var function = context.asValue(new EntryValue(program, entry, 3)); var guest = program.entryTarget(entry); var host = program.hostEntryTarget(3); var stageBackend = stage + "/" + backend;
                    for (var row : rows) check(row, false, function, program, stageBackend);
                    var targets = activeTargets(host); assertSame(host, targets.getLast(), stageBackend + " host root"); assertTrue(targets.size() > 1, stageBackend + " reachable guest roots");
                    int guestCount = targets.size() - 1;
                    for (int i = 0; i < targets.size() - 1; i++) { var target = targets.get(i); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, stageBackend + " guest installation"); }
                    assertTrue(function.invokeMember("compile").asBoolean(), stageBackend); valid(host, stageBackend + "/host installation");
                    for (int i = rows.size() - 1; i >= 0; i--) { var row = rows.get(i);
                        for (int selector = 3; selector >= 0; selector--) {
                            var label = stageBackend + "/" + selector + "/" + row.signed() + "/" + row.unsigned(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.actual().get(selector).longValue(), function.execute(row.signed(), row.unsigned(), selector).asLong(), label);
                            long after = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(before + guestCount, after, label + " exact compiled guest entries");
                            assertSame(guest, program.entryTarget(entry), label + " guest identity"); assertSame(host, program.hostEntryTarget(3), label + " host identity");
                            var active = activeTargets(host); assertEquals(targets.size(), active.size(), label + " active root count");
                            boolean same = true; for (int j = 0; j < Math.min(targets.size(), active.size()); j++) same &= targets.get(j) == active.get(j);
                            assertTrue(same, label + " active root identities"); for (var target : targets) valid(target, label + " active root valid");
                        }
                    }
                } finally { context.leave(); }
            }
        }
    }
}
