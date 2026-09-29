// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ValueProfile;
import thc.Language;

/** Typed use-site identity profiling, never a second library cache or adoption site. */
public final class InteropAccess extends Node {
    private final ValueProfile identity = ValueProfile.createIdentityProfile();
    @Child private ForeignExceptionAccess exceptions = new ForeignExceptionAccess();

    public Object execute(PolyglotOp operation, Object[] arguments) {
        TupleResults.requireVoidCarrier(arguments[arguments.length - 1]);
        var owner = Language.currentState(this);
        Object receiver = arguments[0];
        if (operation == PolyglotOp.IMPORT_VALUE) {
            if (!(receiver instanceof ForeignValue value) || value.getOwner() != owner)
                throw RuntimeFault.fault("Expected a context-owned THC.Polyglot.Value");
            return value.getReceiver();
        }
        var library = identity.profile((InteropLibrary) arguments[1]);
        var threads = owner.getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try {
                    if (!library.accepts(receiver)) throw UnsupportedTypeException.create(new Object[]{receiver}, "Receiver not accepted by the supplied dispatcher");
                    return switch (operation) {
                        case HAS_BUFFER_ELEMENTS -> library.hasBufferElements(receiver) ? 1L : 0L;
                        case IS_BUFFER_WRITABLE -> library.isBufferWritable(receiver) ? 1L : 0L;
                        case GET_BUFFER_SIZE -> library.getBufferSize(receiver);
                        case READ_BUFFER_BYTE -> (int) library.readBufferByte(receiver, (Long) arguments[2]);
                        case WRITE_BUFFER_BYTE -> { library.writeBufferByte(receiver, (Long) arguments[2], (byte) (int) (Integer) arguments[3]); yield Unit.INSTANCE; }
                        case HAS_ARRAY_ELEMENTS -> library.hasArrayElements(receiver) ? 1L : 0L;
                        case GET_ARRAY_SIZE -> library.getArraySize(receiver);
                        case READ_ARRAY_ELEMENT -> library.readArrayElement(receiver, (Long) arguments[2]);
                        case WRITE_ARRAY_ELEMENT -> { library.writeArrayElement(receiver, (Long) arguments[2], arguments[3]); yield Unit.INSTANCE; }
                        case AS_LONG -> library.asLong(receiver);
                        case IS_STRING -> library.isString(receiver) ? 1L : 0L;
                        case AS_TRUFFLE_STRING -> library.asTruffleString(receiver);
                        default -> throw new AssertionError(operation);
                    };
                } catch (InteropException failure) { throw InteropFailure.create(failure); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException failure) { throw exceptions.raise(failure); }
    }
}
