// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class BigNatLiteralTest {
    @Test void literalsUseTheInvokingStoragePolicy() {
        for (String storage : List.of("heap", "native")) for (String backend : List.of("ast", "bytecode"))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowNativeAccess(true)
                    .option("thc.ByteArrayStorage", storage).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (String decimal : List.of("0", "18446744073709551617", "340282366920938463472597979468622987265")) {
                        var body = literal(decimal, exact());
                        var source = map("constructors", list(), "bindings", list(
                            map("id", "value", "name", "value", "lifted", false, "rep", exact(), "expr", body),
                            map("id", "inline", "name", "inline", "lifted", true, "arity", 1,
                                "expr", list("lam", list(map("id", "unused", "lifted", false)), body))));
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                        Object global = program.entryValue("value");
                        assertSame(global, program.entryValue("value"));
                        Object inline = Calls.target(program.entryTarget("inline"), new Object[]{0L, 0L});
                        assertNotSame(global, inline);
                        for (Object value : List.of(global, inline)) {
                            var number = new BigInteger(decimal);
                            int length = (number.bitLength() + 63) / 64 * 8;
                            assertEquals(length, ManagedByteArray.sizeGuest(value));
                            for (int index = 0; index < length; index++) {
                                int bit = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : index / 8 * 8 + 7 - index % 8) * 8;
                                assertEquals(number.shiftRight(bit).and(BigInteger.valueOf(255)).intValue(), ManagedByteArray.readGuest(value, index, true));
                            }
                            var address = ManagedAddress.fromGuestByteArray(value);
                            if (storage.equals("native")) {
                                var allocation = assertInstanceOf(ManagedAllocation.class, value);
                                assertTrue(allocation.hasNativeStorage()); assertFalse(allocation.isPinned());
                                assertEquals(allocation.nativeSegment().address(), address.toNativeBits());
                            } else {
                                assertInstanceOf(byte[].class, value);
                                assertThrows(RuntimeFault.class, address::toNativeBits);
                            }
                            assertSame(value, ManagedByteArray.freezeGuest(value));
                            if (length != 0) {
                                ManagedByteArray.writeGuest(ManagedByteArray.freezeGuest(value), 0, 91);
                                assertEquals(91, address.readWord8(0));
                            }
                        }
                    }
                } finally { context.leave(); }
            }
    }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).build(); }
    @Test void canonicalPrivateBytesAndCheckedSizeMatchGhcLimbLayout() {
        for (int bits : new int[]{0, 1, 63, 64, 65, 127, 128, 129, 192, 256}) {
            var number = bits == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).add(BigInteger.ONE);
            var first = BigNatLiterals.decode(number.toString()); var second = BigNatLiterals.decode(number.toString()); assertNotSame(first, second); assertArrayEquals(first, second);
            assertEquals(((number.bitLength() + 63) / 64) * 8, first.length); var reconstructed = BigInteger.ZERO;
            for (int index = 0; index < first.length; index++) {
                int position = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : (index / 8) * 8 + 7 - index % 8;
                reconstructed = reconstructed.or(BigInteger.valueOf(first[index] & 255).shiftLeft(position * 8));
            }
            assertEquals(number, reconstructed); if (first.length != 0) { first[0] = (byte) (first[0] ^ 255); assertFalse(Arrays.equals(first, second)); }
        }
        assertEquals(0, BigNatLiterals.byteSize(0)); assertEquals(2147483640, BigNatLiterals.byteSize(17179869120L));
        for (long bits : new long[]{-1L, 17179869121L, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> BigNatLiterals.byteSize(bits));
        for (var value : list("", "-1", "+1", "00", "01", " 1", "1 ", "1.0", "0x10", "١")) assertThrows(RuntimeFault.class, () -> BigNatLiterals.decode(value));
    }
    private static Map<String, Object> model(List<Object> body) {
        var binding = map("id", "main:BigNatControl.root", "name", "root", "lifted", true, "arity", 1, "expr", list("lam", list(map("id", "x", "lifted", false)), body, map("rep", map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true), "resultRep", unknown())));
        return map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "BigNatControl", "boundary", "test-model", "constructors", list(), "bindings", list(binding));
    }
    private static String request(Path temporary, String backend, List<Object> body) throws Exception {
        var source = thc.CoreCbdFixtures.write(Files.createTempFile(temporary, "control-", ".cbd"), model(body));
        return CoreModules.request(list(source.toString()), "main:BigNatControl.root", true, false, backend);
    }
    private static Map<String, Object> exact() { return map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true); }
    private static Map<String, Object> unknown() { return map("kind", "unknown", "primReps", null, "evaluated", false); }
    private static List<Object> literal(String value, Map<String, Object> proof) { return list("lit", "bignat", value, map("rep", proof == null ? unknown() : proof)); }
    private static List<Object> size(List<Object> literal) { return list("app", list("prim", "sizeofByteArray#", map("rep", unknown())), list(literal), list(false), false, false, map("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true))); }
    private static List<Map<String, Object>> aggregateForgeries() {
        var voidRep = map("kind", "void", "primReps", list(), "evaluated", true);
        return list(voidRep, map("kind", "unknown", "primReps", list(), "evaluated", true, "aggregate", "unboxed-tuple", "components", list()),
            map("kind", "unknown", "primReps", list("WordRep"), "evaluated", true, "aggregate", "unboxed-sum", "tagSlot", 0, "alternativeSlots", list(list(), list()), "alternatives", list(voidRep, map("kind", "void", "primReps", list(), "evaluated", true))),
            map("kind", "vector", "primReps", list("VecRep 2 Int64ElemRep"), "evaluated", true, "vector", map("lanes", 2, "element", "Int64ElemRep")));
    }
    private void audit(Path temporary, int serial, List<Object> body, boolean accepted, String issue) throws Exception {
        var name = "control-" + serial;
        var source = temporary.resolve(name + ".cbd"); thc.CoreCbdFixtures.write(source, model(body)); var output = temporary.resolve(name + "-report.json");
        var process = new ProcessBuilder("python3", "bin/audit-core.py", source.toString(), "--entry", "main:BigNatControl.root", "--output", output.toString()).directory(root)
            .redirectOutput(temporary.resolve(name + ".stdout").toFile()).redirectError(temporary.resolve(name + ".stderr").toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Shared BigNat auditor timed out: " + name); }
        var report = Files.readString(output); assertEquals(accepted ? 0 : 1, process.exitValue(), name + ": " + report); var actual = object(Json.parse(report)); assertEquals(accepted, actual.get("accepted"), name);
        if (accepted) { assertEquals(list(), actual.get("issues")); assertEquals(list(), actual.get("missingGlobals")); }
        else { var issues = objects(actual.get("issues")); assertFalse(issues.isEmpty(), name); if (issue != null) assertTrue(issues.stream().anyMatch(item -> issue.equals(item.get("code"))), name + "/" + issue); }
    }
    @Test void sharedAuditorRetainsCanonicalIntrinsicAndMalformedControls(@TempDir Path temporary) throws Exception {
        var exact = exact(); var unknown = unknown(); int serial = 0;
        for (var value : list("0", "1", "57896044618658097711785492504343953926975274699741220483192166611388333031427")) for (var proof : list(exact, null, unknown)) {
            audit(temporary, serial++, literal(value, proof), true, null);
            // CLI recovers ByteArray# kind/PrimRep; auditor API separately checks evaluated=true.
            audit(temporary, serial++, size(literal(value, proof)), true, null);
        }
        // The ten malformed decimal spellings remain negative controls in the
        // managed decoder above and the owning Python auditor API. CBD stores
        // a numeric magnitude, not an unvalidated diagnostic spelling.
        var bad = new ArrayList<Map<String, Object>>();
        for (var pair : list(list("long", "IntRep"), list("long", "WordRep"), list("object", "BoxedRep (Just Lifted)"), list("object", "BoxedRep Nothing"), list("unknown", "BoxedRep (Just Unlifted)"), list("data", "BoxedRep (Just Unlifted)"), list("closure", "BoxedRep (Just Unlifted)")))
            bad.add(map("kind", pair.get(0), "primReps", list(pair.get(1)), "evaluated", true));
        bad.addAll(aggregateForgeries()); for (var proof : bad) audit(temporary, serial++, literal("1", proof), false, null);
        for (var proof : list(exact, null, unknown)) audit(temporary, serial++, size(literal("18446744073709551616", proof)), true, null);
        for (var kind : list("data", "closure", "unknown")) audit(temporary, serial++, size(literal("18446744073709551616", with(exact, "kind", kind))), false, null);
        for (var rep : list("BoxedRep (Just Lifted)", "BoxedRep Nothing", "IntRep", "WordRep")) audit(temporary, serial++, size(literal("18446744073709551616", with(exact, "primReps", list(rep)))), false, null);
        audit(temporary, serial++, list("case", list("lit", "int", "0", map()), "scrutinee", list(list("lit", list("bignat", "1"), list(), list("lit", "int", "1", map()), map("binders", list())), list("default", null, list(), list("lit", "int", "0", map()), map("binders", list()))), map()), false, "alternative-kind");
        assertEquals(40, serial);
    }
    private static List<Object> size(Map<String, Object> proof, boolean bind) {
        var exact = exact(); var scalar = map("kind", "long", "primReps", list("IntRep"), "evaluated", true); var literal = literal("18446744073709551616", proof);
        // Direct calls recover the intrinsic proof; exact case binder is an independent control.
        var operand = bind ? list("var", "bytes", map("rep", exact)) : literal; var read = size(operand);
        return !bind ? read : list("case", literal, "bytes", list(list("default", null, list(), read, map("binders", list()))), map("rep", scalar, "binder", map("id", "bytes", "lifted", false, "rep", exact)));
    }
    @Test void exactUnliftedLiteralProofRejectsScalarAggregateAndBoxedForgeries(@TempDir Path temporary) throws Exception {
        var exact = exact(); var bad = new ArrayList<>(list(with(exact, "primReps", list("BoxedRep (Just Lifted)")), with(exact, "primReps", list("BoxedRep Nothing")), with(exact, "kind", "unknown"), with(exact, "kind", "data"), with(exact, "kind", "closure"),
            map("kind", "long", "primReps", list("IntRep"), "evaluated", true), map("kind", "long", "primReps", list("WordRep"), "evaluated", true)));
        bad.addAll(aggregateForgeries());
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            for (boolean bind : new boolean[]{false, true}) {
                for (var proof : list(exact, null, unknown())) assertEquals(16L, context.eval("thc", request(temporary, backend, size(proof, bind))).execute(0L).asLong());
                for (var proof : bad) assertThrows(PolyglotException.class, () -> context.eval("thc", request(temporary, backend, size(proof, bind))));
            }
        }
    }
    @Test void bignatLiteralAlternativesRemainForbidden(@TempDir Path temporary) throws Exception {
        var body = list("case", list("lit", "int", "0", map()), "scrutinee", list(list("lit", list("bignat", "0"), list(), list("lit", "int", "1", map()), map("binders", list())), list("default", null, list(), list("lit", "int", "0", map()), map("binders", list()))), map());
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request(temporary, backend, body)));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("BigNat/rubbish literal alternatives are invalid GHC Core"), failure.getMessage());
        }
    }
}
