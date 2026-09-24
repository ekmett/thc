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
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class MaskFunctionNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/mask-functions")
    private val primitives = linkedMapOf("maskedFunction" to "maskAsyncExceptions#",
        "unmaskedFunction" to "unmaskAsyncExceptions#", "uninterruptibleFunction" to "maskUninterruptible#")
    private val offsets = primitives.keys.zip(listOf(121L, 101L, 212L)).toMap() +
        mapOf("lazyFunctions" to 0L, "bareMasks" to 10L)

    private fun source(stage: String) = Json.parse(
        File(directory, "$stage/core/MaskFunctionAudit.json").readText()) as Map<String, Any?>

    private fun nodes(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::nodes)
        is List<*> -> listOf(value) + value.flatMap(::nodes)
        else -> emptyList()
    }
    private fun calls(value: Any?, tag: String, name: String) = nodes(value).filter {
        it.firstOrNull() == "app" && (it[1] as? List<*>)?.let { head ->
            head.firstOrNull() == tag && head.getOrNull(1) == name
        } == true
    }
    private fun rows(): Map<String, List<Pair<Long, Long>>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(offsets.keys.toList(), manifest["entries"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, hash, "$kind/$path")
            }
        val rows = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }
        assertEquals(15, rows.size)
        return rows.groupBy { it[0] }.mapValues { (name, selected) ->
            val pairs = selected.map { it[1].toLong() to it[2].toLong() }
            assertEquals(listOf(-17L, 0L, 23L), pairs.map { it.first })
            pairs.forEach { (input, output) -> assertEquals(input + offsets.getValue(name), output) }
            pairs
        }
    }

    @Test fun genuinePartialMasksBecomeTypedLambdasWithSaturatedBodies() {
        rows()
        for (stage in listOf("pre", "post")) {
            val module = source(stage)
            for ((entry, primitive) in primitives) {
                val linked = CoreModules.reachable(module, entry, strictLink = true)
                val opaque = calls(linked, "var", "main:MaskFunctionAudit.applyLater").single()
                val lambda = (opaque[2] as List<List<Any?>>)[0]
                assertEquals("lam", lambda[0], "$stage/$entry passes the mask as a function")
                val binder = (lambda[1] as List<Map<String, Any?>>).single()
                assertEquals(CoreKind.VOID, CoreRepresentations.binder(binder).kind)
                assertEquals(emptyList<String>(), CoreRepresentations.binder(binder).primReps)
                val body = lambda[2] as List<Any?>
                assertEquals(primitive, (body[1] as List<*>)[1])
                val arguments = body[2] as List<List<Any?>>
                assertEquals(binder["id"], arguments[1][1], "The state parameter is applied later")
                assertEquals(CoreKind.CLOSURE, CoreRepresentations.expression(arguments[0]).kind)
                assertEquals(CoreRepresentations.expression(body), CoreRepresentations.lambdaResult(lambda))
                CoreSynchronousExceptions.validate(primitive, arguments.map(CoreRepresentations::expression),
                    body[3] as List<*>, CoreRepresentations.expression(body))
                // This is only evidence about the retained GHC dump; execution
                // and proofs above consume the structural export exclusively.
                assertTrue(Regex("applyLater\\s+\\(${Regex.escape(primitive)}\\s+@LiftedRep\\s+@Payload")
                    .containsMatchIn(module["sourceCore"] as String), "$stage/$entry original one-action Core")
            }
            val bare = CoreModules.reachable(module, "bareMasks", strictLink = true)
            val wrappers = calls(bare, "var", "main:MaskFunctionAudit.applyMask").map {
                (it[2] as List<List<Any?>>)[0]
            }
            assertEquals(3, wrappers.size)
            assertEquals(primitives.values.toSet(), wrappers.map {
                assertEquals("lam", it[0]); assertEquals(2, (it[1] as List<*>).size)
                ((it[2] as List<*>)[1] as List<*>)[1]
            }.toSet())
            for (entry in offsets.keys) {
                val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"], "$stage/$entry")
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertEquals(emptyList<Any>(), audit["issues"])
            }
        }
    }

    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)

    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val found = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val roots = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(node)
            roots.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }
                .mapNotNull { it.currentCallTarget as? RootCallTarget }
                .filter { it.rootNode is GuestRoot }.forEach(::visit)
            found += target
        }
        visit(entry)
        return found
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)

    @Test fun nativeFunctionsMatchFirstInstalledAstAndBytecodeEntries() {
        val oracle = rows()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for ((entry, selected) in oracle) Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false")
                .option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(source(stage), entry, strictLink = true) + ("instrument" to true)
                        val program = program(language, linked, backend)
                        val target = program.entryTarget(entry)
                        val label = "$stage/$backend/$entry"
                        fun check(row: Pair<Long, Long>) {
                            assertEquals(row.second, Calls.target(target, arrayOf(0L, row.first)), label)
                            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.rootNode), label)
                            assertEquals(0, language.handoffState.get().arguments.depth, label)
                            assertEquals(0, language.handoffState.get().results.depth, label)
                        }
                        repeat(3) { selected.forEach(::check) }
                        val active = targets(target)
                        assertTrue(active.size >= 2, "$label retains opaque guest calls")
                        active.forEach {
                            it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                            valid(it)
                        }
                        for (row in selected.asReversed() + selected) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row)
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= active.size,
                                "$label first installed call must enter all retained compiled roots")
                            active.forEach(::valid)
                        }
                        assertEquals("reject-at-load", program.diagnostics()["unsupportedPolicy"])
                        assertEquals(0L, (program.diagnostics()["unsupportedTraps"] as Number).toLong(), label)
                        assertEquals(0L, (program.diagnostics()["blackholes"] as Number).toLong(), label)
                    } finally { context.leave() }
                }
    }

    private fun rewrite(value: Any?, primitive: String, change: (List<Any?>) -> List<Any?>): Any? = when (value) {
        is Map<*, *> -> value.mapValues { rewrite(it.value, primitive, change) }
        is List<*> -> if (value.firstOrNull() == "app" &&
            (value[1] as? List<*>)?.take(2) == listOf("prim", primitive)) change(value)
            else value.map { rewrite(it, primitive, change) }
        else -> value
    }

    @Test fun malformedMaskArityAndProofsStillFailInBothPolicies() {
        for (stage in listOf("pre", "post")) for ((entry, primitive) in primitives) {
            val linked = CoreModules.reachable(source(stage), entry, strictLink = true)
            val app = calls(linked, "prim", primitive).single()
            val arguments = (app[2] as List<List<Any?>>).map(CoreRepresentations::expression)
            val flags = app[3] as List<*>
            val result = CoreRepresentations.expression(app)
            assertThrows(RuntimeFault::class.java) {
                CoreSynchronousExceptions.validate(primitive, arguments.take(1), listOf(true), result)
            }
            assertThrows(RuntimeFault::class.java) {
                CoreSynchronousExceptions.validate(primitive, arguments + arguments.last(), flags + false, result)
            }
            assertThrows(RuntimeFault::class.java) {
                CoreSynchronousExceptions.validate(primitive, arguments, flags,
                    result.copy(components = result.components!!.reversed()))
            }
            val mutations: List<(List<Any?>) -> List<Any?>> = listOf(
                { call -> call.toMutableList().also { it[2] = (it[2] as List<*>).take(1); it[3] = listOf(true) } },
                { call -> call.toMutableList().also { it[3] = listOf(true, true) } },
                { call -> call.toMutableList().also {
                    val metadata = it.last() as Map<String, Any?>
                    val proof = metadata["rep"] as Map<String, Any?>
                    it[it.lastIndex] = metadata + ("rep" to (proof + ("components" to (proof["components"] as List<*>).reversed())))
                } }
            )
            for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true))
                executionContext().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val input = linked + ("diagnosticUnsupported" to diagnostic)
                        program(language, input, backend)
                        for ((index, mutation) in mutations.withIndex()) {
                            val broken = rewrite(input, primitive, mutation) as Map<String, Any?>
                            assertThrows(RuntimeFault::class.java, {
                                program(language, broken, backend)
                            }, "$stage/$backend/$diagnostic/$primitive/mutation=$index")
                        }
                        // Missing aggregate evidence follows the existing general
                        // policy: strict loading rejects; diagnostic loading keeps
                        // an explicit trap that must fail when this action runs.
                        val missing = rewrite(input, primitive) { call -> call.toMutableList().also {
                            val metadata = it.last() as Map<String, Any?>
                            it[it.lastIndex] = metadata + ("rep" to ((metadata["rep"] as Map<String, Any?>) - "components"))
                        } } as Map<String, Any?>
                        if (!diagnostic) assertThrows(UnsupportedCore::class.java) { program(language, missing, backend) }
                        else {
                            val deferred = program(language, missing, backend)
                            assertTrue((deferred.diagnostics()["deferredUnsupported"] as List<*>).isNotEmpty())
                            assertEquals(0L, deferred.diagnostics()["unsupportedTraps"])
                            assertThrows(RuntimeFault::class.java) {
                                Calls.target(deferred.entryTarget(entry), arrayOf(0L, 0L))
                            }
                            assertEquals(1L, deferred.diagnostics()["unsupportedTraps"])
                        }
                    } finally { context.leave() }
                }
        }
    }
}
