// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Native metadata groundwork must not turn into accidental aggregate execution. */
class AggregateLayoutTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Map<String, Object> boundaries = map(
        "nestedIdentity", "unboxed-tuple", "lazyIdentity", "unboxed-tuple",
        "alternativesIdentity", "unboxed-sum", "polymorphicTuple", "unboxed-tuple",
        "polymorphicSum", "unboxed-sum", "polymorphicNested", "unboxed-tuple",
        "levityPolymorphic", "unboxed-tuple", "tupleAliasIdentity", "unboxed-tuple",
        "sumAliasIdentity", "unboxed-sum", "nestedAliasIdentity", "unboxed-tuple",
        "emptyAliasIdentity", "unboxed-tuple", "abstractTupleRep", "unboxed-tuple",
        "abstractFixedTupleIdentity", "unboxed-tuple", "abstractEmptyIdentity", "unboxed-tuple",
        "abstractSumIdentity", "unboxed-sum", "familyTupleIdentity", "unboxed-tuple",
        "abstractSumRep", "unboxed-sum", "abstractComponentIdentity", "unboxed-tuple");
    private Object module(String stage) throws Exception { return Json.INSTANCE.parse(Files.readString(root.resolve("build/aggregate-layout/" + stage + "-core/AggregateLayoutAudit.json"))); }
    private String request(Object module, String entry, String backend) {
        return Json.INSTANCE.stringify(map("modules", list(module), "entry", entry, "backend", backend, "diagnosticUnsupported", false));
    }
    @Test void strictLoadingRejectsRecursivePolymorphicAndNewtypeAggregateBoundaries() throws Exception {
        for (String stage : list("pre", "post")) {
            var module = module(stage);
            for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
                for (var boundary : boundaries.entrySet()) {
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(module, boundary.getKey(), backend)));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported Core aggregate representation: " + boundary.getValue()),
                        stage + "/" + backend + "/" + boundary.getKey() + ": " + error.getMessage());
                }
                // Recursive scalar newtypes retain object evidence and terminate unwrapping.
                context.eval("thc", request(module, "recursiveNewtypeIdentity", backend));
                for (String entry : list("stateAliasIdentity", "proxyIdentity")) context.eval("thc", request(module, entry, backend));
            }
        }
    }
    @Test void boxedTuplesAndUnliftedBoxedProductsRemainObjectsWithLazyPayloads() throws Exception {
        for (String stage : list("pre", "post")) {
            var module = module(stage);
            for (String backend : list("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
                for (String entry : list("boxedPairIdentity", "boxedUnitIdentity", "boxedSoloIdentity", "unliftedProductIdentity"))
                    context.eval("thc", request(module, entry, backend));
                for (String entry : list("boxedLazyUse", "unliftedLazyUse")) {
                    var observer = context.eval("thc", request(module, entry, backend));
                    for (long input : new long[]{Long.MIN_VALUE, -4097L, 0L, 4097L, Long.MAX_VALUE})
                        assertEquals(input, observer.execute(input).asLong(), stage + "/" + backend + "/" + entry);
                }
            }
        }
    }
}
