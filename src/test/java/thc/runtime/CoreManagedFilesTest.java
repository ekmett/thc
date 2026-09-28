// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class CoreManagedFilesTest {
    private static final ManagedFileFixtures fixtures = ManagedFileFixtures.INSTANCE;
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
        ManagedFileOp validate() { return CoreManagedFiles.validate(metadata, arguments, flags, result); }
        Map<String, Object> proof(int site) {
            return site == 0 ? (Map<String, Object>) descriptor.get("resultRep") : site == 1 ? (Map<String, Object>) metadata.get("rep") : result;
        }
    }
    private void reject(Input input) {
        var error = assertThrows(RuntimeFault.class, input::validate);
        assertTrue(error.getMessage().startsWith("Invalid managed file call: "), error.getMessage());
    }
    @Test void allElevenExactContractsAndImportingUnits() {
        assertEquals(11, fixtures.getSignatures().size());
        for (var name : fixtures.getSignatures().keySet()) for (var unit : Arrays.asList(null, "main", "library-1.0", "ghc-internal")) {
            var input = new Input(name);
            input.target().put("unit", unit);
            assertEquals("thc_io_v1_" + name, input.validate().getSymbol());
            for (var argument : input.arguments) ((Map<String, Object>) argument).put("evaluated", false);
            input.result.put("evaluated", false);
            assertEquals("thc_io_v1_" + name, input.validate().getSymbol());
        }
    }
    @Test void prefixIsClosedAndPosixNamesNeverDispatch() {
        for (var symbol : List.of("thc_io_v1_", "thc_io_v1_unknown", "thc_io_v1_open64")) {
            var input = new Input("open"); input.target().put("symbol", symbol); reject(input);
        }
        for (var symbol : List.of("open", "read", "write", "close", "fstat", "thc_io_v2_open", "__hsbase_open")) {
            var input = new Input("open"); input.target().put("symbol", symbol); assertNull(input.validate());
        }
        for (var metadata : Arrays.asList(null, Map.of(), Collections.singletonMap("foreignCall", null)))
            assertNull(CoreManagedFiles.validate(metadata, List.of(), List.of(), null));
    }
    @Test void exactTargetDescriptorAndIntegerFields() {
        for (var name : fixtures.getSignatures().keySet()) {
            for (var field : List.of("schema", "arity", "suppliedArity")) {
                for (var value : Arrays.asList(null, true, false, 1.0, 2.0, "1", -1L, 1L << 32)) {
                    var input = new Input(name); input.descriptor.put(field, value); reject(input);
                }
                var input = new Input(name); input.descriptor.remove(field); reject(input);
            }
            for (var entry : Map.of("kind", Arrays.asList(null, "dynamic", false), "unit", List.of("", 7L, false),
                "isFunction", Arrays.asList(null, false, 1L, "true")).entrySet()) for (var value : entry.getValue()) {
                var input = new Input(name); input.target().put(entry.getKey(), value); reject(input);
            }
            for (var entry : Map.of("convention", Arrays.asList(null, "ccall", "capi", "javascript"),
                "safety", Arrays.asList(null, "unsafe", "interruptible")).entrySet()) for (var value : entry.getValue()) {
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
                        {"primReps",List.of("Int32Rep")},{"evaluated",1},{"vector",null},
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
            var extra = new Input(name); extra.arguments.add(extra.arguments.getFirst()); reject(extra);
        }
    }
    @Test void allThreeResultProofsPreserveExactStateAndPayload() {
        for (var name : List.of("open", "error_message")) for (int site = 0; site <= 2; site++) {
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
        var input = new Input("open"); input.proof(0).put("evaluated", true); reject(input);
    }
}
