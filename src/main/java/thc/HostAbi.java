// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import thc.runtime.*;

/** Logical polyglot values to exact Core carriers. This is not a native C ABI. */
final class HostAbi {
    private HostAbi() {}
    private static final BigInteger MAX_WORD = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    static void require(CoreRepresentation proof) { CoreRepresentations.requireInput(proof); }

    @TruffleBoundary static Object[] arguments(Language.State owner, List<CoreRepresentation> proofs, Object[] inputs) {
        if (inputs.length != proofs.size()) throw new IllegalArgumentException("Host signature arity mismatch");
        var physical = new ArrayList<Object>();
        for (int i = 0; i < inputs.length; i++) {
            var proof = proofs.get(i);
            if (proof.getKind() == CoreKind.VOID && !proof.isTypedTransport()) {
                requireNull(inputs[i]); physical.add(Unit.INSTANCE);
            } else pack(owner, proof, inputs[i], physical);
        }
        return physical.toArray();
    }

    private static Object unwrap(Language.State owner, Object value) {
        return value != null && owner.getEnv().isHostObject(value) ? owner.getEnv().asHostObject(value) : value;
    }
    private static IllegalArgumentException mismatch(String wanted) { return new IllegalArgumentException("Host ABI requires " + wanted); }
    private static Object[] array(Language.State owner, Object input, int size) {
        Object raw = unwrap(owner, input);
        if (raw instanceof Object[] array) {
            if (array.length != size) throw mismatch("an array of " + size + " logical fields");
            return array;
        }
        var interop = InteropLibrary.getUncached();
        try {
            if (!interop.hasArrayElements(input) || interop.getArraySize(input) != size)
                throw mismatch("an array of " + size + " logical fields");
            Object[] fields = new Object[size];
            for (int i = 0; i < size; i++) fields[i] = interop.readArrayElement(input, i);
            return fields;
        } catch (InteropException failure) { throw mismatch("readable logical fields"); }
    }
    private static void requireNull(Object input) {
        if (input != null && !InteropLibrary.getUncached().isNull(input)) throw mismatch("null for State#/Void#");
    }
    private static void pack(Language.State owner, CoreRepresentation proof, Object input, ArrayList<Object> output) {
        if (proof.isTuple()) {
            var parts = proof.getComponents();
            var values = array(owner, input, parts.size());
            for (int i = 0; i < parts.size(); i++) pack(owner, parts.get(i), values[i], output);
        } else if (proof.isSum()) {
            var values = array(owner, input, 2);
            long tag = signed(unwrap(owner, values[0]));
            if (tag < 1 || tag > proof.getAlternatives().size()) throw mismatch("a 1-based sum alternative tag");
            var payloadProof = proof.getAlternatives().get((int) tag - 1);
            var payload = new ArrayList<Object>();
            pack(owner, payloadProof, values[1], payload);
            var leaves = SumShape.storage(proof);
            Object[] storage = new Object[leaves.size()];
            for (int i = 0; i < storage.length; i++) storage[i] = empty(leaves.get(i));
            storage[0] = tag;
            var projection = SumShape.projection(proof, (int) tag - 1);
            var payloadLeaves = TupleShape.flatten(payloadProof);
            for (int i = 0; i < payload.size(); i++) {
                var narrow = payloadLeaves.get(i).getNarrowInteger();
                storage[projection.get(i)] = narrow == null ? payload.get(i) : narrow.widen((Integer) payload.get(i));
            }
            java.util.Collections.addAll(output, storage);
        } else if (proof.getKind() == CoreKind.VOID) requireNull(input);
        else output.add(scalar(owner, proof, input));
    }
    private static Object empty(CoreRepresentation proof) {
        if (proof.isLong()) return 0L;
        if (proof.isFloat()) return 0.0f;
        if (proof.isDouble()) return 0.0;
        if (proof.getKind() == CoreKind.ADDRESS) return ManagedAddress.nullAddress();
        if (proof.isVector()) return new VectorLayout(proof).getSpecies().zero();
        return null;
    }
    private static long signed(Object value) {
        if (value instanceof BigInteger integer) {
            try { return integer.longValueExact(); }
            catch (ArithmeticException failure) { throw mismatch("an exact signed 64-bit integer"); }
        }
        try {
            var interop = InteropLibrary.getUncached();
            if (interop.fitsInLong(value)) return interop.asLong(value);
        } catch (UnsupportedMessageException failure) { /* Report the declared host contract. */ }
        throw mismatch("an exact signed 64-bit integer");
    }
    private static boolean unsigned(CoreRepresentation proof) {
        return proof.getPrimReps() != null && proof.getPrimReps().size() == 1 &&
            (proof.getPrimReps().getFirst().equals("WordRep") || proof.getPrimReps().getFirst().equals("Word64Rep"));
    }
    private static Object scalar(Language.State owner, CoreRepresentation proof, Object input) {
        Object value = unwrap(owner, input);
        if (proof.isVector()) return new VectorLayout(proof).require(value);
        var narrow = proof.getNarrowInteger();
        if (narrow != null) return narrow.fromHost(signed(value));
        var interop = InteropLibrary.getUncached();
        try {
            if (proof.isLong() || proof.getKind() == CoreKind.UNKNOWN) {
                if (!unsigned(proof)) return signed(value);
                BigInteger number = value instanceof BigInteger big ? big :
                    interop.fitsInBigInteger(value) ? interop.asBigInteger(value) : null;
                if (number == null || number.signum() < 0 || number.compareTo(MAX_WORD) > 0) throw mismatch("an unsigned 64-bit integer");
                return number.longValue();
            }
            if (proof.isFloat()) {
                if (!interop.fitsInFloat(value)) throw mismatch("an exactly representable Float#");
                return interop.asFloat(value);
            }
            if (proof.isDouble()) {
                if (!interop.fitsInDouble(value)) throw mismatch("an exactly representable Double#");
                return interop.asDouble(value);
            }
        } catch (UnsupportedMessageException failure) { throw mismatch("the declared numeric representation"); }
        if (input instanceof HostReference reference) {
            if (reference.owner != owner) throw mismatch("a reference owned by this context");
            proof.refine(reference.proof);
            if (proof.getKind() == CoreKind.ADDRESS && !(reference.value instanceof ManagedAddress)) throw mismatch("a managed address");
            if (proof.getKind() == CoreKind.CLOSURE && !(reference.value instanceof Closure || reference.value instanceof Thunk)) throw mismatch("a guest function");
            if (proof.getKind() != CoreKind.ADDRESS && reference.value instanceof ManagedAddress) throw mismatch("a guest reference, not an address");
            return reference.value;
        }
        if (proof.getKind() == CoreKind.ADDRESS && (input == null || interop.isNull(input))) return ManagedAddress.nullAddress();
        throw mismatch("a context-owned guest reference");
    }

