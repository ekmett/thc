// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import thc.runtime.*;
import java.nio.ByteOrder;
import java.math.BigInteger;
import java.util.List;
import com.oracle.truffle.api.utilities.TriState;

/** Context-owned boundary view. Guest carriers are never replaced by interop wrappers. */
@ExportLibrary(InteropLibrary.class)
public final class HostReference implements TruffleObject {
    final Language.State owner;
    final Object value;
    final CoreRepresentation proof;
    private final ExecutableProgram program;
    private final Language language;
    private volatile RootCallTarget callTarget;
    private CbitsBuffer buffer;
    private RootCallTarget forceTarget;
    private volatile Object numericValue;
    private final boolean writable;
    private static final CoreRepresentation ELEMENT = new CoreRepresentation(CoreKind.OBJECT, false, true,
        List.of("BoxedRep (Just Lifted)"));
    HostReference(Language.State owner, Object value, CoreRepresentation proof, ExecutableProgram program) {
        this(owner, value, proof, program, false);
    }
    private HostReference(Language.State owner, Object value, CoreRepresentation proof, ExecutableProgram program, boolean writable) {
        this.owner = owner; this.value = value; this.proof = proof; this.program = program;
        this.writable = writable;
        language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
    }
    /** The typed interop intrinsics, not RuntimeRep or the carrier, grant mutation authority. */
    @TruffleBoundary public static HostReference storage(Language.State owner, Object value, boolean writable) {
        if (!(value instanceof byte[] || value instanceof ManagedAllocation || value instanceof Object[] || value instanceof SmallArrayStorage))
            throw new IllegalArgumentException("Expected guest byte or element storage");
        var reference = new HostReference(owner, value, new CoreRepresentation(CoreKind.OBJECT, true, true,
            List.of("BoxedRep (Just Unlifted)")), null, writable);
        if (value instanceof byte[] || value instanceof ManagedAllocation) {
            try { reference.buffer(); }
            catch (UnsupportedMessageException impossible) { throw new AssertionError(impossible); }
        }
        return reference;
    }
    private void requireOwner() {
        if (Language.currentState() != owner) throw new IllegalArgumentException("Storage view belongs to another context");
    }
    // Retained by Truffle's cross-context proxy, unlike Java instanceof.
    @ExportMessage public boolean hasLanguageId() { return true; }
    @ExportMessage public String getLanguageId() { return "thc"; }
    @ExportMessage public Object toDisplayString(boolean allowSideEffects) { return "Haskell reference"; }
    @TruffleBoundary private synchronized CbitsBuffer buffer() throws UnsupportedMessageException {
        requireOwner();
        if (proof.getHostCarrier() != null) throw UnsupportedMessageException.create();
        if (buffer == null) {
            if (value instanceof ManagedAllocation allocation) {
                if (writable && !allocation.isWritable()) throw new IllegalArgumentException("Read-only guest byte storage");
                buffer = new CbitsBuffer(allocation.exposeSegment().asByteBuffer(), writable, allocation::getSize,
                    0, null, null, allocation.storageKey());
            } else if (value instanceof byte[] bytes) buffer = new CbitsBuffer(bytes, writable);
            else throw UnsupportedMessageException.create();
        }
        return buffer;
    }
    // Raw host references carry no guest storage or callable authority.
    @ExportMessage public boolean hasBufferElements() { requireOwner(); return proof.getHostCarrier() == null && (value instanceof byte[] || value instanceof ManagedAllocation); }
    @ExportMessage public boolean isBufferWritable() throws UnsupportedMessageException { return buffer().isBufferWritable(); }
    @ExportMessage public long getBufferSize() throws UnsupportedMessageException { return buffer().getBufferSize(); }
    @ExportMessage public void readBuffer(long offset, byte[] destination, int destinationOffset, int length)
            throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().readBuffer(offset, destination, destinationOffset, length); }
    @ExportMessage public boolean hasArrayElements() { requireOwner(); return proof.getHostCarrier() == null && (value instanceof Object[] || value instanceof SmallArrayStorage); }
    @ExportMessage public long getArraySize() throws UnsupportedMessageException {
        if (!hasArrayElements()) throw UnsupportedMessageException.create();
        if (value instanceof Object[] array) return array.length;
        if (value instanceof SmallArrayStorage array) return array.getLogicalSize();
        throw UnsupportedMessageException.create();
    }
    @ExportMessage public boolean isArrayElementReadable(long index) {
        if (!hasArrayElements()) return false;
        return index >= 0 && index < (value instanceof Object[] array ? array.length : ((SmallArrayStorage) value).getLogicalSize());
    }
    @ExportMessage @TruffleBoundary public boolean isArrayElementModifiable(long index) {
        return writable && isArrayElementReadable(index) && (value instanceof Object[] array
            ? !ManagedArray.isFrozen(array) : !((SmallArrayStorage) value).getFrozen());
    }
    @ExportMessage public boolean isArrayElementInsertable(long index) { return false; }
    @ExportMessage @TruffleBoundary public Object readArrayElement(long index) throws UnsupportedMessageException, InvalidArrayIndexException {
        requireOwner();
        if (!hasArrayElements()) throw UnsupportedMessageException.create();
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
        Object element = value instanceof Object[] array ? array[(int) index] : ManagedSmallArray.read((SmallArrayStorage) value, index);
        return new HostReference(owner, element, ELEMENT, program);
    }
    @ExportMessage @TruffleBoundary public void writeArrayElement(long index, Object element)
            throws UnsupportedMessageException, InvalidArrayIndexException, UnsupportedTypeException {
        requireOwner();
        if (!writable || !hasArrayElements()) throw UnsupportedMessageException.create();
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
        if (!isArrayElementModifiable(index)) throw UnsupportedMessageException.create();
        if (!(element instanceof HostReference reference) || reference.owner != owner ||
                !List.of("BoxedRep (Just Lifted)").equals(reference.proof.getPrimReps()))
            throw UnsupportedTypeException.create(new Object[]{element}, "Expected a context-owned lifted guest reference");
        if (value instanceof Object[] array) ManagedArray.write(array, index, reference.value);
        else ManagedSmallArray.write((SmallArrayStorage) value, index, reference.value);
    }
    @ExportMessage public byte readBufferByte(long offset) throws UnsupportedMessageException, InvalidBufferOffsetException { return buffer().readBufferByte(offset); }
    @ExportMessage public void writeBufferByte(long offset, byte value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().writeBufferByte(offset, value); }
    @ExportMessage public short readBufferShort(ByteOrder order, long offset) throws UnsupportedMessageException, InvalidBufferOffsetException { return buffer().readBufferShort(order, offset); }
    @ExportMessage public void writeBufferShort(ByteOrder order, long offset, short value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().writeBufferShort(order, offset, value); }
    @ExportMessage public int readBufferInt(ByteOrder order, long offset) throws UnsupportedMessageException, InvalidBufferOffsetException { return buffer().readBufferInt(order, offset); }
    @ExportMessage public void writeBufferInt(ByteOrder order, long offset, int value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().writeBufferInt(order, offset, value); }
    @ExportMessage public long readBufferLong(ByteOrder order, long offset) throws UnsupportedMessageException, InvalidBufferOffsetException { return buffer().readBufferLong(order, offset); }
    @ExportMessage public void writeBufferLong(ByteOrder order, long offset, long value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().writeBufferLong(order, offset, value); }
    @ExportMessage public float readBufferFloat(ByteOrder order, long offset) throws UnsupportedMessageException, InvalidBufferOffsetException { return buffer().readBufferFloat(order, offset); }
    @ExportMessage public void writeBufferFloat(ByteOrder order, long offset, float value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().writeBufferFloat(order, offset, value); }
    @ExportMessage public double readBufferDouble(ByteOrder order, long offset) throws UnsupportedMessageException, InvalidBufferOffsetException { return buffer().readBufferDouble(order, offset); }
    @ExportMessage public void writeBufferDouble(ByteOrder order, long offset, double value) throws UnsupportedMessageException, InvalidBufferOffsetException { buffer().writeBufferDouble(order, offset, value); }
    @ExportMessage public boolean isNull() { return value == null || value == ManagedAddress.nullAddress(); }
    @ExportMessage public boolean isExecutable() { return proof.getHostCarrier() == null && (value instanceof Closure || value instanceof Thunk || proof.getKind() == CoreKind.CLOSURE); }
    private Object availableNumber() {
        requireOwner();
        if (proof.getHostCarrier() != null) return null;
        Object number = numericValue;
        if (number != null) return number;
        Object published = LiftedValues.resolveBoxed(value);
        if (published instanceof Thunk) return null;
        number = published instanceof DataValue data ? ManagedExportScalar.toInteropNumber(data)
            : InteropLibrary.isValidValue(published) && InteropLibrary.getUncached().isNumber(published) ? published : null;
        if (number != null) numericValue = number;
        return number;
    }
    private Object requiredNumber() throws UnsupportedMessageException {
        Object number = availableNumber();
        if (number == null) throw UnsupportedMessageException.create();
        return number;
    }
    @ExportMessage public boolean isNumber() { return availableNumber() != null; }
    @ExportMessage public boolean fitsInByte() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInByte(number); }
    @ExportMessage public boolean fitsInShort() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInShort(number); }
    @ExportMessage public boolean fitsInInt() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInInt(number); }
    @ExportMessage public boolean fitsInLong() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInLong(number); }
    @ExportMessage public boolean fitsInBigInteger() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInBigInteger(number); }
    @ExportMessage public boolean fitsInFloat() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInFloat(number); }
    @ExportMessage public boolean fitsInDouble() { Object number = availableNumber(); return number != null && InteropLibrary.getUncached().fitsInDouble(number); }
    @ExportMessage public byte asByte() throws UnsupportedMessageException { return InteropLibrary.getUncached().asByte(requiredNumber()); }
    @ExportMessage public short asShort() throws UnsupportedMessageException { return InteropLibrary.getUncached().asShort(requiredNumber()); }
    @ExportMessage public int asInt() throws UnsupportedMessageException { return InteropLibrary.getUncached().asInt(requiredNumber()); }
    @ExportMessage public long asLong() throws UnsupportedMessageException { return InteropLibrary.getUncached().asLong(requiredNumber()); }
    @ExportMessage public BigInteger asBigInteger() throws UnsupportedMessageException { return InteropLibrary.getUncached().asBigInteger(requiredNumber()); }
    @ExportMessage public float asFloat() throws UnsupportedMessageException { return InteropLibrary.getUncached().asFloat(requiredNumber()); }
    @ExportMessage public double asDouble() throws UnsupportedMessageException { return InteropLibrary.getUncached().asDouble(requiredNumber()); }
    @ExportMessage public int identityHashCode() { return System.identityHashCode(value); }
    @ExportMessage public TriState isIdenticalOrUndefined(Object other) {
        return other instanceof HostReference reference ? TriState.valueOf(owner == reference.owner && value == reference.value) : TriState.UNDEFINED;
    }
    private static boolean asynchronous(Object value) {
        value = LiftedValues.resolveBoxed(value);
        if (value instanceof Thunk thunk) return thunk.getAsynchronousExceptions();
        if (!(value instanceof Closure closure)) return false;
        return closure.target.getRootNode() instanceof GuestRoot root && root.getAsynchronousExceptions();
    }
    private synchronized RootCallTarget forcingTarget() {
        if (program != null) return program.hostEntryTarget(0);
        if (forceTarget == null) forceTarget = new EntryRoot(language, 0, new Metrics(false)).getCallTarget();
        return forceTarget;
    }
    @ExportMessage @TruffleBoundary public Object execute(Object[] arguments,
            @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) throws UnsupportedMessageException, ArityException {
        if (Language.currentState(dispatch) != owner) throw new IllegalArgumentException("Host function belongs to another context");
        if (!isExecutable()) throw UnsupportedMessageException.create();
        owner.admitGuestOrigin();
        var threads = owner.getThreads();
        if (threads.needsHosting()) return threads.hostEntry(dispatch, () -> execute(arguments, dispatch));
        threads.enterCurrent(null, false, asynchronous(value), null);
        var outcome = GuestThreadStatus.FINISHED;
        try {
            try {
                Object demand = LiftedValues.resolveBoxed(value);
                Object forced = demand instanceof Closure ? demand : AsyncContinuations.publicResult(
                    dispatch.executePublic(forcingTarget(), new Object[]{demand, new Object[0]}), dispatch);
                if (!(forced instanceof Closure) && value instanceof Thunk && proof.getKind() != CoreKind.CLOSURE) {
                    if (arguments.length != 0) throw ArityException.create(0, 0, arguments.length);
                    return this;
                }
                if (!(forced instanceof Closure function) || !(function.target.getRootNode() instanceof GuestRoot root))
                    throw new RuntimeFault("Host function has no guest signature");
                // A thunk can return a callable from a separately loaded program.
                threads.setCurrentExternalAsync(asynchronous(function));
                var complete = root.getInputProofs();
                if (complete == null) throw new RuntimeFault("Host function is missing its logical input signature");
                var inputs = complete.subList(function.suppliedCount, complete.size());
                if (arguments.length != inputs.size()) throw new IllegalArgumentException("Host function arity mismatch");
                var result = root.getTupleResult() == null ? root.getScalarResultProof() : root.getTupleResult().getProof();
                var normalized = HostAbi.arguments(owner, inputs, arguments);
                RootCallTarget target = callTarget;
                if (target == null) {
                    synchronized (this) {
                        target = callTarget;
                        if (target == null) callTarget = target = new EntryRoot(language, inputs, result, new Metrics(false), root.getTupleResult()).getCallTarget();
                    }
                }
                Object answer = AsyncContinuations.publicResult(dispatch.executePublic(target, new Object[]{function, normalized}), dispatch);
                return HostAbi.result(owner, result, answer, program);
            } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
        } catch (Throwable failure) {
            outcome = GuestThreadStatus.uncaught(failure);
            if (failure instanceof GuestException guest) dispatch.escaping(guest);
            throw failure;
        } finally { threads.leaveCurrent(outcome); }
    }
}
