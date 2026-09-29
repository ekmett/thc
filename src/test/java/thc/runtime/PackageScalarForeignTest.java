// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

/** Call-proof controls only; no synthetic bitcode is executed. */
public class PackageScalarForeignTest {
    @Test public void int32ProtocolConversionRejectsTruncation() {
        for (int value : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) assertEquals(value, PackageScalarAccess.packageScalarInt32((long) value));
        for (long value : new long[]{(long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1, Long.MIN_VALUE, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> PackageScalarAccess.packageScalarInt32(value));
    }
    private CoreKind kind(String rep) {
        return switch (rep) { case null -> CoreKind.VOID; case "FloatRep" -> CoreKind.FLOAT; case "DoubleRep" -> CoreKind.DOUBLE;
            case "AddrRep" -> CoreKind.ADDRESS; case "ByteArray#", "MutableByteArray#" -> CoreKind.OBJECT; default -> CoreKind.LONG; };
    }
    private List<String> reps(String rep) {
        return rep == null ? List.of() : rep.equals("ByteArray#") || rep.equals("MutableByteArray#") ? List.of("BoxedRep (Just Unlifted)") : List.of(rep);
    }
    private Map<String, Object> scalar(String rep) { return scalar(rep, true); }
    private Map<String, Object> scalar(String rep, boolean evaluated) { return Map.of("kind", kind(rep).name().toLowerCase(Locale.ROOT), "primReps", reps(rep), "evaluated", evaluated); }
    private Map<String, Object> result(String rep) { return result(rep, true); }
    private Map<String, Object> result(String rep, boolean evaluated) {
        return Map.of("kind", "unknown", "primReps", List.of(rep), "evaluated", evaluated, "aggregate", "unboxed-tuple", "components", List.of(scalar(null), scalar(rep)));
    }
    private PackageScalarLink link(String unit, String rep) { return new PackageScalarLink(unit, "unused", "", "", new byte[0], List.of(new PackageScalarSignature("stg_sig_install", "unused", List.of(rep), rep))); }
    private Map<String, Object> plus(Map<String, Object> original, String key, Object value) { var copy = new LinkedHashMap<>(original); copy.put(key, value); return copy; }
    private CoreRepresentation exact(String rep, boolean present) { return new CoreRepresentation(kind(rep), false, present, reps(rep), null, null, null, null, null); }
    private PackageScalarCall validate(Map<String, Object> declaration, Object metadataResult, List<?> arguments, List<?> flags, Object returned, PackageScalarLink link) {
        return CorePackageScalarForeign.validate(Map.of("foreignCall", declaration, "rep", metadataResult), arguments, flags, returned, List.of(link));
    }
    @Test public void javascriptInNativeUnitRetainsItsOwnFullProofCheck() {
        String source = "() => 7", symbol = "thc_javascript_v1_" + java.util.HexFormat.of().formatHex(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var target = Map.<String,Object>of("kind", "static", "symbol", symbol, "unit", "first", "isFunction", true);
        var descriptor = plus(plus(Map.of("schema", 1L, "target", target, "convention", "ccall", "safety", "unsafe",
            "arity", 1L, "suppliedArity", 1L, "argumentReps", List.of(scalar(null, false)), "resultRep", result("IntRep", false)),
            "intrinsic", "javascript-v1"), "javascriptSource", source);
        var output = result("IntRep"); var args = List.of(scalar(null)); var flags = List.of(false);
        for (var call : List.of(descriptor, plus(descriptor, "javascriptSource", "() => 8"), plus(descriptor, "arity", 2L))) {
            assertNull(validate(call, output, args, flags, output, link("first", "IntRep")));
            var expression = List.of("app", List.of("var", "foreign"), List.of(List.of("var", "state", Map.of("rep", scalar(null)))),
                flags, false, false, Map.of("rep", output, "foreignCall", call));
            if (call == descriptor) assertNotNull(CoreJavaScript.validate(expression, false));
            else assertThrows(RuntimeFault.class, () -> CoreJavaScript.validate(expression, false));
        }
        var ordinary = new LinkedHashMap<>(descriptor); ordinary.remove("intrinsic"); ordinary.remove("javascriptSource");
        assertThrows(RuntimeFault.class, () -> validate(ordinary, output, args, flags, output, link("first", "IntRep")));
    }
    @Test public void exactWidthStateAndUnitProofsAreRequiredForAllScalarKinds() {
        for (var rep : List.of("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "FloatRep", "DoubleRep")) {
            var link = link("first", rep);
            Map<String, Object> target = Map.of("kind", "static", "symbol", "stg_sig_install", "unit", "first", "isFunction", true);
            Map<String, Object> descriptor = Map.of("schema", 1L, "target", target, "convention", "ccall", "safety", "unsafe", "arity", 2L, "suppliedArity", 2L,
                "argumentReps", List.of(scalar(rep, false), scalar(null, false)), "resultRep", result(rep, false));
            var output = result(rep); var args = List.of(scalar(rep), scalar(null)); var flags = List.of(false, false);
            var admitted = Objects.requireNonNull(validate(descriptor, output, args, flags, output, link));
            assertSame(link, admitted.getLink()); assertEquals(rep, admitted.getResult());
            var changes = List.of(plus(descriptor, "safety", "safe"), plus(descriptor, "convention", "prim"),
                plus(descriptor, "target", plus(target, "kind", "dynamic")), plus(descriptor, "target", plus(target, "isFunction", false)),
                plus(descriptor, "target", plus(target, "symbol", "unproved_symbol")), plus(descriptor, "arity", 1L), plus(descriptor, "suppliedArity", 1L),
                plus(descriptor, "argumentReps", List.of(scalar("AddrRep", false), scalar(null, false))),
                plus(descriptor, "resultRep", result(rep.equals("Int32Rep") ? "Int64Rep" : "Int32Rep", false)));
            for (int index = 0; index < changes.size(); index++) {
                var changed = changes.get(index);
                assertThrows(RuntimeFault.class, () -> validate(changed, output, args, flags, output, link), rep + " declaration " + index);
            }
            assertThrows(RuntimeFault.class, () -> validate(descriptor, output, List.of(scalar(rep), scalar("Int64Rep")), flags, output, link));
            assertThrows(RuntimeFault.class, () -> validate(descriptor, output, args, List.of(true, false), output, link));
            assertThrows(RuntimeFault.class, () -> validate(descriptor, output, args, flags, scalar(rep), link));
            for (var other : List.of("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep")) if (!other.equals(rep))
                assertThrows(RuntimeFault.class, () -> validate(descriptor, output, List.of(scalar(other), scalar(null)), flags, output, link));
            // Same symbol in another component never selects this component's entry.
            assertNull(validate(plus(descriptor, "target", plus(target, "unit", "second")), output, args, flags, output, link));
            assertNull(validate(plus(descriptor, "target", plus(target, "unit", null)), output, args, flags, output, link));
            assertFalse(CoreSignalForeign.named(Map.of("foreignCall", descriptor)));
            var exact = exact(rep, true);
            CorePackageScalarForeign.validateOperand(admitted, 0, exact, exact);
            CorePackageScalarForeign.validateOperand(admitted, 1, exact(null, true), null);
            assertThrows(RuntimeFault.class, () -> CorePackageScalarForeign.validateOperand(admitted, 0, exact, exact(rep.equals("Word64Rep") ? "Int64Rep" : "Word64Rep", true)));
            assertThrows(RuntimeFault.class, () -> CorePackageScalarForeign.validateOperand(admitted, 0, exact(rep, false), null));
        }
    }
    @Test public void safeCallDoesNotMatchAnUnsafeComponentAdapter() {
        var original = link("safe-unit", "DoubleRep"); var abi = new ArrayList<PackageScalarSignature>();
        for (var item : original.getAbi()) abi.add(new PackageScalarSignature(item.getSymbol(), item.getEntry(), item.getArguments(), item.getResult(), item.getConvention(), "safe"));
        var safe = new PackageScalarLink(original.getUnit(), original.getTarget(), original.getComponentSha256(), original.getBitcodeSha256(), original.getBytes(), abi);
        Map<String, Object> descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", "stg_sig_install", "unit", "safe-unit", "isFunction", true),
            "convention", "ccall", "safety", "safe", "arity", 2L, "suppliedArity", 2L, "argumentReps", List.of(scalar("DoubleRep", false), scalar(null, false)), "resultRep", result("DoubleRep", false));
        var output = result("DoubleRep"); var args = List.of(scalar("DoubleRep"), scalar(null)); var flags = List.of(false, false);
        assertEquals("safe", Objects.requireNonNull(validate(descriptor, output, args, flags, output, safe)).getSignature().getSafety());
        assertThrows(RuntimeFault.class, () -> validate(descriptor, output, args, flags, output, original));
        assertThrows(RuntimeFault.class, () -> validate(plus(descriptor, "safety", "unsafe"), output, args, flags, output, safe));
        assertThrows(RuntimeFault.class, () -> validate(plus(descriptor, "safety", "interruptible"), output, args, flags, output, safe));
    }
    @Test public void capiByteStorageAndAddressesKeepTheirExactVoidWorkerShape() {
        for (var rep : List.of("ByteArray#", "MutableByteArray#", "AddrRep")) {
            var signature = new PackageScalarSignature("wrapper", "unused", List.of(rep), "void", "capi");
            var link = new PackageScalarLink("first", "unused", "", "", new byte[0], List.of(signature));
            Map<String, Object> output = Map.of("kind", "unknown", "primReps", List.of(), "evaluated", true, "aggregate", "unboxed-tuple", "components", List.of(scalar(null)));
            Map<String, Object> descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", "wrapper", "unit", "first", "isFunction", true),
                "convention", "capi", "safety", "unsafe", "arity", 2L, "suppliedArity", 2L, "argumentReps", List.of(scalar(rep, false), scalar(null, false)), "resultRep", plus(output, "evaluated", false));
            var args = List.of(scalar(rep), scalar(null)); var flags = List.of(false, false);
            var call = Objects.requireNonNull(validate(descriptor, output, args, flags, output, link)); assertEquals("void", call.getResult());
            var types = java.util.Arrays.asList(rep.equals("AddrRep") ? null : rep, null);
            var typed = plus(plus(descriptor, "schema", 2L), "argumentTypes", types);
            assertEquals("void", Objects.requireNonNull(validate(typed, output, args, flags, output, link)).getResult());
            assertThrows(RuntimeFault.class, () -> validate(plus(typed, "argumentTypes", List.of("WrongType", "WrongState")), output, args, flags, output, link));
            var exact = exact(rep, true); CorePackageScalarForeign.validateOperand(call, 0, exact, exact);
            assertThrows(RuntimeFault.class, () -> validate(descriptor, output, List.of(scalar("Word64Rep"), scalar(null)), flags, output, link));
            assertThrows(RuntimeFault.class, () -> validate(descriptor, output, args, flags, result("Word64Rep"), link));
            assertThrows(RuntimeFault.class, () -> validate(descriptor, output, List.of(plus(scalar(rep), "primReps", List.of("BoxedRep (Just Lifted)")), scalar(null)), flags, output, link));
        }
    }
    private PackageScalarCall selected(String rep, PackageScalarLink selected) {
        Map<String, Object> descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", "read_bytes", "unit", "first", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", 2L, "suppliedArity", 2L, "argumentReps", List.of(scalar(rep, false), scalar(null, false)), "resultRep", result("WordRep", false));
        return validate(descriptor, result("WordRep"), List.of(scalar(rep), scalar(null)), List.of(false, false), result("WordRep"), selected);
    }
    @Test public void sameSymbolSelectsTheExactPointerOrByteArrayAdapter() {
        var variants = new ArrayList<PackageScalarSignature>(); var reps = List.of("AddrRep", "ByteArray#");
        for (int index = 0; index < reps.size(); index++) variants.add(new PackageScalarSignature("read_bytes", "adapter_" + index, List.of(reps.get(index)), "WordRep"));
        var link = new PackageScalarLink("first", "unused", "", "", new byte[0], variants);
        for (var signature : variants) assertSame(signature, Objects.requireNonNull(selected(signature.getArguments().getFirst(), link)).getSignature());
        assertThrows(RuntimeFault.class, () -> selected("WordRep", link));
        var second = variants.get(1);
        var ambiguous = new PackageScalarLink("first", "unused", "", "", new byte[0], List.of(second,
            new PackageScalarSignature(second.getSymbol(), "writable_adapter", List.of("MutableByteArray#"), second.getResult(), second.getConvention(), second.getSafety())));
        assertThrows(RuntimeFault.class, () -> selected("ByteArray#", ambiguous));
    }
}
