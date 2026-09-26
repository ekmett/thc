// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.PackageScalarLink
import thc.PackageScalarSignature

/** Call-proof controls only; no synthetic bitcode is executed. */
class PackageScalarForeignTest {
    @Test fun int32ProtocolConversionRejectsTruncation() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE))
            assertEquals(value, packageScalarInt32(value.toLong()))
        for (value in listOf(Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1, Long.MIN_VALUE, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { packageScalarInt32(value) }
    }

    private fun kind(rep: String?) = when (rep) {
        null -> CoreKind.VOID
        "FloatRep" -> CoreKind.FLOAT
        "DoubleRep" -> CoreKind.DOUBLE
        "AddrRep" -> CoreKind.ADDRESS
        "ByteArray#", "MutableByteArray#" -> CoreKind.OBJECT
        else -> CoreKind.LONG
    }
    private fun reps(rep: String?) = when (rep) {
        "ByteArray#", "MutableByteArray#" -> listOf("BoxedRep (Just Unlifted)")
        else -> listOfNotNull(rep)
    }
    private fun scalar(rep: String?, evaluated: Boolean = true) =
        mapOf("kind" to kind(rep).name.lowercase(), "primReps" to reps(rep), "evaluated" to evaluated)
    private fun result(rep: String, evaluated: Boolean = true) = mapOf("kind" to "unknown", "primReps" to listOf(rep),
        "evaluated" to evaluated, "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null), scalar(rep)))
    private fun link(unit: String, rep: String) = PackageScalarLink(unit, "unused", "", "", byteArrayOf(),
        listOf(PackageScalarSignature("stg_sig_install", "unused", listOf(rep), rep)))

    @Test fun exactWidthStateAndUnitProofsAreRequiredForAllScalarKinds() {
        for (rep in listOf("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
                "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "FloatRep", "DoubleRep")) {
            val link = link("first", rep)
            val target = mapOf("kind" to "static", "symbol" to "stg_sig_install", "unit" to "first", "isFunction" to true)
            val descriptor = mapOf("schema" to 1L, "target" to target, "convention" to "ccall", "safety" to "unsafe",
                "arity" to 2L, "suppliedArity" to 2L, "argumentReps" to listOf(scalar(rep, false), scalar(null, false)),
                "resultRep" to result(rep, false))
            val output = result(rep)
            fun validate(call: Map<String, Any?> = descriptor, args: List<*> = listOf(scalar(rep), scalar(null)),
                         flags: List<*> = listOf(false, false), returned: Any? = output) =
                CorePackageScalarForeign.validate(mapOf("foreignCall" to call, "rep" to output), args, flags, returned, listOf(link))
            val admitted = validate()!!
            assertSame(link, admitted.link)
            assertEquals(rep, admitted.result)
            val changes = listOf(
                descriptor + ("safety" to "safe"), descriptor + ("convention" to "prim"),
                descriptor + ("target" to (target + ("kind" to "dynamic"))),
                descriptor + ("target" to (target + ("isFunction" to false))),
                descriptor + ("target" to (target + ("symbol" to "unproved_symbol"))),
                descriptor + ("arity" to 1L), descriptor + ("suppliedArity" to 1L),
                descriptor + ("argumentReps" to listOf(scalar("AddrRep", false), scalar(null, false))),
                descriptor + ("resultRep" to result(if (rep == "Int32Rep") "Int64Rep" else "Int32Rep", false)))
            changes.forEachIndexed { index, changed ->
                assertThrows(RuntimeFault::class.java, { validate(changed) }, "$rep declaration $index")
            }
            assertThrows(RuntimeFault::class.java) { validate(args = listOf(scalar(rep), scalar("Int64Rep"))) }
            assertThrows(RuntimeFault::class.java) { validate(flags = listOf(true, false)) }
            assertThrows(RuntimeFault::class.java) { validate(returned = scalar(rep)) }
            for (other in listOf("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep").filter { it != rep }) {
                assertThrows(RuntimeFault::class.java) { validate(args = listOf(scalar(other), scalar(null))) }
            }
            // Same spelling in another component never selects this component's entry.
            assertNull(validate(descriptor + ("target" to (target + ("unit" to "second")))))
            assertNull(validate(descriptor + ("target" to (target + ("unit" to null)))))
            assertFalse(CoreSignalForeign.named(mapOf("foreignCall" to descriptor)))
            val exact = CoreRepresentation(kind(rep), present = true, primReps = listOf(rep))
            CorePackageScalarForeign.validateOperand(admitted, 0, exact, exact)
            CorePackageScalarForeign.validateOperand(admitted, 1,
                CoreRepresentation(CoreKind.VOID, present = true, primReps = emptyList()), null)
            assertThrows(RuntimeFault::class.java) {
                CorePackageScalarForeign.validateOperand(admitted, 0, exact,
                    CoreRepresentation(CoreKind.LONG, present = true,
                        primReps = listOf(if (rep == "Word64Rep") "Int64Rep" else "Word64Rep")))
            }
            assertThrows(RuntimeFault::class.java) {
                CorePackageScalarForeign.validateOperand(admitted, 0, exact.copy(present = false), null)
            }
        }
    }

    @Test fun capiByteStorageAndAddressesKeepTheirExactVoidWorkerShape() {
        for (rep in listOf("ByteArray#", "MutableByteArray#", "AddrRep")) {
            val signature = PackageScalarSignature("wrapper", "unused", listOf(rep), "void", "capi")
            val link = PackageScalarLink("first", "unused", "", "", byteArrayOf(), listOf(signature))
            val output = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
                "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null)))
            val descriptor = mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to "wrapper",
                "unit" to "first", "isFunction" to true), "convention" to "capi", "safety" to "unsafe",
                "arity" to 2L, "suppliedArity" to 2L, "argumentReps" to listOf(scalar(rep, false), scalar(null, false)),
                "resultRep" to (output + ("evaluated" to false)))
            fun validate(first: Any? = scalar(rep), returned: Any? = output) = CorePackageScalarForeign.validate(
                mapOf("foreignCall" to descriptor, "rep" to output), listOf(first, scalar(null)), listOf(false, false),
                returned, listOf(link))
            val call = validate()!!
            assertEquals("void", call.result)
            val exact = CoreRepresentation(kind(rep), present = true, primReps = reps(rep))
            CorePackageScalarForeign.validateOperand(call, 0, exact, exact)
            assertThrows(RuntimeFault::class.java) { validate(scalar("Word64Rep")) }
            assertThrows(RuntimeFault::class.java) { validate(returned = result("Word64Rep")) }
            assertThrows(RuntimeFault::class.java) {
                validate(scalar(rep) + ("primReps" to listOf("BoxedRep (Just Lifted)")))
            }
        }
    }

    @Test fun sameSymbolSelectsTheExactPointerOrByteArrayAdapter() {
        val variants = listOf("AddrRep", "ByteArray#").mapIndexed { index, rep ->
            PackageScalarSignature("read_bytes", "adapter_$index", listOf(rep), "WordRep") }
        val link = PackageScalarLink("first", "unused", "", "", byteArrayOf(), variants)
        val output = result("WordRep")
        fun validate(rep: String, selected: PackageScalarLink = link): PackageScalarCall? {
            val descriptor = mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to "read_bytes",
                "unit" to "first", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
                "arity" to 2L, "suppliedArity" to 2L, "argumentReps" to listOf(scalar(rep, false), scalar(null, false)),
                "resultRep" to result("WordRep", false))
            return CorePackageScalarForeign.validate(mapOf("foreignCall" to descriptor, "rep" to output),
                listOf(scalar(rep), scalar(null)), listOf(false, false), output, listOf(selected))
        }
        for (signature in variants) assertSame(signature, validate(signature.arguments.single())!!.signature)
        assertThrows(RuntimeFault::class.java) { validate("WordRep") }
        val ambiguous = PackageScalarLink("first", "unused", "", "", byteArrayOf(), listOf(variants[1],
            variants[1].copy(entry = "writable_adapter", arguments = listOf("MutableByteArray#"))))
        assertThrows(RuntimeFault::class.java) { validate("ByteArray#", ambiguous) }
    }
}
