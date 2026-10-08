// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import static thc.runtime.RuntimeFault.fault;

/** The boxed scalar portion of a verified static foreign-export signature. */
public final class ManagedExportScalar {
    public enum Role { ARGUMENT, RESULT }
    private enum Kind { SIGNED, UNSIGNED, FLOAT, DOUBLE, CHAR, BOOL, UNIT, ADDRESS, STABLE_POINTER }
    private final Kind kind;
    private final int bits;
    private final DataLayout layout;
    private final DataLayout falseLayout;
    private final long minimum;
    private final long maximum;
    private ManagedExportScalar(Kind kind, int bits, DataLayout layout, DataLayout falseLayout) {
        this.kind = kind; this.bits = bits; this.layout = layout; this.falseLayout = falseLayout;
        minimum = kind == Kind.SIGNED && bits < 64 ? -(1L << (bits - 1)) : kind == Kind.SIGNED ? Long.MIN_VALUE : 0L;
        maximum = kind == Kind.SIGNED && bits < 64 ? (1L << (bits - 1)) - 1
            : kind == Kind.UNSIGNED && bits < 64 ? (1L << bits) - 1 : kind == Kind.CHAR ? 0x10ffffL : Long.MAX_VALUE;
    }
    private static final BigInteger MAX_WORD64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private record Specification(String owner, String constructor, String rep, Kind kind, int bits) {}
    private static final Map<String, Specification> SPECIFICATIONS = Map.ofEntries(
        Map.entry("Int8", new Specification("GHC.Internal.Int", "I8#", "Int8Rep", Kind.SIGNED, 8)),
        Map.entry("Int16", new Specification("GHC.Internal.Int", "I16#", "Int16Rep", Kind.SIGNED, 16)),
        Map.entry("Int32", new Specification("GHC.Internal.Int", "I32#", "Int32Rep", Kind.SIGNED, 32)),
        Map.entry("Int64", new Specification("GHC.Internal.Int", "I64#", "Int64Rep", Kind.SIGNED, 64)),
        Map.entry("Word8", new Specification("GHC.Internal.Word", "W8#", "Word8Rep", Kind.UNSIGNED, 8)),
        Map.entry("Word16", new Specification("GHC.Internal.Word", "W16#", "Word16Rep", Kind.UNSIGNED, 16)),
        Map.entry("Word32", new Specification("GHC.Internal.Word", "W32#", "Word32Rep", Kind.UNSIGNED, 32)),
        Map.entry("Word64", new Specification("GHC.Internal.Word", "W64#", "Word64Rep", Kind.UNSIGNED, 64)),
        Map.entry("Int", new Specification("GHC.Internal.Types", "I#", "IntRep", Kind.SIGNED, 0)),
        Map.entry("Word", new Specification("GHC.Internal.Types", "W#", "WordRep", Kind.UNSIGNED, 0)),
        Map.entry("Float", new Specification("GHC.Internal.Types", "F#", "FloatRep", Kind.FLOAT, 0)),
        Map.entry("Double", new Specification("GHC.Internal.Types", "D#", "DoubleRep", Kind.DOUBLE, 0)),
        Map.entry("Char", new Specification("GHC.Internal.Types", "C#", "WordRep", Kind.CHAR, 0)),
        Map.entry("Bool", new Specification("GHC.Internal.Types", "True", "", Kind.BOOL, 0)),
        Map.entry("Unit", new Specification("GHC.Internal.Tuple", "()", "", Kind.UNIT, 0)));

