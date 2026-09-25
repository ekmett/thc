// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Genuine selected GHC Core, original RTS error payload, and first installed guest waits. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class FileWaitFullCoreTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val fixture = File(root, "build/file-wait")
    private val entries = mapOf("waitReadRoot" to false, "waitWriteRoot" to true)
    private fun document(path: String) = Json.parse(File(fixture, path).readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target), target.rootNode.name)
    }

    @Test fun originalWaitsMatchNativeAndRetainTheExactLazyBadFdPayload() {
        val manifest = document("manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries.keys.toList(), manifest["entries"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/FileWaitAudit.hs", "compiler/test-fixtures/FileWaitNative.hs",
            "test/haskell-fixtures/FileWaitFixtures.hs", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/file-wait/oracle.txt", "build/file-wait/pre/core/FileWaitAudit.json",
            "build/file-wait/post/core/FileWaitAudit.json"))
        assertEquals("read-ready\nwrite-ready\noriginal-bad-fd\n",
            File(root, manifest["oracle"] as String).readText())
        val originals = ArrayList<Map<String, Any?>>()
        val targetLayout = CorePackageManifest.visitModules(
            File(root, manifest["packageManifest"] as String).path) { module, _ -> originals.add(module) }.targetLayout
        assertNotNull(targetLayout)
        val stages = manifest["stages"] as Map<String, String>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, path) in stages) {
            val combined = CoreModules.merge(originals +
                listOf(Json.parse(File(root, path).readText()) as Map<String, Any?>)) +
                ("targetLayout" to targetLayout!!)
            val closure = document("$stage/core/THC.InterfaceClosure.json")
            val discovered = (closure["bindings"] as List<Map<String, Any?>>).map { it["id"] } +
                (closure["missingDefinitions"] as List<Map<String, Any?>>).map { it["id"] }
            assertTrue(CoreFileWait.badFd in discovered, "$stage must discover the original implicit payload")
            for ((entry, writing) in entries) {
                val audit = document("$stage/$entry-audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertTrue((audit["primitives"] as List<Map<String, Any?>>).any {
                    it["name"] == if (writing) "waitWrite#" else "waitRead#" })
                for (backend in listOf("ast", "bytecode")) NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST).use { context ->
                    context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val files = Language.currentState().files
                        val file = directory.resolve("$stage-$backend-$entry")
                        Files.writeString(file, "ready")
                        val address = ManagedAddress.fromByteArray(file.toString().toByteArray() + byteArrayOf(0))
                        val descriptor = files.open(address, if (writing) 2L else 0L)
                        assertTrue(descriptor >= 3L)
                        val linked = CoreModules.reachable(combined, entry, true) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                            else BytecodeProgram(language, linked)
                        val target = program.entryTarget(entry)
                        fun invoke(fd: Long) = Calls.target(target, arrayOf(0L, fd, Unit))
                        repeat(6) { assertSame(Unit, invoke(descriptor)) }
                        compile(target)
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertSame(Unit, invoke(descriptor))
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        assertTrue(valid(target))
                        val original = program.entryValue(CoreFileWait.badFd)
                        for (bad in listOf(-1L, descriptor)) {
                            if (bad == descriptor) assertEquals(0L, files.close(descriptor))
                            val failure = assertThrows(GuestException::class.java) { invoke(bad) }
                            assertSame(original, failure.payload)
                            if (original is Thunk) assertEquals(0, original.state, "original CAF remains lazy")
                        }
                        assertTrue(valid(target), "$stage/$backend/$entry remains installed after bad FD")
                    } finally { context.leave() }
                }
            }
        }
    }
}
