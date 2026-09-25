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
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** Original imported declarations and native observations, not replacement FFIs. */
@EnabledOnOs(OS.LINUX, disabledReason = "Only original Linux stat scalar declarations have native/Core proof")
class OriginalPosixStatTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-posix-stat"
    private val names = listOf("originalStatSize", "originalStatDev", "originalStatIno", "originalStatMode", "originalStatLength", "originalStatTypes")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalPosixStatAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun copy(value: Any?): Any? = Json.parse(Json.stringify(value))
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun context() = Context.newBuilder("thc").allowIO(IOAccess.NONE).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    @Test fun realNativeImagesAndModePredicatesMatchBothBackendsOnFirstInstalledCalls() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(1L, manifest["schema"])
        assertEquals("linux", manifest["platform"])
        assertEquals(true, manifest["supported"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalPosixStatAudit.hs",
            "compiler/test-fixtures/OriginalPosixStatNative.hs", "test/haskell-fixtures/OriginalPosixStatFixtures.hs",
            "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> names.map { "$prefix/$stage/$it.audit.json" } +
                listOf("$prefix/$stage/core/OriginalPosixStatAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val images = oracle["images"] as List<List<List<Number>>>
        val modes = oracle["modes"] as List<List<Number>>
        assertEquals(6, images.size)
        assertEquals((0L..65535L).toList() + listOf(-1L, 2147483647L, 2147483648L, 4294967295L), modes.map { it[0].toLong() })
        for (stage in listOf("pre", "post")) {
            val module = module(stage)
            for (name in names) {
                val audit = json("$prefix/$stage/$name.audit.json")
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (name in names) {
                        val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val entry = program.entryTarget(name)
                        var active = emptyList<RootCallTarget>()
                        fun invoke(argument: Any?, expected: Long, compiled: Boolean) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(expected, Calls.target(entry, arrayOf(0L, argument)), "$stage/$backend/$name/$argument")
                            if (compiled) {
                                assertEquals(before + active.size, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                assertEquals(active, targets(entry))
                                active.forEach(::valid)
                            }
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.results.retainedReferences())
                        }
                        fun exercise(compiled: Boolean) {
                            if (name == "originalStatSize") for (extra in listOf(-7L, 0L, 13L))
                                invoke(extra, (oracle["size"] as Number).toLong() + extra, compiled)
                            else if (name == "originalStatTypes") for (row in modes)
                                invoke(row[0].toLong(), row[1].toLong(), compiled)
                            else {
                                val field = names.indexOf(name) - 1
                                val bytes = ByteArray((oracle["size"] as Number).toInt() + 7)
                                val address = ManagedAddress.fromByteArray(bytes).plus(7)
                                for (row in images) {
                                    assertEquals(bytes.size - 7, row[0].size)
                                    row[0].forEachIndexed { index, value -> bytes[index + 7] = value.toByte() }
                                    invoke(address, row[1][field].toLong(), compiled)
                                }
                            }
                        }
                        exercise(false)
                        active = targets(entry)
                        // The exported root and each immediate runRW lambda are
                        // real instrumented GuestRoots, all installed exactly once.
                        val binding = (linked["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
                        val lambdaCount = OriginalStdioChecks.nodes(binding["expr"]).count { it.firstOrNull() == "lam" }
                        assertEquals(lambdaCount, active.size, "$stage/$backend/$name target shape")
                        for (target in active) {
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            valid(target)
                        }
                        exercise(true)
                    }
                } finally { context.leave() }
            }
        }
    }

    private fun calls() = OriginalStdioChecks.foreignCalls(module("pre")).filter {
        ((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"].let { target ->
            val symbol = (target as Map<*, *>)["symbol"]
            symbol == "__hscore_sizeof_stat" || (symbol as String).startsWith("__hscore_st_") || "ZCSzuIS" in symbol
        }
    }.distinctBy { ((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] }
    private fun validate(call: List<Any?>): OriginalStdioOp? {
        val metadata = call[6] as Map<*, *>
        return CoreOriginalStdio.validate(metadata, (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
            call[3] as List<*>, metadata["rep"])
    }

    private fun rawModule(original: List<Any?>, storedMutation: Int? = null): Map<String, Any?> {
        val call = copy(original) as MutableList<Any?>
        val descriptor = (call[6] as Map<*, *>)["foreignCall"] as Map<*, *>
        val declared = descriptor["argumentReps"] as List<Map<String, Any?>>
        val reps = declared.map { it + ("evaluated" to true) }
        val output = descriptor["resultRep"] as Map<String, Any?>
        val result = (output["components"] as List<Map<String, Any?>>)[1]
        call[1] = listOf("var", "foreign", mapOf("rep" to OriginalStdioFixtures.closure()))
        call[2] = reps.mapIndexed { index, rep -> listOf("var", "p$index", mapOf("rep" to rep)) }
        val formals = reps.mapIndexed { index, rep -> mapOf("id" to "p$index", "name" to "p$index", "lifted" to false,
            "rep" to if (storedMutation == index) OriginalStdioFixtures.scalar("IntRep") else rep) }
        val body = listOf("case", call, "pair", listOf(listOf("data", "T2", listOf("s", "value"),
            listOf("var", "value", mapOf("rep" to result)), mapOf("binders" to listOf(
                mapOf("id" to "s", "lifted" to false, "rep" to OriginalStdioFixtures.scalar(null)),
                mapOf("id" to "value", "lifted" to false, "rep" to result))))),
            mapOf("rep" to result, "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to output)))
        val originalModule = module("pre")
        return mapOf("sourceFiles" to originalModule["sourceFiles"], "sourceSpans" to originalModule["sourceSpans"],
            "instrument" to true, "constructors" to listOf(mapOf("id" to "T2", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "arity" to reps.size,
                "lifted" to true, "rep" to OriginalStdioFixtures.closure(),
                "expr" to listOf("lam", formals, body, mapOf("rep" to OriginalStdioFixtures.closure(), "resultRep" to result)))))
    }

    @Test fun bothLoadersCheckStoredOperandsAndStateBeforeNativeLayoutAccess() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun load(module: Map<String, Any?>): ExecutableProgram =
                    if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                for (call in calls()) {
                    val operation = validate(call)!!
                    val program = load(rawModule(call))
                    val target = program.entryTarget("entry")
                    val args = operation.arguments.dropLast(1).map { if (it == "AddrRep") ManagedAddress.nullAddress() else 0L }
                    val failure = assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, *args.toTypedArray(), 9L)) }
                    assertTrue(failure.message.orEmpty().contains("zero-width scalar carrier"), failure.message)
                    for (index in operation.arguments.indices) {
                        // IntRep is not any stat argument, including the zero-width state.
                        assertThrows(RuntimeFault::class.java) { load(rawModule(call, index)) }
                    }
                    val malformed = copy(rawModule(call)) as Map<String, Any?>
                    val badCall = OriginalStdioChecks.foreignCalls(malformed).single() as MutableList<Any?>
                    (badCall[1] as MutableList<Any?>)[1] = 17L
                    val badHead = assertThrows(RuntimeFault::class.java) { load(malformed) }
                    assertTrue(badHead.message.orEmpty().startsWith("Invalid original stdio call:"))
                    if (operation.statField) assertThrows(RuntimeFault::class.java) {
                        Calls.target(target, arrayOf(0L, ManagedAddress.nullAddress(), Unit))
                    }
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                }
            } finally { context.leave() }
        }
    }

    @Test fun genuineDescriptorsRejectWidthSafetyUnitArityFlagsAndAggregateMutations() {
        val calls = calls()
        assertEquals(11, calls.size)
        assertEquals(11, calls.map { validate(it) }.toSet().size)
        for (original in calls) {
            fun mutate(action: (MutableList<Any?>, MutableMap<String, Any?>, MutableMap<String, Any?>) -> Unit) {
                val call = copy(original) as MutableList<Any?>
                val metadata = call[6] as MutableMap<String, Any?>
                val descriptor = metadata["foreignCall"] as MutableMap<String, Any?>
                action(call, metadata, descriptor)
                assertThrows(RuntimeFault::class.java) { validate(call) }
            }
            for (key in listOf("schema", "arity", "suppliedArity")) for (wrong in listOf(null, true, 1.0, "1", -1L, 4294967296L))
                mutate { _, _, descriptor -> descriptor[key] = wrong }
            for ((key, wrong) in listOf("safety" to "safe", "convention" to "prim", "extra" to 0L))
                mutate { _, _, descriptor -> descriptor[key] = wrong }
            for ((key, wrong) in listOf("unit" to "base", "isFunction" to false, "kind" to "dynamic", "extra" to 1L))
                mutate { _, _, descriptor -> (descriptor["target"] as MutableMap<String, Any?>)[key] = wrong }
            for (index in (original[2] as List<*>).indices) {
                mutate { call, _, _ -> (call[3] as MutableList<Any?>)[index] = true }
                mutate { _, _, descriptor -> (descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[index]["primReps"] = listOf("WordRep") }
                mutate { _, _, descriptor -> (descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[index]["aggregate"] = "unboxed-tuple" }
            }
            for (site in listOf("resultRep", "rep")) mutate { _, metadata, descriptor ->
                ((if (site == "rep") metadata else descriptor)[site] as MutableMap<String, Any?>)["primReps"] = listOf("WordRep")
            }
            val aliased = copy(original) as MutableList<Any?>
            val descriptor = (aliased[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
            val target = descriptor["target"] as MutableMap<String, Any?>
            target["symbol"] = "prefix" + target["symbol"]
            assertNull(validate(aliased))
            target["symbol"] = "__hscore_fstat"
            assertNull(validate(aliased), "No descriptor-stat semantics may be inferred from memory accessors")
        }
        assertFalse(File(root, "scripts/core-capabilities.json").readText().contains("\"__hscore_fstat\""))
    }
}
