// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import thc.CoreCbdFixtures;
import thc.CoreModules;
import static thc.runtime.ScalarValueTestSupport.*;

/** Native GHC supplies mantissa/exponent results for IEEE boundaries and public exponent.
 * Consumes post Core, original Integer Core and oracle.tsv; produces no files.
 * Compile each precision once per backend; aliases and pre/post exporter matrices add no arithmetic coverage. */
class FloatDecodeTest {
    @Test void ieeeBoundariesMatchNativeGhc() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"), "build/float-decode");
        var module = CoreModules.merge(List.of(CoreCbdFixtures.read(root.resolve("post-core/FloatDecodeAudit.cbd")),
            CoreCbdFixtures.read(root.resolve("post-core/FloatDecode.cbd")),
            CoreCbdFixtures.read(root.resolve("original/core/GHC.Internal.Bignum.Integer.cbd"))));
        var rows = Files.readAllLines(root.resolve("oracle.tsv")).stream().map(line -> line.split("\t")).toList();
        for (boolean single : new boolean[]{true, false}) {
            int fraction = single ? 23 : 52;
            long sign = 1L << (single ? 31 : 63), unit = single ? 0x3f800000L : 0x3ff0000000000000L;
            long infinity = single ? 0x7f800000L : 0x7ff0000000000000L;
            var inputs = List.of(0L, sign, 1L, sign | 1, (1L << fraction) - 1, 1L << fraction,
                unit, unit | sign, unit + (1L << (fraction - 1)), infinity - 1, infinity, infinity + 1);
            for (boolean direct : new boolean[]{true, false}) {
                var name = (single ? "float" : "double") + (direct ? "Direct" : "ExampleExponent");
                var cases = new LinkedHashMap<List<Object>, Long>();
                for (long bits : inputs) {
                    var row = rows.stream().filter(r -> r[0].equals(name) && Long.parseLong(r[1]) == bits).findFirst().orElseThrow();
                    for (int field = 0; field < (direct ? 2 : 1); field++)
                        cases.put(direct ? List.of(bits, (long) field) : List.of(bits), Long.parseLong(row[field + 2]));
                }
                nativeValues(module, "main:" + (direct ? "FloatDecodeAudit." : "FloatDecode.") + name,
                    cases, direct, LongUnaryOperator.identity());
            }
        }
    }
}
