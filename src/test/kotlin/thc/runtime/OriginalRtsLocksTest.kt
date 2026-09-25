// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

class OriginalRtsLocksTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-rts-locks"
    private fun json(file: String) = Json.parse(File(root, "$prefix/$file").readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
    }
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
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
    private fun operation(call: List<Any?>): OriginalStdioOp? {
        val metadata = call[6] as Map<*, *>
        return CoreOriginalStdio.validate(metadata, (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
            call[3] as List<*>, metadata["rep"])
    }
    private fun symbol(call: List<Any?>) = (((call[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"]

    @Test fun genuineNativeClaimsMatchPrePostBothBackendsAndFirstInstalledCalls() {
        val manifest = json("manifest.json")
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(true, manifest["originalIdsChecked"]); assertEquals(true, manifest["typeEqualityChecked"])
        assertEquals(false, manifest["installedArtifactsHashed"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalRtsLocksAudit.hs",
            "test/haskell-fixtures/OriginalRtsLocksFixtures.hs", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/pre.json", "$prefix/post.json",
            "$prefix/declarations.json", "$prefix/template-pre.json", "$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> listOf("originalLock", "originalUnlock").map { "$prefix/$stage-$it.audit.json" } }, "$prefix/")
        val declarations = json("declarations.json")
        assertEquals(false, declarations["completeModule"])
        val originalCalls = OriginalStdioChecks.foreignCalls(declarations["projection"])
        val rows = json("oracle.json")["rows"] as List<List<Any?>>
        assertEquals(listOf("unknown", "reader", "repeat-reader", "second-reader", "writer-conflict", "release-one",
            "still-locked", "release-repeat", "release-other", "writer", "reader-conflict", "writer-repeat",
            "release-writer", "release-missing"), rows.map { it[0] })
        assertEquals(listOf(1L,0L,0L,0L,-1L,0L,-1L,0L,0L,0L,-1L,-1L,0L,1L), rows.map { it[4] })
        for (row in rows) { assertEquals(StdioHostAbi.load().error(4), row[5]); assertEquals(row[5], row[6]) }
        assertEquals(listOf(-1L,Long.MIN_VALUE,-1L), rows[1][2]); assertEquals(Int.MIN_VALUE.toLong(), rows[9][3])
        for (stage in listOf("pre", "post")) {
            val module = json("$stage.json")
            val calls = OriginalStdioChecks.foreignCalls(module)
            assertEquals(setOf(OriginalStdioOp.LOCK, OriginalStdioOp.UNLOCK), calls.map(::operation).toSet())
            assertEquals(2, calls.size)
            for (call in calls) {
                // Private Names have no module: the serializer supplies its current
                // owner. The entire original occurrence/type/Unique must survive.
                val head = call[1] as List<*>
                val adaptedPrefix = "main:OriginalRtsLocksAudit."
                val originalPrefix = "ghc-internal:GHC.Internal.IO.FD."
                assertTrue((head[1] as String).startsWith(adaptedPrefix))
                assertTrue(originalCalls.any { source ->
                    val original = source[1] as List<*>
                    symbol(source) == symbol(call) && original[0] == head[0] && original[2] == head[2] &&
                        (original[1] as String).startsWith(originalPrefix) &&
                        (original[1] as String).removePrefix(originalPrefix) == (head[1] as String).removePrefix(adaptedPrefix) &&
                        (source[6] as Map<*, *>)["foreignCall"] == (call[6] as Map<*, *>)["foreignCall"]
                }, "exact original private FCallId occurrence, type, Unique and descriptor")
            }
            for (entry in listOf("originalLock", "originalUnlock")) {
                val audit = json("$stage-$entry.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val program = load(language, backend, module + ("instrument" to true))
                val entries = listOf("originalLock", "originalUnlock").associateWith(program::entryTarget)
                var active = emptyMap<String, List<RootCallTarget>>()
                fun exercise(compiled: Boolean) {
                    for (row in rows) {
                        val locking = row[1] as Boolean
                        val name = if (locking) "originalLock" else "originalUnlock"
                        val words = row[2] as List<Long>
                        val args = if (locking) words + (row[3] as Long) else words.take(1)
                        val state = Language.currentState()
                        assertEquals(-1L, state.stdio.close(-1))
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (compiled) active.values.flatten().forEach(::valid)
                        assertEquals(row[4], Calls.target(entries.getValue(name), arrayOf(0L, *args.toTypedArray())), "$stage/$backend/${row[0]}")
                        assertEquals(row[6], state.stdio.errno())
                        if (compiled) {
                            assertEquals(before + active.getValue(name).size, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            for ((key, entry) in entries) assertEquals(active.getValue(key), targets(entry))
                            active.values.flatten().forEach(::valid)
                        }
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    }
                }
                exercise(false)
                active = entries.mapValues { targets(it.value) }
                for ((name, targets) in active) {
                    assertEquals(2, targets.size, "Original entry plus runRW lambda: $name")
                    val binding = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
                    assertEquals(2, OriginalStdioChecks.nodes(binding["expr"]).count { it.firstOrNull() == "lam" })
                    targets.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                }
                exercise(true)
            } }
        }
    }

    @Test fun stateRawProofsAndMalformedHeadsRejectBeforeTableMutation() {
        val source = json("pre.json")
        for (call in OriginalStdioChecks.foreignCalls(source)) {
            val op = operation(call)!!
            val count = op.arguments.size
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val target = load(language, backend, OriginalStdioChecks.rawModule(call, source)).entryTarget("entry")
                val args = if (op == OriginalStdioOp.LOCK) arrayOf<Any?>(0L, -1L, Long.MIN_VALUE, -1L, 0L, Unit)
                    else arrayOf<Any?>(0L, -1L, Unit)
                val state = Language.currentState(); val locks = state.rtsFileLocks
                assertEquals(0L, locks.lock(-1, Long.MIN_VALUE, -1, 0))
                args[args.lastIndex] = 9L
                assertThrows(RuntimeFault::class.java) { Calls.target(target, args) }
                assertEquals(0L, locks.unlock(-1)); assertEquals(1L, locks.unlock(-1))
                if (op == OriginalStdioOp.LOCK) {
                    args[args.lastIndex] = Unit; args[4] = 1L shl 32
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, args) }
                    assertEquals(1L, locks.unlock(-1))
                }
                for (index in 0 until count) assertThrows(RuntimeFault::class.java) {
                    load(language, backend, OriginalStdioChecks.rawModule(call, source, index))
                }
                for (head in listOf(listOf("var",17L), listOf("var","entry",mapOf("rep" to OriginalStdioFixtures.closure())))) {
                    val module = OriginalStdioChecks.rawModule(call, source)
                    (OriginalStdioChecks.foreignCalls(module).single() as MutableList<Any?>)[1] = head
                    assertThrows(RuntimeFault::class.java) { load(language, backend, module) }
                }
            } }
            fun reject(change: (MutableList<Any?>, MutableMap<String, Any?>) -> Unit) {
                val changed = Json.parse(Json.stringify(call)) as MutableList<Any?>
                val descriptor = (changed[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                change(changed, descriptor); assertThrows(RuntimeFault::class.java) { operation(changed) }
            }
            for (key in listOf("schema", "arity", "suppliedArity")) for (bad in listOf(null,true,"2",2.0,0L,1L shl 32))
                reject { _, d -> d[key] = bad }
            for ((key,bad) in listOf("unit" to "base", "kind" to "dynamic", "isFunction" to false, "extra" to 1L))
                reject { _,d -> (d["target"] as MutableMap<String,Any?>)[key] = bad }
            for ((key,bad) in listOf("convention" to "capi", "safety" to "safe", "extra" to 1L)) reject { _,d -> d[key]=bad }
            for (index in 0 until count) {
                reject { c,_ -> (c[3] as MutableList<Any?>)[index] = true }
                reject { _,d -> (d["argumentReps"] as List<MutableMap<String,Any?>>)[index]["primReps"] = listOf("IntRep") }
                reject { _,d -> (d["argumentReps"] as List<MutableMap<String,Any?>>)[index]["aggregate"] = "unboxed-tuple" }
            }
            reject { _,d -> (d["resultRep"] as MutableMap<String,Any?>)["primReps"] = listOf("IntRep") }
        }
    }

    @Test fun tableIsContextLocalAndIndependentOfDescriptorCloseDupAndErrno() {
        lateinit var retired: RtsFileLocks
        context().use { first -> entered(first) {
            val state = Language.currentState(); val locks = state.rtsFileLocks; retired = locks
            assertEquals(-1L, state.stdio.close(-1)); val errno = state.stdio.errno(); val kind = state.files.errorKind()
            assertEquals(0L, locks.lock(1, -1, Long.MIN_VALUE, 0))
            assertEquals(0L, locks.lock(1, -1, Long.MIN_VALUE, 0))
            assertThrows(RuntimeFault::class.java) { locks.lock(1, -1, 7, 0) }
            assertEquals(0L, locks.lock(7, -1, 7, 1)); assertEquals(0L, locks.unlock(7))
            assertEquals(0L, locks.unlock(1)); assertEquals(-1L, locks.lock(7, -1, Long.MIN_VALUE, 1))
            val alias = state.files.duplicate(1); assertTrue(alias >= 3)
            assertEquals(1L, locks.unlock(alias), "dup does not clone RTS claims")
            assertEquals(0L, state.files.close(1))
            assertEquals(-1L, locks.lock(7, -1, Long.MIN_VALUE, 1), "raw close must not release RTS keys")
            assertEquals(0L, locks.lock(2, 8, 9, 1))
            assertEquals(2L, state.files.duplicateTo(alias, 2))
            assertEquals(-1L, locks.lock(7, 8, 9, 0), "dup2 replacement must not release the target's RTS key")
            assertEquals(0L, locks.unlock(2)); assertEquals(0L, state.files.close(2))
            assertEquals(alias, state.files.duplicateTo(alias, alias)); assertEquals(0L, state.files.close(alias))
            assertEquals(0L, locks.unlock(1)); assertEquals(1L, locks.unlock(1))
            assertEquals(errno, state.stdio.errno()); assertEquals(kind, state.files.errorKind())
            assertEquals(0L, locks.lock(0, -1, Long.MIN_VALUE, -2))
            context().use { second -> entered(second) {
                assertEquals(1L, Language.currentState().rtsFileLocks.unlock(0))
                assertEquals(0L, Language.currentState().rtsFileLocks.lock(0,-1,Long.MIN_VALUE,1))
            } }
        } }
        assertThrows(RuntimeFault::class.java) { retired.lock(0,0,0,0) }
        assertThrows(RuntimeFault::class.java) { retired.unlock(0) }
        retired.dispose() // Idempotent teardown.
    }
}
