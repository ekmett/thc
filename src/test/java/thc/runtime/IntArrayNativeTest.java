// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import thc.CoreCbdFixtures;
import thc.CoreModules;
import thc.Json;
import static thc.runtime.ScalarValueTestSupport.*;

/** Public unboxed-array operations and word/byte aliasing must match native GHC at
 * signed/overflow boundaries. Consumes post modules listed by manifest.json and oracle.tsv.
 * Bounds, lifetime and malformed Core belong to the shared memory/loader tests. No outputs. */
class IntArrayNativeTest {
    @Test void publicArraysAndByteAliasingMatchNativeGhc() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        var manifest = object(Json.parse(Files.readString(root.resolve("build/int-arrays/manifest.json"))));
        var paths = expression(object(manifest.get("stages")).get("post"));
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths) modules.add(CoreCbdFixtures.read(root.resolve((String) path)));
        var module = CoreModules.merge(modules);
        var rows = Files.readAllLines(root.resolve("build/int-arrays/oracle.tsv")).stream().map(line -> line.split("\t")).toList();
        for (var name : List.of("unboxedAccum", "unboxedST", "orderedInts", "aliasIntBytes")) {
            var cases = new LinkedHashMap<List<Object>, Long>();
            for (long input : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x55aa55aa55aa55aaL}) {
                var row = rows.stream().filter(r -> r[0].equals(name) && Long.parseLong(r[1]) == input).findFirst().orElseThrow();
                cases.put(List.of(input), Long.parseLong(row[2]));
            }
            nativeValues(module, "main:" + (name.startsWith("unboxed") ? "UnboxedArrays." : "IntArrayAudit.") + name,
                cases, name.equals("unboxedST"), LongUnaryOperator.identity());
        }
    }
}
