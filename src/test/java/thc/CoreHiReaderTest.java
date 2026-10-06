// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreHiReaderTest {
    private static final Path FIXTURE = Path.of("build/native-hi-reader");
    private static Path path(String mode) { return FIXTURE.resolve(mode).resolve("NativeHiFixture.hi"); }

    @Test void charactersAndTicksPreserveTheExecutedChild() throws Exception {
        var reader = CoreHiReader.read(path("normal"));
        var module = new CoreHiModule(reader);
        var constructors = module.constructorLayouts();
        var admission = module.admission();
        int unit = reader.strings.indexOf(reader.module.unit());
        int owner = reader.strings.indexOf(reader.module.name());
        assertTrue(unit >= 0 && owner >= 0);
        for (int point : new int[]{0, 'A', 0xd800, 0xdc00, 0x10000, 0x10ffff}) {
            var literal = new java.io.ByteArrayOutputStream();
            literal.write(9); literal.write(0); unsigned(literal, point);
            var plain = nativeExpression(reader, module, literal.toByteArray());
            assertEquals(List.of("lit", "char", Integer.toString(point)), plain.subList(0, 3));
            var proof = (Map<?,?>) ((Map<?,?>) plain.getLast()).get("rep");
            assertEquals(List.of("WordRep"), proof.get("primReps"));
            assertEquals(true, proof.get("evaluated"));
        }
        var literal = new java.io.ByteArrayOutputStream();
        literal.write(9); literal.write(0); unsigned(literal, 'A');
        var plain = nativeExpression(reader, module, literal.toByteArray());
        for (int tick : new int[]{0, 1, 2, 3}) {
            for (boolean flag : tick == 1 ? List.of(false, true) : List.of(false)) {
                var bytes = new java.io.ByteArrayOutputStream();
                bytes.write(8); bytes.write(tick);
                if (tick == 1) {
                    bytes.write(0); // NormalCC, IndexedCC flavour.
                    bytes.write(1); bytes.write(4); bytes.write(0);
                    unsigned(bytes, owner);
                }
                if (tick <= 1 || tick == 3) {
                    bytes.write(0); unsigned(bytes, unit); unsigned(bytes, owner);
                }
                if (tick == 0 || tick == 3) bytes.write(0);
                if (tick == 1) { bytes.write(flag ? 1 : 0); bytes.write(flag ? 0 : 1); }
                if (tick == 2) {
                    unsigned(bytes, owner);
                    bytes.writeBytes(new byte[]{1, 1, 1, 2});
                    unsigned(bytes, owner);
                }
                if (tick == 3) {
                    bytes.write(2); // Captured expressions are decoded, never executed/indexed.
                    bytes.writeBytes(new byte[]{3, 1, 0}); // Empty unboxed tuple.
                    bytes.writeBytes(new byte[]{10, 1, 0, 0, 1}); // Dynamic CCall, unsafe, type variable.
                    unsigned(bytes, owner);
                }
                bytes.writeBytes(literal.toByteArray());
                assertEquals(plain, nativeExpression(reader, module, bytes.toByteArray()));
                assertEquals(constructors, module.constructorLayouts());
                assertEquals(admission, module.admission());
            }
        }
        var invalid = new java.io.ByteArrayOutputStream();
        invalid.write(9); invalid.write(0); unsigned(invalid, 0x110000);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> nativeExpression(reader, module, invalid.toByteArray()));
        assertTrue(failure.getCause().getMessage().contains("expression-codec.hi"));
        assertTrue(failure.getCause().getMessage().contains("out-of-range GHC Char"));
        failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> nativeExpression(reader, module, new byte[]{8, 4}));
        assertTrue(failure.getCause().getMessage().contains("tick tag 4"));
    }

    @Test void wiredIdentityTemplatesKeepTheirOperandLazy() throws Exception {
        var reader = CoreHiReader.read(path("normal"));
        var module = new CoreHiModule(reader);
        for (int key : new int[]{104, 126, 127, 109}) {
            var bytes = new java.io.ByteArrayOutputStream();
            bytes.write(11); unsigned(bytes, 0x80000000L | (long) '0' << 22 | key);
            var expression = nativeExpression(reader, module, bytes.toByteArray());
            assertEquals("lam", expression.getFirst());
            var parameter = (Map<?,?>) ((List<?>) expression.get(1)).getFirst();
            var body = (List<?>) expression.get(2);
            assertEquals(List.of("var", parameter.get("id")), body.subList(0, 2));
            assertEquals(false, ((Map<?,?>) parameter.get("rep")).get("evaluated"));
        }
    }

    private static void unsigned(java.io.ByteArrayOutputStream bytes, long value) {
        do {
            int low = (int) (value & 127); value >>>= 7;
            bytes.write(low | (value == 0 ? 0 : 128));
        } while (value != 0);
    }

    private static List<?> nativeExpression(CoreHiReader reader, CoreHiModule module, byte[] expression) throws Exception {
        byte[] bytes = Files.readAllBytes(path("normal"));
        int start = reader.publicSections.get("declarations").start();
        System.arraycopy(expression, 0, bytes, start, expression.length);
        var encoded = new CoreHiReader(bytes, "expression-codec.hi");
        var cursor = encoded.cursor(new CoreHiReader.Section(start, start + expression.length), "expression");
        var parse = CoreHiModule.class.getDeclaredMethod("expr", CoreHiReader.Cursor.class, int.class, boolean.class);
        parse.setAccessible(true);
        Object parsed = parse.invoke(module, cursor, 0, true);
        assertEquals(0, cursor.remaining());
        var lower = CoreHiModule.class.getDeclaredMethod("lower", parsed.getClass(), Map.class, Map.class);
        lower.setAccessible(true);
        Object lowered = lower.invoke(module, parsed, Map.of(), Map.of());
        var result = lowered.getClass().getDeclaredMethod("expression");
        result.setAccessible(true);
        return (List<?>) result.invoke(lowered);
    }

    @Test void typeSubstitutionPreservesLexicalBinding() {
        var kind = CoreHiTypes.con(new CoreHiReader.ExternalName(
                new CoreHiReader.ModuleId("ghc-internal", "GHC.Internal.Types"), 3, null, "Type"), false);
        var free = new CoreHiTypes.Variable("a", kind, false);
        var bound = new CoreHiTypes.Variable("b", kind, false);
        var renamed = new CoreHiTypes.Variable("b", kind, false);
        var body = new CoreHiTypes.Var(free);
        var type = new CoreHiTypes.ForAll(bound, 1, body);
        assertTrue(CoreHiTypes.alphaEquals(type, CoreHiTypes.substitute(type, Map.of())));
        var substituted = CoreHiTypes.substitute(type, Map.of(free, new CoreHiTypes.Var(bound)));
        assertTrue(CoreHiTypes.alphaEquals(new CoreHiTypes.ForAll(renamed, 1, new CoreHiTypes.Var(bound)), substituted),
                "An inserted free variable stays free under a same-named binder");
        assertFalse(CoreHiTypes.alphaEquals(new CoreHiTypes.ForAll(bound, 1, new CoreHiTypes.Var(bound)), substituted),
                "Substitution must not capture the inserted variable");
        var identity = new CoreHiTypes.ForAll(free, 1, body);
        assertTrue(CoreHiTypes.alphaEquals(identity, CoreHiTypes.substitute(identity, Map.of(free, new CoreHiTypes.Var(bound)))),
                "A forall shadows substitution for its own variable");
        assertFalse(CoreHiTypes.alphaEquals(identity, new CoreHiTypes.ForAll(bound, 1, body)),
                "Sharing the body node does not make free and bound occurrences equal");
        assertFalse(CoreHiTypes.alphaEquals(new CoreHiTypes.ForAll(free, 1, new CoreHiTypes.Var(bound)),
                new CoreHiTypes.ForAll(bound, 1, new CoreHiTypes.Var(bound))),
                "An opposing binder cannot capture a free occurrence during alpha comparison");
    }

    @Test void representationRespectsUnknownLevityAndCoercionQuantification() {
        var declarations = new HashMap<CoreHiReader.ExternalName, CoreHiTypes.Declaration>();
        CoreHiTypes[] engine = new CoreHiTypes[1];
        engine[0] = new CoreHiTypes(name -> {
            var declaration = declarations.get(name);
            if (declaration == null) {
                var raw = CoreHiNames.typeDeclaration(name);
                if (raw != null) {
                    declaration = engine[0].declaration(raw);
                    declarations.put(name, declaration);
                }
            }
            return declaration;
        }, name -> null);
        var types = engine[0];
        var primitive = new CoreHiReader.ModuleId("ghc-internal", "GHC.Internal.Prim");
        var runtimeRep = CoreHiTypes.con(new CoreHiReader.ExternalName(
                new CoreHiReader.ModuleId("ghc-internal", "GHC.Internal.Types"), 3, null, "RuntimeRep"), false);
        var family = new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("example", "Kinds"), 3, null, "R");
        declarations.put(family, new CoreHiTypes.Declaration(family, List.of(), runtimeRep, runtimeRep,
                List.of(), "family", null, null));
        var unknownKind = CoreHiTypes.con(new CoreHiReader.ExternalName(primitive, 3, null, "TYPE"), false,
                CoreHiTypes.con(family, false));
        var value = new CoreHiTypes.Var(new CoreHiTypes.Variable("a", unknownKind, false));
        var unknown = types.representation(value, false);
        assertNull(unknown.get("primReps"));
        assertNull(types.lifted(value));
        assertEquals(false, unknown.get("evaluated"), "An opaque representation may still contain a thunk");

        var repVariable = new CoreHiTypes.Var(new CoreHiTypes.Variable("r", runtimeRep, false));
        var componentKind = CoreHiTypes.con(new CoreHiReader.ExternalName(primitive, 3, null, "TYPE"), false, repVariable);
        var component = new CoreHiTypes.Var(new CoreHiTypes.Variable("b", componentKind, false));
        var tuple = new CoreHiTypes.Tuple(1, false, List.of(new CoreHiTypes.Arg(repVariable, 1), new CoreHiTypes.Arg(component, 0)));
        assertEquals(false, types.lifted(tuple), "A known tuple is unlifted even with unknown component storage");

        var integer = CoreHiTypes.con(new CoreHiReader.ExternalName(primitive, 3, null, "Int#"), false);
        var evidence = new CoreHiTypes.Variable("co", types.nominalEquality(integer, integer), true);
        var quantified = new CoreHiTypes.ForAll(evidence, 1, integer);
        assertEquals(List.of("IntRep"), types.representation(integer, false).get("primReps"));
        assertEquals(List.of("BoxedRep (Just Lifted)"), types.representation(quantified, false).get("primReps"),
                "A coercion function is lifted even when its result is unboxed");
    }

    @Test void symbolsPreserveGhcCharSequences() throws Exception {
        for (String mode : List.of("normal", "safe", "max", "thin")) {
            var module = new CoreHiModule(CoreHiReader.read(path(mode)));
            String owner = "thc-native-hi-fixture:NativeHiFixture.";
            assertEquals(new CoreHiTypes.Lit(2, List.of(0xd800, 0xdc00)),
                    module.declaration(owner + "SeparateSurrogates").rhs());
            assertEquals(new CoreHiTypes.Lit(2, List.of(0x10000)),
                    module.declaration(owner + "Supplementary").rhs());
        }
    }

    @Test void indexesActualCompilerInterfaces() throws Exception {
        for (String mode : List.of("normal", "safe", "max", "thin")) {
            var file = CoreHiReader.read(path(mode));
            assertEquals(new CoreHiReader.ModuleId("thc-native-hi-fixture", "NativeHiFixture"), file.module);
            file.requireIdentity("thc-native-hi-fixture", "NativeHiFixture");
            assertNull(file.signatureOf);
            assertEquals(0, file.sourceKind);
            assertTrue(file.strings.containsAll(List.of("entry", "Record", "field", "NativeHiFixture")));
            assertTrue(file.names.stream().anyMatch(name -> name.module().equals(file.module) && name.occurrence().equals("entry") && name.namespace() == 0));
            assertTrue(file.names.stream().anyMatch(name -> name.module().equals(file.module) && name.occurrence().equals("Record") && name.namespace() == 3));
            assertTrue(file.cursor(file.publicSections.get("declarations"), "declarations").count(1) > 0);
            assertNotNull(file.selfRecomp);
            assertTrue(file.extensions.isEmpty());
            if (!mode.equals("thin")) {
                assertTrue(file.strings.contains("privateLoop"));
                assertTrue(file.cursor(file.requireRetainedCore(), "retained Core").count(1) > 0);
                assertThrows(ReadOnlyBufferException.class, () -> file.bytes(file.simplifiedCore).put(0, (byte) 0));
            }
            if (mode.equals("normal")) assertTrue(file.sharedTypes.isEmpty());
            if (mode.equals("max")) assertFalse(file.sharedTypes.isEmpty());
            for (var entry : file.sharedTypes) assertTrue(file.bytes(entry).hasRemaining());
            // Cursors are independent and preserve exactly the selected extent.
            var first = file.cursor(file.publicSections.get("declarations"), "first");
            var second = file.cursor(file.publicSections.get("declarations"), "second");
            int byteValue = first.byteValue();
            assertEquals(byteValue, second.byteValue());
            assertEquals(first.position(), second.position());
        }
    }

    @Test void decodesGhcModifiedUtf8Warnings() throws Exception {
        for (String mode : List.of("normal", "safe", "max", "thin")) {
            var file = CoreHiReader.read(path(mode));
            assertTrue(file.strings.contains("warning\0text \uD800 \uDC00 \uD83D\uDE42 é"));
        }
    }

    @Test void rejectsThinCoreDemandAndWrongIdentity() throws Exception {
        var file = CoreHiReader.read(path("thin"));
        assertNull(file.simplifiedCore);
        var thin = assertThrows(IllegalArgumentException.class, file::requireRetainedCore);
        assertTrue(thin.getMessage().contains("-fwrite-if-simplified-core"));
        assertTrue(thin.getMessage().contains("thc-native-hi-fixture:NativeHiFixture"));
        var mismatch = assertThrows(IllegalArgumentException.class, () -> file.requireIdentity("other", "NativeHiFixture"));
        assertTrue(mismatch.getMessage().contains("identity mismatch"));
    }

    @Test void rejectsTruncatedAndCorruptEnvelopes() throws Exception {
        byte[] bytes = Files.readAllBytes(path("max"));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            var failure = assertThrows(IllegalArgumentException.class, () -> new CoreHiReader(truncated, "truncated.hi"), "length " + length);
            assertTrue(failure.getMessage().contains("truncated.hi"));
        }
        var corruptions = new ArrayList<byte[]>();
        byte[] magic = bytes.clone(); magic[0] = 0; corruptions.add(magic);
        byte[] version = bytes.clone(); version[5] = '8'; corruptions.add(version);
        byte[] way = bytes.clone(); way[9] = 1; way[10] = 'p'; corruptions.add(way);
        // Four envelope pointers: extension, dictionary, names, shared types.
        for (int offset : new int[]{10, 14, 18, 22}) {
            byte[] pointer = bytes.clone(); Arrays.fill(pointer, offset, offset + 4, (byte) 255); corruptions.add(pointer);
        }
        var envelope = ByteBuffer.wrap(bytes);
        int fs = 14 + envelope.getInt(14), ns = 18 + envelope.getInt(18), ts = 22 + envelope.getInt(22);
        byte[] negativeCount = bytes.clone(); negativeCount[fs] = 0x7f; corruptions.add(negativeCount);
        byte[] badUnit = bytes.clone(); badUnit[ns + 1] = 1; corruptions.add(badUnit);
        byte[] badUtf8 = bytes.clone(); badUtf8[fs + 2] = (byte) 255; corruptions.add(badUtf8);
        String hex = HexFormat.of().formatHex(bytes);
        int nul = hex.indexOf("7761726e696e67c080"), supplementary = hex.indexOf("f09f9982");
        assertTrue(nul >= 0 && supplementary >= 0);
        nul = nul / 2 + 7; supplementary /= 2;
        byte[] badContinuation = bytes.clone(); badContinuation[nul + 1] = 'A'; corruptions.add(badContinuation);
        byte[] overlong = bytes.clone(); overlong[nul + 1] = (byte) 0x81; corruptions.add(overlong);
        byte[] badCodePoint = bytes.clone(); badCodePoint[supplementary] = (byte) 0xf4; corruptions.add(badCodePoint);
        byte[] badTypeEnd = bytes.clone(); Arrays.fill(badTypeEnd, ts + 4, ts + 8, (byte) 255); corruptions.add(badTypeEnd);
        var file = CoreHiReader.read(path("max"));
        byte[] badPublic = bytes.clone(); Arrays.fill(badPublic, file.publicInterface.start(), file.publicInterface.start() + 4, (byte) 255); corruptions.add(badPublic);
        for (int index = 0; index < corruptions.size(); index++) {
            byte[] corrupt = corruptions.get(index);
            var failure = assertThrows(IllegalArgumentException.class, () -> new CoreHiReader(corrupt, "corrupt.hi"), "corruption " + index);
            assertTrue(failure.getMessage().contains("corrupt.hi"));
            assertTrue(failure.getMessage().contains("byte"));
        }
    }
}
