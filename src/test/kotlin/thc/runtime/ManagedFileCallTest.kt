// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import thc.Language

/** Synthetic ABI execution controls; public System.IO Handle coverage is separate. */
class ManagedFileCallTest {
    @TempDir lateinit var directory: Path

    private fun context() = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun path(path: Path) = ManagedAddress.fromByteArray((path.toString() + "\u0000").toByteArray(Charsets.UTF_8))

    @Test fun normalAndInstalledCompiledCallsPreserveVisibleFilesTypedPayloadsAndErasedState() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = ManagedFileFixtures.module()
                val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                val targets = ManagedFileFixtures.signatures.keys.associateWith(program::entryTarget)
                var compiled = false
                fun call(name: String, vararg arguments: Any?): Any? {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val target = targets.getValue(name)
                    val result = Calls.target(target, arrayOf(0L, *arguments, Unit))
                    if (compiled) {
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), name)
                        valid(target)
                    }
                    released(language)
                    return result
                }
                fun exercise(pass: String) {
                    val file = directory.resolve("$backend-$pass.bin")
                    val fd = call("open", path(file), 3L) as Long
                    assertTrue(fd >= 3L)
                    val bytes = byteArrayOf(0, 1, 127, -128, -1, 10)
                    assertEquals(6L, call("write", fd, ManagedAddress.fromByteArray(bytes), 6L))
                    assertArrayEquals(bytes, Files.readAllBytes(file))
                    assertEquals(6L, call("size", fd))
                    assertEquals(0L, call("device_type", fd))
                    assertEquals(0L, call("is_terminal", fd))
                    assertEquals(0L, call("seek", fd, 0L, 0L))
                    val buffer = ByteArray(10) { 0x55 }
                    assertEquals(6L, call("read", fd, ManagedAddress.fromByteArray(buffer).plus(2L), 8L))
                    assertArrayEquals(byteArrayOf(0x55, 0x55, 0, 1, 127, -128, -1, 10, 0x55, 0x55), buffer)
                    assertEquals(0L, call("read", fd, ManagedAddress.fromByteArray(buffer), 10L))
                    assertEquals(4L, call("seek", fd, -2L, 2L))
                    assertEquals(3L, call("seek", fd, -1L, 1L))
                    assertEquals(0L, call("set_size", fd, 3L))
                    assertEquals(3L, call("size", fd))
                    assertArrayEquals(bytes.copyOf(3), Files.readAllBytes(file))
                    assertEquals(0L, call("close", fd))
                    assertEquals(-1L, call("size", fd))
                    assertEquals(4L, call("error_kind"))
                    val error = call("error_message") as ManagedAddress
                    assertTrue(error.indexChar(0L) != 0L)
                    assertEquals(4L, call("error_kind"))
                    val append = call("open", path(file), 2L) as Long
                    assertTrue(append >= 3L)
                    assertEquals(2L, call("write", append, ManagedAddress.fromByteArray(byteArrayOf(9, 8)), 2L))
                    assertEquals(0L, call("close", append))
                    assertArrayEquals(byteArrayOf(0, 1, 127, 9, 8), Files.readAllBytes(file))
                    val input = call("open", path(file), 0L) as Long
                    assertTrue(input >= 3L)
                    assertEquals(0L, call("close", input))
                }
                exercise("normal")
                for (target in targets.values) {
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target)
                }
                compiled = true
                exercise("compiled")
                compiled = false
                // An invalid State must fault before truncate/open or writing guest data.
                val untouched = directory.resolve("$backend-state.bin")
                Files.write(untouched, byteArrayOf(8, 7, 6))
                assertThrows(RuntimeFault::class.java) {
                    Calls.target(targets.getValue("open"), arrayOf(0L, path(untouched), 1L, 9L))
                }
                assertArrayEquals(byteArrayOf(8, 7, 6), Files.readAllBytes(untouched))
                val fd = call("open", path(untouched), 3L) as Long
                val buffer = byteArrayOf(4, 5)
                assertThrows(RuntimeFault::class.java) {
                    Calls.target(targets.getValue("read"), arrayOf(0L, fd, ManagedAddress.fromByteArray(buffer), 2L, 9L))
                }
                assertArrayEquals(byteArrayOf(4, 5), buffer)
                assertEquals(0L, call("seek", fd, 0L, 1L))
                assertEquals(0L, call("close", fd))
                released(language)
            } finally { context.leave() }
        }
    }

    @Test fun bothLoadersRejectBoundHeadsUnknownSymbolsAndMalformedProofs() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun load(mutate: (MutableList<Any?>) -> Unit): ExecutableProgram {
                    val module = ManagedFileFixtures.module(listOf("open"), mutate)
                    return if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                }
                for (id in listOf(null, "", 3L, "p0", "open")) assertThrows(RuntimeFault::class.java) {
                    load { (it[1] as MutableList<Any?>)[1] = id }
                }
                for (head in listOf(listOf("prim", "open"), listOf("var", "foreign-open"),
                    listOf("var", "foreign-open", mapOf("rep" to ManagedFileFixtures.scalar(null)))))
                    assertThrows(RuntimeFault::class.java) { load { it[1] = head } }
                for ((field, value) in listOf("convention" to "ccall", "convention" to "javascript",
                    "safety" to "unsafe", "arity" to 3.0, "suppliedArity" to 2L))
                    assertThrows(RuntimeFault::class.java) { load {
                        val descriptor = (it[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                        descriptor[field] = value
                    } }
                assertThrows(RuntimeFault::class.java) { load {
                    val descriptor = (it[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                    (descriptor["target"] as MutableMap<String, Any?>)["symbol"] = "thc_io_v1_unknown"
                } }
                assertThrows(RuntimeFault::class.java) { load { (it[3] as MutableList<Any?>)[0] = true } }
                assertThrows(RuntimeFault::class.java) { load {
                    val arguments = it[2] as List<MutableList<Any?>>
                    arguments[2][2] = mapOf("rep" to ManagedFileFixtures.scalar("IntRep"))
                } }
                assertThrows(RuntimeFault::class.java) { load {
                    val arguments = it[2] as List<MutableList<Any?>>
                    arguments[0][1] = "p1" // The occurrence claims AddrRep, the lexical binder proves IntRep.
                } }
                assertThrows(UnsupportedCore::class.java) { load { (it[6] as MutableMap<String, Any?>).remove("foreignCall") } }
            } finally { context.leave() }
        }
    }
}
