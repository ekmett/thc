// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import thc.CoreCbdFixtures;
import static thc.runtime.ScalarValueTestSupport.*;

/** TupleArithmeticTest owns direct carry/borrow arithmetic. This checks both result fields
 * across a guest call, using the post CBD and native call-oracle.tsv. No generated outputs. */
class WordCarryTest {
    @Test void crossCallCarryAndBorrowMatchNativeGhc() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"), "build/tuple-arithmetic");
        var module = CoreCbdFixtures.read(root.resolve("post-core/TupleArithmeticAudit.cbd"));
        var rows = Files.readAllLines(root.resolve("call-oracle.tsv")).stream().map(line -> line.split("\t")).toList();
        for (var name : List.of("addWordCall", "subWordCall")) {
            var cases = new LinkedHashMap<List<Object>, Long>();
            for (long x : new long[]{0, 1, -1, Long.MIN_VALUE}) for (long y : new long[]{0, 1, -1}) {
                var row = rows.stream().filter(r -> r[0].equals(name) && Long.parseLong(r[1]) == x && Long.parseLong(r[2]) == y).findFirst().orElseThrow();
                for (long field = 0; field < 2; field++)
                    cases.put(List.of(x, y, field), Long.parseLong(row[(int) field + 3]));
            }
            nativeValues(module, "main:TupleArithmeticAudit." + name, cases, true, LongUnaryOperator.identity());
        }
    }
}
