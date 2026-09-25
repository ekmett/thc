// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreOriginalStdioTest {
    private class Input(val name: String) {
        val descriptor = OriginalStdioFixtures.descriptor(name)
        val metadata = mutableMapOf("foreignCall" to descriptor, "rep" to OriginalStdioFixtures.tuple(name))
        val arguments: MutableList<Any?> = OriginalStdioFixtures.signatures.getValue(name).map { OriginalStdioFixtures.scalar(it) }.toMutableList()
        val flags: MutableList<Any?> = MutableList(arguments.size) { false }
        val result = OriginalStdioFixtures.tuple(name)
        val target get() = descriptor["target"] as MutableMap<String, Any?>
        val declared get() = descriptor["argumentReps"] as MutableList<MutableMap<String, Any?>>
        fun validate() = CoreOriginalStdio.validate(metadata, arguments, flags, result)
    }
    private fun reject(input: Input) {
        val error = assertThrows(RuntimeFault::class.java) { input.validate() }
        assertTrue(error.message.orEmpty().startsWith("Invalid original stdio call: "), error.message)
    }

    @Test fun allEightExactContracts() {
        assertEquals(8, OriginalStdioFixtures.signatures.size)
        for (name in OriginalStdioFixtures.signatures.keys) {
            val input = Input(name)
            assertEquals(OriginalStdioFixtures.symbols.getValue(name), input.validate()!!.symbol)
            input.arguments.forEach { (it as MutableMap<String, Any?>)["evaluated"] = false }
            input.result["evaluated"] = false
            assertEquals(OriginalStdioFixtures.symbols.getValue(name), input.validate()!!.symbol)
        }
    }

    @Test fun noSymbolAliasesOrSetterAreAdmitted() {
        val symbol = OriginalStdioFixtures.symbols.getValue("safe_write")
        for (unknown in listOf("write", "read", "__hscore_set_errno", "thc_io_v1_write",
            symbol.replace("ZC20ZC", "ZC22ZC"), symbol + "64", "prefix" + symbol))
            Input("safe_write").also { it.target["symbol"] = unknown; assertNull(it.validate()) }
        for (metadata in listOf(null, emptyMap<String, Any?>(), mapOf("foreignCall" to null)))
            assertNull(CoreOriginalStdio.validate(metadata, emptyList<Any?>(), emptyList<Any?>(), null))
    }

    @Test fun exactTargetDescriptorAndIntegerFields() {
        for (name in OriginalStdioFixtures.signatures.keys) {
            for (field in listOf("schema", "arity", "suppliedArity")) {
                for (value in listOf(null, true, false, 1.0, 2.0, "1", -1L, 1L shl 32))
                    Input(name).also { it.descriptor[field] = value; reject(it) }
                Input(name).also { it.descriptor.remove(field); reject(it) }
            }
            for ((field, values) in mapOf("kind" to listOf(null, "dynamic", false),
                "unit" to listOf(null, "", "main", "other-package", 7L, false), "isFunction" to listOf(null, false, 1L, "true")))
                for (value in values) Input(name).also { it.target[field] = value; reject(it) }
            for ((field, values) in mapOf("convention" to listOf(null, "prim", "javascript", if (OriginalStdioFixtures.convention(name) == "ccall") "capi" else "ccall"),
                "safety" to listOf(null, "interruptible", if (name == "safe_write") "unsafe" else "safe")))
                for (value in values) Input(name).also { it.descriptor[field] = value; reject(it) }
            Input(name).also { it.target.remove("unit"); reject(it) }
            Input(name).also { it.target["extra"] = false; reject(it) }
            Input(name).also { it.descriptor["extra"] = false; reject(it) }
        }
    }

    @Test fun everyArgumentFlagAndProofIsExact() {
        for (name in OriginalStdioFixtures.signatures.keys) {
            for (i in OriginalStdioFixtures.signatures.getValue(name).indices) {
                for (value in listOf(true, 0, null, "false")) Input(name).also { it.flags[i] = value; reject(it) }
                for (declared in listOf(false, true)) {
                    for ((field, value) in listOf("kind" to "unknown", "primReps" to listOf("WordRep"),
                        "primReps" to listOf("IntRep"), "evaluated" to 1, "vector" to null,
                        "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>(), "tagSlot" to 0)) {
                        Input(name).also {
                            val proof = if (declared) it.declared[i] else it.arguments[i] as MutableMap<String, Any?>
                            proof[field] = value; reject(it)
                        }
                    }
                }
                Input(name).also { it.declared[i]["evaluated"] = true; reject(it) }
            }
            Input(name).also { it.arguments.removeAt(0); reject(it) }
            Input(name).also { it.declared.removeAt(0); reject(it) }
            Input(name).also { it.flags.add(false); reject(it) }
            Input(name).also { it.flags.removeAt(0); reject(it) }
            Input(name).also { it.arguments.add(it.arguments[0]); reject(it) }
        }
    }

    @Test fun numericWidthsAndSignednessAreNotInterchangeable() {
        val alternatives = listOf("IntRep", "WordRep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "AddrRep")
        for ((name, expected) in OriginalStdioFixtures.signatures) for ((index, primitive) in expected.withIndex())
            for (replacement in alternatives.filter { it != primitive }) for (declared in listOf(false, true)) {
                Input(name).also {
                    val proof = OriginalStdioFixtures.scalar(replacement, !declared)
                    if (declared) it.declared[index] = proof else it.arguments[index] = proof
                    reject(it)
                }
            }
    }

    @Test fun allThreeResultProofsPreserveExactStateAndPayload() {
        for (name in listOf("safe_write", "errno", "dup", "dup2")) for (site in 0..2) {
            fun proof(input: Input): MutableMap<String, Any?> = when (site) {
                0 -> input.descriptor["resultRep"] as MutableMap<String, Any?>
                1 -> input.metadata["rep"]!!
                else -> input.result
            }
            for ((field, value) in listOf("kind" to "void", "primReps" to emptyList<String>(),
                "aggregate" to "unboxed-sum", "evaluated" to 1, "components" to emptyList<Any?>(), "vector" to null))
                Input(name).also { proof(it)[field] = value; reject(it) }
            for (index in 0..1) {
                for ((field, value) in listOf("evaluated" to false, "primReps" to listOf("WordRep"),
                    "components" to emptyList<Any?>(), "kind" to "object")) Input(name).also {
                    val components = proof(it)["components"] as List<MutableMap<String, Any?>>
                    components[index][field] = value; reject(it)
                }
            }
        }
        Input("safe_write").also { (it.descriptor["resultRep"] as MutableMap<String, Any?>)["evaluated"] = true; reject(it) }
    }

    @Test fun foreignHeadsAreUnboundDeclarationsNotCallerNameAliases() {
        fun head(id: Any? = "foreign") = listOf("var", id, mapOf("rep" to OriginalStdioFixtures.closure()))
        for (name in listOf("arbitrary", "other-package:Caller.inlined", "write"))
            CoreOriginalStdio.validateHead(head(name), false)
        val malformed = listOf(emptyList(), listOf("var", "foreign"), listOf("prim", "foreign"),
            head(null), head(""), head(3), listOf("var", "foreign", emptyMap<String, Any?>()))
        for (value in malformed) assertThrows(RuntimeFault::class.java) { CoreOriginalStdio.validateHead(value, false) }
        assertThrows(RuntimeFault::class.java) { CoreOriginalStdio.validateHead(head(), true) }
        for ((field, value) in listOf("kind" to "long", "primReps" to listOf("IntRep"),
            "evaluated" to false, "evaluated" to 1, "extra" to null)) {
            val proof = OriginalStdioFixtures.closure().also { it[field] = value }
            assertThrows(RuntimeFault::class.java) {
                CoreOriginalStdio.validateHead(listOf("var", "foreign", mapOf("rep" to proof)), false)
            }
        }
    }
}
