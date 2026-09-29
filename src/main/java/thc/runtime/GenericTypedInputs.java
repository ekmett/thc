// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TypedInputs.*;

/** Dynamic metadata stays outside the fixed caller-local copy loops. */
public final class GenericTypedInputs {
    private GenericTypedInputs() {}
    @CompilerDirectives.TruffleBoundary
    public static void validateGenericInput(GuestRoot root, int prefix, ArgumentLayout source, int offset, int count,
            TupleDestination destination, boolean exact, boolean under) {
        ArgumentLayout.validate(root.getInputLayout(), prefix, source, offset, count);
        if (!under) checkInputResult(root, destination, exact);
    }
    @CompilerDirectives.TruffleBoundary private static void putInt(HandoffStorage storage, int field, int value) {
        HandoffLayout shape = storage.getLayout();
        if (shape.isInt(field)) shape.setInt(storage, field, value);
        else { check(shape.isObject(field)); shape.setObject(storage, field, value); }
    }
    @CompilerDirectives.TruffleBoundary private static void putLong(HandoffStorage storage, int field, long value) {
        HandoffLayout shape = storage.getLayout();
        if (shape.isLong(field)) shape.setLong(storage, field, value);
        else { check(shape.isObject(field)); shape.setObject(storage, field, value); }
    }
    @CompilerDirectives.TruffleBoundary private static void putFloat(HandoffStorage storage, int field, float value) {
        HandoffLayout shape = storage.getLayout();
        if (shape.isFloat(field)) shape.setFloat(storage, field, value);
        else { check(shape.isObject(field)); shape.setObject(storage, field, value); }
    }
    @CompilerDirectives.TruffleBoundary private static void putDouble(HandoffStorage storage, int field, double value) {
        HandoffLayout shape = storage.getLayout();
        if (shape.isDouble(field)) shape.setDouble(storage, field, value);
        else { check(shape.isObject(field)); shape.setObject(storage, field, value); }
    }
    @CompilerDirectives.TruffleBoundary private static void putScalar(HandoffStorage storage, int field, Object value) {
        HandoffLayout shape = storage.getLayout();
        if (shape.isInt(field)) {
            if (!(value instanceof Integer scalar)) throw fault("Expected primitive Int input");
            shape.setInt(storage, field, scalar);
        } else if (shape.isLong(field)) {
            if (!(value instanceof Long scalar)) throw fault("Expected primitive Long input");
            shape.setLong(storage, field, scalar);
        } else if (shape.isFloat(field)) {
            if (!(value instanceof Float scalar)) throw fault("Expected primitive Float input");
            shape.setFloat(storage, field, scalar);
        } else if (shape.isDouble(field)) {
            if (!(value instanceof Double scalar)) throw fault("Expected primitive Double input");
            shape.setDouble(storage, field, scalar);
        } else shape.setObject(storage, field, value);
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
    @ExplodeLoop private static void copyGeneric(InputSource source, VirtualFrame frame, Node node, Object[] values,
            int maximum, int from, int width, HandoffStorage storage, int to) {
        for (int i = 0; i < ArgumentLayout.offset(source.getLayout(), maximum); i++) if (i >= from && i < from + width) {
            int field = to + i - from;
            CoreRepresentation proof = source.getPhysicalProofs$org_intelligence_thc() == null ? null : source.getPhysicalProofs$org_intelligence_thc()[i];
            if (proof != null && proof.isInt()) putInt(storage, field, source.readInt(frame, node, values, i));
            else if (proof != null && proof.isLong()) putLong(storage, field, source.readLong(frame, node, values, i));
            else if (proof != null && proof.isFloat()) putFloat(storage, field, source.readFloat(frame, node, values, i));
            else if (proof != null && proof.isDouble()) putDouble(storage, field, source.readDouble(frame, node, values, i));
            else putScalar(storage, field, source.reference(frame, node, values, i));
        }
    }
    @CompilerDirectives.TruffleBoundary private static Object prefixValue(Closure function, TypedInputLayout input, int physical) {
        HandoffStorage prefix = function.typedSupplied;
        return prefix != null ? input.prefix(function.suppliedCount).getObject(prefix, physical) : function.supplied[physical];
    }
    @CompilerDirectives.TruffleBoundary private static HandoffStorage prefixStorage(TypedInputLayout input, int count) {
        return input.prefix(count).create();
    }
    @CompilerDirectives.TruffleBoundary private static void copyPrefix(Closure function, HandoffStorage storage, int offset, int width) {
        HandoffStorage typed = function.typedSupplied;
        if (typed != null) copyInputFields(typed, storage, 0, offset, width, typed.getLayout(), storage.getLayout());
        else {
            check(function.supplied.length == width);
            for (int i = 0; i < width; i++) putScalar(storage, offset + i, function.supplied[i]);
        }
    }
    public static Closure genericTypedPap(Closure function, TypedInputLayout input, InputSource source,
            VirtualFrame frame, Node node, Object[] values, int maximum, int offset, int count) {
        int oldCount = function.suppliedCount, prefixWidth = input.getLogical().offset(oldCount);
        int from = ArgumentLayout.offset(source.getLayout(), offset);
        int width = ArgumentLayout.offset(source.getLayout(), offset + count) - from;
        HandoffStorage storage = prefixStorage(input, oldCount + count);
        copyPrefix(function, storage, 0, prefixWidth);
        copyGeneric(source, frame, node, values, maximum, from, width, storage, prefixWidth);
        return new Closure(function.environment, Closure.NO_PAP_ARGUMENTS, function.arity - count, function.target, oldCount + count, storage);
    }
    private static boolean containsStrictPosition(int[] strict, int position) {
        for (int item : strict) if (item == position) return true;
        return false;
    }
    @ExplodeLoop private static void forceActuals(VirtualFrame frame, Node node, Closure function, InputSource source,
            Object[] values, int maximum, int offset, int count, int[] strict, Force force) {
        for (int i = 0; i < maximum; i++) if (i >= offset && i < offset + count) {
            CoreRepresentation proof = source.getLayout() == null ? null : source.getLayout().proof(i);
            if (proof != null && (proof.isTypedTransport() || proof.isInt() || proof.isLong() || proof.isFloat() || proof.isDouble())) continue;
            int logicalPosition = function.suppliedCount + i - offset;
            if (containsStrictPosition(strict, logicalPosition)) {
                int physical = ArgumentLayout.offset(source.getLayout(), i);
                source.setReference(frame, node, values, physical, force.execute(frame, source.reference(frame, node, values, physical)));
            }
        }
    }
    @CompilerDirectives.TruffleBoundary private static HandoffStorage acquireGenericInput(TypedInputLayout input, boolean compiled) {
        HandoffStorage storage = compiled ? input.getPacket().create() : input.state().getArguments().acquire(input.getPacket());
        storage.setInputMode(compiled ? 3 : 1);
        return storage;
    }
    @CompilerDirectives.TruffleBoundary public static void releaseGenericInput(TypedInputLayout input, HandoffStorage storage, long generation) {
        input.releaseIfOwned(storage, generation);
    }
    public static HandoffStorage prepareGenericInput(VirtualFrame frame, Node node, Closure function, TypedInputLayout input,
            InputSource source, Object[] values, int maximum, int offset, int count, Force force) {
        int[] strict = strictInputPositions(ColdCallChecks.guestRoot(function.target.getRootNode()), input);
        int prefixCount = function.suppliedCount, prefixWidth = input.getLogical().offset(prefixCount);
        boolean strictPrefix = false;
        for (int i : strict) if (i < prefixCount) strictPrefix = true;
        Object[] overrides = strictPrefix ? new Object[prefixWidth] : null;
        for (int i : strict) if (i < prefixCount) {
            int physical = input.getLogical().offset(i);
            if (overrides == null) CompilerDirectives.transferToInterpreter();
            overrides[physical] = force.execute(frame, prefixValue(function, input, physical));
        }
        forceActuals(frame, node, function, source, values, maximum, offset, count, strict, force);
        return packGenericInput(frame, node, function, input, source, values, maximum, offset, count, strict, overrides);
    }
    static Object[] forceGenericInputCaptured(VirtualFrame frame, Node node, Closure function, TypedInputLayout input,
            InputSource source, Object[] values, int maximum, int offset, int[] strict, Force force) {
        Object[] overrides = function.suppliedCount == 0 ? null : new Object[input.getLogical().offset(function.suppliedCount)];
        return forcePrefixFrom(frame, node, function, input, source, values, maximum, offset, strict, force, overrides, 0);
    }
    private static Object[] forcePrefixFrom(VirtualFrame frame, Node node, Closure function, TypedInputLayout input,
            InputSource source, Object[] values, int maximum, int offset, int[] strict, Force force, Object[] overrides, int start) {
        for (int index = start; index < strict.length; index++) if (strict[index] < function.suppliedCount) {
            int physical = input.getLogical().offset(strict[index]);
            Object answer;
            try { answer = AstControl.forceCallback(frame, node, force, prefixValue(function, input, physical)); }
            catch (AstCapture cut) {
                int next = index + 1;
                throw cut.append((saved, value) -> {
                    overrides[physical] = value;
                    return forcePrefixFrom(saved, node, function, input, source, values, maximum, offset, strict, force, overrides, next);
                });
            }
            overrides[physical] = answer;
        }
        return forceActualsFrom(frame, node, function, source, values, maximum, offset, strict, force, overrides, 0);
    }
    // The source slots, unlike a generic target's strict positions, are PE constants.
    @ExplodeLoop private static Object[] forceActualsFrom(VirtualFrame frame, Node node, Closure function, InputSource source,
            Object[] values, int maximum, int offset, int[] strict, Force force, Object[] overrides, int start) {
        for (int i = 0; i < maximum; i++) if (i >= start && i >= offset && i < offset + function.arity) {
            CoreRepresentation proof = source.getLayout() == null ? null : source.getLayout().proof(i);
            if (proof != null && (proof.isTypedTransport() || proof.isInt() || proof.isLong() || proof.isFloat() || proof.isDouble())) continue;
            if (containsStrictPosition(strict, function.suppliedCount + i - offset)) {
                int physical = ArgumentLayout.offset(source.getLayout(), i);
                Object answer;
                try { answer = AstControl.forceCallback(frame, node, force, source.reference(frame, node, values, physical)); }
                catch (AstCapture cut) {
                    int next = i + 1;
                    throw cut.append((saved, value) -> {
                        source.setReference(saved, node, values, physical, value);
                        return forceActualsFrom(saved, node, function, source, values, maximum, offset, strict, force, overrides, next);
                    });
                }
                source.setReference(frame, node, values, physical, answer);
            }
        }
        return overrides;
    }
    static HandoffStorage packGenericInput(VirtualFrame frame, Node node, Closure function, TypedInputLayout input,
            InputSource source, Object[] values, int maximum, int offset, int count, int[] strict, Object[] overrides) {
        int prefixCount = function.suppliedCount, prefixWidth = input.getLogical().offset(prefixCount);
        HandoffStorage storage = acquireGenericInput(input, CompilerDirectives.inCompiledCode());
        try {
            putLong(storage, 0, 0L);
            if (input.getHasEnvironment()) putScalar(storage, 1, function.environment);
            copyPrefix(function, storage, input.getHeader(), prefixWidth);
            int from = ArgumentLayout.offset(source.getLayout(), offset);
            int width = ArgumentLayout.offset(source.getLayout(), offset + count) - from;
            copyGeneric(source, frame, node, values, maximum, from, width, storage, input.getHeader() + prefixWidth);
            if (overrides != null) for (int i : strict) if (i < prefixCount) {
                int physical = input.getLogical().offset(i);
                putScalar(storage, input.getHeader() + physical, overrides[physical]);
            }
            return storage;
        } catch (Throwable failure) {
            releaseGenericInput(input, storage, storage.getGeneration());
            throw failure;
        }
    }
    @ExplodeLoop public static Object[] genericScalarValues(VirtualFrame frame, Node node, InputSource source, Object[] values,
            int maximum, int offset, int count) {
        int from = ArgumentLayout.offset(source.getLayout(), offset);
        Object[] result = new Object[ArgumentLayout.offset(source.getLayout(), offset + count) - from];
        for (int i = 0; i < maximum; i++) if (i >= offset && i < offset + count) {
            CoreRepresentation proof = source.getLayout() == null ? null : source.getLayout().proof(i);
            if (proof != null && proof.isEmptyTuple()) continue;
            if (proof != null && (proof.isTuple() || proof.isVector())) throw fault("Typed input cannot enter a scalar packet");
            int physical = ArgumentLayout.offset(source.getLayout(), i);
            if (proof != null && proof.isLong()) result[physical - from] = source.readLong(frame, node, values, physical);
            else if (proof != null && proof.isFloat()) result[physical - from] = source.readFloat(frame, node, values, physical);
            else if (proof != null && proof.isDouble()) result[physical - from] = source.readDouble(frame, node, values, physical);
            else result[physical - from] = source.reference(frame, node, values, physical);
        }
        return result;
    }
}
