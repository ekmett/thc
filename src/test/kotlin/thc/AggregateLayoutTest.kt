// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Native metadata groundwork must not turn into accidental aggregate execution. */
class AggregateLayoutTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val boundaries = mapOf(
        // The inner binary sum has a known layout; its enclosing tuple-of-sum remains unsupported.
        "nestedIdentity" to "unboxed-tuple", "lazyIdentity" to "unboxed-tuple",
        "alternativesIdentity" to "unboxed-sum", "polymorphicTuple" to "unboxed-tuple",
        "polymorphicSum" to "unboxed-sum", "polymorphicNested" to "unboxed-tuple",
        "levityPolymorphic" to "unboxed-tuple", "tupleAliasIdentity" to "unboxed-tuple",
        "sumAliasIdentity" to "unboxed-sum", "nestedAliasIdentity" to "unboxed-tuple",
        "emptyAliasIdentity" to "unboxed-tuple", "abstractTupleRep" to "unboxed-tuple",
        "abstractFixedTupleIdentity" to "unboxed-tuple", "abstractEmptyIdentity" to "unboxed-tuple",
        "abstractSumIdentity" to "unboxed-sum", "familyTupleIdentity" to "unboxed-tuple",
        "abstractSumRep" to "unboxed-sum", "abstractComponentIdentity" to "unboxed-tuple")

    @Test fun strictLoadingRejectsRecursivePolymorphicAndNewtypeAggregateBoundaries() {
        for (stage in listOf("pre", "post")) {
            val module = Json.parse(File(root,
                "build/aggregate-layout/$stage-core/AggregateLayoutAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                for ((entry, kind) in boundaries) {
                    val request = Json.stringify(mapOf("modules" to listOf(module), "entry" to entry,
                        "backend" to backend, "diagnosticUnsupported" to false))
                    val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request) }
                    assertTrue(error.message.orEmpty().contains("Unsupported Core aggregate representation: $kind"),
                        "$stage/$backend/$entry: ${error.message}")
                }
                // Recursive scalar newtypes keep ordinary object evidence; unwrapping terminates,
                // and the unrelated unsupported aggregate definitions remain unreachable.
                context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                    "entry" to "recursiveNewtypeIdentity", "backend" to backend,
                    "diagnosticUnsupported" to false)))
                for (entry in listOf("stateAliasIdentity", "proxyIdentity")) {
                    context.eval("thc", Json.stringify(mapOf("modules" to listOf(module),
                        "entry" to entry, "backend" to backend, "diagnosticUnsupported" to false)))
                }
            }
        }
    }

    @Test fun boxedTuplesAndUnliftedBoxedProductsRemainObjectsWithLazyPayloads() {
        for (stage in listOf("pre", "post")) {
            val module = Json.parse(File(root,
                "build/aggregate-layout/$stage-core/AggregateLayoutAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                fun request(entry: String) = Json.stringify(mapOf("modules" to listOf(module),
                    "entry" to entry, "backend" to backend, "diagnosticUnsupported" to false))
                for (entry in listOf("boxedPairIdentity", "boxedUnitIdentity", "boxedSoloIdentity",
                        "unliftedProductIdentity")) {
                    context.eval("thc", request(entry))
                }
                for (entry in listOf("boxedLazyUse", "unliftedLazyUse")) {
                    val observer = context.eval("thc", request(entry))
                    for (input in listOf(Long.MIN_VALUE, -4097L, 0L, 4097L, Long.MAX_VALUE)) {
                        assertEquals(input, observer.execute(input).asLong(), "$stage/$backend/$entry")
                    }
                }
            }
        }
    }
}
