// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import thc.Language

/** Synthetic lowering controls; authentic imported consumers are tested separately. */
class OriginalStdioCallTest {
    private fun context(out: ByteArrayOutputStream, err: ByteArrayOutputStream) = Context.newBuilder("thc")
        .out(out).err(err).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }

    @Test fun normalAndFirstInstalledCompiledCallsPreserveBytesErrnoAndState() {
        for (backend in listOf("ast", "bytecode")) {
            val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
            context(out, err).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = OriginalStdioFixtures.module { call ->
                        // The caller's binding name supplies no foreign provenance.
                        (call[1] as MutableList<Any?>)[1] = "unrelated-package:InlineCaller.arbitrary"
                    }
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val targets = OriginalStdioFixtures.signatures.keys.associateWith(program::entryTarget)
                    val ebadf = StdioHostAbi.load().error(4L)
                    var compiled = false
                    fun call(name: String, vararg args: Any?): Any? {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val target = targets.getValue(name)
                        val result = Calls.target(target, arrayOf(0L, *args, Unit))
                        if (compiled) {
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), name)
                            valid(target)
                        }
                        released(language)
                        return result
                    }
                    fun exercise(pass: Int) {
                        for (name in listOf("safe_write", "unsafe_write")) {
                            val bytes = byteArrayOf(0x55, pass.toByte(), 0, -1, 10, 0x66)
                            val address = ManagedAddress.fromByteArray(bytes).plus(1L)
                            val beforeOut = out.size(); val beforeErr = err.size()
                            assertEquals(4L, call(name, 1L, address, 4L))
                            assertArrayEquals(bytes.copyOfRange(1, 5), out.toByteArray().copyOfRange(beforeOut, out.size()))
                            assertEquals(4L, call(name, 2L, address, 4L))
                            assertArrayEquals(bytes.copyOfRange(1, 5), err.toByteArray().copyOfRange(beforeErr, err.size()))
                            assertEquals(0L, call(name, 1L, address, 0L))
                            assertEquals(-1L, call(name, -1L, address, 4L))
                            assertEquals(ebadf, call("errno"))
                            assertEquals(1L, call(name, 1L, address, 1L))
                            assertEquals(ebadf, call("errno")) // successful calls do not clear errno
                        }
                    }
                    assertEquals(0L, call("errno"))
                    exercise(1)
                    for (target in targets.values) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                    }
                    compiled = true
                    exercise(2) // no settling call or recompile before checking installed code
                    compiled = false
                    for (name in listOf("safe_write", "unsafe_write")) {
                        val address = ManagedAddress.fromByteArray(byteArrayOf(3, 4))
                        val before = out.toByteArray()
                        assertThrows(RuntimeFault::class.java) {
                            Calls.target(targets.getValue(name), arrayOf(0L, 1L, address, 2L, 9L))
                        }
                        assertArrayEquals(before, out.toByteArray())
                        assertEquals(ebadf, call("errno"))
                        assertThrows(RuntimeFault::class.java) { call(name, 1L, address, -1L) }
                        assertThrows(RuntimeFault::class.java) { call(name, 1L, address, 3L) }
                        assertThrows(RuntimeFault::class.java) { call(name, 1L shl 32, address, 2L) }
                        assertArrayEquals(before, out.toByteArray())
                        assertEquals(ebadf, call("errno"))
                        released(language)
                    }
                    assertThrows(RuntimeFault::class.java) { Calls.target(targets.getValue("errno"), arrayOf(0L, 9L)) }
                    released(language)
                } finally { context.leave() }
            }
        }
    }

    @Test fun bothLoadersRejectBoundHeadsWrongProvenanceAndMalformedContracts() {
        for (backend in listOf("ast", "bytecode")) context(ByteArrayOutputStream(), ByteArrayOutputStream()).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun load(name: String = "safe_write", mutate: (MutableList<Any?>) -> Unit): ExecutableProgram {
                    val module = OriginalStdioFixtures.module(listOf(name), mutate)
                    return if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                }
                fun descriptor(call: MutableList<Any?>) = (call[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                for (name in OriginalStdioFixtures.signatures.keys) {
                    for (id in listOf(null, "", 3L, "p0", name)) assertThrows(RuntimeFault::class.java) {
                        load(name) { (it[1] as MutableList<Any?>)[1] = id }
                    }
                    for (head in listOf(listOf("prim", name), listOf("var", "foreign"),
                        listOf("var", "foreign", mapOf("rep" to OriginalStdioFixtures.scalar(null)))))
                        assertThrows(RuntimeFault::class.java) { load(name) { it[1] = head } }
                    for ((field, value) in listOf("convention" to "prim", "convention" to "javascript",
                        "safety" to "interruptible", "arity" to 4.0, "suppliedArity" to 0L))
                        assertThrows(RuntimeFault::class.java) { load(name) { descriptor(it)[field] = value } }
                    for (unit in listOf(null, "main", "other-package")) assertThrows(RuntimeFault::class.java) {
                        load(name) { (descriptor(it)["target"] as MutableMap<String, Any?>)["unit"] = unit }
                    }
                    assertThrows(RuntimeFault::class.java) { load(name) { (it[3] as MutableList<Any?>)[0] = true } }
                    assertThrows(RuntimeFault::class.java) { load(name) {
                        val args = it[2] as List<MutableList<Any?>>
                        args.last()[2] = mapOf("rep" to OriginalStdioFixtures.scalar("IntRep"))
                    } }
                    assertThrows(UnsupportedCore::class.java) { load(name) { (it[6] as MutableMap<String, Any?>).remove("foreignCall") } }
                }
                assertThrows(RuntimeFault::class.java) { load {
                    (it[2] as List<MutableList<Any?>>)[1][1] = "p0" // address occurrence cannot disguise a scalar binder
                } }
                for (symbol in listOf("write", "__hscore_set_errno", OriginalStdioFixtures.symbols.getValue("safe_write").replace("ZC20ZC", "ZC22ZC")))
                    assertThrows(UnsupportedCore::class.java) { load { (descriptor(it)["target"] as MutableMap<String, Any?>)["symbol"] = symbol } }
            } finally { context.leave() }
        }
    }
}
