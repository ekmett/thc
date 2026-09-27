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

/** Genuine safe directory CAPI wrapper, leased descriptors and staged stat images. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalFstatAtTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-fstatat"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = json("$prefix/$stage.json")
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun cstring(value: String) = ManagedAddress.fromByteArray(value.toByteArray() + byteArrayOf(0))
    private fun field(address: ManagedAddress, operation: OriginalStdioOp) = PosixStat.execute(operation, address, 0)
    private fun size() = field(ManagedAddress.nullAddress(), OriginalStdioOp.SIZEOF_STAT).toInt()
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
        OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(stage), "pathFstatAt")).single()
    private fun raw(call: List<Any?> = original()) = OriginalStdioChecks.rawModule(call, source("post"))
    private fun rawPath(scratch: Path, bytes: ByteArray): Path =
        Path.of(URI(scratch.toUri().toASCIIString() + bytes.joinToString("") { "%%%02X".format(it.toInt() and 255) }))
    private fun open(path: Path): Long = Language.currentState().stdio.open(cstring(path.toString()),
        StdioHostAbi.load().flagConstant(OriginalStdioOp.O_RDONLY), 0).also { assertTrue(it >= 3L) }
    private fun cwd(path: Path) {
        val env = Language.currentState().env
        env.setCurrentWorkingDirectory(env.getPublicTruffleFile(path.toString()))
    }
    private fun setup(): Path = Files.createTempDirectory(directory, "native-").also { scratch ->
        Files.setAttribute(scratch, "unix:mode", 448)
        Files.write(scratch.resolve("target"), ByteArray(32) { it.toByte() })
        Files.writeString(scratch.resolve("regular"), "unchanged")
        for (name in listOf("target", "regular")) Files.setAttribute(scratch.resolve(name), "unix:mode", 416)
        Files.createDirectory(scratch.resolve("sub")); Files.setAttribute(scratch.resolve("sub"), "unix:mode", 488)
        Files.createSymbolicLink(scratch.resolve("link"), Path.of("target"))
        Files.createSymbolicLink(scratch.resolve("dangling"), Path.of("absent-target"))
        Files.createSymbolicLink(rawPath(scratch, byteArrayOf(-1, 'n'.code.toByte())), Path.of("target"))
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
    private fun values(destination: ManagedAddress, target: Path): List<Long> {
        val mode = field(destination, OriginalStdioOp.ST_MODE)
        val isDirectory = PosixStat.execute(OriginalStdioOp.IS_DIR, ManagedAddress.nullAddress(), mode) != 0L
        return listOf(if (isDirectory) -1L else field(destination, OriginalStdioOp.ST_SIZE), mode and 65535L,
            if (field(destination, OriginalStdioOp.ST_DEV) == (Files.getAttribute(target, "unix:dev") as Number).toLong()) 1L else 0L,
            if (field(destination, OriginalStdioOp.ST_INO) == (Files.getAttribute(target, "unix:ino") as Number).toLong()) 1L else 0L)
    }

    @Test fun genuineNativeImagesMatchBothBackendsAndEveryFirstInstalledCall() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(listOf("pathFstatAt"), manifest["entries"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalFstatAtAudit.hs",
            "test/haskell-fixtures/OriginalFstatAtFixtures.hs", "src/main/c/stdio-abi-probe.c",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { listOf("$prefix/$it.json", "$prefix/$it-pathFstatAt.audit.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val at = oracle["at"] as Map<*, *>
        val abi = StdioHostAbi.load()
        assertEquals(at["AT_FDCWD"], abi.atFdcwd)
        assertEquals(at["AT_SYMLINK_NOFOLLOW"], abi.atSymlinkNoFollow)
        assertEquals(at["AT_EMPTY_PATH"], abi.atEmptyPath)
        val imageSize = size()
        assertEquals(oracle["size"], imageSize.toLong())
        val rows = oracle["rows"] as List<Map<String, Any?>>
        assertEquals(47, rows.size); assertEquals(47, rows.map { it["name"] }.toSet().size)
        assertEquals(setOf("cwd", "directory", "duplicate", "closed", "regular", "invalid"), rows.map { it["fdMode"] }.toSet())
        assertEquals(18, rows.count { (it["path"] as List<*>).isEmpty() })
        assertTrue(rows.any { it["absolute"] == true && it["fdMode"] == "invalid" })
        assertTrue(rows.any { it["flags"] == 0x1_0000_0000L })
        assertTrue(rows.any { it["fdOffset"] == 0x1_0000_0000L })
        for (stage in listOf("pre", "post")) {
            val audit = json("$prefix/$stage-pathFstatAt.audit.json")
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val linked = CoreModules.reachable(source(stage), "pathFstatAt") + ("instrument" to true)
            val evidence = ArrayCoreEvidence(linked, "pathFstatAt")
            assertEquals(1, evidence.bindings.size)
            assertEquals(1, evidence.guestLambdas(evidence.root["expr"]).size)
            assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
            assertEquals(OriginalStdioOp.FSTATAT, validate(original(stage)))
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                // Keep CWD and row fixtures on the same filesystem. Match the native
                // CWD's recorded permissions, while using a different actual directory.
                val anchor = Files.createTempDirectory(directory, "context-cwd-")
                Files.setAttribute(anchor, "unix:mode", (oracle["cwdMode"] as Number).toInt() and 4095)
                cwd(anchor)
                val executable = program(language, backend, linked)
                val entry = executable.entryTarget("pathFstatAt")
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
                            val bytes = ByteArray(imageSize + 16) { 90 }
                            val destination = ManagedAddress.fromByteArray(bytes).plus(8)
                            assertEquals(-1L, stdio.close(-1))
                            val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (compiled) valid(entry)
                            assertEquals(row["status"], Calls.target(entry,
                                arrayOf(0L, fd + (row["fdOffset"] as Long), rowAddress(scratch, row), destination, row["flags"])),
                                "$stage/$backend/" + row["name"])
                            assertEquals(row["errno"], stdio.errno(), row["name"].toString())
                            if (compiled) {
                                assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(entry)
                            }
                            assertEquals(true, row["guardsIntact"])
                            if (row["status"] == 0L) {
                                assertEquals(row["values"], values(destination, scratch.resolve("target")), row["name"].toString())
                                assertTrue((bytes.take(8) + bytes.drop(imageSize + 8)).all { it == 90.toByte() })
                            } else assertTrue(bytes.all { it == 90.toByte() })
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

    @Test fun exactDirectoryWrapperOwnerSafeAbiAndStoredProofsRemainRequired() {
        val call = original()
        assertEquals(OriginalStdioOp.FSTATAT, validate(call))
        val owner = (((call[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"]
        assertEquals(json("$prefix/manifest.json")["directoryUnit"], owner)
        fun label(suffix: String) = "ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zm${suffix}ZCSystemziDirectoryziInternalziPosixZCfstatat"
        fun declaration(unit: String, symbol: String): MutableList<Any?> = (copy(call) as MutableList<Any?>).also {
            val target = (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)
            target["unit"] = unit; target["symbol"] = symbol
        }
        for (suffix in listOf("inplace", "02fc", "deadbeef")) {
            assertEquals(OriginalStdioOp.FSTATAT, validate(declaration("directory-1.3.10.0-$suffix", label(suffix))))
            val other = if (suffix == "inplace") "02fc" else "inplace"
            assertThrows(RuntimeFault::class.java) { validate(declaration("directory-1.3.10.0-$suffix", label(other))) }
        }
        for ((key, value) in listOf("safety" to "unsafe", "safety" to "interruptible", "convention" to "ccall",
            "arity" to 4L, "suppliedArity" to 4L)) {
            val bad = copy(call) as MutableList<Any?>
            ((bad[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)[key] = value
            assertThrows(RuntimeFault::class.java) { validate(bad) }
        }
        for (unit in listOf("main", "ghc-internal", "unix-2.8.8.0-inplace", "directory-1.3.9.0-inplace",
            "directory-1.3.10.0-", "directory-1.3.10.0-ABCD", "directory-1.3.10.0-02fc:forged"))
            assertThrows(RuntimeFault::class.java) { validate(declaration(unit, label("02fc"))) }
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            for (symbol in listOf(label("02FC"), label("nothex"), label(""), label("02fc").replace("ZC1ZC", "ZC2ZC"),
                label("02fc").replace("InternalziPosix", "InternalziWindows"), label("02fc").replace("ZCfstatat", "ZCstat")))
                assertThrows(RuntimeFault::class.java) { program(language, backend, raw(declaration("directory-1.3.10.0-02fc", symbol))) }
            for (index in 0..4) assertThrows(RuntimeFault::class.java) {
                program(language, backend, OriginalStdioChecks.rawModule(call, source("post"), index))
            }
            val shadowed = raw(call)
            val copied = OriginalStdioChecks.foreignCalls(shadowed).single() as MutableList<Any?>
            copied[1] = listOf("var", "p0", (copied[1] as List<*>)[2])
            assertThrows(RuntimeFault::class.java) { program(language, backend, shadowed) }
            for (index in listOf(0, 3)) for (rep in listOf("IntRep", "Word32Rep", "Word64Rep")) {
                val bad = copy(call) as MutableList<Any?>
                val descriptor = (bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                ((descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[index])["primReps"] = listOf(rep)
                assertThrows(RuntimeFault::class.java) { program(language, backend, raw(bad)) }
            }
        } }
    }

    @Test fun stateCanonicalIntegersAndCompleteDestinationChecksPrecedeObservation() {
        val imageSize = size()
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val entry = program(language, backend, raw()).entryTarget("entry")
            val stdio = Language.currentState().stdio
            val bytes = ByteArray(imageSize + 16) { 90 }
            val destination = ManagedAddress.fromByteArray(bytes).plus(8)
            fun invoke(output: ManagedAddress = destination, path: ManagedAddress = cstring(directory.resolve("missing").toString()),
                       fd: Long = StdioHostAbi.load().atFdcwd, flags: Long = 0L, token: Any = Unit) =
                Calls.target(entry, arrayOf(0L, fd, path, output, flags, token))
            assertEquals(-1L, stdio.close(-1)); val prior = stdio.errno()
            assertThrows(RuntimeFault::class.java) { invoke(token = 9L) }
            for (number in listOf(Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1, 0x1_0000_0000L)) {
                assertThrows(RuntimeFault::class.java) { invoke(fd = number) }
                assertThrows(RuntimeFault::class.java) { invoke(flags = number) }
            }
            for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(ByteArray(imageSize - 1)),
                ManagedAddress.fromHex("00".repeat(imageSize)), destination.plus(9)))
                assertThrows(RuntimeFault::class.java) { invoke(output = bad) }
            val allocation = ManagedAllocation.mutable(imageSize.toLong() + 16, 8)
            allocation.writeAddressByteOffset(8, destination)
            assertThrows(RuntimeFault::class.java) { invoke(output = ManagedAddress.fromAllocation(allocation)) }
            assertSame(destination, allocation.readAddressByteOffset(8))
            for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromByteArray(byteArrayOf(65)),
                ManagedAddress.fromAllocation(allocation).plus(8)))
                assertThrows(RuntimeFault::class.java) { invoke(path = bad) }
            assertEquals(prior, stdio.errno()); assertTrue(bytes.all { it == 90.toByte() })
            assertEquals(-1L, invoke()); assertEquals(StdioHostAbi.load().error(1), stdio.errno())
            assertTrue(bytes.all { it == 90.toByte() }); released(language)
        } }
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> entered(context) { language ->
            val bytes = ByteArray(imageSize) { 90 }
            val entry = program(language, backend, raw()).entryTarget("entry")
            assertEquals(-1L, Calls.target(entry, arrayOf(0L, -999L, cstring(directory.toString()),
                ManagedAddress.fromByteArray(bytes), 0L, Unit)))
            assertEquals(StdioHostAbi.load().error(7), Language.currentState().stdio.errno())
            assertTrue(bytes.all { it == 90.toByte() })
        } }
    }

    @Test fun emptyPathUsesContextDirectoryAndFollowsItsSymlinkAnchorWithNoFollow() {
        val first = Files.createDirectory(directory.resolve("first"))
        val second = Files.createDirectory(directory.resolve("second"))
        val link = Files.createSymbolicLink(directory.resolve("anchor-link"), Path.of("second"))
        val abi = StdioHostAbi.load()
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val env = Language.currentState().env
            val initialCwd = env.currentWorkingDirectory.path
            val entry = program(language, backend, raw()).entryTarget("entry")
            for (anchor in listOf(first, second, link)) {
                cwd(anchor)
                for (flags in listOf(abi.atEmptyPath, abi.atEmptyPath or abi.atSymlinkNoFollow)) {
                    val bytes = ByteArray(size()) { 90 }
                    val destination = ManagedAddress.fromByteArray(bytes)
                    assertEquals(0L, Calls.target(entry, arrayOf(0L, abi.atFdcwd, cstring(""), destination, flags, Unit)))
                    assertEquals((Files.getAttribute(anchor, "unix:ino") as Number).toLong(), field(destination, OriginalStdioOp.ST_INO))
                    assertEquals((Files.getAttribute(anchor, "unix:mode") as Number).toLong(), field(destination, OriginalStdioOp.ST_MODE))
                    assertEquals(1L, PosixStat.execute(OriginalStdioOp.IS_DIR, ManagedAddress.nullAddress(), field(destination, OriginalStdioOp.ST_MODE)))
                }
                val unchanged = ByteArray(size()) { 90 }
                assertEquals(-1L, Calls.target(entry, arrayOf(0L, abi.atFdcwd, cstring(""), ManagedAddress.fromByteArray(unchanged), 0L, Unit)))
                assertEquals(abi.error(1), Language.currentState().stdio.errno()); assertTrue(unchanged.all { it == 90.toByte() })
            }
            context().use { other -> entered(other) { assertEquals(initialCwd, Language.currentState().env.currentWorkingDirectory.path) } }
            released(language)
        } }
    }

    @Test fun aliasedNativeImagesAndDirectoryDuplicatesKeepContextAndLifetimeChecks() {
        val imageSize = size()
        for (backend in listOf("ast", "bytecode")) context().use { first -> entered(first) { language ->
            val scratch = setup()
            val state = Language.currentState()
            val fd = open(scratch)
            val duplicate = state.stdio.duplicate(fd)
            assertTrue(duplicate >= 3); assertNotEquals(fd, duplicate)
            assertEquals(0L, state.stdio.close(fd))
            val entry = program(language, backend, raw()).entryTarget("entry")
            val base = state.nativeAllocations.malloc(imageSize.toLong() + 16)
            val alias = base.plus(8)
            try {
                base.fill(imageSize.toLong() + 16, 90)
                // Path bytes and the output share an allocation. The input snapshot
                // must finish before successful stat publication overwrites it.
                ManagedAddress.fromByteArray(byteArrayOf(116, 97, 114, 103, 101, 116, 0)).copyNonOverlappingTo(alias, 7)
                context().use { second -> entered(second) { other ->
                    val foreign = program(other, backend, raw()).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(foreign, arrayOf(0L, duplicate, cstring("target"), alias, 0L, Unit))
                    }
                    val untouched = ByteArray(imageSize) { 90 }
                    assertEquals(-1L, Calls.target(foreign, arrayOf(0L, duplicate, cstring("target"),
                        ManagedAddress.fromByteArray(untouched), 0L, Unit)))
                    assertEquals(StdioHostAbi.load().error(4), Language.currentState().stdio.errno())
                    assertTrue(untouched.all { it == 90.toByte() }); released(other)
                } }
                assertEquals(0L, Calls.target(entry, arrayOf(0L, duplicate, alias, alias, 0L, Unit)))
                assertEquals(32L, field(alias, OriginalStdioOp.ST_SIZE))
                for (index in 0L..7L) {
                    assertEquals(90L, base.readWord8(index)); assertEquals(90L, base.readWord8(imageSize + 8L + index))
                }
                assertEquals(0L, state.stdio.close(duplicate))
                base.fill(imageSize.toLong() + 16, 90)
                assertEquals(-1L, Calls.target(entry, arrayOf(0L, duplicate, cstring("target"), alias, 0L, Unit)))
                for (index in 0L until imageSize + 16L) assertEquals(90L, base.readWord8(index))
            } finally { state.nativeAllocations.free(base) }
            assertThrows(RuntimeFault::class.java) {
                Calls.target(entry, arrayOf(0L, StdioHostAbi.load().atFdcwd, cstring(scratch.resolve("target").toString()), alias, 0L, Unit))
            }
            released(language)
        } }
    }

    @Test fun completedSafeStatResumesWithTheSavedImageWithoutReplay() {
        context().use { context -> entered(context) { language ->
            val state = Language.currentState()
            val identity = state.threads.enterCurrent()
            try {
                val file = Files.writeString(directory.resolve("completed"), "before")
                val destination = ManagedAddress.fromByteArray(ByteArray(size()) { 90 })
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
                        pending = state.threads.send(identity, "after fstatat")
                        return Unit
                    }
                }
                val body = OriginalStdioExpression(OriginalStdioOp.FSTATAT,
                    arrayOf(value(StdioHostAbi.load().atFdcwd), value(cstring(file.toString())), value(destination), value(0L), enqueue), proof)
                val ast = FunctionRoot(language, layout.build(), "fstatat completion", null,
                    intArrayOf(), intArrayOf(), intArrayOf(), body, Metrics(false),
                    tuple = shape, tupleSlots = slots, enableAsync = true)
                val saved = checkNotNull(savedGuestContinuation(Calls.target(ast.callTarget, arrayOf(0L))))
                assertSame(pending, saved.asyncRequest()); assertEquals(6L, field(destination, OriginalStdioOp.ST_SIZE))
                pending!!.acknowledge(); Files.writeString(file, "changed after native stat")
                val completed = saved.continueWith(Unit)
                assertEquals(0L, shape.layout.getLong(ownedTupleResult(completed, shape), 0))
                assertEquals(6L, field(destination, OriginalStdioOp.ST_SIZE)); assertEquals(1, evaluated)
                released(language)

                val request = state.threads.send(identity, "after bytecode fstatat")
                val target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    val result = b.createLocal("completed CInt", "primitive")
                    val token = b.createLocal("pending request", "object")
                    b.beginOriginalFstatAt(result)
                    b.emitLoadConstant(StdioHostAbi.load().atFdcwd); b.emitLoadConstant(cstring(file.toString()))
                    b.emitLoadConstant(destination); b.emitLoadConstant(0L); b.emitLoadConstant(Unit); b.endOriginalFstatAt()
                    b.beginIfThen(); b.emitPollAsync(token)
                    b.beginYield(); b.emitLoadLocal(token); b.endYield(); b.endIfThen()
                    b.beginReturn(); b.emitLoadLocal(result); b.endReturn()
                    b.endRoot()
                }.getNode(0).callTarget
                val suspended = Calls.target(target, arrayOf(0L)) as ContinuationResult
                assertSame(request, suspended.result)
                val savedSize = Files.size(file)
                assertEquals(savedSize, field(destination, OriginalStdioOp.ST_SIZE))
                request.acknowledge(); Files.writeString(file, "x")
                assertEquals(0L, suspended.continueWith(Unit))
                assertEquals(savedSize, field(destination, OriginalStdioOp.ST_SIZE))
                val lowered = BytecodeProgram(language, raw(), true).entryTarget("entry").rootNode as BytecodeRoot
                val names = lowered.bytecodeNode.instructions.map { it.name }
                val effect = names.indexOfFirst { it.contains("OriginalFstatAt", ignoreCase = true) }
                assertTrue(effect >= 0, names.toString())
                assertTrue(names.drop(effect + 1).any { it.contains("PollAsync", ignoreCase = true) }, names.toString())
                released(language)
            } finally { state.threads.leaveCurrent() }
        } }
    }
}
