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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ProxyVoidTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val void = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val empty = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
        "aggregate" to "unboxed-tuple", "components" to emptyList<Any>())
    private fun module(stage: String) = Json.parse(File(root,
        "build/proxy-void/$stage/core/ProxyVoidAudit.json").readText()) as Map<String, Any?>
    private fun bindings(module: Map<String, Any?>) = module["bindings"] as List<Map<String, Any?>>
    private fun lambda(module: Map<String, Any?>, name: String) = bindings(module).single { it["name"] == name }["expr"] as List<*>
    private fun nodes(value: Any?): List<List<*>> = when (value) {
        is List<*> -> listOf(value) + value.flatMap(::nodes)
        is Map<*, *> -> value.values.flatMap(::nodes)
        else -> emptyList()
    }
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "installed")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry)
        return targets
    }
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    @BeforeEach fun evidence() {
        val manifest = Json.parse(File(root, "build/proxy-void/manifest.json").readText()) as Map<*, *>
        assertEquals(25L, (manifest["nativeRows"] as Number).toLong())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale proxy evidence: $path")
        }
        for (stage in listOf("pre", "post")) {
            val audit = Json.parse(File(root, "build/proxy-void/$stage/audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"])
            for (key in listOf("missingGlobals", "issues", "runtimeExternals")) assertEquals(emptyList<Any>(), audit[key])
        }
    }

    @Test fun nativeValuesInlined() = native(true)
    @Test fun nativeValuesAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = File(root, "build/proxy-void/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for ((name, selected) in rows) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, CoreModules.reachable(module(stage), name) + ("instrument" to true), backend)
                val function = context.asValue(EntryValue(program, name, 1))
                val host = program.hostEntryTarget(1)
                val original = program.entryTarget(name)
                val label = "$stage/$backend/$name/inlining=$inlining"
                fun check(row: List<String>) = assertEquals(row[2].toLong(), function.execute(row[1].toLong()).asLong(), label)
                selected.forEach(::check)
                val targets = activeTargets(host)
                assertEquals(if (name == "direct") 5 else 4, targets.size, "$label adopted roots including host")
                targets.filter { it !== host }.forEach(::compile)
                assertTrue(function.invokeMember("compile").asBoolean())
                for (row in selected.asReversed()) {
                    val before = count(program)
                    check(row)
                    assertEquals(before + if (name == "direct") 4 else 3, count(program), "$label first/subsequent compiled entry")
                    assertEquals(targets, activeTargets(host), "$label stable adopted targets")
                    valid(original, label); targets.forEach { valid(it, label) }; released(language)
                }
                if (name == "effect") {
                    val thrown = assertThrows(GuestException::class.java) {
                        Calls.target(host, arrayOf(program.entryValue(name), arrayOf(-1L)))
                    }
                    assertEquals("main:ProxyVoidAudit.Failure", (thrown.payload as DataValue).layout.id)
                    released(language)
                }
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            } finally { context.leave() }
        }
    }

    @Test fun ghcKeepsProxyScalarVoidDistinctFromEmptyTuple() {
        for (stage in listOf("pre", "post")) {
            val module = module(stage)
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep", module["boundary"])
            assertTrue((module["sourceCore"] as String).contains("proxy#"))
            val token = lambda(module, "token")
            assertEquals(empty, ((token[1] as List<Map<*, *>>).single())["rep"])
            assertEquals(void, (token[3] as Map<*, *>)["resultRep"])
            assertEquals("void", (token[2] as List<*>)[0])
            val lowered = nodes(module["bindings"]).filter { it.firstOrNull() == "void" }
            assertEquals(4, lowered.size, "token, through's type application, pair, required")
            lowered.forEach { assertEquals(void, (it.last() as Map<*, *>)["rep"]) }
            assertFalse(nodes(module["bindings"]).any { it.take(2) == listOf("var", "ghc-internal:GHC.Internal.Prim.proxy#") })
            val result = (lambda(module, "pair")[3] as Map<*, *>)["resultRep"] as Map<*, *>
            val components = result["components"] as List<*>
            assertEquals(void, components[0]); assertEquals(empty, components[1])
            assertEquals(listOf("IntRep"), result["primReps"])
            assertFalse(TupleShape.compatible(CoreRepresentations.parse(components[0]), CoreRepresentations.parse(components[1])))
            val required = bindings(module).single { it["name"] == "required" }["id"]
            assertTrue(nodes(lambda(module, "effect")).any { it.take(2) == listOf("var", required) }, "Retain the effectful producer")
        }
    }

    @Test fun sameWidthEmptyTupleCannotReplaceProxyField() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module(stage)
                val proof = (lambda(module, "pair")[3] as Map<*, *>)["resultRep"] as Map<*, *>
                (proof["components"] as MutableList<Any?>)[0] = empty
                assertThrows(RuntimeFault::class.java) { program(language, CoreModules.reachable(module, "tupleCase"), backend) }
            } finally { context.leave() }
        }
    }
}
