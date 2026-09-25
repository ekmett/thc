// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalSavedTermiosTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-termios"
    private val names = listOf("originalGetSavedTermios", "originalSetSavedTermios")
    private val symbols = listOf("__hscore_get_saved_termios", "__hscore_set_saved_termios")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalSavedTermiosAudit", "THC.InterfaceClosure")
        .map { json("$prefix/saved/$stage/core/$it.json") })
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }.filter { it.rootNode is GuestRoot }.forEach(::visit)
            result.add(target)
        }
        visit(entry); return result
    }

    @Test fun originalNativeRowsMatchInterpretedAndFirstInstalledAstAndBytecode() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(true, manifest["supported"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalSavedTermiosAudit.hs", "compiler/test-fixtures/OriginalSavedTermiosNative.hs",
            "test/haskell-fixtures/OriginalTermiosFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/saved/oracle.json") +
            listOf("pre", "post").flatMap { stage -> names.map { "$prefix/saved/$stage/$it.audit.json" } }, "$prefix/")
        val rows = json("$prefix/saved/oracle.json")["rows"] as List<List<Long>>
        val expected = listOf(Int.MIN_VALUE.toLong(), -1L, 0L, 1L, 2L, 3L, Int.MAX_VALUE.toLong()).flatMap { fd ->
            listOf(-1L, 0L, 3L, 8L).map { offset ->
                val next = if (offset == -1L) 5L else 8L - offset
                listOf(fd, offset, if (fd in 0L..2L) offset else -1L, next, if (fd in 0L..2L) next else -1L, 1L)
            }
        }
        assertEquals(expected, rows)
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            assertEquals(symbols.toSet(), OriginalStdioChecks.foreignCalls(source).map { validate(it)!!.symbol }.toSet())
            for ((index, name) in names.withIndex()) {
                val audit = json("$prefix/saved/$stage/$name.audit.json")
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["issues"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertEquals(listOf(symbols[index]), (audit["foreignCalls"] as List<Map<*, *>>).map { it["symbol"] })
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(source, names, true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val get = program.entryTarget(names[0]); val set = program.entryTarget(names[1])
                    var compiled = false
                    fun call(target: RootCallTarget, vararg values: Any?): Any? {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val value = Calls.target(target, arrayOf(0L, *values))
                        if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        assertEquals(0, language.handoffState.get().arguments.depth)
                        assertEquals(0, language.handoffState.get().results.depth)
                        return value
                    }
                    fun exercise() {
                        for (row in rows) {
                            val bytes = ByteArray(8)
                            val base = ManagedAddress.fromByteArray(bytes)
                            fun pointer(offset: Long) = if (offset == -1L) ManagedAddress.nullAddress() else base.plus(offset)
                            for (fd in 0L..2L) assertEquals(0L, call(set, fd, ManagedAddress.nullAddress()))
                            val first = pointer(row[1]); val next = pointer(row[3])
                            assertEquals(0L, call(set, row[0], first))
                            assertSame(if (row[2] == -1L) ManagedAddress.nullAddress() else first, call(get, row[0]))
                            assertEquals(0L, call(set, row[0], next))
                            assertSame(if (row[4] == -1L) ManagedAddress.nullAddress() else next, call(get, row[0]))
                            for (other in (0L..2L).filter { it != row[0] }) assertSame(ManagedAddress.nullAddress(), call(get, other))
                            assertEquals(0L, call(set, row[0], ManagedAddress.nullAddress()))
                            assertSame(ManagedAddress.nullAddress(), call(get, row[0]))
                        }
                    }
                    exercise()
                    val active = (targets(get) + targets(set)).distinct()
                    val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    active.forEach { target ->
                        cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, cls.getMethod("isValidLastTier").invoke(target))
                    }
                    for (target in listOf(get, set)) Truffle.getRuntime().let { runtime ->
                        runtime.javaClass.getMethod("bypassedInstalledCode", cls).invoke(runtime, target)
                    }
                    compiled = true; exercise()
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                } finally { context.leave() }
            }
        }
    }

    @Test fun stateAndCanonicalDescriptorValidationPrecedeSlotEffects() {
        val source = module("pre")
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun load(raw: Map<String, Any?>): ExecutableProgram = if (backend == "ast") Program(language, raw) else BytecodeProgram(language, raw)
                val saved = Language.currentState().savedTermios
                val original = ManagedAddress.fromByteArray(ByteArray(8)).plus(3)
                saved.set(1, original)
                for (call in OriginalStdioChecks.foreignCalls(source)) {
                    val operation = validate(call)!!
                    val target = load(OriginalStdioChecks.rawModule(call, source)).entryTarget("entry")
                    val arguments = if (operation == OriginalStdioOp.SET_SAVED_TERMIOS) arrayOf<Any?>(1L, ManagedAddress.nullAddress()) else arrayOf<Any?>(1L)
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *arguments, 9L)) }
                    assertSame(original, saved.get(1))
                    for (bad in listOf(Long.MIN_VALUE, 2147483648L)) {
                        arguments[0] = bad
                        assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *arguments, Unit)) }
                        assertSame(original, saved.get(1))
                    }
                    for (index in operation.arguments.indices)
                        assertThrows(RuntimeFault::class.java) { load(OriginalStdioChecks.rawModule(call, source, index)) }
                }
            } finally { context.leave() }
        }
    }

    @Test fun onlyExactOriginalForeignDescriptorsAreRecognized() {
        val calls = OriginalStdioChecks.foreignCalls(module("pre"))
        assertEquals(2, calls.size)
        for (original in calls) {
            fun reject(edit: (MutableMap<String, Any?>) -> Unit) {
                val call = Json.parse(Json.stringify(original)) as MutableList<Any?>
                edit((call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)
                assertThrows(RuntimeFault::class.java) { validate(call) }
            }
            reject { it["safety"] = "safe" }; reject { it["convention"] = "capi" }
            reject { it["arity"] = 1L }; reject { it["suppliedArity"] = 0L }
            reject { (it["target"] as MutableMap<String, Any?>)["unit"] = "base" }
            reject { (it["target"] as MutableMap<String, Any?>)["isFunction"] = false }
            reject { (it["argumentReps"] as List<MutableMap<String, Any?>>)[0]["primReps"] = listOf("IntRep") }
            reject { (it["resultRep"] as MutableMap<String, Any?>)["components"] = emptyList<Any>() }
        }
    }
}
