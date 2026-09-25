// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Descriptor controls only; genuine imported declarations have a separate oracle. */
class CoreGmpForeignTest {
    @Test fun loadTimeUnliftedObjectPredicateRetainsExactNullSentinelRule() {
        for (kind in CoreKind.entries) for (evaluated in listOf(false, true))
            for (primitive in listOf(null, "BoxedRep (Just Lifted)", GMP_ARRAY_REP, "IntRep")) {
                val proof = CoreRepresentation(kind, evaluated, true, listOfNotNull(primitive))
                assertEquals(kind == CoreKind.OBJECT && evaluated && primitive == GMP_ARRAY_REP,
                    proof.isEvaluatedUnliftedObject)
                assertFalse(proof.copy(evaluated = false).isEvaluatedUnliftedObject)
            }
    }
    private fun scalar(primitive: String?, evaluated: Boolean) = mapOf("kind" to when (primitive) {
        null -> "void"; GMP_ARRAY_REP -> "object"; else -> "long"
    }, "primReps" to listOfNotNull(primitive), "evaluated" to evaluated)
    private fun result(operation: GmpForeignOp, evaluated: Boolean): Map<String, Any?> = mapOf(
        "kind" to "unknown", "primReps" to listOfNotNull(operation.result), "evaluated" to evaluated,
        "aggregate" to "unboxed-tuple", "components" to (listOf(scalar(null, true)) +
            if (operation.result == null) emptyList() else listOf(scalar(operation.result, true))))
    private fun descriptor(operation: GmpForeignOp): Map<String, Any?> = mapOf(
        "schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to operation.symbol,
            "unit" to "ghc-internal", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
        "arity" to operation.arguments.size.toLong(), "suppliedArity" to operation.arguments.size.toLong(),
        "argumentReps" to operation.arguments.map { scalar(it, false) }, "resultRep" to result(operation, false))
    @Test fun allElevenExactContractsIncludeOriginalStateAndLogicalTuple() {
        assertEquals(11, GmpForeignOp.entries.size)
        for (operation in GmpForeignOp.entries) {
            val proof = result(operation, true)
            assertEquals(operation, CoreGmpForeign.validate(mapOf("rep" to proof, "foreignCall" to descriptor(operation)),
                operation.arguments.map { scalar(it, true) }, operation.arguments.map { false }, proof))
            assertNull(operation.arguments.last())
            assertTrue(operation.objectIndices.size <= 4)
            assertTrue(operation.longIndices.size <= 3)
        }
        assertNull(CoreGmpForeign.validate(emptyMap<String, Any>(), emptyList<Any>(), emptyList<Any>(), null))
    }
    @Test fun malformedDescriptorsAndOccurrenceProofsCannotChangeTheAbi() {
        for (operation in GmpForeignOp.entries) {
            val good = descriptor(operation)
            val arguments = operation.arguments.map { scalar(it, true) }
            val flags = operation.arguments.map { false }
            val output = result(operation, true)
            fun check(call: Map<String, Any?> = good, args: List<*> = arguments,
                cbv: List<*> = flags, ret: Any? = output) {
                assertThrows(RuntimeFault::class.java) {
                    CoreGmpForeign.validate(mapOf("rep" to ret, "foreignCall" to call), args, cbv, ret)
                }
            }
            for ((key, value) in listOf("schema" to 1.0, "convention" to "capi", "safety" to "safe",
                "arity" to (operation.arguments.size - 1), "suppliedArity" to 1.0,
                "argumentReps" to operation.arguments.map { scalar(it, true) },
                "resultRep" to result(operation, true))) check(good + (key to value))
            val target = good["target"] as Map<*, *>
            for ((key, value) in listOf("unit" to "base", "kind" to "dynamic", "isFunction" to false))
                check(good + ("target" to (target + (key to value))))
            check(good + ("extra" to true))
            check(good + ("resultRep" to scalar(operation.result, false)))
            for (index in operation.arguments.indices) {
                check(args = arguments.toMutableList().also { it[index] = scalar("AddrRep", true) })
                check(cbv = flags.toMutableList().also { it[index] = true })
            }
            check(ret = output + ("components" to emptyList<Any>()))
            check(ret = output + ("evaluated" to "true"))
        }
    }
    @Test fun headsAndStoredOperandsCannotForgeKnownRepresentations() {
        val head = listOf("var", "genuine-fcall-unique", mapOf("rep" to mapOf("kind" to "closure",
            "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)))
        CoreGmpForeign.validateHead(head, false)
        assertThrows(RuntimeFault::class.java) { CoreGmpForeign.validateHead(head, true) }
        assertThrows(RuntimeFault::class.java) { CoreGmpForeign.validateHead(listOf("var", ""), false) }
        for (operation in GmpForeignOp.entries) for (index in operation.arguments.indices) {
            val primitive = operation.arguments[index]
            val kind = when (primitive) { null -> CoreKind.VOID; GMP_ARRAY_REP -> CoreKind.OBJECT; else -> CoreKind.LONG }
            val proof = CoreRepresentation(kind, true, true, listOfNotNull(primitive))
            CoreGmpForeign.validateOperand(operation, index, proof, proof)
            val incorrect = proof.copy(kind = CoreKind.ADDRESS, primReps = listOf("AddrRep"))
            assertThrows(RuntimeFault::class.java) { CoreGmpForeign.validateOperand(operation, index, incorrect, proof) }
            assertThrows(RuntimeFault::class.java) { CoreGmpForeign.validateOperand(operation, index, proof, incorrect) }
        }
    }
}
