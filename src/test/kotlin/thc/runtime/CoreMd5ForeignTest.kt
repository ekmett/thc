// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Synthetic descriptor tests, not renamed main-unit exports or Fingerprint execution.
 * The actual installed-interface unit evidence is documented in
 * compiler/test-fixtures/md5-foreign-unit-evidence.md. */
class CoreMd5ForeignTest {
    private fun scalar(primitive: String? = null, evaluated: Boolean = false): MutableMap<String, Any?> = mutableMapOf(
        "kind" to when (primitive) { "AddrRep" -> "address"; null -> "void"; else -> "long" },
        "primReps" to (if (primitive == null) emptyList<String>() else listOf(primitive)), "evaluated" to evaluated)
    private fun result(): MutableMap<String, Any?> = mutableMapOf("kind" to "unknown", "primReps" to emptyList<String>(),
        "evaluated" to false, "aggregate" to "unboxed-tuple", "components" to listOf(scalar(evaluated = true)))
    private data class Input(val metadata: MutableMap<String, Any?>, val arguments: MutableList<Any?>,
                             val flags: MutableList<Any?>, val result: MutableMap<String, Any?>) {
        val descriptor get() = metadata["foreignCall"] as MutableMap<String, Any?>
        val target get() = descriptor["target"] as MutableMap<String, Any?>
        val declared get() = descriptor["argumentReps"] as MutableList<MutableMap<String, Any?>>
        fun validate() = CoreMd5Foreign.validate(metadata, arguments, flags, result)
    }
    private fun fixture(operation: Md5ForeignOp = Md5ForeignOp.INIT): Input {
        // Independently written schema, rather than deriving expected shapes from the validator.
        val primitives = when (operation) {
            Md5ForeignOp.INIT -> listOf("AddrRep", null)
            Md5ForeignOp.UPDATE -> listOf("AddrRep", "AddrRep", "Int32Rep", null)
            Md5ForeignOp.FINAL -> listOf("AddrRep", "AddrRep", null)
        }
        val descriptor = mutableMapOf<String, Any?>("schema" to 1L,
            "target" to mutableMapOf<String, Any?>("kind" to "static", "symbol" to operation.symbol,
                "unit" to "ghc-internal", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
            "arity" to primitives.size.toLong(), "suppliedArity" to primitives.size.toLong(),
            "argumentReps" to primitives.map { scalar(it) }.toMutableList(), "resultRep" to result())
        return Input(mutableMapOf("foreignCall" to descriptor, "rep" to result()),
            primitives.map<String?, Any?> { scalar(it, true) }.toMutableList(),
            MutableList<Any?>(primitives.size) { false }, result())
    }
    private fun reject(input: Input) {
        val error = assertThrows(RuntimeFault::class.java) { input.validate() }
        assertTrue(error.message!!.startsWith("Invalid MD5 foreign call: "))
    }

    @Test fun exactThreeContractsAndActualEvaluationFacts() {
        for (operation in Md5ForeignOp.entries) {
            val input = fixture(operation)
            assertEquals(operation, input.validate())
            input.arguments.forEach { (it as MutableMap<String, Any?>)["evaluated"] = false }
            (input.metadata["rep"] as MutableMap<String, Any?>)["evaluated"] = true
            input.result["evaluated"] = true
            assertEquals(operation, input.validate())
            input.descriptor["schema"] = 1
            input.descriptor["arity"] = operation.arity
            input.descriptor["suppliedArity"] = operation.arity
            assertEquals(operation, input.validate())
        }
    }

    @Test fun MissingUnknownAndDynamicTargetsRetainOrdinaryResolution() {
        val metadata = listOf(null, emptyMap<String, Any?>(), mapOf("foreignCall" to null),
            mapOf("foreignCall" to mapOf("target" to mapOf("kind" to "dynamic"))),
            mapOf("foreignCall" to mapOf("target" to mapOf("symbol" to "MD5Init"))),
            mapOf("foreignCall" to mapOf("target" to mapOf("symbol" to "__hsbase_MD5Other"))))
        for (value in metadata) assertNull(CoreMd5Foreign.validate(value, emptyList<Any?>(), emptyList<Any?>(), null))
    }

    @Test fun ExactIntegerAndDescriptorKeysRejectNormalizedForgeries() {
        for (field in listOf("schema", "arity", "suppliedArity")) {
            for (value in listOf(null, true, false, 1.0, 2.0, 4.0, "2", -1L, 1L shl 32))
                fixture().also { it.descriptor[field] = value; reject(it) }
            fixture().also { it.descriptor.remove(field); reject(it) }
        }
        fixture().also { it.descriptor["extra"] = true; reject(it) }
    }

    @Test fun KnownSymbolsRequireOriginalUnitStaticFunctionUnsafeCcall() {
        val targets = mapOf("kind" to listOf("dynamic", null, 0),
            "unit" to listOf("main", null, "ghc-internal-9.1401.0"),
            "isFunction" to listOf(false, 1, "true", null))
        for ((field, values) in targets) for (value in values)
            fixture().also { it.target[field] = value; reject(it) }
        val modes = mapOf("safety" to listOf("safe", "interruptible", null),
            "convention" to listOf("capi", "stdcall", "prim", "javascript", null))
        for ((field, values) in modes) for (value in values)
            fixture().also { it.descriptor[field] = value; reject(it) }
        fixture().also { it.target["extra"] = 0; reject(it) }
    }

    @Test fun EveryDeclaredAndActualScalarSlotRejectsWrongOrAggregateProofs() {
        val changes = listOf("primReps" to listOf("IntRep"), "primReps" to listOf("Word32Rep"), "primReps" to null,
            "kind" to "unknown", "evaluated" to 1, "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>(),
            "vector" to null, "alternatives" to emptyList<Any?>(), "tagSlot" to 0)
        for (operation in Md5ForeignOp.entries) for (declared in listOf(false, true)) for (i in 0 until operation.arity) {
            for ((field, value) in changes) fixture(operation).also { input ->
                val proof = if (declared) input.declared[i] else input.arguments[i] as MutableMap<String, Any?>
                proof[field] = value; reject(input)
            }
            fixture(operation).also { input ->
                val proof = if (declared) input.declared[i] else input.arguments[i] as MutableMap<String, Any?>
                proof.remove("evaluated"); reject(input)
            }
        }
        fixture().also { it.declared[0]["evaluated"] = true; reject(it) }
    }

    @Test fun EveryResultSiteRequiresOneExactStateComponent() {
        val changes = listOf("kind" to "void", "primReps" to listOf("IntRep"), "evaluated" to 0,
            "aggregate" to "unboxed-sum", "components" to emptyList<Any?>(),
            "components" to listOf(scalar(), scalar()), "components" to listOf(scalar("Int32Rep", true)),
            "components" to listOf(scalar()), "vector" to null, "alternatives" to emptyList<Any?>(), "tagSlot" to 0)
        for (site in 0..2) {
            fun proof(input: Input): MutableMap<String, Any?> = when (site) {
                0 -> input.descriptor["resultRep"] as MutableMap<String, Any?>
                1 -> input.metadata["rep"] as MutableMap<String, Any?>
                else -> input.result
            }
            for ((field, value) in changes) fixture().also { proof(it)[field] = value; reject(it) }
            fixture().also { proof(it).remove("aggregate"); reject(it) }
        }
        fixture().also { (it.descriptor["resultRep"] as MutableMap<String, Any?>)["evaluated"] = true; reject(it) }
    }

    @Test fun ActualArityAndEveryUnliftedFlagAreExact() {
        for (operation in Md5ForeignOp.entries) {
            for (i in 0 until operation.arity) for (value in listOf(true, 0, null, "false"))
                fixture(operation).also { it.flags[i] = value; reject(it) }
            fixture(operation).also { it.arguments.removeAt(0); reject(it) }
            fixture(operation).also { it.arguments.add(it.arguments[0]); reject(it) }
            fixture(operation).also { it.flags.removeAt(0); reject(it) }
            fixture(operation).also { it.flags.add(false); reject(it) }
            fixture(operation).also { it.declared.removeAt(0); reject(it) }
        }
    }

    @Test fun DeclaredActualAndSymbolProofsCannotContradictEachOther() {
        fixture(Md5ForeignOp.UPDATE).also { it.target["symbol"] = "__hsbase_MD5Final"; reject(it) }
        fixture(Md5ForeignOp.UPDATE).also { it.arguments[2] = scalar("AddrRep", true); reject(it) }
        fixture().also { it.metadata.remove("rep"); reject(it) }
    }
}
