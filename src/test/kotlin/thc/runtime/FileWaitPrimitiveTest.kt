// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Language
import thc.NativeIO
import java.nio.file.Files
import java.nio.file.Path

/** Original payload identity at the primitive boundary; installed-Core proof is separate. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class FileWaitPrimitiveTest {
    @TempDir lateinit var directory: Path
    private val fd = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"),
        "evaluated" to true)
    private val payload = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"),
        "evaluated" to false)
    private fun module(name: String): Map<String, Any?> {
        val bad = CoreFileWait.badFd
        val params = listOf(mapOf("id" to "descriptor", "lifted" to false, "rep" to fd),
            mapOf("id" to "s", "lifted" to false, "rep" to state))
        val call = listOf("app", listOf("prim", name),
            listOf(listOf("var", "descriptor", mapOf("rep" to fd)),
                listOf("var", "s", mapOf("rep" to state))),
            listOf(false, false), false, false, mapOf("rep" to state))
        val root = mapOf("id" to "wait", "name" to "wait", "arity" to 2, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", params, call,
                mapOf("rep" to closure, "resultRep" to state)))
        val original = mapOf("id" to bad, "name" to "blockedOnBadFD", "arity" to 0,
            "lifted" to true, "rep" to payload,
            "expr" to listOf("var", bad, mapOf("rep" to payload)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Test.FileWait",
            "bindings" to listOf(root, original), "constructors" to emptyList<Any>())
    }
    private fun valid(target: RootCallTarget) =
        target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }

    @Test fun firstInstalledWaitsKeepExactDescriptorAndLazyBadFdPayload() {
        NativeIO.createContext().use { context ->
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val files = Language.currentState().files
                val path = directory.resolve("ready")
                Files.writeString(path, "ready")
                val address = ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0))
                for (backend in listOf("ast", "bytecode")) for (name in listOf("waitRead#", "waitWrite#")) {
                    val opened = files.open(address, if (name == "waitWrite#") 2L else 0L)
                    assertTrue(opened >= 3L)
                    val linked = CoreModules.reachable(module(name), "wait", true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                        else BytecodeProgram(language, linked)
                    val target = program.entryTarget("wait")
                    fun call(descriptor: Long) = Calls.target(target, arrayOf(0L, descriptor, Unit))
                    repeat(6) { assertSame(Unit, call(opened)) }
                    compile(target)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertSame(Unit, call(opened))
                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    assertTrue(valid(target), "$backend/$name keeps its installed target")
                    val original = program.entryValue(CoreFileWait.badFd) as Thunk
                    for (bad in listOf(-1L, opened)) {
                        if (bad == opened) assertEquals(0L, files.close(opened))
                        val failure = assertThrows(GuestException::class.java) { call(bad) }
                        assertSame(original, failure.payload)
                        assertEquals(0, original.state, "RTS payload must remain lazy")
                    }
                    assertTrue(valid(target), "$backend/$name remains installed after bad FD")
                }
            } finally { context.leave() }
        }
    }
}
