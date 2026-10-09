// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class CoreOriginalStdioTest {
    @Test void synchronousSafetyKeepsExactDeclarationsAndUnsupportedFailures() {
        assertSame(ForeignSafety.UNSAFE, ForeignSafety.synchronous("unsafe"));
        assertSame(ForeignSafety.SAFE, ForeignSafety.synchronous("safe"));
        for (var invalid : List.of("interruptible", "", "Safe"))
            assertEquals("Unsupported synchronous foreign safety: " + invalid,
                assertThrows(RuntimeFault.class, () -> ForeignSafety.synchronous(invalid)).getMessage());
        assertThrows(NullPointerException.class, () -> ForeignSafety.synchronous(null));
    }
    private static class Input {
        final Map<String, Object> descriptor;
        final Map<String, Object> metadata;
        final List<Object> arguments = new ArrayList<>();
        final List<Object> flags;
        final Map<String, Object> result;
        Input(String name) {
            descriptor = OriginalStdioFixtures.descriptor(name);
            metadata = new LinkedHashMap<>(Map.of("foreignCall", descriptor, "rep", OriginalStdioFixtures.tuple(name, true)));
            for (var rep : OriginalStdioFixtures.signatures.get(name)) arguments.add(OriginalStdioFixtures.scalar(rep, true));
            flags = new ArrayList<>(Collections.nCopies(arguments.size(), false));
            result = OriginalStdioFixtures.tuple(name, true);
        }
        Map<String, Object> target() { return (Map<String, Object>) descriptor.get("target"); }
        List<Map<String, Object>> declared() { return (List<Map<String, Object>>) descriptor.get("argumentReps"); }
        OriginalStdioOp validate() { return CoreOriginalStdio.validate(metadata, arguments, flags, result); }
        Map<String, Object> proof(int site) {
            return site == 0 ? (Map<String, Object>) descriptor.get("resultRep") : site == 1 ? (Map<String, Object>) metadata.get("rep") : result;
        }
    }
    private void reject(Input input) {
        var error = assertThrows(RuntimeFault.class, input::validate);
        assertTrue(error.getMessage().startsWith("Invalid original stdio call: "), error.getMessage());
    }
    private Map<String,Object> declaration(String symbol, String safety, List<String> primitives) {
        // Checked models of the pinned declarations. Windows CMode is Word16#;
        // IO.Windows.Encoding also declares the safe WideCharToMultiByte call.
        var result = OriginalStdioFixtures.tuple("dup2", true);
        var call = new LinkedHashMap<String,Object>();
        call.put("schema", 1);
        call.put("target", Map.of("kind", "static", "symbol", symbol, "unit", "ghc-internal", "isFunction", true));
        call.put("convention", "ccall");
        call.put("safety", safety);
        call.put("arity", primitives.size() + 1);
        call.put("suppliedArity", primitives.size() + 1);
        var declared = new ArrayList<Map<String,Object>>();
        for (var primitive : primitives) declared.add(OriginalStdioFixtures.scalar(primitive, false));
        declared.add(OriginalStdioFixtures.scalar(null, false));
        call.put("argumentReps", declared);
        call.put("resultRep", OriginalStdioFixtures.tuple("dup2", false));
        return Map.of("foreignCall", call, "rep", result);
    }
    private OriginalStdioOp validateDeclaration(Map<String,Object> metadata) {
        var call = (Map<?,?>) metadata.get("foreignCall");
        var declared = (List<?>) call.get("argumentReps");
        var actual = new ArrayList<Map<String,Object>>();
        for (var raw : declared) {
            var argument = new LinkedHashMap<>((Map<String,Object>) raw);
            argument.put("evaluated", true);
            actual.add(argument);
        }
        return CoreOriginalStdio.validate(metadata, actual, Collections.nCopies(actual.size(), false), metadata.get("rep"));
    }
    @Test void originalOpenRetainsBothUnsignedModeWidthsAndEveryDeclaredSafety() {
        for (var mode : List.of("Word16Rep", "Word32Rep")) {
            for (var safety : List.of("unsafe", "safe", "interruptible")) {
                var operation = validateDeclaration(declaration("__hscore_open", safety, List.of("AddrRep", "Int32Rep", mode)));
                assertTrue(operation.getOpening());
                assertEquals(mode, operation.getArguments().get(2));
                assertEquals(safety, operation.getSafety());
                CoreOriginalStdio.validateScalarOperand(operation, 2,
                    CoreRepresentations.parse(OriginalStdioFixtures.scalar(mode, true)), null);
            }
        }
        for (var mode : List.of("Word8Rep", "Int16Rep", "Int32Rep", "Word64Rep"))
            assertThrows(RuntimeFault.class, () -> validateDeclaration(declaration("__hscore_open", "unsafe", List.of("AddrRep", "Int32Rep", mode))));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void windowsOpeningRequiresTheActualSelectedAdapterInsteadOfLogicalFds() {
        for (var safety : List.of("unsafe", "safe")) {
            var metadata = declaration("__hscore_open", safety, List.of("AddrRep", "Int32Rep", "Word16Rep"));
            var operation = validateDeclaration(metadata);
            var actual = List.of(OriginalStdioFixtures.scalar("AddrRep", true),
                OriginalStdioFixtures.scalar("Int32Rep", true), OriginalStdioFixtures.scalar("Word16Rep", true),
                OriginalStdioFixtures.scalar(null, true));
            var flags = Collections.nCopies(4, false);
            var signature = new PackageScalarSignature("__hscore_open", "checked-adapter",
                List.of("AddrRep", "Int32Rep", "Word16Rep"), "Int32Rep", "ccall", safety);
            // Validation model only; no invented CBD or native execution claim.
            for (var target : List.of("x86_64-pc-windows-msvc19.33.0", "x86_64-unknown-linux-gnu")) {
                var link = new PackageScalarLink("ghc-internal", target, "model", "model", new byte[]{1},
                    List.of(signature), "llvm-bitcode", Set.of(), new byte[]{1});
                if (target.contains("windows")) {
                    var call = CoreOriginalStdio.windowsOpening(operation, metadata, actual, flags, metadata.get("rep"), List.of(link));
                    assertSame(link, call.getLink()); assertEquals(signature, call.getSignature());
                } else assertThrows(RuntimeFault.class, () ->
                    CoreOriginalStdio.windowsOpening(operation, metadata, actual, flags, metadata.get("rep"), List.of(link)));
            }
            assertThrows(RuntimeFault.class, () ->
                CoreOriginalStdio.windowsOpening(operation, metadata, actual, flags, metadata.get("rep"), List.of()));
        }
    }
    @Test void windowsWideConversionRetainsSafeAndUnsafeDeclarations() {
        var primitives = List.of("Word32Rep", "Word32Rep", "AddrRep", "Int32Rep", "AddrRep", "Int32Rep", "AddrRep", "AddrRep");
        for (var safety : List.of("unsafe", "safe")) {
            var metadata = declaration("WideCharToMultiByte", safety, primitives);
            assertSame(CoreForeignOverride.STDIO, CoreForeignOverride.select(metadata));
            var operation = validateDeclaration(metadata);
            assertTrue(operation.getWindowsEncoding());
            assertEquals(safety, operation.getSafety());
        }
        assertThrows(RuntimeFault.class, () -> validateDeclaration(declaration("WideCharToMultiByte", "interruptible", primitives)));
    }
    @Test void unixPipeAndDupToKeepTheirOriginalOwnerAndExactAbi() {
        // unix-2.8.8.0 System.Posix.IO.Common: c_pipe and c_dup2.
        for (var symbol : List.of("pipe", "dup2")) {
            var input = new Input(symbol.equals("pipe") ? "unlink" : "dup2");
            input.target().put("symbol", symbol);
            for (var owner : List.of("unix-2.8.8.0-inplace", "unix-2.8.8.0-0123abcdef")) {
                input.target().put("unit", owner);
                assertSame(symbol.equals("pipe") ? OriginalStdioOp.PIPE : OriginalStdioOp.DUP2, input.validate());
            }
            for (var owner : List.of("main", "unix-2.8.7.0-inplace", "unix-2.8.8.0-not-a-hash")) {
                input.target().put("unit", owner);
                reject(input);
            }
            input.target().put("unit", "unix-2.8.8.0-inplace");
            input.declared().set(0, OriginalStdioFixtures.scalar("Word64Rep", false));
            reject(input);
        }
    }
    @Test void allElevenExactContracts() {
        assertEquals(Set.of("safe_write", "unsafe_write", "errno", "set_errno", "dup", "dup2", "unlink",
            "seek_set", "seek_cur", "seek_end", "strerror"), OriginalStdioFixtures.signatures.keySet());
        for (var name : OriginalStdioFixtures.signatures.keySet()) {
            var input = new Input(name);
            assertEquals(OriginalStdioFixtures.symbols.get(name), input.validate().getSymbol());
            for (var argument : input.arguments) ((Map<String, Object>) argument).put("evaluated", false);
            input.result.put("evaluated", false);
            assertEquals(OriginalStdioFixtures.symbols.get(name), input.validate().getSymbol());
        }
    }
    @Test void noSymbolAliasesAreAdmitted() {
        var symbol = OriginalStdioFixtures.symbols.get("safe_write");
        // Direct Unix read/write are recognized declarations, not aliases of this capi wrapper.
        for (var direct : List.of("write", "read")) {
            var input = new Input("safe_write"); input.target().put("symbol", direct); reject(input);
        }
        for (var unknown : List.of("__hscore_set_errno64", "thc_io_v1_write",
            symbol.replace("ZC20ZC", "ZC22ZC"), symbol + "64", "prefix" + symbol)) {
            var input = new Input("safe_write"); input.target().put("symbol", unknown); assertNull(input.validate());
        }
        for (var metadata : Arrays.asList(null, Map.of(), Collections.singletonMap("foreignCall", null)))
            assertNull(CoreOriginalStdio.validate(metadata, List.of(), List.of(), null));
    }
    @Test void exactTargetDescriptorAndIntegerFields() {
        for (var name : OriginalStdioFixtures.signatures.keySet()) {
            for (var field : List.of("schema", "arity", "suppliedArity")) {
                for (var value : Arrays.asList(null, true, false, 1.0, 2.0, "1", -1L, 1L << 32)) {
                    var input = new Input(name); input.descriptor.put(field, value); reject(input);
                }
                var input = new Input(name); input.descriptor.remove(field); reject(input);
            }
            for (var entry : Map.of("kind", Arrays.asList(null, "dynamic", false), "unit", Arrays.asList(null, "", "main", "other-package", 7L, false),
                "isFunction", Arrays.asList(null, false, 1L, "true")).entrySet()) for (var value : entry.getValue()) {
                var input = new Input(name); input.target().put(entry.getKey(), value); reject(input);
            }
            for (var entry : Map.of("convention", Arrays.asList(null, "prim", "javascript", OriginalStdioFixtures.convention(name).equals("ccall") ? "capi" : "ccall"),
                "safety", Arrays.asList(null, "interruptible", OriginalStdioFixtures.safety(name).equals("safe") ? "unsafe" : "safe")).entrySet()) for (var value : entry.getValue()) {
                var input = new Input(name); input.descriptor.put(entry.getKey(), value); reject(input);
            }
            var missing = new Input(name); missing.target().remove("unit"); reject(missing);
            var target = new Input(name); target.target().put("extra", false); reject(target);
            var descriptor = new Input(name); descriptor.descriptor.put("extra", false); reject(descriptor);
        }
    }
    @Test void everyArgumentFlagAndProofIsExact() {
        for (var name : OriginalStdioFixtures.signatures.keySet()) {
            for (int i = 0; i < OriginalStdioFixtures.signatures.get(name).size(); i++) {
                for (var value : Arrays.asList(true, 0, null, "false")) {
                    var input = new Input(name); input.flags.set(i, value); reject(input);
                }
                for (boolean declared : new boolean[]{false, true}) {
                    for (var change : new Object[][]{{"kind","unknown"},{"primReps",List.of("WordRep")},
                        {"primReps",List.of("IntRep")},{"evaluated",1},{"vector",null},
                        {"aggregate","unboxed-tuple"},{"components",List.of()},{"tagSlot",0}}) {
                        var input = new Input(name);
                        var proof = declared ? input.declared().get(i) : (Map<String, Object>) input.arguments.get(i);
                        proof.put((String) change[0], change[1]); reject(input);
                    }
                }
                var input = new Input(name); input.declared().get(i).put("evaluated", true); reject(input);
            }
            var args = new Input(name); args.arguments.remove(0); reject(args);
            var declared = new Input(name); declared.declared().remove(0); reject(declared);
            var flags = new Input(name); flags.flags.add(false); reject(flags);
            var missing = new Input(name); missing.flags.remove(0); reject(missing);
            var extra = new Input(name); extra.arguments.add(extra.arguments.getFirst()); reject(extra);
        }
    }
    @Test void allThreeResultProofsPreserveExactStateAndPayload() {
        for (var name : List.of("safe_write", "errno", "dup", "dup2")) for (int site = 0; site <= 2; site++) {
            for (var change : new Object[][]{{"kind","void"},{"primReps",List.of()},
                {"aggregate","unboxed-sum"},{"evaluated",1},{"components",List.of()},{"vector",null}}) {
                var input = new Input(name); input.proof(site).put((String) change[0], change[1]); reject(input);
            }
            for (int index = 0; index <= 1; index++) {
                for (var change : new Object[][]{{"evaluated",false},{"primReps",List.of("WordRep")},
                    {"components",List.of()},{"kind","object"}}) {
                    var input = new Input(name);
                    var components = (List<Map<String, Object>>) input.proof(site).get("components");
                    components.get(index).put((String) change[0], change[1]); reject(input);
                }
            }
        }
        var input = new Input("safe_write"); input.proof(0).put("evaluated", true); reject(input);
    }
    @Test void numericWidthsAndSignednessAreNotInterchangeable() {
        var alternatives = List.of("IntRep", "WordRep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "AddrRep");
        for (var entry : OriginalStdioFixtures.signatures.entrySet()) for (int index = 0; index < entry.getValue().size(); index++)
            for (var replacement : alternatives) if (!replacement.equals(entry.getValue().get(index)))
                for (boolean declared : new boolean[]{false, true}) {
                    var input = new Input(entry.getKey());
                    var proof = OriginalStdioFixtures.scalar(replacement, !declared);
                    if (declared) input.declared().set(index, proof); else input.arguments.set(index, proof);
                    reject(input);
                }
    }
    @Test void setterRequiresSingletonStateAtAllResultProofSites() {
        for (int site = 0; site <= 2; site++) for (var mutation : List.of("bare", "empty", "extra", "sum")) {
            var input = new Input("set_errno");
            var proof = input.proof(site);
            switch (mutation) {
                case "bare" -> { proof.clear(); proof.putAll(OriginalStdioFixtures.scalar(null, site != 0)); }
                case "empty" -> proof.put("components", List.of());
                case "extra" -> proof.put("components", List.of(OriginalStdioFixtures.scalar(null, true), OriginalStdioFixtures.scalar(null, true)));
                case "sum" -> proof.put("aggregate", "unboxed-sum");
            }
            reject(input);
        }
    }
    private List<Object> head(Object id) { return Arrays.asList("var", id, Map.of("rep", OriginalStdioFixtures.closure())); }
    @Test void foreignHeadsAreUnboundDeclarationsNotCallerNameAliases() {
        for (var name : List.of("arbitrary", "other-package:Caller.inlined", "write"))
            CoreOriginalStdio.validateHead(head(name), false);
        var malformed = List.of(List.of(), List.of("var", "foreign"), List.of("prim", "foreign"),
            head(null), head(""), head(3), List.of("var", "foreign", Map.of()));
        for (var value : malformed) assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validateHead(value, false));
        assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validateHead(head("foreign"), true));
        for (var change : new Object[][]{{"kind","long"},{"primReps",List.of("IntRep")},
            {"evaluated",false},{"evaluated",1},{"extra",null}}) {
            var proof = OriginalStdioFixtures.closure(); proof.put((String) change[0], change[1]);
            assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validateHead(List.of("var", "foreign", Map.of("rep", proof)), false));
        }
    }
}
