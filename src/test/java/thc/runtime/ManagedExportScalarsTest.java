// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

public class ManagedExportScalarsTest {
    @Test public void unsignedWord64InteropChecksExactFloatingAndSignedRanges() throws Exception {
        var interop = InteropLibrary.getUncached(); var limit = BigInteger.ONE.shiftLeft(64);
        var values = new LinkedHashSet<BigInteger>(); values.add(BigInteger.ZERO); values.add(limit.subtract(BigInteger.ONE));
        for (int bit = 0; bit <= 63; bit++) for (int delta = -1; delta <= 1; delta++) {
            var candidate = BigInteger.ONE.shiftLeft(bit).add(BigInteger.valueOf(delta));
            if (candidate.signum() >= 0 && candidate.compareTo(limit) < 0) values.add(candidate);
        }
        for (int precision : new int[]{24, 53}) for (int delta = -1; delta <= 1; delta++)
            values.add(limit.subtract(BigInteger.ONE.shiftLeft(64 - precision)).add(BigInteger.valueOf(delta)));
        for (var expected : values) {
            var value = new UnsignedWord64(expected.longValue());
            assertEquals(expected, interop.asBigInteger(value));
            assertEquals(expected.compareTo(BigInteger.valueOf(Byte.MAX_VALUE)) <= 0, interop.fitsInByte(value));
            assertEquals(expected.compareTo(BigInteger.valueOf(Short.MAX_VALUE)) <= 0, interop.fitsInShort(value));
            assertEquals(expected.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0, interop.fitsInInt(value));
            assertEquals(expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0, interop.fitsInLong(value));
            float asFloat = expected.floatValue(); double asDouble = expected.doubleValue();
            boolean exactFloat = new BigDecimal((double) asFloat).toBigIntegerExact().equals(expected);
            boolean exactDouble = new BigDecimal(asDouble).toBigIntegerExact().equals(expected);
            assertEquals(exactFloat, interop.fitsInFloat(value), "binary32 " + expected);
            assertEquals(exactDouble, interop.fitsInDouble(value), "binary64 " + expected);
            if (exactFloat) assertEquals(Float.floatToRawIntBits(asFloat), Float.floatToRawIntBits(interop.asFloat(value)));
            else assertThrows(UnsupportedMessageException.class, () -> interop.asFloat(value));
            if (exactDouble) assertEquals(Double.doubleToRawLongBits(asDouble), Double.doubleToRawLongBits(interop.asDouble(value)));
            else assertThrows(UnsupportedMessageException.class, () -> interop.asDouble(value));
        }
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void inContext(Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private Map<String, Object> normalized(String module, String occurrence) {
        return Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", module, "occurrence", occurrence, "namespace", "type"), "arguments", List.of());
    }
    private Map<String, DataLayout> layouts(Language language) {
        String[][] rows = {
            {"GHC.Internal.Int.I8#", "I8#", "Int8Rep"}, {"GHC.Internal.Int.I16#", "I16#", "Int16Rep"},
            {"GHC.Internal.Int.I32#", "I32#", "Int32Rep"}, {"GHC.Internal.Int.I64#", "I64#", "Int64Rep"},
            {"GHC.Internal.Word.W8#", "W8#", "Word8Rep"}, {"GHC.Internal.Word.W16#", "W16#", "Word16Rep"},
            {"GHC.Internal.Word.W32#", "W32#", "Word32Rep"}, {"GHC.Internal.Word.W64#", "W64#", "Word64Rep"},
            {"GHC.Internal.Types.I#", "I#", "IntRep"}, {"GHC.Internal.Types.W#", "W#", "WordRep"},
            {"GHC.Internal.Types.F#", "F#", "FloatRep"}, {"GHC.Internal.Types.D#", "D#", "DoubleRep"},
            {"GHC.Internal.Types.C#", "C#", "WordRep"}, {"GHC.Internal.Types.True", "True", ""},
            {"GHC.Internal.Types.False", "False", ""}, {"GHC.Internal.Tuple.()", "()", ""}
        };
        var result = new LinkedHashMap<String, DataLayout>();
        for (var row : rows) {
            var fields = row[2].isEmpty() ? new String[0] : new String[]{row[2]}; var full = "ghc-internal:" + row[0];
            result.put(full, new DataLayout(language, full, row[1], fields));
        }
        return result;
    }
    private ManagedExportScalar codec(Map<String, DataLayout> layouts, String module, String occurrence) {
        return codec(layouts, module, occurrence, ManagedExportScalar.Role.ARGUMENT, 64);
    }
    private ManagedExportScalar codec(Map<String, DataLayout> layouts, String module, String occurrence, ManagedExportScalar.Role role, int wordBits) {
        return ManagedExportScalar.fromNormalizedType(normalized(module, occurrence), role, wordBits, id -> {
            var layout = layouts.get(id); if (layout == null) throw new IllegalStateException("Missing test layout: " + id); return layout;
        });
    }
    @Test public void signedAndUnsignedWidthsCheckIngressAndBoxedResult() throws Exception {
        inContext(language -> {
            var layouts = layouts(language);
            record Case(String module, String occurrence, BigInteger input) {}
            var cases = List.of(new Case("GHC.Internal.Int", "Int8", BigInteger.valueOf(-128)),
                new Case("GHC.Internal.Int", "Int16", BigInteger.valueOf(32767)), new Case("GHC.Internal.Int", "Int32", BigInteger.valueOf(Integer.MIN_VALUE)),
                new Case("GHC.Internal.Int", "Int64", BigInteger.valueOf(Long.MIN_VALUE)), new Case("GHC.Internal.Word", "Word8", BigInteger.valueOf(255)),
                new Case("GHC.Internal.Word", "Word16", BigInteger.valueOf(65535)), new Case("GHC.Internal.Word", "Word32", BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)));
            for (var item : cases) {
                var scalar = codec(layouts, item.module(), item.occurrence()); var boxed = scalar.fromHost(item.input()); var answer = (Number) scalar.toHost(boxed);
                assertEquals(item.input(), BigInteger.valueOf(answer.longValue()), item.occurrence());
                var outside = switch (item.occurrence()) { case "Int8", "Int32", "Int64" -> item.input().subtract(BigInteger.ONE); default -> item.input().add(BigInteger.ONE); };
                assertThrows(RuntimeFault.class, () -> scalar.fromHost(outside));
                if (item.module().equals("GHC.Internal.Word")) assertThrows(RuntimeFault.class, () -> scalar.fromHost(-1));
                assertThrows(RuntimeFault.class, () -> scalar.fromHost(1.5));
                assertThrows(RuntimeFault.class, () -> scalar.toHost(layouts.get("ghc-internal:GHC.Internal.Types.I#").createLong(1)));
            }
            for (int bits : new int[]{32, 64}) {
                var signed = codec(layouts, "GHC.Internal.Types", "Int", ManagedExportScalar.Role.ARGUMENT, bits);
                var unsigned = codec(layouts, "GHC.Internal.Types", "Word", ManagedExportScalar.Role.ARGUMENT, bits);
                assertThrows(RuntimeFault.class, () -> signed.fromHost(BigInteger.ONE.shiftLeft(bits - 1)));
                assertThrows(RuntimeFault.class, () -> unsigned.fromHost(BigInteger.ONE.shiftLeft(bits)));
            }
        });
    }
    @Test public void word64UpperHalfIsInteropBigIntegerNeverNegativeHostLong() throws Exception {
        inContext(language -> {
            var scalar = codec(layouts(language), "GHC.Internal.Word", "Word64"); var interop = InteropLibrary.getUncached(); var maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
            for (var number : List.of(BigInteger.ZERO, BigInteger.ONE.shiftLeft(63), maximum)) {
                var boxed = scalar.fromHost(number); var result = Objects.requireNonNull(scalar.toHost(boxed));
                assertTrue(interop.isNumber(result)); assertTrue(interop.fitsInBigInteger(result)); assertEquals(number, interop.asBigInteger(result));
                var publicValue = Context.getCurrent().asValue(result); assertTrue(publicValue.isNumber()); assertEquals(number, publicValue.asBigInteger());
                assertEquals(number.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0, interop.fitsInLong(result));
                if (number.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) assertThrows(UnsupportedMessageException.class, () -> interop.asLong(result));
            }
            assertThrows(RuntimeFault.class, () -> scalar.fromHost(BigInteger.ONE.shiftLeft(64)));
            assertThrows(RuntimeFault.class, () -> scalar.fromHost(BigInteger.valueOf(-1)));
        });
    }
    @Test public void floatsBoolCharAndUnitRetainExactBoxedConventions() throws Exception {
        inContext(language -> {
            var layouts = layouts(language); var floating = codec(layouts, "GHC.Internal.Types", "Float"); var doubles = codec(layouts, "GHC.Internal.Types", "Double");
            for (float number : new float[]{-0.0f, Float.NaN, Float.POSITIVE_INFINITY}) {
                var answer = (Float) floating.toHost(floating.fromHost(number)); assertEquals(Float.floatToRawIntBits(number), Float.floatToRawIntBits(answer));
            }
            for (double number : new double[]{-0.0, Double.NaN, Double.NEGATIVE_INFINITY}) {
                var answer = (Double) doubles.toHost(doubles.fromHost(number)); assertEquals(Double.doubleToRawLongBits(number), Double.doubleToRawLongBits(answer));
            }
            var bool = codec(layouts, "GHC.Internal.Types", "Bool");
            assertEquals(true, bool.toHost(bool.fromHost(true))); assertEquals(false, bool.toHost(bool.fromHost(false)));
            assertThrows(RuntimeFault.class, () -> bool.fromHost(1));
            var character = codec(layouts, "GHC.Internal.Types", "Char");
            assertEquals("😀", character.toHost(character.fromHost("😀"))); assertEquals("A", character.toHost(character.fromHost(65)));
            assertEquals("\ud800", character.toHost(character.fromHost("\ud800"))); assertEquals("\udfff", character.toHost(character.fromHost(0xdfff)));
            for (var invalid : List.of("", "AB", 0x110000, -1)) assertThrows(RuntimeFault.class, () -> character.fromHost(invalid));
            var unit = codec(layouts, "GHC.Internal.Tuple", "Unit", ManagedExportScalar.Role.RESULT, 64);
            var result = unit.toHost(layouts.get("ghc-internal:GHC.Internal.Tuple.()").allocate());
            assertTrue(InteropLibrary.getUncached().isNull(result)); assertTrue(Context.getCurrent().asValue(result).isNull());
            assertThrows(RuntimeFault.class, () -> codec(layouts, "GHC.Internal.Tuple", "Unit", ManagedExportScalar.Role.ARGUMENT, 64));
        });
    }
    @Test public void exactTypeAndProgramOwnedLayoutProofsRejectLookalikes() throws Exception {
        inContext(language -> {
            var layouts = layouts(language); var type = normalized("GHC.Internal.Int", "Int8");
            assertThrows(RuntimeFault.class, () -> {
                var applied = new LinkedHashMap<>(type); applied.put("arguments", List.of(type));
                ManagedExportScalar.fromNormalizedType(applied, ManagedExportScalar.Role.ARGUMENT, 64, id -> Objects.requireNonNull(layouts.get(id)));
            });
            assertThrows(RuntimeFault.class, () -> ManagedExportScalar.fromNormalizedType(normalized("GHC.Internal.Word", "Int8"), ManagedExportScalar.Role.ARGUMENT, 64, id -> Objects.requireNonNull(layouts.get(id))));
            var wrong = new LinkedHashMap<>(layouts); wrong.put("ghc-internal:GHC.Internal.Int.I8#", new DataLayout(language, "ghc-internal:GHC.Internal.Int.I8#", "I8#", new String[]{"Word8Rep"}));
            assertThrows(RuntimeFault.class, () -> codec(wrong, "GHC.Internal.Int", "Int8"));
            var anotherProgram = layouts(language); var scalar = codec(layouts, "GHC.Internal.Int", "Int8");
            assertThrows(RuntimeFault.class, () -> scalar.toHost(anotherProgram.get("ghc-internal:GHC.Internal.Int.I8#").createLong(7)));
        });
    }
}
