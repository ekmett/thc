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
    @Child private InteropLibrary names = InteropLibrary.getFactory().createDispatched(3);
    @Child private ForeignExceptionAccess exceptions = new ForeignExceptionAccess();

    public Object execute(PolyglotOp operation, Object[] arguments) {
        TupleResults.requireVoidCarrier(arguments[arguments.length - 1]);
        var owner = Language.currentState(this);
        Object receiver = arguments.length > 1 ? arguments[0] : null;
        if (operation == PolyglotOp.IMPORT_VALUE) {
            if (!(receiver instanceof ForeignValue value) || value.getOwner() != owner)
                throw RuntimeFault.fault("Expected a context-owned THC.Polyglot.Value");
            return value.getReceiver();
        }
        if (operation.directJava()) {
            try {
                return switch (operation) {
                    case BOX_JAVA_BOOLEAN -> ((Long) arguments[0]) != 0L;
                    case NEW_JAVA_BOOLEAN_ARRAY -> new boolean[Math.toIntExact((Long) receiver)];
                    case JAVA_BOOLEAN_ARRAY_LENGTH -> (long) ((boolean[]) receiver).length;
                    case READ_JAVA_BOOLEAN_ARRAY -> { boolean value = ((boolean[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield value ? 1L : 0L; }
                    case WRITE_JAVA_BOOLEAN_ARRAY -> { ((boolean[]) receiver)[Math.toIntExact((Long) arguments[1])] = ((Long) arguments[2]) != 0L; yield Unit.INSTANCE; }
                    case COPY_JAVA_BOOLEAN_ARRAY -> { System.arraycopy((boolean[]) receiver, Math.toIntExact((Long) arguments[1]), (boolean[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_BOOLEAN_ARRAY -> (boolean[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_BYTE -> (byte) (int) (Integer) arguments[0];
                    case NEW_JAVA_BYTE_ARRAY -> new byte[Math.toIntExact((Long) receiver)];
                    case JAVA_BYTE_ARRAY_LENGTH -> (long) ((byte[]) receiver).length;
                    case READ_JAVA_BYTE_ARRAY -> { byte value = ((byte[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield (int) value; }
                    case WRITE_JAVA_BYTE_ARRAY -> { ((byte[]) receiver)[Math.toIntExact((Long) arguments[1])] = (byte) (int) (Integer) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_BYTE_ARRAY -> { System.arraycopy((byte[]) receiver, Math.toIntExact((Long) arguments[1]), (byte[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_BYTE_ARRAY -> (byte[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_SHORT -> (short) (int) (Integer) arguments[0];
                    case NEW_JAVA_SHORT_ARRAY -> new short[Math.toIntExact((Long) receiver)];
                    case JAVA_SHORT_ARRAY_LENGTH -> (long) ((short[]) receiver).length;
                    case READ_JAVA_SHORT_ARRAY -> { short value = ((short[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield (int) value; }
                    case WRITE_JAVA_SHORT_ARRAY -> { ((short[]) receiver)[Math.toIntExact((Long) arguments[1])] = (short) (int) (Integer) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_SHORT_ARRAY -> { System.arraycopy((short[]) receiver, Math.toIntExact((Long) arguments[1]), (short[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_SHORT_ARRAY -> (short[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_CHAR -> (char) (int) (Integer) arguments[0];
                    case NEW_JAVA_CHAR_ARRAY -> new char[Math.toIntExact((Long) receiver)];
                    case JAVA_CHAR_ARRAY_LENGTH -> (long) ((char[]) receiver).length;
                    case READ_JAVA_CHAR_ARRAY -> { char value = ((char[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield (int) value; }
                    case WRITE_JAVA_CHAR_ARRAY -> { ((char[]) receiver)[Math.toIntExact((Long) arguments[1])] = (char) (int) (Integer) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_CHAR_ARRAY -> { System.arraycopy((char[]) receiver, Math.toIntExact((Long) arguments[1]), (char[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_CHAR_ARRAY -> (char[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_INT -> (Integer) arguments[0];
                    case NEW_JAVA_INT_ARRAY -> new int[Math.toIntExact((Long) receiver)];
                    case JAVA_INT_ARRAY_LENGTH -> (long) ((int[]) receiver).length;
                    case READ_JAVA_INT_ARRAY -> { int value = ((int[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield value; }
                    case WRITE_JAVA_INT_ARRAY -> { ((int[]) receiver)[Math.toIntExact((Long) arguments[1])] = (Integer) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_INT_ARRAY -> { System.arraycopy((int[]) receiver, Math.toIntExact((Long) arguments[1]), (int[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_INT_ARRAY -> (int[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_LONG -> (Long) arguments[0];
                    case NEW_JAVA_LONG_ARRAY -> new long[Math.toIntExact((Long) receiver)];
                    case JAVA_LONG_ARRAY_LENGTH -> (long) ((long[]) receiver).length;
                    case READ_JAVA_LONG_ARRAY -> { long value = ((long[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield value; }
                    case WRITE_JAVA_LONG_ARRAY -> { ((long[]) receiver)[Math.toIntExact((Long) arguments[1])] = (Long) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_LONG_ARRAY -> { System.arraycopy((long[]) receiver, Math.toIntExact((Long) arguments[1]), (long[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_LONG_ARRAY -> (long[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_FLOAT -> (Float) arguments[0];
                    case NEW_JAVA_FLOAT_ARRAY -> new float[Math.toIntExact((Long) receiver)];
                    case JAVA_FLOAT_ARRAY_LENGTH -> (long) ((float[]) receiver).length;
                    case READ_JAVA_FLOAT_ARRAY -> { float value = ((float[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield value; }
                    case WRITE_JAVA_FLOAT_ARRAY -> { ((float[]) receiver)[Math.toIntExact((Long) arguments[1])] = (Float) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_FLOAT_ARRAY -> { System.arraycopy((float[]) receiver, Math.toIntExact((Long) arguments[1]), (float[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_FLOAT_ARRAY -> (float[]) java.util.Objects.requireNonNull(receiver);
                    case BOX_JAVA_DOUBLE -> (Double) arguments[0];
                    case NEW_JAVA_DOUBLE_ARRAY -> new double[Math.toIntExact((Long) receiver)];
                    case JAVA_DOUBLE_ARRAY_LENGTH -> (long) ((double[]) receiver).length;
                    case READ_JAVA_DOUBLE_ARRAY -> { double value = ((double[]) receiver)[Math.toIntExact((Long) arguments[1])]; yield value; }
                    case WRITE_JAVA_DOUBLE_ARRAY -> { ((double[]) receiver)[Math.toIntExact((Long) arguments[1])] = (Double) arguments[2]; yield Unit.INSTANCE; }
                    case COPY_JAVA_DOUBLE_ARRAY -> { System.arraycopy((double[]) receiver, Math.toIntExact((Long) arguments[1]), (double[]) arguments[2], Math.toIntExact((Long) arguments[3]), Math.toIntExact((Long) arguments[4])); yield Unit.INSTANCE; }
                    case OBJECT_AS_JAVA_DOUBLE_ARRAY -> (double[]) java.util.Objects.requireNonNull(receiver);
                    default -> throw new AssertionError(operation);
                };
            }
            catch (AbstractTruffleException failure) { throw exceptions.raise(failure); }
            catch (RuntimeException failure) { throw exceptions.raiseHost(failure); }
        }
        var library = operation == PolyglotOp.LOOKUP_HOST_SYMBOL || operation == PolyglotOp.AS_GUEST_VALUE || operation == PolyglotOp.AS_BOXED_GUEST_VALUE || operation == PolyglotOp.JAVA_NULL || operation == PolyglotOp.JAVA_STRING_UTF8 ? null : identity.profile((InteropLibrary) arguments[1]);
        var threads = owner.getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try {
                    if (library != null && !library.accepts(receiver)) throw UnsupportedTypeException.create(new Object[]{receiver}, "Receiver not accepted by the supplied dispatcher");
                    return switch (operation) {
                        case LOOKUP_HOST_SYMBOL -> owner.getEnv().lookupHostSymbol(names.asString(receiver));
                        case AS_GUEST_VALUE -> owner.getEnv().asGuestValue(receiver);
                        case AS_BOXED_GUEST_VALUE -> owner.getEnv().asBoxedGuestValue(receiver);
                        case JAVA_NULL -> owner.getEnv().asGuestValue(null);
                        case JAVA_STRING_UTF8 -> ((ManagedAddress) receiver).utf8();
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
                        case AS_HOST_OBJECT -> library.asHostObject(receiver);
                        case EXECUTE -> library.execute(receiver, (Object[]) arguments[2]);
                        case INSTANTIATE -> library.instantiate(receiver, (Object[]) arguments[2]);
                        case INTEROP_READ_MEMBER -> library.readMember(receiver, names.asString(arguments[2]));
                        case INTEROP_WRITE_MEMBER -> { library.writeMember(receiver, names.asString(arguments[2]), arguments[3]); yield Unit.INSTANCE; }
                        case INVOKE_MEMBER -> library.invokeMember(receiver, names.asString(arguments[2]), (Object[]) arguments[3]);
                        case UNBOX_JAVA_BOOLEAN -> library.asBoolean(receiver) ? 1L : 0L;
                        case UNBOX_JAVA_BYTE -> (int) library.asByte(receiver);
                        case UNBOX_JAVA_SHORT -> (int) library.asShort(receiver);
                        case UNBOX_JAVA_CHAR -> character(library.asString(receiver));
                        case UNBOX_JAVA_INT -> library.asInt(receiver);
                        case UNBOX_JAVA_LONG -> library.asLong(receiver);
                        case UNBOX_JAVA_FLOAT -> library.asFloat(receiver);
                        case UNBOX_JAVA_DOUBLE -> library.asDouble(receiver);
                        case IS_STRING -> library.isString(receiver) ? 1L : 0L;
                        case AS_TRUFFLE_STRING -> library.asTruffleString(receiver);
                        default -> throw new AssertionError(operation);
                    };
                } catch (InteropException failure) { throw InteropFailure.create(failure); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException failure) { throw exceptions.raise(failure); }
    }
    private static int character(String value) throws UnsupportedMessageException {
        if (value.length() != 1) throw UnsupportedMessageException.create();
        return value.charAt(0);
    }
}
