// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class ByteStringDataLabelsTest {
    private static final String SYMBOL = "hs_bytestring_lower_hex_table";
    private static final Map<String, Object> ADDRESS = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private static final Map<String, Object> INDEX = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> WORD16 = Map.of("kind", "long", "primReps", List.of("Word16Rep"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);

    private ManagedAddress table() { return CoreDataLabels.fromCore(SYMBOL, CoreRepresentations.parse(ADDRESS)); }

    private int expectedWord(int value) {
        String digits = HexFormat.of().toHexDigits((byte) value);
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            ? digits.charAt(0) | digits.charAt(1) << 8
            : digits.charAt(0) << 8 | digits.charAt(1);
    }

    @Test void immutableTablePreservesAllBytePairsTerminatorAndBounds() {
        var table = table();
        for (int value = 0; value < 256; value++) {
            String digits = HexFormat.of().toHexDigits((byte) value);
            assertEquals(digits.charAt(0), table.readWord8(2L * value));
            assertEquals(digits.charAt(1), table.readWord8(2L * value + 1));
            assertEquals(expectedWord(value), ManagedAddressRead.WORD16.readInt(table, value));
        }
        assertEquals(0, table.readWord8(512));
        assertThrows(RuntimeFault.class, () -> table.readWord8(513));
        assertThrows(RuntimeFault.class, () -> table.readWord8(-1));
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD16.readInt(table, 256));
        assertThrows(RuntimeFault.class, () -> table.writeWord8(0, 'z'));
        assertThrows(RuntimeFault.class, () -> table.writeWord16(0, 0));
        assertEquals('0', table().readWord8(0));
    }

    @Test void exactSymbolAndEvaluatedAddressProofRemainRequired() {
        for (String symbol : List.of("hs_bytestring_lower_hex_table_extra", "other_symbol"))
            assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(symbol, CoreRepresentations.parse(ADDRESS)));
        assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(SYMBOL, null));
        for (var change : List.of(Map.of("evaluated", false), Map.of("kind", "long"),
                                 Map.of("primReps", List.of("WordRep")), Map.of("aggregate", "unboxed-tuple"))) {
            var bad = new LinkedHashMap<String, Object>(ADDRESS);
            bad.putAll(change);
            assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(SYMBOL, CoreRepresentations.parse(bad)));
        }
    }

    private Map<String, Object> module() {
        var label = List.of("lit", "data-addr", SYMBOL, Map.of("rep", ADDRESS));
        var parameter = Map.of("id", "index", "lifted", false, "rep", INDEX);
        var call = List.of("app", List.of("prim", "indexWord16OffAddr#"),
            List.of(label, List.of("var", "index", Map.of("rep", INDEX))),
            List.of(false, false), false, false, Map.of("rep", WORD16));
        return Map.of("instrument", true, "constructors", List.of(),
            "bindings", List.of(Map.of("id", "read", "name", "read", "arity", 1,
                "lifted", true, "rep", CLOSURE,
                "expr", List.of("lam", List.of(parameter), call, Map.of("rep", CLOSURE, "resultRep", WORD16)))));
    }

    @Test void bothBackendsReadTheTableOnTheFirstInstalledCall() throws Exception {
        for (String backend : List.of("ast", "bytecode"))
            try (var context = Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var threads = Language.currentState().getThreads();
                    threads.enterCurrent(null, false, true, null);
                    try {
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                        var target = program.entryTarget("read");
                        for (int i = 0; i < 100; i++)
                            assertEquals(expectedWord(i), Calls.target(target, new Object[]{0L, (long) i}));
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(expectedWord(255), Calls.target(target, new Object[]{0L, 255L}));
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        assertEquals(0, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { context.leave(); }
            }
    }
}
