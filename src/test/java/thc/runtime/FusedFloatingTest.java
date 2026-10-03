// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import thc.CoreCbdFixtures;
import static thc.runtime.ScalarValueTestSupport.*;

/** Native GHC is the oracle for single rounding, cancellation, subnormal and nonfinite cases.
 * Consumes post FloatingAudit.cbd and oracle.tsv; produces no files. Compile one fused
 * operation per precision; exercise all four signs without a pre/post/inlining matrix. */
class FusedFloatingTest {
    @Test void fusedBoundariesMatchNativeGhc() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"), "build/fused-floating");
        var module = CoreCbdFixtures.read(root.resolve("post-core/FloatingAudit.cbd"));
        var rows = Files.readAllLines(root.resolve("oracle.tsv")).stream().map(line -> line.split("\t")).toList();
        for (boolean single : new boolean[]{true, false}) {
            int fraction = single ? 23 : 52;
            long sign = 1L << (single ? 31 : 63), unit = single ? 0x3f800000L : 0x3ff0000000000000L;
            long infinity = single ? 0x7f800000L : 0x7ff0000000000000L;
            var inputs = List.of(List.of(0L, 0L, 0L), List.of(sign, unit, 0L), List.of(unit, unit, sign),
                List.of(unit + 1, unit - 2, unit ^ sign), List.of(1L, unit - (1L << fraction), 1L),
                List.of(infinity - 1, unit + (1L << fraction), (infinity - 1) ^ sign),
                List.of(1L << fraction, unit - 1, (1L << fraction) ^ sign),
                List.of(infinity, unit, 0L), List.of(infinity, 0L, 0L), List.of(infinity + 1, unit, 0L));
            LongUnaryOperator normalize = bits -> (bits & ~sign) > infinity ? infinity + 1 : bits;
            for (var operation : List.of("Add", "Sub", "NegAdd", "NegSub")) {
                var name = "fused" + (single ? "Float" : "Double") + operation;
                var cases = new LinkedHashMap<List<Object>, Long>();
                for (var input : inputs) {
                    var row = rows.stream().filter(r -> r[0].equals(name) &&
                        Long.parseUnsignedLong(r[1]) == input.get(0) && Long.parseUnsignedLong(r[2]) == input.get(1) &&
                        Long.parseUnsignedLong(r[3]) == input.get(2)).findFirst().orElseThrow();
                    cases.put(input.stream().map(bits -> (Object) new BigInteger(Long.toUnsignedString(bits))).toList(), Long.parseUnsignedLong(row[4]));
                }
                nativeValues(module, "main:FloatingAudit." + name, cases, operation.equals("Add"), normalize);
            }
        }
    }
}
