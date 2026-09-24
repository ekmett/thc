// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json

/** Every baseline is a retained original FCall application, not a fresh FFI fixture. */
class CoreStackInfoForeignTest {
    private val symbols = linkedMapOf(
        "getStackInfoTableAddrzh" to OriginalStackInfoOp.STACK_INFO,
        "getInfoTableAddrszh" to OriginalStackInfoOp.FRAME_INFO,
        "lookupIPE" to OriginalStackInfoOp.LOOKUP_IPE)
    private fun original() = Json.parse(javaClass.getResource("/core/original-stack-info-calls.json")!!.readText()) as Map<String, Any?>
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private inner class Input(val symbol: String) {
        val application = (original()["calls"] as List<Map<String, Any?>>).map { it["application"] as MutableList<Any?> }
            .single { (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] == symbol }
        val metadata = application[6] as MutableMap<String, Any?>
        val descriptor = metadata["foreignCall"] as MutableMap<String, Any?>
        val target get() = descriptor["target"] as MutableMap<String, Any?>
        val declared get() = descriptor["argumentReps"] as MutableList<Any?>
        val arguments: MutableList<Any?> = (application[2] as List<List<Any?>>)
            .map { copy((it.last() as Map<*, *>)["rep"]) }.toMutableList()
        val flags = application[3] as MutableList<Any?>
        var result = copy(metadata["rep"])
        fun validate() = CoreStackInfoForeign.validate(metadata, arguments, flags, result)
        fun resultAt(site: Int): MutableMap<String, Any?> = when (site) {
            0 -> descriptor["resultRep"]
            1 -> metadata["rep"]
            else -> result
        } as MutableMap<String, Any?>
        fun replaceResult(site: Int, value: Any?) {
            when (site) { 0 -> descriptor["resultRep"] = value; 1 -> metadata["rep"] = value; else -> result = value }
        }
    }
    private fun reject(input: Input) {
        val error = assertThrows(RuntimeFault::class.java) { input.validate() }
        assertTrue(error.message.orEmpty().startsWith("Invalid original stack info call: "), error.message)
    }
    private fun scalar(rep: String?, evaluated: Boolean = false): MutableMap<String, Any?> = mutableMapOf(
        "kind" to when (rep) { null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> "object"; else -> "long" },
        "primReps" to (rep?.let { listOf(it) } ?: emptyList<String>()), "evaluated" to evaluated)

    @Test fun retainedProofProvenanceAndAllThreeExactContracts() {
        val source = original()
        assertEquals(1L, source["schema"]); assertEquals("9.14.1", source["ghc"])
        assertEquals("902339d332fb4ce2b3c87dcac1ee6495d41ad886", source["ghcRevision"])
        assertEquals("62e3400c5b889d3971cb4047709c408fd270255f", source["exporterRevision"])
        val expectedHashes = mapOf(
            "GHC.Internal.Stack.Decode/GHC.Internal.Stack.Decode.json" to "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42",
            "GHC.Internal.InfoProv.Types/GHC.Internal.InfoProv.Types.json" to "63fe524cfd81c88ebd4f835c8718a30b86828c9e53549a2c001cbffac5ab2d1e")
        val sources = source["sources"] as List<Map<String, Any?>>
        assertEquals(expectedHashes, sources.associate { it["file"] to it["sha256"] })
        sources.forEach { assertEquals("optimized-Core-after-Tidy-before-CorePrep", it["boundary"]) }
        val calls = source["calls"] as List<Map<String, Any?>>
        assertEquals(3, calls.size)
        assertEquals(setOf("ghc-internal:GHC.Internal.Stack.Decode.\$wdecodeStackWithFrameUnpack",
            "ghc-internal:GHC.Internal.Stack.Decode.\$wunpackStackFrameTo", "ghc-internal:GHC.Internal.InfoProv.Types.lookupIPE"), calls.map { it["owner"] }.toSet())
        calls.forEach { assertTrue((it["path"] as String).startsWith("/expr/")); assertTrue((it["source"] as String) in expectedHashes) }
        assertEquals(symbols.values.toSet(), OriginalStackInfoOp.entries.toSet())
        for ((symbol, operation) in symbols) {
            val input = Input(symbol)
            assertEquals(operation, input.validate())
            CoreStackInfoForeign.validateHead(input.application[1] as List<Any?>, false)
            CoreStackInfoForeign.validateHeads(mapOf("nested" to listOf(input.application)))
            input.arguments.forEach { (it as MutableMap<String, Any?>)["evaluated"] = false }
            input.resultAt(1)["evaluated"] = true; input.resultAt(2)["evaluated"] = true
            assertEquals(operation, input.validate(), "Actual evaluation information may differ, unlike declared proofs")
        }
    }

