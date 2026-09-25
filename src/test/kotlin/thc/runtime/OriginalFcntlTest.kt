// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.ContextProfile
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalFcntlTest {
    @TempDir lateinit var directory: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-fcntl"
    private val names = listOf("originalAppend", "originalCreat", "originalNoctty", "originalNonblock", "originalRdonly",
        "originalRdwr", "originalWronly", "originalGetfl", "originalSetfl", "originalGetFlags", "originalSetFlags")
    private val constants = listOf(OriginalStdioOp.O_APPEND, OriginalStdioOp.O_CREAT, OriginalStdioOp.O_NOCTTY,
        OriginalStdioOp.O_NONBLOCK, OriginalStdioOp.O_RDONLY, OriginalStdioOp.O_RDWR, OriginalStdioOp.O_WRONLY,
        OriginalStdioOp.F_GETFL, OriginalStdioOp.F_SETFL)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun source(stage: String) = CoreModules.merge(listOf("OriginalFcntlAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun context() = NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun program(backend: String, language: Language, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)

    @Test fun genuineOriginalCallsMatchNativeFlagsAndAliasesInBothCompiledBackends() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(true, manifest["supported"]); assertEquals("linux", manifest["platform"])
        assertEquals(names, manifest["entries"]); assertEquals(true, manifest["strictAccepted"])
        assertEquals(false, manifest["runtimeVerified"]); assertEquals(4L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalFcntlAudit.hs",
            "compiler/test-fixtures/OriginalFcntlNative.hs", "test/haskell-fixtures/OriginalStdioFixtures.hs",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/native/oracle") +
            listOf("pre", "post").flatMap { stage -> names.map { "$prefix/$stage/$it.audit.json" } +
                listOf("$prefix/$stage/core/OriginalFcntlAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val expected = oracle["constants"] as List<Long>
        val rows = oracle["rows"] as List<List<Long>>
        val abi = StdioHostAbi.load()
        assertEquals(constants.map(abi::flagConstant), expected)
        assertEquals(listOf(-1L, abi.error(4)), oracle["invalid"])
        for (stage in listOf("pre", "post")) {
            val module = source(stage)
            assertEquals((constants + listOf(OriginalStdioOp.FCNTL_READ, OriginalStdioOp.FCNTL_WRITE)).toSet(),
                OriginalStdioChecks.foreignCalls(module).map { validate(it)!! }.toSet())
            for (name in names) {
                val audit = json("$prefix/$stage/$name.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val executable = program(backend, language, module + ("instrument" to true))
                    val entries = names.associateWith(executable::entryTarget)
                    val state = Language.currentState()
                    val file = directory.resolve("$stage-$backend")
                    Files.writeString(file, "abc")
                    val path = ManagedAddress.fromByteArray(file.toString().toByteArray() + byteArrayOf(0))
                    val fd = state.stdio.open(path, abi.flagConstant(OriginalStdioOp.O_RDWR), 0)
                    assertTrue(fd >= 3)
                    val alias = state.stdio.duplicate(fd)
                    var compiled = false
                    var installed = emptyList<RootCallTarget>()
                    fun call(name: String, vararg arguments: Any?): Long {
                        val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val result = Calls.target(entries.getValue(name), arrayOf(0L, *arguments)) as Long
                        if (compiled) {
                            assertTrue((executable.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            installed.forEach(::valid)
                        }
                        released(language)
                        return result
                    }
                    fun exercise() {
                        names.take(9).forEachIndexed { index, name -> assertEquals(expected[index] + 7L, call(name, 7L)) }
                        for (row in rows) {
                            assertEquals(row[1], call("originalSetFlags", fd, row[0]))
                            assertEquals(row[2], call("originalGetFlags", alias), "status belongs to the shared open description")
                        }
                        assertEquals(-1L, call("originalGetFlags", -1L))
                        assertEquals(abi.error(4), state.stdio.errno())
                        assertEquals(0L, call("originalSetFlags", alias, rows.first()[0]))
                        assertEquals(abi.error(4), state.stdio.errno(), "success preserves sticky errno")
                    }
                    exercise()
                    installed = entries.values.flatMap(::targets).distinct()
                    installed.forEach(::compile); compiled = true
                    exercise() // Each target's first invocation after installation is checked.
                    assertEquals(0L, state.stdio.close(fd))
                    assertEquals(rows.first()[2], call("originalGetFlags", alias))
                    assertEquals(-1L, call("originalGetFlags", fd))
                    assertEquals(0L, state.stdio.close(alias))
                    assertEquals("abc", Files.readString(file))
                    assertEquals(0L, (executable.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    @Test fun exactWidthsStateAndCommandBoundariesRejectBeforeEffects() {
        val module = source("post")
        val calls = OriginalStdioChecks.foreignCalls(module).distinctBy { validate(it) }
        for (call in calls) {
            val original = validate(call)!!
            for ((key, replacement) in listOf("safety" to "safe", "convention" to "prim")) {
                val changed = Json.parse(Json.stringify(call)) as MutableList<Any?>
                ((changed[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>)[key] = replacement
                assertThrows(RuntimeFault::class.java) { validate(changed) }
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val raw = OriginalStdioChecks.rawModule(call, module)
                    val executable = program(backend, language, raw)
                    val target = executable.entryTarget("entry")
                    val state = Language.currentState()
                    val abi = StdioHostAbi.load()
                    val path = directory.resolve("raw-$backend-${original.name}")
                    Files.writeString(path, "abc")
                    val fd = state.stdio.open(ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0)),
                        abi.flagConstant(OriginalStdioOp.O_RDWR), 0)
                    val before = state.stdio.fcntl(fd, abi.flagConstant(OriginalStdioOp.F_GETFL), 0, false)
                    val args: Array<Any?> = when (original) {
                        OriginalStdioOp.FCNTL_READ -> arrayOf(fd, abi.flagConstant(OriginalStdioOp.F_GETFL), Unit)
                        OriginalStdioOp.FCNTL_WRITE -> arrayOf(fd, abi.flagConstant(OriginalStdioOp.F_SETFL),
                            before or abi.flagConstant(OriginalStdioOp.O_APPEND), Unit)
                        else -> arrayOf(Unit)
                    }
                    Calls.target(target, arrayOf(0L, *args))
                    assertEquals(0L, state.stdio.fcntl(fd, abi.flagConstant(OriginalStdioOp.F_SETFL), before, true))
                    compile(target)
                    val count = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result = Calls.target(target, arrayOf(0L, *args))
                    assertEquals(count + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong())
                    valid(target); released(language)
                    assertEquals(when (original) {
                        OriginalStdioOp.FCNTL_READ -> before
                        OriginalStdioOp.FCNTL_WRITE -> 0L
                        else -> abi.flagConstant(original)
                    }, result)
                    val stable = state.stdio.fcntl(fd, abi.flagConstant(OriginalStdioOp.F_GETFL), 0, false)
                    val badState = args.copyOf().also { it[it.lastIndex] = 9L }
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *badState)) }
                    if (original.fcntl) {
                        val badWidth = args.copyOf().also { it[0] = 1L shl 32 }
                        assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *badWidth)) }
                        val wrongCommand = args.copyOf().also { it[1] = -7L }
                        assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *wrongCommand)) }
                        for (index in original.arguments.indices) {
                            assertThrows(RuntimeFault::class.java) {
                                program(backend, language, OriginalStdioChecks.rawModule(call, module, index))
                            }
                        }
                    }
                    assertEquals(stable, state.stdio.fcntl(fd, abi.flagConstant(OriginalStdioOp.F_GETFL), 0, false))
                    assertEquals("abc", Files.readString(path)); released(language)
                    assertEquals(0L, state.stdio.close(fd))
                } finally { context.leave() }
            }
        }
    }

    @Test fun appendChangesAffectActualWritesAndPrivateExtensionPolicy() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val state = Language.currentState()
                val abi = StdioHostAbi.load()
                val path = directory.resolve("append")
                Files.writeString(path, "abc")
                val fd = state.stdio.open(ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0)),
                    abi.flagConstant(OriginalStdioOp.O_RDWR), 0)
                val get = abi.flagConstant(OriginalStdioOp.F_GETFL)
                val set = abi.flagConstant(OriginalStdioOp.F_SETFL)
                assertEquals(-1L, state.stdio.fcntl(1, get, 0, false), "ungranted embedding stream is not a host fd")
                assertEquals(abi.error(7), state.stdio.errno())
                val original = state.stdio.fcntl(fd, get, 0, false)
                assertEquals(0L, state.stdio.fcntl(fd, set, original or abi.flagConstant(OriginalStdioOp.O_APPEND), true))
                assertEquals(1L, state.stdio.write(fd, ManagedAddress.fromByteArray(byteArrayOf(90)), 1))
                assertEquals("abcZ", Files.readString(path))
                assertEquals(-1L, state.files.setSize(fd, 8))
                assertEquals(0L, state.stdio.fcntl(fd, set, original, true))
                assertEquals(0L, state.files.setSize(fd, 8))
                assertEquals(8L, Files.size(path))
                assertEquals(0L, state.stdio.close(fd))
            } finally { context.leave() }
        }
    }
}
