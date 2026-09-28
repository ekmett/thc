// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.ContextProfile
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Genuine directory FCallId, authenticated guest descriptors and completed safe effects. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalUnlinkAtTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-unlinkat"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun cstring(value: String) = ManagedAddress.fromByteArray(value.toByteArray() + byteArrayOf(0))
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context() = NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST)
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun valid(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun original(stage: String = "post") =
        OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(stage), "pathUnlinkAt")).single()
    private fun raw(call: List<Any?> = original()) = OriginalStdioChecks.rawModule(call, source("post"))
    private fun rawPath(scratch: Path, bytes: ByteArray): Path =
        Path.of(URI(scratch.toUri().toASCIIString() + bytes.joinToString("") { "%%%02X".format(it.toInt() and 255) }))
    private fun readOnly() = StdioHostAbi.load().flagConstant(OriginalStdioOp.O_RDONLY)
    private fun open(path: Path): Long = Language.currentState().stdio.open(cstring(path.toString()), readOnly(), 0).also {
        assertTrue(it >= 3L)
    }


    private val remainingKeys = listOf("file", "other", "regular", "link", "dangling", "empty", "nonempty", "child", "sub", "raw")
    private fun setup(): Path = Files.createTempDirectory(directory, "native-").also { scratch ->
        for (name in listOf("file", "other", "regular")) Files.writeString(scratch.resolve(name), "unchanged")
        Files.createSymbolicLink(scratch.resolve("link"), Path.of("file"))
        Files.createSymbolicLink(scratch.resolve("dangling"), Path.of("missing-target"))
        for (name in listOf("empty", "nonempty", "sub")) Files.createDirectory(scratch.resolve(name))
        Files.writeString(scratch.resolve("nonempty/child"), "unchanged")
        Files.writeString(rawPath(scratch, byteArrayOf(-1, 'n'.code.toByte())), "unchanged")
    }
    private fun objectPath(scratch: Path, key: String): Path = when (key) {
        "child" -> scratch.resolve("nonempty/child")
        "raw" -> rawPath(scratch, byteArrayOf(-1, 'n'.code.toByte()))
        else -> scratch.resolve(key)
    }
    private fun rowAddress(scratch: Path, row: Map<String, Any?>): ManagedAddress {
        val bytes = (row["path"] as List<Long>).map { it.toByte() }.toByteArray()
        val anchor = if (bytes.isEmpty()) byteArrayOf() else when {
            row["absolute"] == true -> (scratch.toString() + "/").toByteArray()
            row["fdMode"] == "cwd" ->
                (Path.of(Language.currentState().env.currentWorkingDirectory.path).relativize(scratch).toString() + "/").toByteArray()
            else -> byteArrayOf()
        }
        return ManagedAddress.fromByteArray(anchor + bytes + byteArrayOf(0))
    }

    @Test fun originalNativeUnlinkAtMatchesBothBackendsAndEveryFirstInstalledCall() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(listOf("pathUnlinkAt"), manifest["entries"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalUnlinkAtAudit.hs",
            "test/haskell-fixtures/OriginalUnlinkAtFixtures.hs", "src/main/c/stdio-abi-probe.c",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { listOf("$prefix/$it.json", "$prefix/$it-pathUnlinkAt.audit.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val at = oracle["at"] as Map<*, *>
        val abi = StdioHostAbi.load()
        assertEquals(at["AT_FDCWD"], abi.atFdcwd); assertEquals(at["AT_REMOVEDIR"], abi.atRemoveDir)
        val rows = oracle["rows"] as List<Map<String, Any?>>
        assertEquals(34, rows.size)
        assertEquals(34, rows.map { it["name"] }.toSet().size)
        assertEquals(setOf("cwd", "directory", "duplicate", "closed", "regular", "invalid"), rows.map { it["fdMode"] }.toSet())
        assertTrue(rows.any { it["absolute"] == true && it["fdMode"] == "invalid" })
        assertTrue(rows.any { it["flags"] == abi.atRemoveDir })
        assertTrue(rows.any { it["flags"] == 0x1_0000_0000L })
        assertTrue(rows.any { it["fdOffset"] == 0x1_0000_0000L })
        for (stage in listOf("pre", "post")) {
            val audit = json("$prefix/$stage-pathUnlinkAt.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(source(stage), "pathUnlinkAt") + ("instrument" to true)
            val evidence = ArrayCoreEvidence(linked, "pathUnlinkAt")
            assertEquals(1, evidence.bindings.size)
            assertEquals(1, evidence.guestLambdas(evidence.root["expr"]).size)
            assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
            assertEquals(OriginalStdioOp.UNLINKAT, validate(original(stage)))
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget("pathUnlinkAt")
                val stdio = Language.currentState().stdio
                fun exercise(compiled: Boolean) {
                    for (row in rows) {
                        val scratch = setup()
                        var owned: Long? = null
                        val fd = when (row["fdMode"]) {
                            "cwd" -> abi.atFdcwd
                            "invalid" -> -1L
                            "directory" -> open(scratch).also { owned = it }
                            "regular" -> open(scratch.resolve("regular")).also { owned = it }
                            "closed" -> open(scratch).also { assertEquals(0L, stdio.close(it)) }
                            "duplicate" -> {
                                val first = open(scratch)
                                stdio.duplicate(first).also {
                                    assertTrue(it >= 3); owned = it
                                    assertEquals(0L, stdio.close(first))
                                }
                            }
                            else -> error("Unknown native descriptor setup")
                        }
                        try {
                            assertEquals(-1L, stdio.close(-1))
                            val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (compiled) valid(entry)
                            assertEquals(row["status"], Calls.target(entry,
                                arrayOf(0L, fd + (row["fdOffset"] as Long), rowAddress(scratch, row), row["flags"])),
                                "$stage/$backend/" + row["name"])
                            assertEquals(row["errno"], stdio.errno(), row["name"].toString())
                            if (compiled) {
                                assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            val remaining = remainingKeys.filter { Files.exists(objectPath(scratch, it), NOFOLLOW_LINKS) }
                            assertEquals(row["remaining"], remaining, row["name"].toString())
                            for (key in remaining.intersect(setOf("file", "other", "regular", "child", "raw")))
                                assertEquals("unchanged", Files.readString(objectPath(scratch, key)))
                            if ("link" in remaining) assertEquals(Path.of("file"), Files.readSymbolicLink(scratch.resolve("link")))
                            if ("dangling" in remaining) assertEquals(Path.of("missing-target"), Files.readSymbolicLink(scratch.resolve("dangling")))
                            released(language)
                        } finally { owned?.let { assertEquals(0L, stdio.close(it)) } }
                    }
                }
                exercise(false)
                entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true)
                valid(entry); exercise(true)
                assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } }
        }
    }


    @Test fun exactDirectoryOwnerSafeAbiAndStoredProofsRemainRequired() {
        val call = original()
        assertEquals(OriginalStdioOp.UNLINKAT, validate(call))
        val owner = (((call[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"]
        assertEquals(json("$prefix/manifest.json")["directoryUnit"], owner)
        for ((key, value) in listOf("safety" to "unsafe", "safety" to "interruptible", "convention" to "capi",
            "arity" to 3L, "suppliedArity" to 3L)) {
            val bad = copy(call) as MutableList<Any?>
            ((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
            assertThrows(RuntimeFault::class.java) { validate(bad) }
        }
        for (unit in listOf("main", "ghc-internal", "unix-2.8.8.0-inplace", "directory-1.3.9.0-inplace",
            "directory-1.3.10.0-", "directory-1.3.10.0-ABCD", "directory-1.3.10.0-02fc:forged")) {
            val bad = copy(call) as MutableList<Any?>
            (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
            assertThrows(RuntimeFault::class.java) { validate(bad) }
        }
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            for (index in 0..3) assertThrows(RuntimeFault::class.java) {
                program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
            }
            val shadowed = raw(call)
            val body = (shadowed["bindings"] as List<Map<String, Any?>>).single()["expr"]
            val copied = OriginalStdioChecks.foreignCalls(body).single() as MutableList<Any?>
            copied[1] = listOf("var", "p0", (copied[1] as List<*>)[2])
            assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
            for (index in listOf(0, 2)) for (rep in listOf("IntRep", "Word32Rep", "Word64Rep")) {
                val bad = copy(call) as MutableList<Any?>
                val descriptor = (bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                ((descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[index])["primReps"] = listOf(rep)
                assertThrows(RuntimeFault::class.java) { program(language, backend, raw(bad)) }
            }
        } }
    }

    @Test fun stateCanonicalIntegersAndPathOwnershipPrecedeDestructiveEffects() {
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val entry = program(language, backend, raw()).entryTarget("entry")
            val stdio = Language.currentState().stdio
            val file = Files.createTempFile(directory, "preserved-", ".txt")
            fun invoke(address: ManagedAddress = cstring(file.toString()), fd: Long = StdioHostAbi.load().atFdcwd,
                       flags: Long = 0L, state: Any = Unit) =
                Calls.target(entry, arrayOf(0L, fd, address, flags, state))
            assertEquals(-1L, stdio.close(-1)); val prior = stdio.errno()
            assertThrows(RuntimeFault::class.java) { invoke(state = 9L) }
            for (number in listOf(Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1, 0x1_0000_0000L)) {
                assertThrows(RuntimeFault::class.java) { invoke(fd = number) }
                assertThrows(RuntimeFault::class.java) { invoke(flags = number) }
            }
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.nullAddress()) }
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.fromByteArray(byteArrayOf(65))) }
            val allocation = ManagedAllocation.mutable(16, 8)
            allocation.writeAddressByteOffset(0, ManagedAddress.nullAddress())
            assertThrows(RuntimeFault::class.java) { invoke(ManagedAddress.fromAllocation(allocation)) }
            assertTrue(Files.exists(file)); assertEquals(prior, stdio.errno())
            released(language)
        } }
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            val file = Files.createTempFile(directory, "denied-", ".txt")
            val entry = program(language, backend, raw()).entryTarget("entry")
            assertEquals(-1L, Calls.target(entry, arrayOf(0L, -999L, cstring(file.toString()), 0L, Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
            assertTrue(Files.exists(file))
        } }
    }

    @Test fun directoryDuplicatesAndNativePathAliasesRetainOwnershipAndLifetime() {
        for (backend in listOf("ast", "bytecode")) context().use { first -> entered(first) { language ->
            val scratch = Files.createTempDirectory(directory, "lease-")
            val file = Files.writeString(scratch.resolve("target"), "original")
            val state = Language.currentState()
            val fd = open(scratch)
            val duplicate = state.stdio.duplicate(fd)
            assertTrue(duplicate >= 3L); assertNotEquals(fd, duplicate)
            assertEquals(0L, state.stdio.close(fd))
            val entry = program(language, backend, raw()).entryTarget("entry")
            val bytes = byteArrayOf(116, 97, 114, 103, 101, 116, 0)
            val base = state.nativeAllocations.malloc(bytes.size.toLong() + 8)
            val alias = base.plus(8)
            try {
                ManagedAddress.fromByteArray(bytes).copyNonOverlappingTo(alias, bytes.size.toLong())
                context().use { second -> entered(second) { other ->
                    val foreign = program(other, backend, raw()).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) { Calls.target(foreign, arrayOf(0L, duplicate, alias, 0L, Unit)) }
                    assertEquals(-1L, Calls.target(foreign, arrayOf(0L, duplicate, cstring("target"), 0L, Unit)))
                    assertEquals(StdioHostAbi.load().error(4), Language.currentState().stdio.errno())
                    released(other)
                } }
                assertEquals(-1L, Calls.target(entry, arrayOf(0L, fd, alias, 0L, Unit)))
                assertTrue(Files.exists(file))
                val prior = state.stdio.errno()
                assertEquals(0L, Calls.target(entry, arrayOf(0L, duplicate, alias, 0L, Unit)))
                assertFalse(Files.exists(file)); assertEquals(prior, state.stdio.errno())
                assertEquals(0L, state.stdio.close(duplicate))
                Files.writeString(file, "replacement")
                assertEquals(-1L, Calls.target(entry, arrayOf(0L, duplicate, alias, 0L, Unit)))
                assertTrue(Files.exists(file))
            } finally { state.nativeAllocations.free(base) }
            assertThrows(RuntimeFault::class.java) { Calls.target(entry, arrayOf(0L, duplicate, alias, 0L, Unit)) }
            released(language)
        } }
    }

    @Test fun completedSafeEffectsResumeAfterTheSavedResultWithoutReplay() {
        context().use { context -> entered(context) { language ->
            val state = Language.currentState()
            val identity = state.threads.enterCurrent()
            try {
                val file = Files.createTempFile(directory, "completed-", ".txt")
                val proof = CoreRepresentations.parse((original()[6] as Map<*, *>)["rep"])
                val layout = FrameLayout()
                val slots = intArrayOf(layout.bind("completed CInt"))
                val shape = TupleShape(proof, language)
                var pending: AsyncRequest? = null
                var evaluated = 0
                fun value(value: Any) = object : Expr() { override fun execute(frame: VirtualFrame): Any = value }
                val enqueue = object : Expr() {
                    override fun execute(frame: VirtualFrame): Any {
                        evaluated++
                        pending = state.threads.send(identity, "after unlinkat")
                        return Unit
                    }
                }
                val body = OriginalStdioExpression(OriginalStdioOp.UNLINKAT,
                    arrayOf(value(StdioHostAbi.load().atFdcwd), value(cstring(file.toString())), value(0L), enqueue), proof)
                val ast = FunctionRoot(language, layout.build(), "unlinkat completion", null,
                    intArrayOf(), intArrayOf(), intArrayOf(), body,
                    Metrics(false), emptyArray(), body.representation, body.coreSourceLocation,
                    booleanArrayOf(), null, shape, slots,
                    null, true, emptyArray(), false,
                    FunctionRootRole.FUNCTION, false)
                val saved = checkNotNull(SavedGuestContinuationKt.savedGuestContinuation(Calls.target(ast.callTarget, arrayOf(0L))))
                assertSame(pending, saved.asyncRequest()); assertFalse(Files.exists(file))
                pending!!.acknowledge()
                Files.writeString(file, "replacement")
                val completed = saved.continueWith(Unit)
                assertEquals(0L, shape.layout.getLong(ownedTupleResult(completed, shape), 0))
                assertEquals("replacement", Files.readString(file)); assertEquals(1, evaluated)
                released(language)

                // The bytecode operation writes its destination before any yield.
                val request = state.threads.send(identity, "after bytecode unlinkat")
                val target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    val result = b.createLocal("completed CInt", "primitive")
                    val token = b.createLocal("pending request", "object")
                    b.beginOriginalUnlinkAt(result)
                    b.emitLoadConstant(StdioHostAbi.load().atFdcwd); b.emitLoadConstant(cstring(file.toString()))
                    b.emitLoadConstant(0L); b.emitLoadConstant(Unit); b.endOriginalUnlinkAt()
                    b.beginIfThen(); b.emitPollAsync(token)
                    b.beginYield(); b.emitLoadLocal(token); b.endYield(); b.endIfThen()
                    b.beginReturn(); b.emitLoadLocal(result); b.endReturn()
                    b.endRoot()
                }.getNode(0).callTarget
                val suspended = Calls.target(target, arrayOf(0L)) as ContinuationResult
                assertSame(request, suspended.result); assertFalse(Files.exists(file))
                request.acknowledge(); Files.writeString(file, "second replacement")
                assertEquals(0L, suspended.continueWith(Unit))
                assertEquals("second replacement", Files.readString(file))
                // Inspect the actual original-call lowering as well as the instruction.
                val lowered = BytecodeProgram(language, raw(), true).entryTarget("entry").rootNode as BytecodeRoot
                val names = lowered.bytecodeNode.instructions.map { it.name }
                val effect = names.indexOfFirst { it.contains("OriginalUnlinkAt", ignoreCase = true) }
                assertTrue(effect >= 0, names.toString())
                assertTrue(names.drop(effect + 1).any { it.contains("PollAsync", ignoreCase = true) }, names.toString())
                released(language)
            } finally { state.threads.leaveCurrent() }
        } }
    }
}
