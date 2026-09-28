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

/** Synthetic descriptors, not renamed main-unit exports or Fingerprint execution.
 * Installed-interface unit evidence: t/fixtures/compiler/md5-foreign-unit-evidence.md. */
@SuppressWarnings("unchecked")
class CoreMd5ForeignTest {
    private Map<String, Object> scalar(String primitive, boolean evaluated) {
        return new LinkedHashMap<>(Map.of("kind", primitive == null ? "void" : primitive.equals("AddrRep") ? "address" : "long",
            "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", evaluated));
    }
    private Map<String, Object> result() {
        return new LinkedHashMap<>(Map.of("kind", "unknown", "primReps", List.of(), "evaluated", false,
            "aggregate", "unboxed-tuple", "components", List.of(scalar(null, true))));
    }
    private record Input(Map<String, Object> metadata, List<Object> arguments, List<Object> flags, Map<String, Object> result) {
        Map<String, Object> descriptor() { return (Map<String, Object>) metadata.get("foreignCall"); }
        Map<String, Object> target() { return (Map<String, Object>) descriptor().get("target"); }
        List<Map<String, Object>> declared() { return (List<Map<String, Object>>) descriptor().get("argumentReps"); }
        Md5ForeignOp validate() { return CoreMd5Foreign.validate(metadata, arguments, flags, result); }
        Map<String, Object> proof(int site) {
            return site == 0 ? (Map<String, Object>) descriptor().get("resultRep") : site == 1 ? (Map<String, Object>) metadata.get("rep") : result;
        }
    }
    private Input fixture() { return fixture(Md5ForeignOp.INIT); }
    private Input fixture(Md5ForeignOp operation) {
        // Independently written schema, not shapes derived from the validator.
        List<String> primitives = switch (operation) {
            case INIT -> Arrays.asList("AddrRep", null);
            case UPDATE -> Arrays.asList("AddrRep", "AddrRep", "Int32Rep", null);
            case FINAL -> Arrays.asList("AddrRep", "AddrRep", null);
        };
        var declared = new ArrayList<Map<String, Object>>();
        var arguments = new ArrayList<Object>();
        for (var primitive : primitives) { declared.add(scalar(primitive, false)); arguments.add(scalar(primitive, true)); }
        var descriptor = new LinkedHashMap<String, Object>(Map.of("schema", 1L,
            "target", new LinkedHashMap<>(Map.of("kind", "static", "symbol", operation.getSymbol(), "unit", "ghc-internal", "isFunction", true)),
            "convention", "ccall", "safety", "unsafe", "arity", (long) primitives.size(), "suppliedArity", (long) primitives.size(),
            "argumentReps", declared, "resultRep", result()));
        return new Input(new LinkedHashMap<>(Map.of("foreignCall", descriptor, "rep", result())), arguments,
            new ArrayList<>(Collections.nCopies(primitives.size(), false)), result());
    }
    private void reject(Input input) {
        var error = assertThrows(RuntimeFault.class, input::validate);
        assertTrue(error.getMessage().startsWith("Invalid MD5 foreign call: "));
    }
    @Test void exactThreeContractsAndActualEvaluationFacts() {
        for (var operation : Md5ForeignOp.values()) {
            var input = fixture(operation);
            assertEquals(operation, input.validate());
            for (var argument : input.arguments) ((Map<String, Object>) argument).put("evaluated", false);
            input.proof(1).put("evaluated", true); input.result.put("evaluated", true);
            assertEquals(operation, input.validate());
            input.descriptor().put("schema", 1); input.descriptor().put("arity", operation.getArity());
            input.descriptor().put("suppliedArity", operation.getArity());
            assertEquals(operation, input.validate());
        }
    }
    @Test void MissingUnknownAndDynamicTargetsRetainOrdinaryResolution() {
        for (var value : Arrays.asList(null, Map.of(), Collections.singletonMap("foreignCall", null),
            Map.of("foreignCall", Map.of("target", Map.of("kind", "dynamic"))),
            Map.of("foreignCall", Map.of("target", Map.of("symbol", "MD5Init"))),
            Map.of("foreignCall", Map.of("target", Map.of("symbol", "__hsbase_MD5Other")))))
            assertNull(CoreMd5Foreign.validate(value, List.of(), List.of(), null));
    }
    @Test void ExactIntegerAndDescriptorKeysRejectNormalizedForgeries() {
        for (var field : List.of("schema", "arity", "suppliedArity")) {
            for (var value : Arrays.asList(null, true, false, 1.0, 2.0, 4.0, "2", -1L, 1L << 32)) {
                var input = fixture(); input.descriptor().put(field, value); reject(input);
            }
            var input = fixture(); input.descriptor().remove(field); reject(input);
        }
        var input = fixture(); input.descriptor().put("extra", true); reject(input);
    }
    @Test void KnownSymbolsRequireOriginalUnitStaticFunctionUnsafeCcall() {
        var targets = Map.of("kind", Arrays.asList("dynamic", null, 0), "unit", Arrays.asList("main", null, "ghc-internal-9.1401.0"),
            "isFunction", Arrays.asList(false, 1, "true", null));
        for (var entry : targets.entrySet()) for (var value : entry.getValue()) {
            var input = fixture(); input.target().put(entry.getKey(), value); reject(input);
        }
        var modes = Map.of("safety", Arrays.asList("safe", "interruptible", null),
            "convention", Arrays.asList("capi", "stdcall", "prim", "javascript", null));
        for (var entry : modes.entrySet()) for (var value : entry.getValue()) {
            var input = fixture(); input.descriptor().put(entry.getKey(), value); reject(input);
        }
        var input = fixture(); input.target().put("extra", 0); reject(input);
    }
    @Test void EveryDeclaredAndActualScalarSlotRejectsWrongOrAggregateProofs() {
        var changes = new Object[][]{{"primReps",List.of("IntRep")},{"primReps",List.of("Word32Rep")},{"primReps",null},
            {"kind","unknown"},{"evaluated",1},{"aggregate","unboxed-tuple"},{"components",List.of()},
            {"vector",null},{"alternatives",List.of()},{"tagSlot",0}};
        for (var operation : Md5ForeignOp.values()) for (boolean declared : new boolean[]{false, true})
            for (int i = 0; i < operation.getArity(); i++) {
                for (var change : changes) {
                    var input = fixture(operation);
                    var proof = declared ? input.declared().get(i) : (Map<String, Object>) input.arguments.get(i);
                    proof.put((String) change[0], change[1]); reject(input);
                }
                var input = fixture(operation);
                var proof = declared ? input.declared().get(i) : (Map<String, Object>) input.arguments.get(i);
                proof.remove("evaluated"); reject(input);
            }
        var input = fixture(); input.declared().getFirst().put("evaluated", true); reject(input);
    }
    @Test void EveryResultSiteRequiresOneExactStateComponent() {
        var changes = new Object[][]{{"kind","void"},{"primReps",List.of("IntRep")},{"evaluated",0},
            {"aggregate","unboxed-sum"},{"components",List.of()},{"components",List.of(scalar(null,false),scalar(null,false))},
            {"components",List.of(scalar("Int32Rep",true))},{"components",List.of(scalar(null,false))},
            {"vector",null},{"alternatives",List.of()},{"tagSlot",0}};
        for (int site = 0; site <= 2; site++) {
            for (var change : changes) { var input = fixture(); input.proof(site).put((String) change[0], change[1]); reject(input); }
            var input = fixture(); input.proof(site).remove("aggregate"); reject(input);
        }
        var input = fixture(); input.proof(0).put("evaluated", true); reject(input);
    }
    @Test void ActualArityAndEveryUnliftedFlagAreExact() {
        for (var operation : Md5ForeignOp.values()) {
            for (int i = 0; i < operation.getArity(); i++) for (var value : Arrays.asList(true, 0, null, "false")) {
                var input = fixture(operation); input.flags.set(i, value); reject(input);
            }
            var missing = fixture(operation); missing.arguments.removeFirst(); reject(missing);
            var extra = fixture(operation); extra.arguments.add(extra.arguments.getFirst()); reject(extra);
            var flags = fixture(operation); flags.flags.removeFirst(); reject(flags);
            var more = fixture(operation); more.flags.add(false); reject(more);
            var declared = fixture(operation); declared.declared().removeFirst(); reject(declared);
        }
    }
    @Test void DeclaredActualAndSymbolProofsCannotContradictEachOther() {
        var symbol = fixture(Md5ForeignOp.UPDATE); symbol.target().put("symbol", "__hsbase_MD5Final"); reject(symbol);
        var argument = fixture(Md5ForeignOp.UPDATE); argument.arguments.set(2, scalar("AddrRep", true)); reject(argument);
        var missing = fixture(); missing.metadata.remove("rep"); reject(missing);
    }
}
