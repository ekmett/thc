// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreManagedFilesTest {
    private class Input(val name: String) {
        val descriptor = ManagedFileFixtures.descriptor(name)
        val metadata = mutableMapOf("foreignCall" to descriptor, "rep" to ManagedFileFixtures.tuple(name))
        val arguments: MutableList<Any?> = ManagedFileFixtures.signatures.getValue(name).map { ManagedFileFixtures.scalar(it) }.toMutableList()
        val flags: MutableList<Any?> = MutableList(arguments.size) { false }
        val result = ManagedFileFixtures.tuple(name)
        val target get() = descriptor["target"] as MutableMap<String, Any?>
        val declared get() = descriptor["argumentReps"] as MutableList<MutableMap<String, Any?>>
        fun validate() = CoreManagedFiles.validate(metadata, arguments, flags, result)
    }
    private fun reject(input: Input) {
        val error = assertThrows(RuntimeFault::class.java) { input.validate() }
        assertTrue(error.message.orEmpty().startsWith("Invalid managed file call: "), error.message)
    }

    @Test fun allElevenExactContractsAndImportingUnits() {
        assertEquals(11, ManagedFileFixtures.signatures.size)
        for (name in ManagedFileFixtures.signatures.keys) for (unit in listOf(null, "main", "library-1.0", "ghc-internal")) {
            val input = Input(name)
            input.target["unit"] = unit
            assertEquals("thc_io_v1_$name", input.validate()!!.symbol)
            input.arguments.forEach { (it as MutableMap<String, Any?>)["evaluated"] = false }
            input.result["evaluated"] = false
            assertEquals("thc_io_v1_$name", input.validate()!!.symbol)
        }
    }

    @Test fun prefixIsClosedAndPosixNamesNeverDispatch() {
        for (symbol in listOf("thc_io_v1_", "thc_io_v1_unknown", "thc_io_v1_open64"))
            Input("open").also { it.target["symbol"] = symbol; reject(it) }
        for (symbol in listOf("open", "read", "write", "close", "fstat", "thc_io_v2_open", "__hsbase_open"))
            Input("open").also { it.target["symbol"] = symbol; assertNull(it.validate()) }
        for (metadata in listOf(null, emptyMap<String, Any?>(), mapOf("foreignCall" to null)))
            assertNull(CoreManagedFiles.validate(metadata, emptyList<Any?>(), emptyList<Any?>(), null))
    }

    @Test fun exactTargetDescriptorAndIntegerFields() {
        for (name in ManagedFileFixtures.signatures.keys) {
            for (field in listOf("schema", "arity", "suppliedArity")) {
                for (value in listOf(null, true, false, 1.0, 2.0, "1", -1L, 1L shl 32))
                    Input(name).also { it.descriptor[field] = value; reject(it) }
                Input(name).also { it.descriptor.remove(field); reject(it) }
            }
            for ((field, values) in mapOf("kind" to listOf(null, "dynamic", false),
                "unit" to listOf("", 7L, false), "isFunction" to listOf(null, false, 1L, "true")))
                for (value in values) Input(name).also { it.target[field] = value; reject(it) }
            for ((field, values) in mapOf("convention" to listOf(null, "ccall", "capi", "javascript"),
                "safety" to listOf(null, "unsafe", "interruptible")))
                for (value in values) Input(name).also { it.descriptor[field] = value; reject(it) }
            Input(name).also { it.target.remove("unit"); reject(it) }
            Input(name).also { it.target["extra"] = false; reject(it) }
            Input(name).also { it.descriptor["extra"] = false; reject(it) }
        }
    }

    @Test fun everyArgumentFlagAndProofIsExact() {
        for (name in ManagedFileFixtures.signatures.keys) {
            for (i in ManagedFileFixtures.signatures.getValue(name).indices) {
                for (value in listOf(true, 0, null, "false")) Input(name).also { it.flags[i] = value; reject(it) }
                for (declared in listOf(false, true)) {
                    for ((field, value) in listOf("kind" to "unknown", "primReps" to listOf("WordRep"),
                        "primReps" to listOf("Int32Rep"), "evaluated" to 1, "vector" to null,
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
            Input(name).also { it.arguments.add(it.arguments[0]); reject(it) }
        }
    }

    @Test fun allThreeResultProofsPreserveExactStateAndPayload() {
        for (name in listOf("open", "error_message")) for (site in 0..2) {
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
        Input("open").also { (it.descriptor["resultRep"] as MutableMap<String, Any?>)["evaluated"] = true; reject(it) }
    }
}