    @TruffleBoundary static Object result(Language.State owner, CoreRepresentation proof, Object value, ExecutableProgram program) {
        if (proof.isTypedTransport()) return unpack(owner, proof, (Object[]) value, new int[1], program);
        return exportScalar(owner, proof, value, program);
    }
    private static Object unpack(Language.State owner, CoreRepresentation proof, Object[] storage, int[] cursor, ExecutableProgram program) {
        if (proof.isTuple()) {
            Object[] fields = new Object[proof.getComponents().size()];
            for (int i = 0; i < fields.length; i++) fields[i] = unpack(owner, proof.getComponents().get(i), storage, cursor, program);
            return new HostArray(fields);
        }
        if (proof.isSum()) {
            int start = cursor[0];
            if (!(storage[start] instanceof Long tag) || tag < 1 || tag > proof.getAlternatives().size())
                throw new RuntimeFault("Invalid host sum result tag");
            var payload = proof.getAlternatives().get(tag.intValue() - 1);
            var projection = SumShape.projection(proof, tag.intValue() - 1);
            var leaves = TupleShape.flatten(payload);
            Object[] fields = new Object[projection.size()];
            for (int i = 0; i < fields.length; i++) {
                var narrow = leaves.get(i).getNarrowInteger();
                Object field = storage[start + projection.get(i)];
                fields[i] = narrow == null ? field : narrow.fromHost((Long) field);
            }
            cursor[0] += SumShape.storage(proof).size();
            return new HostArray(new Object[]{tag, unpack(owner, payload, fields, new int[1], program)});
        }
        if (proof.getKind() == CoreKind.VOID) return ForeignExportUnit.INSTANCE;
        return exportScalar(owner, proof, storage[cursor[0]++], program);
    }
    private static Object exportScalar(Language.State owner, CoreRepresentation proof, Object value, ExecutableProgram program) {
        if (proof.getKind() == CoreKind.VOID) {
            if (value != Unit.INSTANCE) throw new RuntimeFault("Invalid void host result");
            return ForeignExportUnit.INSTANCE;
        }
        if (proof.isVector()) return owner.getEnv().asGuestValue(new VectorLayout(proof).require(value));
        var narrow = proof.getNarrowInteger();
        if (narrow != null) {
            if (!(value instanceof Integer integer)) throw new RuntimeFault("Expected narrow integer result at public boundary");
            return narrow.widen(integer);
        }
        if (proof.isLong()) {
            if (!(value instanceof Long integer)) throw new RuntimeFault("Expected Long result at public boundary");
            return unsigned(proof) && integer < 0 ? new UnsignedWord64(integer) : integer;
        }
        if (proof.isFloat()) {
            if (!(value instanceof Float)) throw new RuntimeFault("Expected Float result at public boundary");
            return value;
        }
        if (proof.isDouble()) {
            if (!(value instanceof Double)) throw new RuntimeFault("Expected Double result at public boundary");
            return value;
        }
        if (proof.getKind() == CoreKind.UNKNOWN && (value instanceof Long || value instanceof Integer || value instanceof Float || value instanceof Double)) return value;
        return new HostReference(owner, value, proof, program);
    }
}