    @Test fun unknownSymbolsAndOtherColdGettersStayUnrecognized() {
        for (unknown in listOf("getStackInfoTableAddr", "getInfoTableAddrszh64", "stg_getInfoTableAddrszh", "lookUpIPE",
            "getSmallBitmapzh", "advanceStackFrameLocationzh", "getWordzh", "getStackClosurezh", "stg_cloneMyStackzh", "")) {
            val input = Input("lookupIPE"); input.target["symbol"] = unknown
            input.application[1] = 7L // An unrelated unknown symbol must retain ordinary unsupported handling.
            assertNull(input.validate()); assertDoesNotThrow { CoreStackInfoForeign.validateHeads(input.application) }
        }
        for (metadata in listOf(null, 1L, emptyMap<String, Any?>(), mapOf("foreignCall" to null),
            mapOf("foreignCall" to mapOf("target" to emptyMap<String, Any?>()))))
            assertNull(CoreStackInfoForeign.validate(metadata, emptyList<Any?>(), emptyList<Any?>(), null))
    }

    @Test fun descriptorKeysTargetAndIntegralFieldsAreClosed() {
        for (symbol in symbols.keys) {
            for (key in listOf("schema", "arity", "suppliedArity")) {
                for (wrong in listOf(null, true, false, 1.0, 2.0, 3.0, "1", -1L, 0L, 1L shl 32))
                    Input(symbol).also { it.descriptor[key] = wrong; reject(it) }
                Input(symbol).also { it.descriptor.remove(key); reject(it) }
            }
            for (key in listOf("convention", "safety", "argumentReps", "resultRep"))
                Input(symbol).also { it.descriptor.remove(key); reject(it) }
            for ((key, wrong) in listOf("kind" to "dynamic", "kind" to null, "unit" to "main", "unit" to null,
                "unit" to "other-unit", "isFunction" to false, "isFunction" to 1L, "isFunction" to "true", "extra" to null))
                Input(symbol).also { it.target[key] = wrong; reject(it) }
            for (key in listOf("kind", "unit", "isFunction")) Input(symbol).also { it.target.remove(key); reject(it) }
            for (wrong in listOf("unsafe", "interruptible", null, 1L))
                Input(symbol).also { it.descriptor["safety"] = wrong; reject(it) }
            for (wrong in listOf("capi", "javascript", if (symbol == "lookupIPE") "prim" else "ccall", null))
                Input(symbol).also { it.descriptor["convention"] = wrong; reject(it) }
            Input(symbol).also { it.descriptor["extra"] = false; reject(it) }
        }
    }

