// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Genuine sum signatures and interpreted results, independent of producer check reports. */
class SumLayoutMetadataTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private static final List<String> OBSERVERS = list("sumCase", "directCase", "nestedCase", "lazyCase", "zeroCase", "unitCase",
        "boxedKindsCase", "floatDoubleCase", "narrowWideCase", "threeWayCase", "boxedSumLiftedUse", "boxedSumUnliftedUse", "boxedNestedUse");
    private List<?> expression(Map<?, ?> module, String name) {
        return (List<?>) objects(module.get("bindings")).stream().filter(binding -> ("main:SumLayoutAudit." + name).equals(binding.get("id")))
            .findFirst().orElseThrow().get("expr");
    }
    private Map<String, Object> result(Map<?, ?> module, String name) { return object(object(expression(module, name).get(3)).get("resultRep")); }
    @Test void genuineSumLayoutsEnforceResultCapabilityBoundariesOnBothBackends() throws Exception {
        var rows = new LinkedHashMap<String, Map<Long, Long>>();
        for (String name : OBSERVERS) rows.put(name, new LinkedHashMap<>());
        for (String row : Files.readAllLines(root.resolve("build/sum-layout/oracle.tsv"))) {
            var columns = row.split("\t", -1); assertEquals(3, columns.length, row);
            assertTrue(rows.containsKey(columns[0]), "Unknown native observer: " + row);
            assertNull(rows.get(columns[0]).put(Long.parseLong(columns[1]), Long.parseLong(columns[2])), "Duplicate native input: " + row);
        }
        for (var entry : rows.entrySet()) {
            var inputs = entry.getValue().keySet();
            assertTrue(inputs.contains(0L) && inputs.stream().anyMatch(x -> x < Integer.MIN_VALUE)
                && inputs.stream().anyMatch(x -> x > 0xffffffffL), "Missing sign/zero/wide native inputs: " + entry.getKey());
        }
        for (String stage : list("pre", "post")) {
            var artifact = root.resolve("build/sum-layout/" + stage + "-core/SumLayoutAudit.cbd");
            var module = CoreCbdFixtures.read(artifact);
            assertEquals("9.14.1", module.get("ghc"));
            assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", module.get("boundary"));
            var alias = expression(module, "aliasIdentity");
            // Identical tag-only storage must not merge scalar State with an empty tuple.
            for (var proof : list(result(module, "zeroSum"), result(module, "aliasIdentity"), object(objects(alias.get(1)).getFirst().get("rep")))) {
                assertEquals("unboxed-sum", proof.get("aggregate"));
                assertEquals(list(list(), list()), proof.get("alternativeSlots"));
                var alternatives = objects(proof.get("alternatives"));
                var state = alternatives.getFirst(); var empty = alternatives.get(1);
                assertEquals("void", state.get("kind")); assertEquals(list(), state.get("primReps")); assertFalse(state.containsKey("aggregate"));
                assertEquals("unboxed-tuple", empty.get("aggregate")); assertEquals(list(), empty.get("components"));
            }
            // Concrete callback results cannot certify generic pointer levity or WHNF.
            for (var proof : list(result(module, "boxedSumThrough"), object(objects(result(module, "boxedNestedThrough").get("components")).getFirst()))) {
                assertEquals("unboxed-sum", proof.get("aggregate")); assertNull(proof.get("primReps")); assertNull(proof.get("alternativeSlots"));
                var pointer = objects(proof.get("alternatives")).getFirst();
                assertEquals("object", pointer.get("kind")); assertEquals(list("BoxedRep Nothing"), pointer.get("primReps")); assertEquals(false, pointer.get("evaluated"));
            }
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                for (String name : list("aliasIdentity", "addressResult", "vectorResult", "levityPolymorphic"))
                    context.eval("thc", CoreModules.request(list(artifact.toString()), "main:SumLayoutAudit." + name, true, false, backend));
                for (String name : list("runtimePolymorphic", "abstractSumIdentity", "abstractRuntimeSum", "abstractAlternative")) {
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc",
                        CoreModules.request(list(artifact.toString()), "main:SumLayoutAudit." + name, true, false, backend)));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported Core aggregate representation:"), stage + "/" + backend + "/" + name + ": " + error.getMessage());
                }
                for (var observer : rows.entrySet()) {
                    var target = context.eval("thc", CoreModules.request(list(artifact.toString()), "main:SumLayoutAudit." + observer.getKey(), true, false, backend));
                    for (var row : observer.getValue().entrySet()) assertEquals(row.getValue().longValue(), target.execute(row.getKey()).asLong(),
                        stage + "/" + backend + "/" + observer.getKey() + "/" + row.getKey());
                }
            }
        }
    }
}
