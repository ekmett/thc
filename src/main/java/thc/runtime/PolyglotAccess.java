// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.CallTarget;
import thc.Language;
import thc.HostReference;
import java.nio.ByteOrder;
/** Cached interop messages can specialize on the foreign language's actual objects. */
public final class PolyglotAccess extends Node {
    private final int programSlot;
    public PolyglotAccess() { this(-1); }
    public PolyglotAccess(int programSlot) {
        this.programSlot = programSlot;
        members = library(); functions = library(); numbers = library(); storage = library();
    }
    private InteropLibrary library() {
        return programSlot < 0 ? InteropLibrary.getFactory().createDispatched(3) : InteropLibrary.getUncached();
    }
    private RuntimeException raise(VirtualFrame frame, AbstractTruffleException error) {
        return programSlot < 0 ? foreignExceptions.raise(error)
            : foreignExceptions.raise(error, Program.instance(frame, programSlot).foreignExceptionBridge());
    }
    @Child private IndirectCallNode evalCall = IndirectCallNode.create();
    @Child private InteropLibrary members;
    @Child private InteropLibrary functions;
    @Child private InteropLibrary numbers;
    @Child private InteropLibrary storage;
    @Child private ForeignExceptionAccess foreignExceptions = new ForeignExceptionAccess();
    @TruffleBoundary private CallTarget parse(ManagedAddress language, ManagedAddress source, ManagedAddress name) {
        return Language.currentState(this).getEnv().parsePublic(Source.newBuilder(language.utf8(), source.utf8(), name.utf8()).build());
    }
    public ForeignValue eval(ManagedAddress language, ManagedAddress source, ManagedAddress name, Object state) {
        return eval(null, language, source, name, state);
    }
    public ForeignValue eval(VirtualFrame frame, ManagedAddress language, ManagedAddress source, ManagedAddress name, Object state) {
        TupleResults.requireVoidCarrier(state);
        var owner = Language.currentState(this);
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                var value = evalCall.call(parse(language, source, name));
                if (value == null) throw RuntimeFault.fault("Foreign evaluation returned a host null");
                return new ForeignValue(owner, value);
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw raise(frame, error); }
    }
    private Object receiver(VirtualFrame frame, Object value) {
        // Lowering owns resumable handle demand; this opaque boundary never forces.
        if (!(value instanceof ForeignValue handle)) throw RuntimeFault.fault("Expected THC.Polyglot.Value");
        if (handle.getOwner() != Language.currentState(this)) throw RuntimeFault.fault("Polyglot value belongs to a different context");
        return handle.getReceiver();
    }
    public ForeignValue readMember(VirtualFrame frame, Object value, ManagedAddress name, Object state) {
        TupleResults.requireVoidCarrier(state);
        var receiver = receiver(frame, value);
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try { return new ForeignValue(Language.currentState(this), members.readMember(receiver, name.utf8())); }
                catch (InteropException error) { throw interopFailure("readMember", error); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw raise(frame, error); }
    }
    public long executeInt(VirtualFrame frame, Object value, long argument, Object state) {
        TupleResults.requireVoidCarrier(state);
        var receiver = receiver(frame, value);
        // This first scalar bridge uses the integer range shared by Int# and JS Number.
        if (argument < -9_007_199_254_740_991L || argument > 9_007_199_254_740_991L)
            throw RuntimeFault.fault("Polyglot executeInt input exceeds the exact Number integer range");
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try {
                    var answer = functions.execute(receiver, argument);
                    if (!numbers.fitsInLong(answer)) throw RuntimeFault.fault("Polyglot executeInt result is not an exact Int#");
                    return numbers.asLong(answer);
                } catch (InteropException error) { throw interopFailure("executeInt", error); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw raise(frame, error); }
    }
    /** Arrays here are boundary operands, never guest primop storage wrappers. */
    public Object storage(VirtualFrame frame, PolyglotOp operation, Object[] arguments) {
        TupleResults.requireVoidCarrier(arguments[arguments.length - 1]);
        var owner = Language.currentState(this);
        Object value;
        switch (operation) {
            case BUFFER_VIEW, BUFFER_MUTABLE_VIEW -> {
                value = arguments[0];
                ManagedByteArray.sizeGuest(value);
                return new ForeignValue(owner, HostReference.storage(owner, value, operation == PolyglotOp.BUFFER_MUTABLE_VIEW));
            }
            case ARRAY_VIEW, ARRAY_MUTABLE_VIEW -> {
                value = arguments[0];
                if (!(value instanceof Object[] || value instanceof SmallArrayStorage))
                    throw RuntimeFault.fault("Expected Array# or SmallArray# storage");
                return new ForeignValue(owner, HostReference.storage(owner, value, operation == PolyglotOp.ARRAY_MUTABLE_VIEW));
            }
            default -> value = receiver(frame, arguments[0]);
        }
        // Both handles have already completed their resumable guest demand.
        Object argument = operation == PolyglotOp.EXECUTE_VALUE ? receiver(frame, arguments[1])
            : operation == PolyglotOp.ARRAY_WRITE ? receiver(frame, arguments[2]) : null;
        var threads = owner.getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try {
                    return accessStorage(owner, operation, value, argument, arguments);
                } catch (InteropException error) { throw interopFailure(operation.getSymbol(), error); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw raise(frame, error); }
    }
    /** Receiver-dependent library specialization must not retire a cold guest target.
     * Guest demand, SAFE transitions and the completed-result poll remain outside this frame-free boundary. */
    @TruffleBoundary private Object accessStorage(Language.State owner, PolyglotOp operation,
            Object value, Object argument, Object[] arguments) throws InteropException {
        return switch (operation) {
            case EXECUTE_VALUE -> new ForeignValue(owner, functions.execute(value, argument));
            case BUFFER_SIZE -> storage.getBufferSize(value);
            case BUFFER_READ_BYTE -> (long) Byte.toUnsignedInt(storage.readBufferByte(value, (Long) arguments[1]));
            case BUFFER_WRITE_BYTE -> {
                long number = (Long) arguments[2];
                if (number < 0 || number > 255) throw RuntimeFault.fault("Buffer byte must be in 0..255");
                storage.writeBufferByte(value, (Long) arguments[1], (byte) number); yield 0L;
            }
            case BUFFER_READ_LONG -> storage.readBufferLong(value, order((Long) arguments[1]), (Long) arguments[2]);
            case BUFFER_WRITE_LONG -> {
                storage.writeBufferLong(value, order((Long) arguments[1]), (Long) arguments[2], (Long) arguments[3]); yield 0L;
            }
            case BUFFER_COPY -> ManagedByteArray.fromFreshBytes(copy(value, (Long) arguments[1], (Long) arguments[2]));
            case BUFFER_COPY_INTO -> {
                Object destination = arguments[2];
                long offset = (Long) arguments[3], length = (Long) arguments[4];
                range(ManagedByteArray.sizeGuest(destination), offset, length);
                // Validate pointer-cell exclusion and writability before executing foreign reads.
                if (destination instanceof ManagedAllocation allocation) allocation.requireByteRegion(offset, length, true);
                byte[] bytes = copy(value, (Long) arguments[1], length);
                ManagedByteArray.copyGuest(bytes, 0, destination, offset, length, true); yield 0L;
            }
            case ARRAY_SIZE -> storage.getArraySize(value);
            case ARRAY_READ -> new ForeignValue(owner, storage.readArrayElement(value, (Long) arguments[1]));
            case ARRAY_WRITE -> { storage.writeArrayElement(value, (Long) arguments[1], argument); yield 0L; }
            case ARRAY_COPY -> {
                int size = size(storage.getArraySize(value));
                Object[] elements = new Object[size];
                for (int i = 0; i < size; i++) elements[i] = new ForeignValue(owner, storage.readArrayElement(value, i));
                yield ManagedArray.freeze(elements);
            }
            default -> throw new AssertionError(operation);
        };
    }
    private byte[] copy(Object value, long offset, long length) throws InteropException {
        range(storage.getBufferSize(value), offset, length);
        byte[] bytes = new byte[size(length)];
        storage.readBuffer(value, offset, bytes, 0, bytes.length);
        return bytes;
    }
    private static int size(long size) {
        if (size < 0 || size > Integer.MAX_VALUE) throw RuntimeFault.fault("Foreign storage size outside managed allocation domain");
        return (int) size;
    }
    private static void range(long size, long offset, long length) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset)
            throw RuntimeFault.fault("Foreign buffer range outside storage");
    }
    private static ByteOrder order(long order) {
        if (order == 0) return ByteOrder.LITTLE_ENDIAN;
        if (order == 1) return ByteOrder.BIG_ENDIAN;
        throw RuntimeFault.fault("Invalid buffer byte order");
    }
    @TruffleBoundary private RuntimeException interopFailure(String operation, InteropException error) {
        throw new RuntimeFault("Polyglot " + operation + ": " + error.getMessage());
    }
}