    /** layoutById must resolve constructors from the same executable as the exported binder. */
    public static ManagedExportScalar fromNormalizedType(Map<String, ?> type, Role role, int wordBits,
                                                        Function<String, DataLayout> layoutById) {
        var spec = specification(type, role, wordBits);
        var id = "ghc-internal:" + spec.owner + "." + spec.constructor;
        var layout = layoutById.apply(id);
        requireLayout(spec, layout);
        var falseLayout = spec.kind == Kind.BOOL ? layoutById.apply("ghc-internal:GHC.Internal.Types.False") : null;
        if (falseLayout != null && (!falseLayout.getId().equals("ghc-internal:GHC.Internal.Types.False") ||
            !falseLayout.getName().equals("False") || falseLayout.getArity() != 0))
            throw fault("Foreign-export Bool constructor layout mismatch");
        return new ManagedExportScalar(spec.kind, spec.bits == 0 ? wordBits : spec.bits, layout, falseLayout);
    }
    private static void requireLayout(Specification spec, DataLayout layout) {
        var id = "ghc-internal:" + spec.owner + "." + spec.constructor;
        if (!layout.getId().equals(id) || !layout.getName().equals(spec.constructor) ||
            layout.getArity() != (spec.rep.isEmpty() ? 0 : 1) ||
            (!spec.rep.isEmpty() && !layout.hasFieldRepresentation(0, spec.rep)))
            throw fault("Foreign-export constructor layout mismatch: " + id);
    }
    /** Observe a genuine numeric constructor; unrelated one-field records remain opaque. */
    @TruffleBoundary public static Object toInteropNumber(DataValue value) {
        var layout = value.getLayout();
        for (var spec : SPECIFICATIONS.values()) {
            if (spec.kind != Kind.SIGNED && spec.kind != Kind.UNSIGNED && spec.kind != Kind.FLOAT && spec.kind != Kind.DOUBLE) continue;
            if (!layout.getId().equals("ghc-internal:" + spec.owner + "." + spec.constructor)) continue;
            requireLayout(spec, layout);
            return new ManagedExportScalar(spec.kind, spec.bits == 0 ? Long.SIZE : spec.bits, layout, null).toHost(value);
        }
        return null;
    }
    private static Specification specification(Map<String, ?> type, Role role, int wordBits) {
        if (wordBits != 32 && wordBits != 64) throw fault("Unsupported foreign-export target word width");
        if (!type.keySet().equals(Set.of("kind", "name", "arguments")) || !"tycon".equals(type.get("kind")) ||
            !(type.get("arguments") instanceof List<?> typeArguments)) throw fault("Unsupported foreign-export scalar type");
        if (!(type.get("name") instanceof Map<?, ?> name)) throw fault("Missing foreign-export type identity");
        if (!name.keySet().equals(Set.of("unit", "module", "occurrence", "namespace")) ||
            !"ghc-internal".equals(name.get("unit")) || !"type".equals(name.get("namespace")))
            throw fault("Unsupported foreign-export type identity");
        if (!(name.get("occurrence") instanceof String occurrence)) throw fault("Missing foreign-export type occurrence");
        var spec = "GHC.Internal.Ptr".equals(name.get("module")) && Set.of("Ptr", "FunPtr").contains(occurrence) && typeArguments.size() == 1
            ? new Specification("GHC.Internal.Ptr", occurrence, "AddrRep", Kind.ADDRESS, wordBits)
            : "GHC.Internal.Stable".equals(name.get("module")) && occurrence.equals("StablePtr") && typeArguments.size() == 1
                ? new Specification("GHC.Internal.Stable", "StablePtr", "AddrRep", Kind.STABLE_POINTER, wordBits) : SPECIFICATIONS.get(occurrence);
        if (spec == null) throw fault("Unsupported foreign-export scalar: " + occurrence);
        if (spec.kind != Kind.ADDRESS && spec.kind != Kind.STABLE_POINTER && !typeArguments.isEmpty()) throw fault("Unsupported foreign-export scalar type arguments");
        if (!spec.owner.equals(name.get("module"))) throw fault("Foreign-export scalar owner mismatch: " + occurrence);
        if (spec.kind == Kind.UNIT && role == Role.ARGUMENT) throw fault("GHC does not marshal a unit foreign-export argument");
        return spec;
    }
    private long checkedLong(long number) {
        if (number < minimum || number > maximum) throw fault("Foreign-export integral argument is out of range");
        return number;
    }
    private long checkedBigInteger(BigInteger number) {
        if (kind == Kind.UNSIGNED && bits == 64) {
            if (number.signum() < 0 || number.compareTo(MAX_WORD64) > 0) throw fault("Foreign-export integral argument is out of range");
            return number.longValue(); // Preserve the unsigned high bit in the primitive field.
        }
        long narrow;
        try { narrow = number.longValueExact(); }
        catch (ArithmeticException failure) { throw fault("Foreign-export integral argument is out of range"); }
        return checkedLong(narrow);
    }
    private long checkedInteger(Object value, InteropLibrary interop) {
        if (value instanceof BigInteger number) return checkedBigInteger(number); // Raw Java BigInteger is not Truffle numeric.
        if (value == null || !interop.isNumber(value)) throw fault("Expected an exact integral foreign-export argument");
        try {
            if (interop.fitsInLong(value)) return checkedLong(interop.asLong(value));
            if (kind == Kind.UNSIGNED && bits == 64 && interop.fitsInBigInteger(value)) return checkedBigInteger(interop.asBigInteger(value));
        } catch (UnsupportedMessageException failure) { /* The advertised conversion was withdrawn. */ }
        throw fault("Expected an exact integral foreign-export argument");
    }
    private long codePoint(Object value, InteropLibrary interop) {
        long point;
        if (value != null && interop.isString(value)) {
            String string;
            try { string = interop.asString(value); }
            catch (UnsupportedMessageException failure) { throw fault("Expected one foreign-export character"); }
            if (string.codePointCount(0, string.length()) != 1) throw fault("Expected one foreign-export character");
            point = string.codePointAt(0);
        } else point = checkedInteger(value, interop);
        if (point > 0x10ffff) throw fault("Foreign-export character is out of range");
        return point;
    }
    public DataValue fromHost(Object value) { return fromHost(value, InteropLibrary.getUncached()); }
    public DataValue fromHost(Object value, InteropLibrary interop) {
        return switch (kind) {
            case ADDRESS, STABLE_POINTER -> {
                if (!(value instanceof ManagedAddress address)) throw fault("Expected a checked foreign-export address");
                if (kind == Kind.STABLE_POINTER) thc.Language.currentState(null).getStablePointers().validate(address);
                var result = layout.allocate(); layout.initialize(result, 0, address); yield result;
            }
            case SIGNED, UNSIGNED -> {
                long number = checkedInteger(value, interop);
                yield layout.isInt(0) ? layout.createInt((int) number) : layout.createLong(number);
            }
            case CHAR -> layout.createLong(codePoint(value, interop));
            case BOOL -> {
                if (value == null || !interop.isBoolean(value)) throw fault("Expected a foreign-export Boolean");
                boolean truth;
                try { truth = interop.asBoolean(value); }
                catch (UnsupportedMessageException failure) { throw fault("Expected a foreign-export Boolean"); }
                yield (truth ? layout : falseLayout).allocate();
            }
            case FLOAT -> {
                if (value == null || !interop.isNumber(value) || !interop.fitsInFloat(value)) throw fault("Expected a foreign-export Float");
                float number;
                try { number = interop.asFloat(value); }
                catch (UnsupportedMessageException failure) { throw fault("Expected a foreign-export Float"); }
                var result = layout.allocate(); layout.initializeFloat(result, 0, number); yield result;
            }
            case DOUBLE -> {
                if (value == null || !interop.isNumber(value) || !interop.fitsInDouble(value)) throw fault("Expected a foreign-export Double");
                double number;
                try { number = interop.asDouble(value); }
                catch (UnsupportedMessageException failure) { throw fault("Expected a foreign-export Double"); }
                var result = layout.allocate(); layout.initializeDouble(result, 0, number); yield result;
            }
            case UNIT -> throw fault("GHC does not marshal a unit foreign-export argument");
        };
    }
    /** The caller must force the lifted result at its resumable guest boundary first. */
    public Object toHost(Object forcedValue) {
        if (!(forcedValue instanceof DataValue value)) throw fault("Foreign-export result is not a boxed scalar");
        if (kind == Kind.BOOL) {
            if (layout.matches(value)) return true;
            if (falseLayout.matches(value)) return false;
            throw fault("Foreign-export Boolean constructor mismatch");
        }
        if (!layout.matches(value)) throw fault("Foreign-export result constructor mismatch");
        return switch (kind) {
            case SIGNED -> checkedGuestSigned(layout.isInt(0) ? layout.readInt(value, 0) : layout.readLong(value, 0));
            case UNSIGNED -> checkedGuestUnsigned(layout.isInt(0) ? Integer.toUnsignedLong(layout.readInt(value, 0)) : layout.readLong(value, 0));
            case CHAR -> {
                long point = layout.readLong(value, 0);
                if (point < 0 || point > 0x10ffffL) throw fault("Foreign-export result character is out of range");
                yield new String(Character.toChars((int) point));
            }
            case FLOAT -> layout.readFloat(value, 0);
            case DOUBLE -> layout.readDouble(value, 0);
            case UNIT -> ForeignExportUnit.INSTANCE;
            case ADDRESS -> layout.read(value, 0);
            case STABLE_POINTER -> {
                var address = (ManagedAddress) layout.read(value, 0);
                thc.Language.currentState(null).getStablePointers().validate(address); yield address;
            }
            case BOOL -> throw new IllegalStateException("handled above");
        };
    }
    public String nativeRepresentation() {
        return nativeRepresentation(kind, bits);
    }
    /** Signature selection does not prepare/evaluate the exported Haskell closure. */
    public static String nativeRepresentation(Map<String, ?> type, Role role, int wordBits) {
        var spec = specification(type, role, wordBits);
        return nativeRepresentation(spec.kind, spec.bits == 0 ? wordBits : spec.bits);
    }
    private static String nativeRepresentation(Kind kind, int bits) {
        return switch (kind) {
            case SIGNED -> "Int" + bits + "Rep";
            case UNSIGNED -> "Word" + bits + "Rep";
            case FLOAT -> "FloatRep"; case DOUBLE -> "DoubleRep"; case ADDRESS, STABLE_POINTER -> "AddrRep";
            case CHAR -> "Word32Rep"; case BOOL -> "Int" + bits + "Rep"; case UNIT -> "void";
        };
    }
    /** NFI's signed carriers transport unsigned values by preserving their bits. */
    public DataValue fromNative(Object value, NativeCallbacks callbacks) {
        try {
            if (kind == Kind.ADDRESS || kind == Kind.STABLE_POINTER) return fromHost(callbacks.incoming(value));
            var interop = InteropLibrary.getUncached();
            if (kind == Kind.UNSIGNED) {
                long raw = interop.asLong(value);
                long number = bits == 64 ? raw : raw & ((1L << bits) - 1);
                return layout.isInt(0) ? layout.createInt((int) number) : layout.createLong(number);
            }
            if (kind == Kind.BOOL) return fromHost(interop.asLong(value) != 0);
            return fromHost(value, interop);
        } catch (UnsupportedMessageException failure) { throw fault("Native callback argument differs from declared ABI"); }
    }
    public Object toNative(Object value, NativeCallbacks callbacks) {
        Object result = toHost(value); // Retain the exact constructor/range checks.
        if (kind == Kind.ADDRESS || kind == Kind.STABLE_POINTER) return callbacks.outgoing((ManagedAddress) result);
        if (kind == Kind.BOOL) return Boolean.TRUE.equals(result) ? 1L : 0L;
        if (kind == Kind.UNIT) return 0;
        if (kind == Kind.CHAR) return ((String) result).codePointAt(0);
        if (kind == Kind.UNSIGNED) {
            var data = (DataValue) value;
            long raw = layout.isInt(0) ? layout.readInt(data, 0) : layout.readLong(data, 0);
            return switch (bits) { case 8 -> (byte) raw; case 16 -> (short) raw; case 32 -> (int) raw; default -> raw; };
        }
        return result;
    }
    private Object checkedGuestSigned(long value) {
        if (value < minimum || value > maximum) throw fault("Foreign-export signed result is out of range");
        return switch (bits) { case 8 -> Byte.valueOf((byte) value); case 16 -> Short.valueOf((short) value);
            case 32 -> Integer.valueOf((int) value); default -> Long.valueOf(value); };
    }
    private Object checkedGuestUnsigned(long value) {
        if (bits < 64 && (value < minimum || value > maximum)) throw fault("Foreign-export unsigned result is out of range");
        return switch (bits) { case 8, 16 -> Integer.valueOf((int) value); case 32 -> Long.valueOf(value);
            default -> value >= 0 ? Long.valueOf(value) : new UnsignedWord64(value); };
    }
}
