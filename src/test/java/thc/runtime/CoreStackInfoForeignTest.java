// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Every baseline is a retained original FCall application, not a fresh FFI fixture. */
@SuppressWarnings("unchecked")
public class CoreStackInfoForeignTest {
    private final Map<String, OriginalStackInfoOp> symbols = new LinkedHashMap<>();
    public CoreStackInfoForeignTest() { for (var op : OriginalStackInfoOp.values()) symbols.put(op.getSymbol(), op); }
    private Map<String, Object> original() throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/core/original-stack-info-calls.json"))) {
            return (Map<String, Object>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private Map<String, Object> getterProof;
    private synchronized Map<String, Object> getterProof() throws Exception {
        if (getterProof == null) {
            var file = new File(System.getProperty("thc.projectRoot"), "t/fixtures/compiler/OriginalStackProof.json");
            var bytes = Files.readAllBytes(file.toPath());
            assertEquals("db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            getterProof = (Map<String, Object>) Json.parse(new String(bytes, StandardCharsets.UTF_8));
        }
        return getterProof;
    }
    private Map<Object, List<List<Object>>> applications;
    private synchronized Map<Object, List<List<Object>>> applications() throws Exception {
        if (applications == null) {
            var grouped = new LinkedHashMap<Object, List<List<Object>>>();
            for (var record : (List<Map<String, Object>>) getterProof().get("calls")) {
                var expression = (List<Object>) record.get("expression");
                var target = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) expression.get(6)).get("foreignCall")).get("target");
                grouped.computeIfAbsent(target.get("symbol"), _ -> new ArrayList<>()).add(expression);
            }
            applications = grouped;
        }
        return applications;
    }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private final class Input {
        final String symbol;
        final List<Object> application;
        final Map<String, Object> metadata;
        final Map<String, Object> descriptor;
        final List<Object> arguments = new ArrayList<>();
        final List<Object> flags;
        Object result;
        Input(String symbol) throws Exception {
            this.symbol = symbol; application = (List<Object>) copy(applications().get(symbol).getFirst());
            metadata = (Map<String, Object>) application.get(6); descriptor = (Map<String, Object>) metadata.get("foreignCall");
            for (var argument : (List<List<Object>>) application.get(2)) arguments.add(copy(((Map<?, ?>) argument.getLast()).get("rep")));
            flags = (List<Object>) application.get(3); result = copy(metadata.get("rep"));
        }
        Map<String, Object> target() { return (Map<String, Object>) descriptor.get("target"); }
        List<Object> declared() { return (List<Object>) descriptor.get("argumentReps"); }
        OriginalStackInfoOp validate() { return CoreStackInfoForeign.validate(metadata, arguments, flags, result); }
        Map<String, Object> resultAt(int site) { return (Map<String, Object>) switch (site) { case 0 -> descriptor.get("resultRep"); case 1 -> metadata.get("rep"); default -> result; }; }
        void replaceResult(int site, Object value) { switch (site) { case 0 -> descriptor.put("resultRep", value); case 1 -> metadata.put("rep", value); default -> result = value; } }
    }
    private void reject(Input input) {
        var error = assertThrows(RuntimeFault.class, input::validate);
        assertTrue(error.getMessage() != null && error.getMessage().startsWith("Invalid original stack info call: "), error.getMessage());
    }
    private Map<String, Object> scalar(String rep) { return scalar(rep, false); }
    private Map<String, Object> scalar(String rep, boolean evaluated) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", switch (rep) { case null -> "void"; case "AddrRep" -> "address"; case "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> "object"; default -> "long"; });
        result.put("primReps", rep == null ? List.of() : List.of(rep)); result.put("evaluated", evaluated); return result;
    }
    private record Mutation(String key, Object value) {}
    @Test public void retainedProofProvenanceAndAllFourteenExactContracts() throws Exception {
        var source = original(); assertEquals(1L, source.get("schema")); assertEquals("9.14.1", source.get("ghc"));
        assertEquals("902339d332fb4ce2b3c87dcac1ee6495d41ad886", source.get("ghcRevision"));
        assertEquals("62e3400c5b889d3971cb4047709c408fd270255f", source.get("exporterRevision"));
        var expectedHashes = Map.of("GHC.Internal.Stack.Decode/GHC.Internal.Stack.Decode.json", "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42",
            "GHC.Internal.InfoProv.Types/GHC.Internal.InfoProv.Types.json", "63fe524cfd81c88ebd4f835c8718a30b86828c9e53549a2c001cbffac5ab2d1e");
        var sources = (List<Map<String, Object>>) source.get("sources"); var hashes = new LinkedHashMap<Object, Object>();
        for (var item : sources) hashes.put(item.get("file"), item.get("sha256")); assertEquals(expectedHashes, hashes);
        for (var item : sources) assertEquals("optimized-Core-after-Tidy-before-CorePrep", item.get("boundary"));
        var calls = (List<Map<String, Object>>) source.get("calls"); assertEquals(3, calls.size());
        var owners = new LinkedHashSet<>(); for (var call : calls) owners.add(call.get("owner"));
        assertEquals(Set.of("ghc-internal:GHC.Internal.Stack.Decode.$wdecodeStackWithFrameUnpack", "ghc-internal:GHC.Internal.Stack.Decode.$wunpackStackFrameTo", "ghc-internal:GHC.Internal.InfoProv.Types.lookupIPE"), owners);
        for (var call : calls) { assertTrue(((String) call.get("path")).startsWith("/expr/")); assertTrue(expectedHashes.containsKey((String) call.get("source"))); }
        assertEquals(new LinkedHashSet<>(symbols.values()), new LinkedHashSet<>(Arrays.asList(OriginalStackInfoOp.values())));
        var expectedSymbols = new LinkedHashSet<>(symbols.keySet()); expectedSymbols.add("stg_cloneMyStackzh"); assertEquals(expectedSymbols, applications().keySet());
        for (var entry : symbols.entrySet()) {
            var input = new Input(entry.getKey()); assertEquals(entry.getValue(), input.validate());
            CoreStackInfoForeign.validateHead((List<?>) input.application.get(1), false);
            CoreStackInfoForeign.validateHeads(Map.of("nested", List.of(input.application)));
            for (var argument : input.arguments) ((Map<String, Object>) argument).put("evaluated", false);
            input.resultAt(1).put("evaluated", true); input.resultAt(2).put("evaluated", true);
            assertEquals(entry.getValue(), input.validate(), "Actual evaluation information may differ, unlike declared proofs");
        }
    }
    @Test public void unknownSymbolsAliasesAndSeparateCloneProtocolStayUnrecognized() throws Exception {
        for (var unknown : List.of("getStackInfoTableAddr", "getInfoTableAddrszh64", "stg_getInfoTableAddrszh", "lookUpIPE", "getSmallBitmapzh2", "advanceStackFrameLocationzh2", "getWordzh2", "getStackClosurezh2", "stg_cloneMyStackzh", "")) {
            var input = new Input("lookupIPE"); input.target().put("symbol", unknown);
            input.application.set(1, 7L); // An unrelated unknown symbol must retain ordinary unsupported handling.
            assertNull(input.validate()); assertDoesNotThrow(() -> CoreStackInfoForeign.validateHeads(input.application));
        }
        var nullCall = new LinkedHashMap<String, Object>(); nullCall.put("foreignCall", null);
        for (var metadata : Arrays.asList(null, 1L, Map.of(), nullCall, Map.of("foreignCall", Map.of("target", Map.of()))))
            assertNull(CoreStackInfoForeign.validate(metadata, List.of(), List.of(), null));
    }
    @Test public void descriptorKeysTargetAndIntegralFieldsAreClosed() throws Exception {
        for (var symbol : symbols.keySet()) {
            for (var key : List.of("schema", "arity", "suppliedArity")) {
                for (var wrong : Arrays.asList(null, true, false, 1.0, 2.0, 3.0, "1", -1L, 0L, 1L << 32)) {
                    var input = new Input(symbol); input.descriptor.put(key, wrong); reject(input);
                }
                var input = new Input(symbol); input.descriptor.remove(key); reject(input);
            }
            for (var key : List.of("convention", "safety", "argumentReps", "resultRep")) { var input = new Input(symbol); input.descriptor.remove(key); reject(input); }
            for (var mutation : List.of(new Mutation("kind", "dynamic"), new Mutation("kind", null), new Mutation("unit", "main"), new Mutation("unit", null),
                    new Mutation("unit", "other-unit"), new Mutation("isFunction", false), new Mutation("isFunction", 1L), new Mutation("isFunction", "true"), new Mutation("extra", null))) {
                var input = new Input(symbol); input.target().put(mutation.key(), mutation.value()); reject(input);
            }
            for (var key : List.of("kind", "unit", "isFunction")) { var input = new Input(symbol); input.target().remove(key); reject(input); }
            for (var wrong : Arrays.asList("unsafe", "interruptible", null, 1L)) { var input = new Input(symbol); input.descriptor.put("safety", wrong); reject(input); }
            for (var wrong : Arrays.asList("capi", "javascript", symbol.equals("lookupIPE") ? "prim" : "ccall", null)) { var input = new Input(symbol); input.descriptor.put("convention", wrong); reject(input); }
            var input = new Input(symbol); input.descriptor.put("extra", false); reject(input);
        }
    }
    @Test public void argumentWidthsLevitiesAndLogicalStateCannotBeRelabeled() throws Exception {
        var alternatives = Arrays.asList(null, "IntRep", "WordRep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "Word8Rep", "AddrRep", "BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "BoxedRep Nothing");
        for (var symbol : symbols.keySet()) {
            var baseline = new Input(symbol);
            for (int index = 0; index < baseline.arguments.size(); index++) for (boolean declared : new boolean[]{false, true}) {
                var actualRep = ((Map<?, ?>) baseline.arguments.get(index)).get("primReps");
                for (var replacement : alternatives) {
                    var proof = scalar(replacement); if (Objects.equals(proof.get("primReps"), actualRep)) continue;
                    var input = new Input(symbol); (declared ? input.declared() : input.arguments).set(index, proof); reject(input);
                }
                for (var mutation : List.of(new Mutation("kind", "unknown"), new Mutation("evaluated", 1L), new Mutation("evaluated", null), new Mutation("primReps", null),
                        new Mutation("aggregate", "unboxed-tuple"), new Mutation("components", List.of()), new Mutation("vector", null))) {
                    var input = new Input(symbol); var proof = (Map<String, Object>) (declared ? input.declared() : input.arguments).get(index);
                    proof.put(mutation.key(), mutation.value()); reject(input);
                }
                var evaluated = new Input(symbol); ((Map<String, Object>) evaluated.declared().get(index)).put("evaluated", true); reject(evaluated);
                for (var key : List.of("kind", "primReps", "evaluated")) {
                    var input = new Input(symbol); ((Map<String, Object>) (declared ? input.declared() : input.arguments).get(index)).remove(key); reject(input);
                }
            }
        }
    }
    @Test public void saturationAndFlagsRejectMissingExtraAndNonBooleanValues() throws Exception {
        for (var symbol : symbols.keySet()) {
            int count = new Input(symbol).arguments.size();
            for (int index = 0; index < count; index++) {
                for (var wrong : Arrays.asList(true, 0L, null, "false")) { var input = new Input(symbol); input.flags.set(index, wrong); reject(input); }
                var flags = new Input(symbol); flags.flags.remove(index); reject(flags);
                var arguments = new Input(symbol); arguments.arguments.remove(index); reject(arguments);
                var declared = new Input(symbol); declared.declared().remove(index); reject(declared);
            }
            var flags = new Input(symbol); flags.flags.add(false); reject(flags);
            var arguments = new Input(symbol); arguments.arguments.add(scalar(null)); reject(arguments);
            var declared = new Input(symbol); declared.declared().add(scalar(null)); reject(declared);
        }
    }
    @Test public void allThreeResultProofsPreserveScalarVersusTupleShape() throws Exception {
        for (var symbol : symbols.keySet()) for (int site = 0; site <= 2; site++) {
            for (var mutation : List.of(new Mutation("kind", "closure"), new Mutation("primReps", List.of()), new Mutation("evaluated", 0L), new Mutation("evaluated", null),
                    new Mutation("aggregate", "unboxed-sum"), new Mutation("components", List.of()), new Mutation("extra", true))) {
                var input = new Input(symbol); input.resultAt(site).put(mutation.key(), mutation.value()); reject(input);
            }
            for (var key : new Input(symbol).resultAt(site).keySet()) { var input = new Input(symbol); input.resultAt(site).remove(key); reject(input); }
            var absent = new Input(symbol); absent.replaceResult(site, null); reject(absent);
            var list = new Input(symbol); list.replaceResult(site, List.of(list.resultAt(site))); reject(list);
            if (symbol.equals("getStackInfoTableAddrzh")) {
                var input = new Input(symbol); var field = scalar("AddrRep", true);
                input.replaceResult(site, Map.of("kind", "unknown", "primReps", List.of("AddrRep"), "evaluated", false, "aggregate", "unboxed-tuple", "components", List.of(field))); reject(input);
            }
            var evaluated = new Input(symbol); evaluated.resultAt(0).put("evaluated", true); reject(evaluated);
        }
    }
    private List<Map<String, Object>> fields(Input input, int site) { return (List<Map<String, Object>>) input.resultAt(site).get("components"); }
    @Test public void tupleFieldsAreOrderedExactEvaluatedAndKeepZeroWidthState() throws Exception {
        for (var symbol : symbols.keySet()) if (symbols.get(symbol).getTupleResult()) for (int site = 0; site <= 2; site++) {
            for (int index = 0; index < symbols.get(symbol).getResults().size(); index++) {
                var wrongPrimitive = "IntRep".equals(symbols.get(symbol).getResults().get(index)) ? "WordRep" : "IntRep";
                for (var mutation : List.of(new Mutation("evaluated", false), new Mutation("evaluated", 1L), new Mutation("primReps", List.of(wrongPrimitive)),
                        new Mutation("kind", "unknown"), new Mutation("components", List.of()), new Mutation("vector", null))) {
                    var input = new Input(symbol); fields(input, site).get(index).put(mutation.key(), mutation.value()); reject(input);
                }
                var input = new Input(symbol); fields(input, site).remove(index); reject(input);
            }
            var extra = new Input(symbol); fields(extra, site).add(scalar("AddrRep", true)); reject(extra);
            var scalar = new Input(symbol); scalar.replaceResult(site, scalar("AddrRep")); reject(scalar);
            var reps = new Input(symbol); reps.resultAt(site).put("primReps", List.of("AddrRep")); reject(reps);
        }
        for (int site = 0; site <= 2; site++) { var input = new Input("lookupIPE"); Collections.reverse((List<?>) input.resultAt(site).get("components")); reject(input); }
        for (var rep : List.of("WordRep", "Word32Rep", "Int8Rep", "IntRep", "AddrRep")) for (int site = 0; site <= 2; site++) {
            var input = new Input("lookupIPE"); input.resultAt(site).put("primReps", List.of(rep)); fields(input, site).set(1, scalar(rep, true)); reject(input);
        }
    }
    @Test public void malformedRecognizedHeadsFailBeforeAnyGenericIdCast() throws Exception {
        for (var symbol : symbols.keySet()) {
            var originalHead = (List<Object>) new Input(symbol).application.get(1);
            assertThrows(RuntimeFault.class, () -> CoreStackInfoForeign.validateHead(originalHead, true));
            var extra = new ArrayList<>(originalHead); extra.add("extra");
            for (var wrong : Arrays.asList(null, 7L, List.of(), List.of("var"), List.of("prim", "fake"), extra)) {
                var input = new Input(symbol); input.application.set(1, wrong);
                assertThrows(RuntimeFault.class, () -> CoreStackInfoForeign.validateHeads(Map.of("body", List.of(input.application))));
            }
            for (var id : Arrays.asList(null, "", 7L, true, List.of())) {
                var input = new Input(symbol); ((List<Object>) input.application.get(1)).set(1, id);
                assertThrows(RuntimeFault.class, () -> CoreStackInfoForeign.validateHeads(input.application));
            }
            for (var mutation : List.of(new Mutation("kind", "object"), new Mutation("primReps", List.of("BoxedRep (Just Unlifted)")), new Mutation("evaluated", false),
                    new Mutation("evaluated", 1L), new Mutation("aggregate", "unboxed-tuple"), new Mutation("extra", false))) {
                var input = new Input(symbol); var head = (List<Object>) input.application.get(1);
                ((Map<String, Object>) ((Map<String, Object>) head.get(2)).get("rep")).put(mutation.key(), mutation.value());
                assertThrows(RuntimeFault.class, () -> CoreStackInfoForeign.validateHeads(input.application));
            }
        }
    }
}
