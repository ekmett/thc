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
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class CoreOriginalStdioTest {
    private static final OriginalStdioFixtures fixtures = OriginalStdioFixtures.INSTANCE;
    private static class Input {
        final Map<String, Object> descriptor;
        final Map<String, Object> metadata;
        final List<Object> arguments = new ArrayList<>();
        final List<Object> flags;
        final Map<String, Object> result;
        Input(String name) {
            descriptor = fixtures.descriptor(name);
            metadata = new LinkedHashMap<>(Map.of("foreignCall", descriptor, "rep", fixtures.tuple(name, true)));
            for (var rep : fixtures.getSignatures().get(name)) arguments.add(fixtures.scalar(rep, true));
            flags = new ArrayList<>(Collections.nCopies(arguments.size(), false));
            result = fixtures.tuple(name, true);
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
    @Test void allElevenExactContracts() {
        assertEquals(Set.of("safe_write", "unsafe_write", "errno", "set_errno", "dup", "dup2", "unlink",
            "seek_set", "seek_cur", "seek_end", "strerror"), fixtures.getSignatures().keySet());
        for (var name : fixtures.getSignatures().keySet()) {
            var input = new Input(name);
            assertEquals(fixtures.getSymbols().get(name), input.validate().getSymbol());
            for (var argument : input.arguments) ((Map<String, Object>) argument).put("evaluated", false);
            input.result.put("evaluated", false);
            assertEquals(fixtures.getSymbols().get(name), input.validate().getSymbol());
        }
    }
    @Test void noSymbolAliasesAreAdmitted() {
        var symbol = fixtures.getSymbols().get("safe_write");
        for (var unknown : List.of("write", "read", "__hscore_set_errno64", "thc_io_v1_write",
            symbol.replace("ZC20ZC", "ZC22ZC"), symbol + "64", "prefix" + symbol)) {
            var input = new Input("safe_write"); input.target().put("symbol", unknown); assertNull(input.validate());
        }
        for (var metadata : Arrays.asList(null, Map.of(), Collections.singletonMap("foreignCall", null)))
            assertNull(CoreOriginalStdio.validate(metadata, List.of(), List.of(), null));
    }
    @Test void exactTargetDescriptorAndIntegerFields() {
        for (var name : fixtures.getSignatures().keySet()) {
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
            for (var entry : Map.of("convention", Arrays.asList(null, "prim", "javascript", fixtures.convention(name).equals("ccall") ? "capi" : "ccall"),
                "safety", Arrays.asList(null, "interruptible", fixtures.safety(name).equals("safe") ? "unsafe" : "safe")).entrySet()) for (var value : entry.getValue()) {
                var input = new Input(name); input.descriptor.put(entry.getKey(), value); reject(input);
            }
            var missing = new Input(name); missing.target().remove("unit"); reject(missing);
            var target = new Input(name); target.target().put("extra", false); reject(target);
            var descriptor = new Input(name); descriptor.descriptor.put("extra", false); reject(descriptor);
        }
    }
    @Test void everyArgumentFlagAndProofIsExact() {
        for (var name : fixtures.getSignatures().keySet()) {
            for (int i = 0; i < fixtures.getSignatures().get(name).size(); i++) {
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
        for (var entry : fixtures.getSignatures().entrySet()) for (int index = 0; index < entry.getValue().size(); index++)
            for (var replacement : alternatives) if (!replacement.equals(entry.getValue().get(index)))
                for (boolean declared : new boolean[]{false, true}) {
                    var input = new Input(entry.getKey());
                    var proof = fixtures.scalar(replacement, !declared);
                    if (declared) input.declared().set(index, proof); else input.arguments.set(index, proof);
                    reject(input);
                }
    }
    @Test void setterRequiresSingletonStateAtAllResultProofSites() {
        for (int site = 0; site <= 2; site++) for (var mutation : List.of("bare", "empty", "extra", "sum")) {
            var input = new Input("set_errno");
            var proof = input.proof(site);
            switch (mutation) {
                case "bare" -> { proof.clear(); proof.putAll(fixtures.scalar(null, site != 0)); }
                case "empty" -> proof.put("components", List.of());
                case "extra" -> proof.put("components", List.of(fixtures.scalar(null, true), fixtures.scalar(null, true)));
                case "sum" -> proof.put("aggregate", "unboxed-sum");
            }
            reject(input);
        }
    }
    private List<Object> head(Object id) { return Arrays.asList("var", id, Map.of("rep", fixtures.closure())); }
    @Test void foreignHeadsAreUnboundDeclarationsNotCallerNameAliases() {
        for (var name : List.of("arbitrary", "other-package:Caller.inlined", "write"))
            CoreOriginalStdio.validateHead(head(name), false);
        var malformed = List.of(List.of(), List.of("var", "foreign"), List.of("prim", "foreign"),
            head(null), head(""), head(3), List.of("var", "foreign", Map.of()));
        for (var value : malformed) assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validateHead(value, false));
        assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validateHead(head("foreign"), true));
        for (var change : new Object[][]{{"kind","long"},{"primReps",List.of("IntRep")},
            {"evaluated",false},{"evaluated",1},{"extra",null}}) {
            var proof = fixtures.closure(); proof.put((String) change[0], change[1]);
            assertThrows(RuntimeFault.class, () -> CoreOriginalStdio.validateHead(List.of("var", "foreign", Map.of("rep", proof)), false));
        }
    }
}
