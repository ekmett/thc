// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Synchronous borrowed views of existing ByteArray# storage, without staging or native projection. */
public final class ManagedText {
    private ManagedText() {}

    /** Original text promises valid UTF-8 and allocates distinct output storage. */
    @TruffleBoundary
    public static void reverse(Object destination, Object source, long offset, long length) {
        var owner = Language.currentState(null);
        var cbits = owner.cbits();
        var function = cbits.textFunction(TextForeignOp.REVERSE);
        if (destination instanceof ManagedAllocation output && source instanceof ManagedAllocation input) {
            output.withOrderedLocks(input, () -> {
                reverseLocked(owner, cbits, function, destination, source, offset, length);
                return null;
            });
        } else if (destination instanceof ManagedAllocation) {
            synchronized (destination) { reverseLocked(owner, cbits, function, destination, source, offset, length); }
        } else if (source instanceof ManagedAllocation) {
            synchronized (source) { reverseLocked(owner, cbits, function, destination, source, offset, length); }
        } else reverseLocked(owner, cbits, function, destination, source, offset, length);
    }

    private static long size(Object bytes) {
        return switch (bytes) {
            case byte[] array -> array.length;
            case ManagedAllocation allocation -> allocation.getSize();
            case null, default -> throw fault("Text reverse requires ByteArray# carriers");
        };
    }

    private static Object key(Object bytes) {
        return bytes instanceof ManagedAllocation allocation ? allocation.storageKey() : bytes;
    }

    private static CbitsBuffer view(Object bytes, boolean writable, long size) {
        ByteBuffer buffer = switch (bytes) {
            case byte[] array -> ByteBuffer.wrap(array);
            case ManagedAllocation allocation -> allocation.exposeSegment().asByteBuffer();
            case null, default -> throw fault("Text reverse requires ByteArray# carriers");
        };
        return new CbitsBuffer(writable ? buffer : buffer.asReadOnlyBuffer(), writable, () -> size);
    }

    private static void reverseLocked(Language.State owner, SulongCbits cbits, Object function,
                                      Object destination, Object source, long offset, long length) {
        long sourceSize = size(source);
        long destinationSize = size(destination);
        if (offset < 0 || length < 0 || offset > sourceSize || length > sourceSize - offset || length > destinationSize)
            throw fault("Text reverse range outside its backing storage");
        if (destination instanceof ManagedAllocation allocation && !allocation.isWritable())
            throw fault("Text reverse requires mutable output storage");
        if (length != 0 && key(destination) == key(source))
            throw fault("Text reverse requires distinct input and output storage");
        var input = view(source, false, sourceSize);
        var output = view(destination, true, length);
        var threads = owner.getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            // The empty original call writes nothing; avoid forming dst - 1.
            if (length != 0) cbits.textReverse(function, output, input, offset, length);
        } finally {
            threads.leaveForeign(previous);
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(destination);
            Reference.reachabilityFence(input);
            Reference.reachabilityFence(output);
        }
    }

    @TruffleBoundary
    public static long invoke(TextForeignOp operation, Object bytes, long offset, long length, long count) {
        var owner = Language.currentState(null);
        var cbits = owner.cbits();
        // Loading another guest language must not hold a ByteArray owner lock.
        var function = cbits.textFunction(operation);
        if (bytes instanceof byte[] array) {
            check(operation, array.length, offset, length, count);
            return call(owner, cbits, function, operation, bytes, ByteBuffer.wrap(array), array.length, offset, length, count);
        }
        if (bytes instanceof ManagedAllocation allocation) {
            synchronized (allocation) {
                // Exclude shrink and pointer-cell changes through the complete original C invocation.
                check(operation, allocation.getSize(), offset, length, count);
                return call(owner, cbits, function, operation, bytes, allocation.exposeSegment().asByteBuffer(),
                    allocation.getSize(), offset, length, count);
            }
        }
        throw fault("Text call requires the ByteArray# carrier");
    }

    private static void check(TextForeignOp operation, long size, long offset, long length, long count) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset)
            throw fault("Text ByteArray# range outside its backing storage");
        if (operation == TextForeignOp.MEMCHR && (count < 0 || count > 255))
            throw fault("Text memchr byte is outside Word8");
    }

    private static long call(Language.State owner, SulongCbits cbits, Object function, TextForeignOp operation,
                             Object bytes, ByteBuffer buffer, long size, long offset, long length, long count) {
        var view = new CbitsBuffer(buffer.asReadOnlyBuffer(), false, () -> size);
        var threads = owner.getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { return cbits.text(function, operation, view, offset, length, count); }
        finally {
            threads.leaveForeign(previous);
            Reference.reachabilityFence(bytes);
            Reference.reachabilityFence(view);
        }
    }
}
