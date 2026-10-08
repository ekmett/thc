// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Json;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class NarrowIntegerCarrierTest {
    private void entered(CheckedConsumer<Language> action) throws Exception {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private CoreRepresentation proof(String rep) { return new CoreRepresentation(CoreKind.LONG, true, true, list(rep), null, null, null, null, null); }
    private CoreRepresentation proof(NarrowInteger integer) { return proof(integer.getRep()); }
    private ExecutableProgram literalProgram(Language language, String backend, List<Object> literal) {
        var input = map("id", "ignored", "name", "ignored", "lifted", false,
            "rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true));
        var module = map("schema", 1, "ghc", "9.14.1", "module", "NarrowLiteral", "constructors", list(),
            "bindings", list(map("id", "entry", "name", "entry", "arity", 1, "lifted", true,
                "expr", list("lam", list(input), literal))));
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @Test void narrowLiteralsRetainIntrinsicValuesWhenProofsAreErased() throws Exception {
        record Literal(String kind, String value, Object expected) {}
        var cases = list(new Literal("int8", "-128", -128), new Literal("word8", "255", 255),
            new Literal("int", "255", 255L));
        var unknown = map("kind", "unknown", "primReps", null, "evaluated", false);
        entered(language -> {
            for (var backend : list("ast", "bytecode")) for (var value : cases) {
                // Literal kind recovers narrow values without an exported proof;
                // ordinary machine literals must not acquire narrow certification.
                for (boolean erased : new boolean[]{false, true}) {
                    var literal = new ArrayList<Object>(list("lit", value.kind(), value.value()));
                    if (erased) literal.add(map("rep", unknown));
                    if (value.kind().equals("int")) assertFalse(CoreRepresentations.expression(literal).isInt());
                    var program = literalProgram(language, backend, literal);
                    assertEquals(value.expected(), Calls.target(program.hostEntryTarget(1),
                        new Object[]{program.entryValue("entry"), new Object[]{0L}}), backend + "/" + value.kind());
                }
                if (!value.kind().equals("int")) {
                    List<Object> malformed = list("lit", value.kind(), value.value(), map("rep", list()));
                    assertThrows(RuntimeFault.class, () -> literalProgram(language, backend, malformed));
                    List<Object> outOfRange = list("lit", value.kind(), value.kind().equals("int8") ? "128" : "256");
                    assertThrows(RuntimeFault.class, () -> literalProgram(language, backend, outOfRange));
                }
            }
        });
    }
    @Test void exactLoweredProofSeparatesIntComputationFromMachineAnd64BitLong() {
        for (var integer : NarrowInteger.values()) {
            var proof = proof(integer); assertTrue(proof.isInt()); assertFalse(proof.isLong()); assertEquals(list(integer.getRep()), proof.getPrimReps());
            assertEquals(integer.getBits() == 8 ? byte.class : integer.getBits() == 16 ? short.class : int.class, integer.getStorageClass());
            assertThrows(RuntimeFault.class, () -> proof.refine(proof("IntRep")));
        }
        for (var rep : list("IntRep", "WordRep", "Int64Rep", "Word64Rep")) { var proof = proof(rep); assertTrue(proof.isLong()); assertFalse(proof.isInt()); }
        assertEquals(-1, NarrowInteger.WORD32.narrow(-1)); assertEquals(4294967295L, NarrowInteger.WORD32.widen(-1));
        assertEquals(-1L, NarrowInteger.INT32.widen(-1));
    }
    @Test void primitiveIntFramesWidenOnlyToObjectAndRetainConcurrentActivationTags() {
        var builder = FrameDescriptor.newBuilder(); builder.addSlot(FrameSlotKind.Illegal, "value", null); var descriptor = builder.build();
        var first = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor); var second = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        FrameAccess.writeInt(first, 0, Integer.MIN_VALUE); assertEquals(FrameSlotKind.Int, descriptor.getSlotKind(0));
        FrameAccess.writeObject(second, 0, "reference"); assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0));
        assertEquals(Integer.MIN_VALUE, FrameAccess.read(first, 0)); FrameAccess.writeInt(first, 0, Integer.MAX_VALUE);
        assertTrue(first.isObject(0)); assertEquals(Integer.MAX_VALUE, first.getObject(0)); assertEquals("reference", FrameAccess.read(second, 0));
    }
    @Test void capturesHandoffsAndConstructorsKeepNarrowStoredWidthsAndIntCarriers() throws Exception {
        entered(language -> {
            for (var integer : NarrowInteger.values()) {
                var proof = proof(integer);
                var capture = CaptureLayout.withVectors(language, new CoreRepresentation[]{null}, new boolean[]{true}, new boolean[]{false},
                    new Class<?>[1], new boolean[]{false}, new boolean[]{false}, new NarrowInteger[]{integer});
                var packet = language.getHandoffLayouts().intern(list(integer.getRep()));
                var data = new DataLayout(language, "NarrowCarrier." + integer.name(), integer.name(), new String[]{integer.getRep()});
                for (int bits : list(Integer.MIN_VALUE, -65537, -129, -1, 0, 1, 127, 65535, Integer.MAX_VALUE)) {
                    int expected = integer.narrow(bits); var value = capture.captureValues(new Object[]{bits});
                    assertTrue(capture.isInt(value, 0)); assertFalse(capture.isLong(value, 0));
                    assertEquals(expected, capture.readInt(value, 0)); assertEquals(expected, capture.read(value, 0));
                    var storage = packet.create(); packet.setInt(storage, 0, bits);
                    assertTrue(packet.isInt(0)); assertFalse(packet.isLong(0)); assertEquals(expected, packet.getInt(storage, 0));
                    assertThrows(RuntimeFault.class, () -> packet.getLong(storage, 0));
                    var constructed = data.createInt(bits); assertTrue(data.isInt(0)); assertFalse(data.isLong(0));
                    assertEquals(expected, data.readInt(constructed, 0)); assertThrows(RuntimeFault.class, () -> data.readLong(constructed, 0));
                }
                assertTrue(Objects.requireNonNull(ArgumentLayout.fromProofs(list(proof))).getRequiresTyped());
                assertThrows(RuntimeFault.class, () -> capture.captureValues(new Object[]{1L}));
                assertThrows(RuntimeFault.class, () -> packet.copyIn(packet.create(), new Object[]{1L}));
                assertThrows(RuntimeFault.class, () -> data.create(new Object[]{1L}));
            }
        });
    }
    @Test void scalarOperationsUseIntAndOnlyDeclaredWideningsProduceLong() {
        for (var integer : NarrowInteger.values()) {
            var family = integer.getRep().substring(0, integer.getRep().length() - 3);
            var op = Objects.requireNonNull(NarrowScalarOp.named("plus" + family + "#"));
            assertEquals(integer.narrow(Integer.MAX_VALUE + 1), op.intResult(Integer.MAX_VALUE, 1)); assertTrue(op.getResult().isInt());
        }
        assertNull(NarrowScalarOp.named("narrow32Int#")); assertTrue(Objects.requireNonNull(NarrowScalarOp.named("intToInt32#")).getSourceLong());
        assertEquals(-1L, Objects.requireNonNull(NarrowScalarOp.named("int32ToInt#")).longResult(-1, 0));
        assertEquals(4294967295L, Objects.requireNonNull(NarrowScalarOp.named("word32ToWord#")).longResult(-1, 0));
        assertEquals(2147483647, Objects.requireNonNull(NarrowScalarOp.named("quotWord32#")).intResult(-1, 2));
        assertEquals(1, Objects.requireNonNull(NarrowScalarOp.named("remWord32#")).intResult(-1, 2));
        assertEquals(1L, Objects.requireNonNull(NarrowScalarOp.named("gtWord32#")).longResult(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

}
