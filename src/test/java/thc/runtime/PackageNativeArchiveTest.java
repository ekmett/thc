// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.PackageNativeArchive;
import static org.junit.jupiter.api.Assertions.*;

class PackageNativeArchiveTest {
    private static Map<String,Object> scalar(String kind, String primitive, boolean evaluated) {
        return Map.of("kind", kind, "primReps", primitive.isEmpty() ? List.of() : List.of(primitive), "evaluated", evaluated);
    }
    private static Map<String,Object> descriptor(String unit, String symbol) {
        var result = Map.of("kind", "unknown", "primReps", List.of("Int32Rep"), "evaluated", false,
            "aggregate", "unboxed-tuple", "components", List.of(scalar("void", "", true), scalar("long", "Int32Rep", true)));
        return new LinkedHashMap<>(Map.of("schema", 1, "target", Map.of("kind", "static", "symbol", symbol,
            "unit", unit, "isFunction", true), "convention", "ccall", "safety", "interruptible", "arity", 4,
            "suppliedArity", 4, "argumentReps", List.of(scalar("address", "AddrRep", false),
                scalar("long", "Int32Rep", false), scalar("long", "Word32Rep", false), scalar("void", "", false)),
            "resultRep", result));
    }
    private static Map<String,Object> metadata(Map<String,Object> call) {
        return Map.of("foreignCall", call, "rep", call.get("resultRep"));
    }
    private static PackageNativeArchive archive(String unit, boolean whole, String... symbols) {
        var excluded = new ArrayList<Map<?,?>>();
        for (var symbol : symbols) excluded.add(Map.of("symbol", symbol, "convention", "ccall", "safety", "interruptible"));
        return new PackageNativeArchive("test obligations", whole, unit, excluded);
    }
    private static OriginalStdioOp validate(Map<String,Object> call) {
        return CoreOriginalStdio.validate(metadata(call), (List<?>) call.get("argumentReps"),
            List.of(false, false, false, false), call.get("resultRep"));
    }
    @Test void managedInterruptibleOpenRetainsItsStrictValidator() {
        var call = descriptor("ghc-internal", "__hscore_open");
        assertFalse(archive("ghc-internal", false, "__hscore_open").blocks(metadata(call)));
        assertEquals(OriginalStdioOp.OPEN_INTERRUPTIBLE, validate(call));
    }
    @Test void wrongArityAndCarrierStillRejectBeforeExecution() {
        var wrongArity = descriptor("ghc-internal", "__hscore_open");
        wrongArity.put("suppliedArity", 3);
        assertFalse(archive("ghc-internal", false, "__hscore_open").blocks(metadata(wrongArity)));
        assertThrows(RuntimeFault.class, () -> validate(wrongArity));
        var wrongCarrier = descriptor("ghc-internal", "__hscore_open");
        wrongCarrier.put("argumentReps", List.of(scalar("long", "IntRep", false),
            scalar("long", "Int32Rep", false), scalar("long", "Word32Rep", false), scalar("void", "", false)));
        assertFalse(archive("ghc-internal", false, "__hscore_open").blocks(metadata(wrongCarrier)));
        assertThrows(RuntimeFault.class, () -> validate(wrongCarrier));
    }
    @Test void foreignOwnerAndUnrelatedInterruptibleCallsStayBlocked() {
        assertTrue(archive("other-unit", false, "__hscore_open")
            .blocks(metadata(descriptor("other-unit", "__hscore_open"))));
        assertTrue(archive("ghc-internal", false, "ordinary_wait")
            .blocks(metadata(descriptor("ghc-internal", "ordinary_wait"))));
        assertThrows(RuntimeFault.class, () -> validate(descriptor("other-unit", "__hscore_open")));
    }
    @Test void managedCallDoesNotDischargeMixedOrWholeModuleObligations() {
        var open = metadata(descriptor("ghc-internal", "__hscore_open"));
        var ordinary = metadata(descriptor("ghc-internal", "ordinary_wait"));
        assertTrue(archive("ghc-internal", false, "__hscore_open", "ordinary_wait").blocks(List.of(open, ordinary)));
        assertTrue(archive("ghc-internal", true, "__hscore_open").blocks(open));
    }
}
