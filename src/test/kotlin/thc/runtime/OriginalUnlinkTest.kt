// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.ContextProfile
import thc.Language
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalUnlinkTest {
    @TempDir lateinit var directory: Path
    private fun address(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + byteArrayOf(0))
    private fun path(value: Path) = address(value.toString().toByteArray())
    private fun <T> entered(context: Context, body: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
    }

    @Test fun bothInstalledBackendsRemoveNamesAndPreserveOpenedStorage() {
        for (backend in listOf("ast", "bytecode"))
            NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST).use { context -> entered(context) { language ->
                val module = OriginalStdioFixtures.module(listOf("unlink"))
                val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                val target = program.entryTarget("unlink")
                val state = Language.currentState()
                var compiled = false
                fun remove(name: ManagedAddress): Long {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result = Calls.target(target, arrayOf(0L, name, Unit)) as Long
                    assertEquals(before + if (compiled) 1 else 0,
                        (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    if (compiled) assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    assertNull(handoff.pending)
                    return result
                }
                fun exercise() {
                    val root = Files.createTempDirectory(directory, "case-")
                    val file = Files.writeString(root.resolve("file"), "payload")
                    val open = state.stdio.open(path(file), 0, 0)
                    assertTrue(open >= 0)
                    assertEquals(0L, remove(path(file)))
                    assertFalse(Files.exists(file))
                    val bytes = ByteArray(7)
                    assertEquals(7L, state.stdio.read(open, ManagedAddress.fromByteArray(bytes), 7))
                    assertEquals("payload", bytes.toString(Charsets.UTF_8))
                    assertEquals(0L, state.stdio.close(open))
                    assertEquals(-1L, remove(path(file)))
                    val missing = state.stdio.errno()
                    assertEquals(StdioHostAbi.load().error(1), missing)
                    val targetFile = Files.writeString(root.resolve("target"), "untouched")
                    val link = Files.createSymbolicLink(root.resolve("link"), targetFile)
                    assertEquals(0L, remove(path(link)))
                    assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
                    assertEquals("untouched", Files.readString(targetFile))
                    assertEquals(missing, state.stdio.errno(), "Success preserves sticky errno")
                    assertEquals(-1L, remove(path(root)))
                    assertTrue(Files.isDirectory(root))
                    assertEquals(-1L, remove(address(byteArrayOf())))
                    assertEquals(missing, state.stdio.errno())
                    // Raw invalid-UTF8 bytes must survive both relative paths
                    // and CWD resolution. Create with the existing raw open.
                    val relative = Path.of(state.env.currentWorkingDirectory.path).relativize(root).toString()
                    val raw = address((relative + "/raw-").toByteArray() + byteArrayOf(0xff.toByte()))
                    val flags = state.stdio.flagConstant(OriginalStdioOp.O_CREAT) or
                        state.stdio.flagConstant(OriginalStdioOp.O_WRONLY)
                    val rawFd = state.stdio.open(raw, flags, 384)
                    assertTrue(rawFd >= 0); assertEquals(0L, state.stdio.close(rawFd))
                    assertEquals(0L, remove(raw)); assertEquals(-1L, remove(raw))
                }
                exercise()
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                compiled = true
                exercise()
                val survivor = Files.writeString(directory.resolve("survivor-$backend"), "safe")
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, path(survivor), 7L)) }
                assertTrue(Files.exists(survivor), "Invalid State must reject before deletion")
                assertThrows(RuntimeFault::class.java) { state.stdio.unlink(ManagedAddress.fromByteArray(byteArrayOf(65))) }
            } }
    }

    @Test fun arbitraryEmbeddingFilesystemDoesNotGainNativeUnlinkAuthority() {
        val survivor = Files.writeString(directory.resolve("protected"), "safe")
        for (io in listOf(IOAccess.NONE, IOAccess.ALL)) Context.newBuilder("thc")
            .allowNativeAccess(true).allowIO(io).build().use { context -> entered(context) {
                assertEquals(-1L, Language.currentState().stdio.unlink(path(survivor)))
                assertTrue(Files.exists(survivor))
            } }
    }
}