    @Test fun argumentWidthsLevitiesAndLogicalStateCannotBeRelabeled() {
        val alternatives = listOf(null, "IntRep", "WordRep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "Word8Rep",
            "AddrRep", "BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "BoxedRep Nothing")
        for (symbol in symbols.keys) {
            val baseline = Input(symbol)
            for (index in baseline.arguments.indices) for (declared in listOf(false, true)) {
                val actualRep = (baseline.arguments[index] as Map<*, *>)["primReps"]
                for (replacement in alternatives) {
                    val proof = scalar(replacement)
                    if (proof["primReps"] == actualRep) continue
                    Input(symbol).also {
                        (if (declared) it.declared else it.arguments)[index] = proof; reject(it)
                    }
                }
                for ((key, value) in listOf("kind" to "unknown", "evaluated" to 1L, "evaluated" to null,
                    "primReps" to null, "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>(), "vector" to null))
                    Input(symbol).also {
                        val proof = (if (declared) it.declared else it.arguments)[index] as MutableMap<String, Any?>
                        proof[key] = value; reject(it)
                    }
                Input(symbol).also { (it.declared[index] as MutableMap<String, Any?>)["evaluated"] = true; reject(it) }
                for (key in listOf("kind", "primReps", "evaluated")) Input(symbol).also {
                    ((if (declared) it.declared else it.arguments)[index] as MutableMap<String, Any?>).remove(key); reject(it)
                }
            }
        }
    }

    @Test fun saturationAndFlagsRejectMissingExtraAndNonBooleanValues() {
        for (symbol in symbols.keys) {
            for (index in Input(symbol).arguments.indices) {
                for (wrong in listOf(true, 0L, null, "false")) Input(symbol).also { it.flags[index] = wrong; reject(it) }
                Input(symbol).also { it.flags.removeAt(index); reject(it) }
                Input(symbol).also { it.arguments.removeAt(index); reject(it) }
                Input(symbol).also { it.declared.removeAt(index); reject(it) }
            }
            Input(symbol).also { it.flags.add(false); reject(it) }
            Input(symbol).also { it.arguments.add(scalar(null)); reject(it) }
            Input(symbol).also { it.declared.add(scalar(null)); reject(it) }
        }
    }

    @Test fun allThreeResultProofsPreserveScalarVersusTupleShape() {
        for (symbol in symbols.keys) for (site in 0..2) {
            for ((key, value) in listOf("kind" to "closure", "primReps" to emptyList<String>(), "evaluated" to 0L,
                "evaluated" to null, "aggregate" to "unboxed-sum", "components" to emptyList<Any?>(), "extra" to true))
                Input(symbol).also { it.resultAt(site)[key] = value; reject(it) }
            for (key in Input(symbol).resultAt(site).keys) Input(symbol).also { it.resultAt(site).remove(key); reject(it) }
            Input(symbol).also { it.replaceResult(site, null); reject(it) }
            Input(symbol).also { it.replaceResult(site, listOf(it.resultAt(site))); reject(it) }
            if (symbol == "getStackInfoTableAddrzh") Input(symbol).also {
                val field = scalar("AddrRep", true)
                it.replaceResult(site, mapOf("kind" to "unknown", "primReps" to listOf("AddrRep"), "evaluated" to false,
                    "aggregate" to "unboxed-tuple", "components" to listOf(field))); reject(it)
            }
            Input(symbol).also { it.resultAt(0)["evaluated"] = true; reject(it) }
        }
    }

    @Test fun tupleFieldsAreOrderedExactEvaluatedAndKeepZeroWidthState() {
        for (symbol in listOf("getInfoTableAddrszh", "lookupIPE")) for (site in 0..2) {
            fun fields(input: Input) = input.resultAt(site)["components"] as MutableList<MutableMap<String, Any?>>
            for (index in 0..1) {
                for ((key, value) in listOf("evaluated" to false, "evaluated" to 1L, "primReps" to listOf("IntRep"),
                    "kind" to "unknown", "components" to emptyList<Any?>(), "vector" to null))
                    Input(symbol).also { fields(it)[index][key] = value; reject(it) }
                Input(symbol).also { fields(it).removeAt(index); reject(it) }
            }
            Input(symbol).also { fields(it).add(scalar("AddrRep", true)); reject(it) }
            Input(symbol).also { it.replaceResult(site, scalar("AddrRep")); reject(it) }
            Input(symbol).also { it.resultAt(site)["primReps"] = listOf("AddrRep"); reject(it) }
        }
        for (site in 0..2) Input("lookupIPE").also {
            (it.resultAt(site)["components"] as MutableList<Any?>).reverse(); reject(it)
        }
        for (rep in listOf("WordRep", "Word32Rep", "Int8Rep", "IntRep", "AddrRep")) for (site in 0..2)
            Input("lookupIPE").also {
                it.resultAt(site)["primReps"] = listOf(rep)
                (it.resultAt(site)["components"] as MutableList<Any?>)[1] = scalar(rep, true); reject(it)
            }
    }

    @Test fun malformedRecognizedHeadsFailBeforeAnyGenericIdCast() {
        for (symbol in symbols.keys) {
            val originalHead = Input(symbol).application[1] as List<Any?>
            assertThrows(RuntimeFault::class.java) { CoreStackInfoForeign.validateHead(originalHead, true) }
            for (wrong in listOf(null, 7L, emptyList<Any?>(), listOf("var"), listOf("prim", "fake"),
                originalHead + "extra")) {
                val input = Input(symbol); input.application[1] = wrong
                assertThrows(RuntimeFault::class.java) { CoreStackInfoForeign.validateHeads(mapOf("body" to listOf(input.application))) }
            }
            for (id in listOf(null, "", 7L, true, emptyList<String>())) {
                val input = Input(symbol); (input.application[1] as MutableList<Any?>)[1] = id
                assertThrows(RuntimeFault::class.java) { CoreStackInfoForeign.validateHeads(input.application) }
            }
            for ((key, value) in listOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"),
                "evaluated" to false, "evaluated" to 1L, "aggregate" to "unboxed-tuple", "extra" to false)) {
                val input = Input(symbol)
                val head = input.application[1] as MutableList<Any?>
                ((head[2] as MutableMap<String, Any?>)["rep"] as MutableMap<String, Any?>)[key] = value
                assertThrows(RuntimeFault::class.java) { CoreStackInfoForeign.validateHeads(input.application) }
            }
        }
    }
}
