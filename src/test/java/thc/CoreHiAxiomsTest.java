// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Differential endpoints come from GHC's selected callbacks, not this evaluator. */
class CoreHiAxiomsTest {
    private static CoreHiTypes engine() {
        CoreHiTypes[] holder = new CoreHiTypes[1];
        var declarations = new HashMap<CoreHiReader.ExternalName, CoreHiTypes.Declaration>();
        holder[0] = new CoreHiTypes(name -> {
            var existing = declarations.get(name);
            if (existing != null) return existing;
            Map<?, ?> raw = CoreHiNames.typeDeclaration(name);
            var declaration = raw == null ? holder[0].tupleDeclaration(name) : holder[0].declaration(raw);
            if (declaration != null) declarations.put(name, declaration);
            return declaration;
        }, name -> null);
        return holder[0];
    }
    private static Map<?, ?> oracle() throws IOException {
        try (var input = CoreHiAxiomsTest.class.getResourceAsStream("/thc/native-hi-axiom-oracle.json")) {
            assertNotNull(input, "Compiler-generated axiom oracle missing from test resources");
            return (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test void everySelectedRuleMatchesCompilerEndpointsAndGuards() throws Exception {
        var types = engine();
        var oracle = oracle();
        assertEquals("9.14.1", oracle.get("ghc"));
        var seen = new HashSet<String>();
        var successful = new HashSet<String>();
        int rejected = 0;
        for (Object raw : (List<?>) oracle.get("cases")) {
            var row = (Map<?, ?>) raw;
            String rule = (String) row.get("rule");
            seen.add(rule);
            var scope = new HashMap<String, CoreHiTypes.Variable>();
            for (Object binder : (List<?>) row.get("binders")) {
                var variable = types.binder(binder, scope);
                scope.put(variable.spelling(), variable);
            }
            var arguments = new ArrayList<List<CoreHiTypes.Ty>>();
            for (Object pair : (List<?>) row.get("arguments"))
                arguments.add(((List<?>) pair).stream().map(value -> types.read(value, scope)).toList());
            if (row.get("result") == null) {
                assertThrows(IllegalArgumentException.class, () -> CoreHiAxioms.endpoints(types, rule, arguments), rule);
                rejected++;
            } else {
                var expected = ((List<?>) row.get("result")).stream().map(value -> types.read(value, scope)).toList();
                List<CoreHiTypes.Ty> actual;
                try { actual = CoreHiAxioms.endpoints(types, rule, arguments); }
                catch (RuntimeException failure) { throw new AssertionError(rule + " " + Json.stringify(row), failure); }
                assertTrue(types.equal(expected.get(0), actual.get(0)), () -> rule + " left endpoint: " + Json.stringify(row));
                assertTrue(types.equal(expected.get(1), actual.get(1)), () -> rule + " right endpoint: " + Json.stringify(row));
                successful.add(rule);
                assertEquals(1, CoreHiAxioms.role(rule));
            }
        }
        var compilerNames = new HashSet<String>();
        for (Object raw : CoreHiNames.wiredAxiomRules()) compilerNames.add((String) ((Map<?, ?>) raw).get("name"));
        assertEquals(compilerNames, seen, "Every compiler-selected rule has differential cases");
        assertEquals(compilerNames, successful, "Every compiler-selected rule has a valid differential case");
        assertTrue(rejected > 0, "Compiler-rejected preconditions are qualified too");
    }
    @Test void literalWidthsAndEndpointOrientationRemainGeneral() {
        var types = engine();
        BigInteger huge = BigInteger.ONE.shiftLeft(257).add(BigInteger.valueOf(11));
        var left = new CoreHiTypes.Lit(1, huge);
        var right = new CoreHiTypes.Lit(1, BigInteger.valueOf(3));
        var argument = List.<CoreHiTypes.Ty>of(left, right);
        var result = CoreHiAxioms.endpoints(types, "Add0R", List.of(argument));
        assertEquals(right, result.get(1));
        assertTrue(result.get(0) instanceof CoreHiTypes.Con);
        assertEquals(left, ((CoreHiTypes.Con) result.get(0)).arguments().getFirst().type());
        var sum = CoreHiAxioms.endpoints(types, "AddDef", List.of(List.of(left, left), List.of(right, right)));
        assertEquals(new CoreHiTypes.Lit(1, huge.add(BigInteger.valueOf(3))), sum.get(1));
        // GHC's power accepts an arbitrarily wide exponent at base one.
        var one = new CoreHiTypes.Lit(1, BigInteger.ONE);
        var powered = CoreHiAxioms.endpoints(types, "ExpDef", List.of(List.of(one, one), List.of(left, left)));
        assertEquals(one, powered.get(1));
    }
}
