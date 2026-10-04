// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class ScalarLexicalProofTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> proof(String rep) { return map("kind", "long", "primReps", list(rep), "evaluated", true); }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").build(); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Map<String, Object> module(Map<String, Object> stored, Map<String, Object> occurrence) { return module(stored, occurrence, null); }
    private Map<String, Object> module(Map<String, Object> stored, Map<String, Object> occurrence, List<Object> body) {
        var binder = map("id", "x", "name", "x", "lifted", false);
        if (stored != null) binder.put("rep", stored);
        var variable = new ArrayList<Object>(list("var", "x"));
        if (occurrence != null) variable.add(map("rep", occurrence));
        var primitive = list("app", list("prim", "plusInt8#"), list(variable, variable), list(false, false),
            false, false, map("rep", proof("Int8Rep")));
        return map("schema", 1, "ghc", "9.14.1", "module", "ScalarLexicalProof", "constructors", list(),
            "bindings", list(map("id", "entry", "name", "entry", "arity", 1, "lifted", true,
                "expr", list("lam", list(binder), body == null ? primitive : body))));
    }
    private void visit(CheckedBiConsumer<Language, String> action) throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); }
            finally { context.leave(); }
        }
    }
    @Test void tupleAndScalarArithmeticRespectIntegralCarrierWidths() throws Exception {
        visit((language, backend) -> {
            for (var rep : list("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep")) {
                var tupleModule = object(thc.CoreCbdFixtures.read(root.resolve("build/tuple-arithmetic/pre-core/TupleArithmeticAudit.cbd")));
                var reached = CoreModules.reachable(tupleModule, "main:TupleArithmeticAudit.quotRemInt", false);
                var bindings = objects(reached.get("bindings")); assertEquals(1, bindings.size());
                var lambda = expression(bindings.getFirst().get("expr"));
                objects(lambda.get(1)).getFirst().put("rep", proof(rep));
                boolean narrow = list("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep").contains(rep);
                if (narrow) {
                    assertThrows(RuntimeFault.class, () -> program(language, reached, backend), backend + "/" + rep + " cannot replace the native Long tuple input");
                    var scalar = program(language, module(proof(rep), proof("Int8Rep")), backend);
                    for (int value : new int[]{-128, -1, 0, 1, 127})
                        assertEquals((int) (byte) (value * 2), Calls.target(scalar.hostEntryTarget(1),
                            new Object[]{scalar.entryValue("entry"), new Object[]{value}}), backend + "/" + rep + "/" + value);
                } else {
                    var tuple = program(language, reached, backend);
                    long[] expected = {-2L, -3L};
                    for (int field = 0; field < expected.length; field++)
                        assertEquals(expected[field], Calls.target(tuple.hostEntryTarget(3),
                            new Object[]{tuple.entryValue("main:TupleArithmeticAudit.quotRemInt"), new Object[]{-13L, 5L, (long) field}}), backend + "/" + rep + "/" + field);
                    assertThrows(RuntimeFault.class, () -> program(language, module(proof(rep), proof("Int8Rep")), backend),
                        backend + "/" + rep + " cannot replace an Int scalar input");
                }
            }
        });
    }
    @Test void differentLexicalCarriersRemainIncompatible() throws Exception {
        visit((language, backend) -> {
            for (boolean diagnostic : new boolean[]{false, true}) assertThrows(RuntimeFault.class, () ->
                program(language, with(module(proof("Int8Rep"), map("kind", "float", "primReps", list("FloatRep"), "evaluated", true)),
                    "diagnosticUnsupported", diagnostic), backend));
        });
    }
    private record ProofPair(Map<String, Object> stored, Map<String, Object> occurrence) {}
    @Test void absentUnknownAndMatchingScalarProofsRemainCompatible() throws Exception {
        visit((language, backend) -> {
            var exact = proof("Int8Rep");
            var unknown = map("kind", "unknown", "primReps", null, "evaluated", false);
            for (var pair : list(new ProofPair(null, exact), new ProofPair(unknown, exact), new ProofPair(exact, null), new ProofPair(exact, unknown), new ProofPair(exact, exact))) {
                var program = program(language, module(pair.stored(), pair.occurrence()), backend);
                for (int x : new int[]{-128, -1, 0, 1, 127})
                    assertEquals((int) (byte) (x * 2), Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{x}}));
            }
            // The inner let is a different value, despite reusing the source identifier.
            var word = proof("WordRep");
            var binding = map("id", "x", "name", "x", "lifted", false, "rep", word,
                "expr", list("lit", "word", "18446744073709551615", map("rep", word)));
            List<Object> body = list("let", false, list(binding), list("var", "x", map("rep", word)), map("rep", word));
            var program = program(language, module(proof("IntRep"), null, body), backend);
            assertEquals(-1L, Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{1L}}));
        });
    }
}
