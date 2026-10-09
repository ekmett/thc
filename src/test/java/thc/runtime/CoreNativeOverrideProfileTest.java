// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Capability/ABI controls, not substituted original guest bodies or native adapters. */
class CoreNativeOverrideProfileTest {
    private List<Map<?, ?>> calls() throws Exception {
        try (var input = getClass().getResourceAsStream("/thc/core-native-overrides.json")) {
            assertNotNull(input);
            var profile = (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            var result = new ArrayList<Map<?, ?>>();
            for (var call : (List<?>) profile.get("calls")) result.add((Map<?, ?>) call);
            return result;
        }
    }
    private Map<Object, Object> with(Map<?, ?> value, String key, Object replacement) {
        var result = new LinkedHashMap<Object, Object>(value); result.put(key, replacement); return result;
    }
    @Test void eachIncludedDescriptorRequiresItsActualLiveOwningValidator() throws Exception {
        var calls = calls();
        assertEquals(Set.of("freeHaskellFunctionPtr", "getNumberOfProcessors", "setNumCapabilities",
            "getOrSetGHCConcSignalSignalHandlerStore", "getProgArgv", "setProgArgv", "getRTSStatsEnabled",
            "getRTSStats", "performGC", "performMajorGC", "performBlockingMajorGC", "lockFile", "unlockFile",
            "lookupIPE", "reportHeapOverflow", "rts_isThreaded", "shutdownHaskellAndExit",
            "stg_asyncReadzh", "stg_asyncWritezh", "rts_InstallConsoleEvent", "rts_ConsoleHandlerDone"),
            Set.copyOf(calls.stream().map(call -> ((Map<?, ?>) call.get("target")).get("symbol")).toList()));
        assertEquals(21, calls.size());
        for (var call : calls) {
            assertTrue(CoreForeignOverride.nativeCall(call), call.toString());
            assertTrue(CoreForeignOverride.nativeCall(with(call, "schema", 1)), "CBD/JSON integer carriers agree");
        }
    }
    @Test void changedAbiNeverEscapesThroughTheNativeCompanion() throws Exception {
        for (var call : calls()) {
            var target = (Map<?, ?>) call.get("target");
            var result = (Map<?, ?>) call.get("resultRep");
            for (var wrong : List.of(with(call, "schema", 2L), with(call, "schema", 1.0),
                    with(call, "arity", 0L), with(call, "suppliedArity", 0L),
                    with(call, "convention", "capi"), with(call, "safety", "interruptible"),
                    with(call, "argumentTypes", List.of()), with(call, "argumentReps", List.of()),
                    with(call, "resultRep", with(result, "aggregate", "unboxed-sum")),
                    with(call, "target", with(target, "kind", "dynamic")),
                    with(call, "target", with(target, "isFunction", false))))
                assertThrows(RuntimeFault.class, () -> CoreForeignOverride.nativeCall(wrong));
            assertFalse(CoreForeignOverride.nativeCall(with(call, "target", with(target, "unit", "ordinary-unit"))));
            assertFalse(CoreForeignOverride.nativeCall(with(call, "target", with(target, "symbol", target.get("symbol") + "_extra"))));
        }
        for (var symbol : List.of("getMonotonicNSec", "hs_spt_key_count"))
            assertFalse(CoreForeignOverride.nativeCall(Map.of("target", Map.of("unit", "ghc-internal", "symbol", symbol))),
                "unwired or unsupported native demands receive no new capability");
    }
    @Test void freeCallbackUsesTheExistingDynamicValidatorBeforePackageLookup() throws Exception {
        var call = calls().stream().filter(entry -> "freeHaskellFunctionPtr".equals(((Map<?, ?>) entry.get("target")).get("symbol")))
            .findFirst().orElseThrow();
        var arguments = (List<?>) call.get("argumentReps"); var result = call.get("resultRep");
        var metadata = Map.of("foreignCall", call, "rep", result);
        var selected = CorePackageScalarForeign.validate(metadata, arguments, Collections.nCopies(arguments.size(), false), result, List.of());
        assertNotNull(selected);
        assertEquals(PackageScalarCall.Kind.FREE_CALLBACK, selected.getKind());
        assertFalse(selected.executesForeign());
        var archive = new thc.PackageNativeArchive("original native obligation", false, "ghc-internal",
            List.of(Map.of("symbol", "freeHaskellFunctionPtr", "convention", "ccall", "safety", "unsafe")));
        assertEquals("FREE_CALLBACK", CoreForeignOverride.owner(metadata));
        assertFalse(archive.blocks(metadata), "native obligations cannot prohibit THC-owned callback release");
        assertTrue(new thc.PackageNativeArchive("unresolved native symbol", true, "ghc-internal", List.of()).blocks(metadata));
        var malformed = with(call, "arity", 0L);
        assertFalse(archive.blocks(Map.of("foreignCall", malformed)), "selection does not admit malformed ABI");
        assertThrows(RuntimeFault.class, () -> CorePackageScalarForeign.validate(
            Map.of("foreignCall", malformed, "rep", result), arguments, Collections.nCopies(arguments.size(), false), result, List.of()));
        assertNull(selected.getLink(), "this is not a fabricated C entry or exported native callback namespace");
        assertThrows(RuntimeFault.class, () -> CorePackageScalarForeign.validate(metadata, arguments,
            List.of(true, false), result, List.of()), "the actual operand/flag validator still runs");
    }
    @Test void originalCcallHeaderDoesNotChangeItsExactOwnedAbi() {
        var emitted = Map.of("symbol", "shutdownHaskellAndExit", "unit", "ghc-internal",
            "convention", "ccall", "safety", "safe", "arguments", List.of("Int32Rep", "Int32Rep", "void"),
            "result", List.of("void"));
        var imported = Map.of("symbol", "shutdownHaskellAndExit", "isFunction", true,
            "header", "Rts.h", "convention", "ccall", "safety", "safe", "emitted", emitted);
        assertTrue(CoreForeignOverride.nativeImport(imported, List.of()),
            "GHC.Internal.TopHandler retains this actual configured header");
        for (var header : List.of("", "bad\u0000.h", "bad\n.h", "bad\r.h", "bad\".h", "bad\\.h", 1L))
            assertThrows(RuntimeFault.class, () -> CoreForeignOverride.nativeImport(with(imported, "header", header), List.of()),
                "retained source headers still require well-formed provenance");
        for (var wrong : List.of(with(imported, "isFunction", false), with(imported, "convention", "capi"),
                with(imported, "safety", "unsafe"), with(imported, "emitted", with(emitted, "safety", "unsafe")),
                with(imported, "emitted", with(emitted, "arguments", List.of("IntRep", "Int32Rep", "void"))),
                with(imported, "emitted", with(emitted, "result", List.of("void", "Int32Rep")))))
            assertThrows(RuntimeFault.class, () -> CoreForeignOverride.nativeImport(wrong, List.of()));
        assertFalse(CoreForeignOverride.nativeImport(with(imported, "emitted", with(emitted, "unit", "ordinary-unit")), List.of()));
    }
}
