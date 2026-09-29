// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.*;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SimdFamiliesTest {
    @Test void frameEntriesDoNotInitializeVectorLibrary() throws Exception {
        // With shared arenas, Native Image excludes vector-library static
        // initialization from runtime graphs. Such work may remain in native
        // helpers, but a residual call must never receive the guest frame.
        for (var type : List.of(Vector16Pack.class, Vector16Operation.class, Vector16Unpack.class,
                GeneratedInt16X16Pack.class, GeneratedInt16X16Operation.class,
                GeneratedInt16X16Unpack.class, GeneratedInt16X16Shuffle.class)) {
            try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                var model = java.lang.classfile.ClassFile.of().parse(Objects.requireNonNull(bytes).readAllBytes());
                for (var method : model.methods()) {
                    if (!method.methodType().stringValue().contains("Lcom/oracle/truffle/api/frame/VirtualFrame;")) continue;
                    for (var element : method.code().orElseThrow()) {
                        if (element instanceof java.lang.classfile.instruction.FieldInstruction field
                                && field.opcode() == java.lang.classfile.Opcode.GETSTATIC)
                            assertFalse(field.owner().asInternalName().startsWith("jdk/incubator/vector/"),
                                type.getSimpleName() + "." + method.methodName() + " must keep species access outside its frame entry");
                        if (element instanceof java.lang.classfile.instruction.InvokeInstruction call
                                && call.opcode() == java.lang.classfile.Opcode.INVOKESTATIC)
                            assertFalse(call.owner().asInternalName().startsWith("jdk/incubator/vector/"),
                                type.getSimpleName() + "." + method.methodName() + " must keep vector initialization outside its frame entry");
                    }
                }
            }
        }
    }
    private final File root = new File(System.getProperty("thc.projectRoot")), directory = new File(root, "build/simd-families");
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed"); }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot root) for (var instruction : root.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot) visit(active, seen, result);
        result.add(target);
    }
    private long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private CoreRepresentation copy(CoreRepresentation p, List<String> reps, List<CoreRepresentation> components) {
        return new CoreRepresentation(p.getKind(), p.getEvaluated(), p.getPresent(), reps, components, p.getVector(), p.getAlternatives(), p.getTagSlot(), p.getAlternativeSlots());
    }
    @Test void runtimeVectorChecksDoNotInitializeProofMetadata() throws Exception {
        // Native Image rejects an unpack graph if its carrier check initializes
        // the proof registry. Inspect the actual call owner, not a hardcoded name.
        var classes = java.lang.classfile.ClassFile.of();
        String owner;
        try (var bytes = Vector16Unpack.class.getResourceAsStream("Vector16Unpack.class")) {
            var calls = classes.parse(Objects.requireNonNull(bytes).readAllBytes()).methods().stream()
                .flatMap(m -> m.code().stream()).flatMap(java.lang.classfile.CodeModel::elementStream)
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                .filter(call -> call.name().equalsString("requireShort")).toList();
            assertEquals(1, calls.size());
            owner = calls.getFirst().owner().asInternalName();
        }
        try (var bytes = Vector16Unpack.class.getResourceAsStream("/" + owner + ".class")) {
            var helper = classes.parse(Objects.requireNonNull(bytes).readAllBytes());
            assertTrue(helper.fields().stream().allMatch(field -> field.fieldName().equalsString("$assertionsDisabled")
                && field.fieldType().equalsString("Z") && field.flags().has(java.lang.reflect.AccessFlag.STATIC)
                && field.flags().has(java.lang.reflect.AccessFlag.FINAL) && field.flags().has(java.lang.reflect.AccessFlag.SYNTHETIC)),
                "runtime carrier checks must not own proof metadata");
            for (var initializer : helper.methods().stream().filter(m -> m.methodName().equalsString("<clinit>")).toList()) {
                // RuntimeTypes has only javac's assertion-status flag. Preserve
                // it, but reject object allocation or any metadata initializer.
                var code = initializer.code().orElseThrow();
                assertTrue(code.exceptionHandlers().isEmpty());
                var instructions = code.elementStream().filter(java.lang.classfile.Instruction.class::isInstance)
                    .map(java.lang.classfile.Instruction.class::cast).toList();
                for (var instruction : instructions) {
                    assertFalse(instruction.opcode().name().contains("NEW"), "no object or array allocation");
                    assertFalse(instruction instanceof java.lang.classfile.instruction.InvokeDynamicInstruction);
                    if (instruction instanceof java.lang.classfile.instruction.ConstantInstruction constant) {
                        assertFalse(constant.constantValue() instanceof java.lang.constant.DynamicConstantDesc<?>);
                        if (constant.constantValue() instanceof java.lang.constant.ClassDesc type)
                            assertEquals(java.lang.constant.ClassDesc.ofDescriptor("L" + owner + ";"), type);
                    } else if (instruction instanceof java.lang.classfile.instruction.InvokeInstruction call) {
                        assertEquals("java/lang/Class", call.owner().asInternalName());
                        assertEquals("desiredAssertionStatus", call.name().stringValue());
                        assertEquals("()Z", call.type().stringValue());
                    } else if (instruction instanceof java.lang.classfile.instruction.FieldInstruction store) {
                        assertEquals(java.lang.classfile.Opcode.PUTSTATIC, store.opcode());
                        assertEquals(owner, store.owner().asInternalName());
                        assertEquals("$assertionsDisabled", store.name().stringValue());
                        assertEquals("Z", store.type().stringValue());
                    }
                }
            }
        }
    }
    @Test void rawVectorBoundariesRejectWrongSpeciesAndElementTypes() {
        var shorts = ShortVector.broadcast(ShortVector.SPECIES_128, (short) 0).withLane(1, (short) 1).withLane(2, (short) -1).withLane(3, Short.MIN_VALUE)
            .withLane(4, Short.MAX_VALUE).withLane(5, (short) 5).withLane(6, (short) 6).withLane(7, (short) 7);
        assertEquals(ShortVector.SPECIES_128, shorts.species()); var actual = new ArrayList<Integer>(); for (int i = 0; i < 8; i++) actual.add((int) shorts.lane(i));
        assertEquals(List.of(0, 1, -1, -32768, 32767, 5, 6, 7), actual); assertEquals(shorts, RuntimeTypes.requireShort(shorts, ShortVector.SPECIES_128));
        assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireShort(shorts, ShortVector.SPECIES_256)); assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireInt(shorts, IntVector.SPECIES_128));
        for (var wrong : Arrays.asList(null, 1L, new short[]{1, 2})) assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireShort(wrong, ShortVector.SPECIES_128));
        assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireByte(ByteVector.zero(ByteVector.SPECIES_128), ByteVector.SPECIES_256));
        assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireInt(IntVector.zero(IntVector.SPECIES_128), IntVector.SPECIES_256));
        assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireLong(LongVector.zero(LongVector.SPECIES_128), LongVector.SPECIES_256));
        assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireFloat(FloatVector.zero(FloatVector.SPECIES_128), FloatVector.SPECIES_256));
        assertThrows(RuntimeFault.class, () -> RuntimeTypes.requireDouble(DoubleVector.zero(DoubleVector.SPECIES_128), DoubleVector.SPECIES_256));
        for (long index : List.of(-1L, 8L, Long.MIN_VALUE, Long.MAX_VALUE)) assertThrows(RuntimeFault.class, () -> RuntimeTypes.laneIndex(index, 8));
        for (long index = 0; index <= 7; index++) assertEquals((int) index, RuntimeTypes.laneIndex(index, 8));
    }
    @Test void operationOperandsRunOnceInOrderAndStopAtTheOriginalFailure() {
        var operations = List.<Function<Expr[], Expr>>of(
            arguments -> new Vector16Operation("plusInt16X8#", arguments),
            arguments -> new GeneratedInt16X16Operation("plusInt16X16#", arguments),
            arguments -> new VectorFloat8Fused("fmaddFloatX8#", arguments));
        var values = List.of(ShortVector.broadcast(ShortVector.SPECIES_128, (short) 2),
            ShortVector.broadcast(ShortVector.SPECIES_256, (short) 2),
            FloatVector.broadcast(FloatVector.SPECIES_256, 2.0f));
        var expected = List.of(ShortVector.broadcast(ShortVector.SPECIES_128, (short) 4),
            ShortVector.broadcast(ShortVector.SPECIES_256, (short) 4),
            FloatVector.broadcast(FloatVector.SPECIES_256, 6.0f));
        var wrongSpecies = List.of(ShortVector.zero(ShortVector.SPECIES_256),
            ShortVector.zero(ShortVector.SPECIES_128), FloatVector.zero(FloatVector.SPECIES_128));
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        for (int family = 0; family < operations.size(); family++) {
            int arity = family == 2 ? 3 : 2;
            var order = new ArrayList<Integer>();
            var arguments = new Expr[arity];
            for (int i = 0; i < arity; i++) arguments[i] = observedOperand(i, values.get(family), order, null);
            assertEquals(expected.get(family), operations.get(family).apply(arguments).execute(frame));
            assertEquals(arity == 3 ? List.of(0, 1, 2) : List.of(0, 1), order);
            for (int failed = 0; failed < arity; failed++) {
                order.clear();
                var failure = new RuntimeFault("operand " + failed);
                for (int i = 0; i < arity; i++) arguments[i] = observedOperand(i, values.get(family), order, i == failed ? failure : null);
                var operation = operations.get(family).apply(arguments);
                assertSame(failure, assertThrows(RuntimeFault.class, () -> operation.execute(frame)));
                assertEquals(List.of(0, 1, 2).subList(0, failed + 1), order);
            }
            order.clear();
            for (int i = 0; i < arity; i++) arguments[i] = observedOperand(i, i == 0 ? wrongSpecies.get(family) : values.get(family), order, null);
            var operation = operations.get(family).apply(arguments);
            assertThrows(RuntimeFault.class, () -> operation.execute(frame));
            assertEquals(List.of(0), order);
        }
    }
    private Expr observedOperand(int index, Object value, List<Integer> order, RuntimeFault failure) {
        return new Expr() {
            @Override public Object execute(VirtualFrame frame) {
                order.add(index);
                if (failure != null) throw failure;
                return value;
            }
        };
    }
    private record Family(String name, CoreRepresentation tuple, CoreRepresentation vector) {}
    @Test void exactLaneSignWidthLogicalTupleAndCallingProofsRemainRequired() {
        assertEquals(315, GeneratedVectors.operations.size());
        for (var family : List.of(
            new Family("Int8X32", GeneratedVectors.unpackedInt8X32, GeneratedVectors.proofInt8X32),
            new Family("Word8X32", GeneratedVectors.unpackedWord8X32, GeneratedVectors.proofWord8X32),
            new Family("Int8X64", GeneratedVectors.unpackedInt8X64, GeneratedVectors.proofInt8X64),
            new Family("Word8X64", GeneratedVectors.unpackedWord8X64, GeneratedVectors.proofWord8X64),
            new Family("Int16X32", GeneratedVectors.unpackedInt16X32, GeneratedVectors.proofInt16X32),
            new Family("Word16X32", GeneratedVectors.unpackedWord16X32, GeneratedVectors.proofWord16X32),
            new Family("Word64X2", GeneratedVectors.unpackedWord64X2, GeneratedVectors.proofWord64X2),
            new Family("Word32X8", GeneratedVectors.unpackedWord32X8, GeneratedVectors.proofWord32X8),
            new Family("Int32X8", GeneratedVectors.unpackedInt32X8, GeneratedVectors.proofInt32X8),
            new Family("Int32X16", GeneratedVectors.unpackedInt32X16, GeneratedVectors.proofInt32X16),
            new Family("FloatX8", GeneratedVectors.unpackedFloatX8, GeneratedVectors.proofFloatX8),
            new Family("DoubleX4", GeneratedVectors.unpackedDoubleX4, GeneratedVectors.proofDoubleX4),
            new Family("Int64X4", GeneratedVectors.unpackedInt64X4, GeneratedVectors.proofInt64X4),
            new Family("Int64X8", GeneratedVectors.unpackedInt64X8, GeneratedVectors.proofInt64X8),
            new Family("Word64X4", GeneratedVectors.unpackedWord64X4, GeneratedVectors.proofWord64X4),
            new Family("Word64X8", GeneratedVectors.unpackedWord64X8, GeneratedVectors.proofWord64X8),
            new Family("Word32X16", GeneratedVectors.unpackedWord32X16, GeneratedVectors.proofWord32X16),
            new Family("FloatX16", GeneratedVectors.unpackedFloatX16, GeneratedVectors.proofFloatX16),
            new Family("DoubleX8", GeneratedVectors.unpackedDoubleX8, GeneratedVectors.proofDoubleX8),
            new Family("Int16X16", GeneratedVectors.unpackedInt16X16, GeneratedVectors.proofInt16X16),
            new Family("Word16X16", GeneratedVectors.unpackedWord16X16, GeneratedVectors.proofWord16X16),
            new Family("Int8X16", CoreVectors.unpacked8, CoreVectors.proof8),
            new Family("Word8X16", CoreVectors.unpackedWord8, CoreVectors.proofWord8),
            new Family("Int16X8", CoreVectors.unpacked16, CoreVectors.proof16),
            new Family("Word16X8", CoreVectors.unpackedWord16, CoreVectors.proofWord16),
            new Family("Int32X4", CoreVectors.unpacked32, CoreVectors.proof32),
            new Family("Word32X4", CoreVectors.unpackedWord32, CoreVectors.proofWord32),
            new Family("Int64X2", CoreVectors.unpacked, CoreVectors.proof),
            new Family("FloatX4", CoreVectors.unpackedFloat, CoreVectors.proofFloat),
            new Family("DoubleX2", CoreVectors.unpackedDouble, CoreVectors.proofDouble))) {
            var name = family.name; var tuple = family.tuple; var vector = family.vector;
            CoreVectors.validate("pack" + name + "#", List.of(tuple), vector); CoreVectors.validate("times" + name + "#", List.of(vector, vector), vector);
            CoreVectors.validate("unpack" + name + "#", List.of(vector), tuple);
            var lane = Objects.requireNonNull(tuple.getComponents()).getFirst();
            var index = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
            CoreVectors.validate("insert" + name + "#", List.of(vector, lane, index), vector);
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate("insert" + name + "#", List.of(vector, lane, copy(index, List.of("WordRep"), index.getComponents())), vector));
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate("insert" + name + "#", List.of(vector, copy(lane, List.of("IntRep"), lane.getComponents()), index), vector));
            for (int component = 0; component < tuple.getComponents().size(); component++) {
                var components = new ArrayList<CoreRepresentation>();
                for (int i = 0; i < tuple.getComponents().size(); i++) { var proof = tuple.getComponents().get(i); components.add(i == component ? copy(proof, List.of("WordRep"), proof.getComponents()) : proof); }
                var wrong = copy(tuple, tuple.getPrimReps(), components);
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate("pack" + name + "#", List.of(wrong), vector));
            }
            for (var wrong : List.of(CoreVectors.proof32, CoreVectors.proof16, CoreVectors.proofFloat, tuple)) if (!Objects.equals(wrong.getVector(), vector.getVector())) {
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate("times" + name + "#", List.of(vector, wrong), vector));
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate("times" + name + "#", List.of(vector, vector), wrong));
            }
            for (int arity : List.of(0, 1, 3)) assertThrows(RuntimeFault.class, () -> CoreVectors.validate("times" + name + "#", Collections.nCopies(arity, vector), vector));
            CoreRepresentations.requireInput(vector); var input = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(vector)));
            assertTrue(input.getRequiresTyped()); assertEquals(1, input.getPhysicalArity());
            assertThrows(RuntimeFault.class, () -> ArgumentLayout.validate(input, 0, ArgumentLayout.fromProofs(List.of(tuple)), 0, 1));
        }
        for (var flags : List.of(List.of(true), Collections.singletonList(null), List.of(0L))) assertThrows(RuntimeFault.class, () -> CoreVectors.validateFlags(flags));
    }
    @Test void insertRejectsInvalidMachineIndicesBeforeAnyNarrowing() {
        var original = LongVector.broadcast(LongVector.SPECIES_128, 1L).withLane(1, 2L);
        for (long index : List.of(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE, 0x1_0000_0000L)) {
            assertThrows(RuntimeFault.class, () -> original.withLane(RuntimeTypes.laneIndex(index, 2), -1L));
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.GeneratedDoubleX2Insert.apply(DoubleVector.broadcast(DoubleVector.SPECIES_128, 1.0), 2.0, index));
        }
        for (long index : List.of(-1L, 16L, Long.MAX_VALUE, 0x1_0000_0000L))
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.GeneratedInt8X16Insert.apply(ByteVector.broadcast(ByteVector.SPECIES_128, (byte) 1), 2, index));
    }
    @Test void floatingExtremaRequireTwoExactCarriersAndResult() {
        var proofs = new LinkedHashMap<String, CoreRepresentation>(); proofs.put("FloatX4", CoreVectors.proofFloat); proofs.put("FloatX8", GeneratedVectors.proofFloatX8);
        proofs.put("FloatX16", GeneratedVectors.proofFloatX16); proofs.put("DoubleX2", CoreVectors.proofDouble); proofs.put("DoubleX4", GeneratedVectors.proofDoubleX4); proofs.put("DoubleX8", GeneratedVectors.proofDoubleX8);
        for (var entry : proofs.entrySet()) for (var operation : List.of("min", "max")) {
            var proof = entry.getValue(); var name = operation + entry.getKey() + "#"; CoreVectors.validate(name, List.of(proof, proof), proof);
            for (int index = 0; index <= 1; index++) {
                var inputs = new ArrayList<>(Collections.nCopies(2, proof)); inputs.set(index, CoreVectors.proof);
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, inputs, proof));
            }
            for (int arity : List.of(1, 3)) assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, Collections.nCopies(arity, proof), proof));
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(proof, proof), CoreVectors.proof));
            assertThrows(RuntimeFault.class, () -> CoreVectors.validateFlags(List.of(false, true)));
        }
    }
    @Test void word32X16ExtremaUseUnsignedLaneOrderAcrossTheFullCarrier() {
        var left = IntVector.broadcast(IntVector.SPECIES_512, Integer.MIN_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), -1);
        var right = IntVector.broadcast(IntVector.SPECIES_512, Integer.MAX_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), 0);
        var minimum = left.lanewise(VectorOperators.UMIN, right); var maximum = left.lanewise(VectorOperators.UMAX, right);
        assertEquals(Integer.MAX_VALUE, minimum.lane(0)); assertEquals(Integer.MAX_VALUE, minimum.lane(7)); assertEquals(0, minimum.lane(15));
        assertEquals(Integer.MIN_VALUE, maximum.lane(0)); assertEquals(Integer.MIN_VALUE, maximum.lane(7)); assertEquals(-1, maximum.lane(15));
    }
    @Test void int32X16ExtremaKeepSignedLanesAndExactShape() {
        var left = IntVector.broadcast(IntVector.SPECIES_512, Integer.MIN_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), -1);
        var right = IntVector.broadcast(IntVector.SPECIES_512, Integer.MAX_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), 0);
        var minimum = left.min(right); var maximum = left.max(right);
        assertEquals(Integer.MIN_VALUE, minimum.lane(0)); assertEquals(Integer.MIN_VALUE, minimum.lane(7)); assertEquals(-1, minimum.lane(15));
        assertEquals(Integer.MAX_VALUE, maximum.lane(0)); assertEquals(Integer.MAX_VALUE, maximum.lane(7)); assertEquals(0, maximum.lane(15));
        var proof = GeneratedVectors.proofInt32X16;
        for (var name : List.of("minInt32X16#", "maxInt32X16#")) {
            CoreVectors.validate(name, List.of(proof, proof), proof);
            for (var wrong : List.of(GeneratedVectors.proofWord32X16, GeneratedVectors.proofInt32X8, GeneratedVectors.unpackedInt32X16)) {
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(wrong, proof), proof));
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(proof, wrong), proof));
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(proof, proof), wrong));
            }
            for (int arity : List.of(0, 1, 3)) assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, Collections.nCopies(arity, proof), proof));
        }
    }
    @Test void short16ExtremaDistinguishSignedFromUnsignedOrder() {
        var signedLeft = ShortVector.broadcast(ShortVector.SPECIES_256, Short.MIN_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), (short) -1);
        var signedRight = ShortVector.broadcast(ShortVector.SPECIES_256, Short.MAX_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), (short) 0);
        var unsignedLeft = ShortVector.broadcast(ShortVector.SPECIES_256, Short.MIN_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), (short) -1);
        var unsignedRight = ShortVector.broadcast(ShortVector.SPECIES_256, Short.MAX_VALUE).withLane(RuntimeTypes.laneIndex(15L, 16), (short) 0);
        var signedMin = signedLeft.min(signedRight); var signedMax = signedLeft.max(signedRight);
        var unsignedMin = unsignedLeft.lanewise(VectorOperators.UMIN, unsignedRight); var unsignedMax = unsignedLeft.lanewise(VectorOperators.UMAX, unsignedRight);
        assertEquals(List.of(-32768, -32768, -1), List.of((int) signedMin.lane(0), (int) signedMin.lane(7), (int) signedMin.lane(15)));
        assertEquals(List.of(32767, 32767, 0), List.of((int) signedMax.lane(0), (int) signedMax.lane(7), (int) signedMax.lane(15)));
        assertEquals(List.of(32767, 32767, 0), List.of(unsignedMin.lane(0) & 0xffff, unsignedMin.lane(7) & 0xffff, unsignedMin.lane(15) & 0xffff));
        assertEquals(List.of(32768, 32768, 65535), List.of(unsignedMax.lane(0) & 0xffff, unsignedMax.lane(7) & 0xffff, unsignedMax.lane(15) & 0xffff));
        for (var family : List.of(new Family("Int16X16", GeneratedVectors.proofInt16X16, GeneratedVectors.proofWord16X16),
            new Family("Word16X16", GeneratedVectors.proofWord16X16, GeneratedVectors.proofInt16X16))) for (var op : List.of("min", "max")) {
            var name = op + family.name + "#"; var proof = family.tuple; CoreVectors.validate(name, List.of(proof, proof), proof);
            for (var wrong : List.of(family.vector, CoreVectors.proof16, GeneratedVectors.unpackedInt16X16)) {
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(wrong, proof), proof));
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(proof, wrong), proof));
                assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, List.of(proof, proof), wrong));
            }
        }
    }
    // These early gates use actual pre-Tidy Core and the independent model.
    // They are deliberately separate from native evidence and capability enablement.
    @Tag("simd-families-experiment") @Test void widerPreparedCoreCompilesWithInlining() throws Exception { execute(true, true); }
    @Tag("simd-families-experiment") @Test void widerPreparedCoreCompilesAcrossResidualCalls() throws Exception { execute(false, true); }
    @Tag("simd-families-experiment") @Test void nativeFamiliesWithInlining() throws Exception { execute(true, false); }
    @Tag("simd-families-experiment") @Test void nativeFamiliesAcrossResidualCalls() throws Exception { execute(false, false); }
    private void check(Value function, List<String> row, String label) {
        assertEquals(Long.parseLong(row.get(4)), function.execute(Long.parseLong(row.get(1)), Long.parseLong(row.get(2)), Long.parseLong(row.get(3))).asLong(), label + "/" + row.subList(1, row.size()));
    }
    private void execute(boolean inlining, boolean earlyWideGate) throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        var items = new ArrayList<>((List<Map<String, String>>) manifest.get("inputs")); items.addAll((List<Map<String, String>>) manifest.get("artifacts"));
        for (var item : items) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.get("path")).toPath())));
            assertEquals(item.get("sha256"), hash, "Stale SIMD source/artifact: " + item.get("path"));
        }
        var expectedText = Files.readString(new File(directory, "expected.tsv").toPath()); var lines = expectedText.split("\\R", -1);
        if (!earlyWideGate) {
            long lineCount = 0; for (var line : lines) if (!line.isEmpty()) lineCount++;
            assertEquals(lineCount, ((Number) manifest.get("nativeRows")).longValue()); assertEquals(expectedText, Files.readString(new File(directory, "oracle.tsv").toPath()));
            assertEquals(List.of("pre", "post"), manifest.get("stages"));
        }
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : lines) if (!line.isEmpty()) { var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); }
        var names = earlyWideGate ? List.of("timesInt32X8", "timesInt32X16", "timesWord64X2", "timesWord32X8", "floatX8Composite", "doubleX4Composite") : new ArrayList<>(rows.keySet());
        for (var stage : (List<String>) manifest.get("stages")) {
            var module = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "-core/GeneratedSimdFamilies.json").toPath()));
            var structures = (Map<String, Map<String, Object>>) manifest.get("structures"); var calls = (Map<String, Number>) structures.get(stage).get("expectedGuestCalls");
            for (var name : names) for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var input = new LinkedHashMap<>(CoreModules.reachable(module, name)); input.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                    var entry = program.entryTarget(name); var host = program.hostEntryTarget(3); var function = context.asValue(new EntryValue(program, name, 3));
                    List<List<String>> cases;
                    if (!earlyWideGate) cases = rows.get(name);
                    else if (name.endsWith("Composite")) {
                        var selectors = new LinkedHashMap<String, List<List<String>>>(); for (var row : rows.get(name)) selectors.computeIfAbsent(row.get(1), ignored -> new ArrayList<>()).add(row);
                        cases = new ArrayList<>();
                        for (var selectorRows : selectors.values()) {
                            // Extrema's native corpus excludes NaNs, infinities and mixed-zero ties.
                            var indices = switch (selectorRows.size()) { case 28 -> List.of(12, 14, 16); case 398 -> List.of(0, 199, 397); default -> List.of(348, 350, 404); };
                            for (int index : indices) cases.add(selectorRows.get(index));
                        }
                    } else {
                        cases = new ArrayList<>(); var source = rows.get(name); for (int i = 0; i < source.size(); i++) if (List.of(0, 112, 224).contains(i % 225)) cases.add(source.get(i));
                    }
                    var label = stage + "/" + backend + "/" + name + "/inline=" + inlining; for (var row : cases) check(function, row, label);
                    var active = activeTargets(host); assertEquals(calls.get(name).intValue() + 1, active.size(), label + " actual target count");
                    var distinct = new LinkedHashSet<>(active); distinct.add(entry); for (var target : distinct) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean(), label + " host installation");
                    for (var row : cases) {
                        long before = count(program); check(function, row, label); assertEquals(before + calls.get(name).longValue(), count(program), label + " exact guest entries");
                        assertEquals(active, activeTargets(host), label + " actual target identity"); valid(entry, label + " original"); for (var target : active) valid(target, label + " active"); released(language);
                    }
                    for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                } finally { context.leave(); }
            }
        }
    }
}
