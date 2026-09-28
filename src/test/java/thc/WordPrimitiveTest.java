// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Word primitives preserve all 64 bits; malformed applications remain load errors. */
public final class WordPrimitiveTest {
    private String request(String backend, String name, int arity, int supplied, boolean diagnostic) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (int i = 0; i < arity; i++) parameters.add(Map.of("id", "x" + i, "name", "x" + i,
            "type", "Word#", "lifted", false, "coercion", false));
        List<List<String>> operands = new ArrayList<>();
        for (int i = 0; i < supplied; i++) operands.add(i < arity ? List.of("var", "x" + i) : List.of("lit", "word", "0"));
        var body = List.of("app", List.of("prim", name), operands, Collections.nCopies(supplied, false));
        var entry = Map.of("id", "entry", "name", "entry", "type", "Synthetic", "lifted", true,
            "arity", arity, "expr", List.of("lam", parameters, body));
        return Json.INSTANCE.stringify(Map.of("entry", "entry", "backend", backend, "diagnosticUnsupported", diagnostic,
            "modules", List.of(Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.WordPrimitives",
                "constructors", List.of(), "bindings", List.of(entry)))));
    }

    @Test void bitCountsCoverEveryPositionNeighborsAndAlternatingPatterns() {
        long alternating = 0x5555_5555_5555_5555L;
        Set<Long> inputs = new LinkedHashSet<>();
        for (int bit = 0; bit < 64; bit++) {
            long single = 1L << bit;
            inputs.add(single - 1);
            inputs.add(single);
            inputs.add(single + 1);
        }
        inputs.addAll(List.of(0L, -1L, alternating, ~alternating));
        for (String backend : List.of("ast", "bytecode")) try (Context context = Main.executionContext(false)) {
            for (String name : List.of("clz#", "ctz#", "popCnt#")) {
                var function = context.eval("thc", request(backend, name, 1, 1, false));
                for (long input : inputs) {
                    // Enumerate set positions independently of the runtime's Long intrinsics.
                    List<Integer> setBits = new ArrayList<>();
                    for (int bit = 0; bit < 64; bit++) if (((input >>> bit) & 1L) != 0) setBits.add(bit);
                    long expected = switch (name) {
                        case "clz#" -> setBits.isEmpty() ? 64 : 63 - setBits.getLast();
                        case "ctz#" -> setBits.isEmpty() ? 64 : setBits.getFirst();
                        default -> setBits.size();
                    };
                    assertEquals(expected, function.execute(input).asLong(), backend + " " + name + "(" + input + ")");
                }
            }
        }
    }

    @Test void comparisonsFollowUnsignedOrderIncludingEquality() {
        // This literal order is the unsigned order, independent of the runtime comparator.
        long alternating = 0x5555_5555_5555_5555L;
        List<Long> ordered = List.of(0L, 1L, alternating, Long.MAX_VALUE, Long.MIN_VALUE, ~alternating, -1L);
        for (String backend : List.of("ast", "bytecode")) try (Context context = Main.executionContext(false)) {
            for (String name : List.of("ltWord#", "leWord#")) {
                var function = context.eval("thc", request(backend, name, 2, 2, false));
                for (int leftIndex = 0; leftIndex < ordered.size(); leftIndex++) {
                    long left = ordered.get(leftIndex);
                    for (int rightIndex = 0; rightIndex < ordered.size(); rightIndex++) {
                        long right = ordered.get(rightIndex);
                        long expected = leftIndex < rightIndex || name.equals("leWord#") && leftIndex == rightIndex ? 1 : 0;
                        assertEquals(expected, function.execute(left, right).asLong(), backend + " " + name + "(" + left + ", " + right + ")");
                    }
                }
            }
        }
    }

    @Test void wrongAritiesAreRejectedAtLoadEvenInDiagnosticMode() {
        record Primitive(String name, int arity) {}
        for (String backend : List.of("ast", "bytecode")) for (boolean diagnostic : new boolean[] {false, true}) {
            try (Context context = Main.executionContext(false)) {
                for (var primitive : List.of(new Primitive("clz#", 1), new Primitive("ctz#", 1), new Primitive("popCnt#", 1),
                        new Primitive("ltWord#", 2), new Primitive("leWord#", 2))) {
                    for (int supplied : new int[] {primitive.arity() - 1, primitive.arity() + 1}) {
                        var failure = assertThrows(PolyglotException.class, () -> context.eval("thc",
                            request(backend, primitive.name(), primitive.arity(), supplied, diagnostic)));
                        assertTrue(failure.getMessage() != null && failure.getMessage().contains("Primitive arity mismatch: " + primitive.name()),
                            backend + " diagnostic=" + diagnostic + " " + primitive.name() + "/" + supplied + ": " + failure.getMessage());
                    }
                }
            }
        }
    }
}
