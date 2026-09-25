// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File

/** Genuine declaration certificates in explicitly synthetic scalar consumers. */
class LibdwUnavailableTest {
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private fun declarations() = Json.parse(javaClass.getResource("/core/original-libdw-descriptors.json")!!.readText())
        as List<Map<String, Any?>>
    private fun symbol(call: Map<String, Any?>) = (call.getValue("target") as Map<String, Any?>).getValue("symbol") as String
    private fun module(mutate: (MutableMap<String, Any?>) -> Unit = {}): Map<String, Any?> {
        val bindings = declarations().map { declaration ->
            val name = symbol(declaration)
            val tuple = declaration.getValue("resultRep") as Map<String, Any?>
            val components = tuple.getValue("components") as List<Map<String, Any?>>
            val formals = (declaration.getValue("argumentReps") as List<Map<String, Any?>>).mapIndexed { i, rep ->
                mapOf("id" to "$name-arg-$i", "lifted" to false, "rep" to (rep + ("evaluated" to true)))
            }
            val call = listOf("app", listOf("var", "$name-synthetic-fcall-id", mapOf("rep" to closure)),
                formals.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) }, formals.map { false },
                false, false, mapOf("rep" to tuple, "foreignCall" to declaration))
            val ids = components.indices.map { "$name-field-$it" }
            val output = if (components.size == 1) long else components.last()
            val result = if (components.size == 1) listOf("lit", "int", "23", mapOf("rep" to long))
                else listOf("var", ids.last(), mapOf("rep" to output))
            val ctor = if (components.size == 1) "tuple1" else "tuple2"
            val body = listOf("case", call, "$name-tuple", listOf(listOf("data", ctor, ids, result,
                mapOf("binders" to components.mapIndexed { i, rep -> mapOf("id" to ids[i], "lifted" to false, "rep" to rep) }))),
                mapOf("rep" to output, "binder" to mapOf("id" to "$name-tuple", "lifted" to false, "rep" to (tuple + ("evaluated" to true)))))
            mutableMapOf<String, Any?>("id" to name, "name" to name, "arity" to formals.size, "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to output))).also(mutate)
        }
        return mapOf("schema" to 1, "module" to "SyntheticLibdwConsumers", "unit" to "test", "ghc" to "9.14.1",
            "instrument" to true, "bindings" to bindings, "constructors" to listOf(
                mapOf("id" to "tuple1", "name" to "Solo#", "kind" to "unboxed-tuple", "arity" to 1, "tag" to 1),
                mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }

    @Test fun unavailableBackendMatchesNativeFailureAndNeverTouchesLocationIncludingFirstCompiledEntry() {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = load(language, backend, module())
                val targets = LibdwForeignOp.entries.associate { it.symbol to program.entryTarget(it.symbol) }
                var compiled = false
                fun call(name: String, vararg args: Any?): Any? {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val target = targets.getValue(name)
                    val result = Calls.target(target, arrayOf(0L, *args))
                    if (compiled) {
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$backend/$name")
                        valid(target)
                    }
                    released(language)
                    return result
                }
                val bytes = ManagedAllocation.mutable(64, 8)
                for (offset in 0L until 64L) bytes.writeByte(offset, 165L)
                val pointer = ManagedAddress.fromAllocation(bytes)
                val nil = ManagedAddress.nullAddress()
                fun exercise() {
                    assertTrue((call("libdwPoolTake", Unit) as ManagedAddress).sameLocation(nil))
                    for (session in listOf(nil, pointer)) {
                        assertTrue((call("libdwGetBacktrace", session, Unit) as ManagedAddress).sameLocation(nil))
                        assertEquals(1L, call("libdwLookupLocation", session, pointer, session, Unit))
                        assertEquals(List(64) { 165L }, (0L until 64L).map(bytes::readByte))
                    }
                    // Failure does not require a writable Location, or dereference any pointer.
                    assertEquals(1L, call("libdwLookupLocation", nil, nil, nil, Unit))
                    assertEquals(23L, call("libdwPoolClear", Unit))
                }
                repeat(3) { exercise() }
                targets.values.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                compiled = true
                exercise() // no settling or recompilation after installation
                compiled = false
                assertThrows(RuntimeFault::class.java) { call("libdwPoolTake", 0L) }
                assertThrows(RuntimeFault::class.java) { call("libdwGetBacktrace", 0L, Unit) }
                assertThrows(RuntimeFault::class.java) { call("libdwLookupLocation", nil, 0L, nil, Unit) }
                released(language)
            } finally { context.leave() }
        }
    }

    @Test fun nativeOracleUsesTheSelectedDisabledDwarfConfigurationAndOriginalHaskellWrapper() {
        val root = File(System.getProperty("thc.projectRoot"))
        val prefix = "build/libdw-unavailable"
        val manifest = Json.parse(File(root, "$prefix/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/LibdwUnavailableNative.hs", "compiler/test-fixtures/CFinalizerNative.hs",
            "compiler/test-fixtures/ForeignLabelAudit.hs", "compiler/THC/Plugin.hs",
            "test/haskell-fixtures/LibdwUnavailableFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/foreign-labels.json"), "$prefix/")
        val oracle = Json.parse(File(root, "$prefix/oracle.json").readText()) as Map<String, Any?>
        assertEquals(false, oracle["useLibdw"])
        assertEquals(List(8) { true }, oracle["observations"])
        assertEquals(List(14) { true }, oracle["cFinalizerObservations"])
        val labels = ArrayList<Pair<String, String>>()
        fun walk(value: Any?) {
            when (value) {
                is Map<*, *> -> value.values.forEach(::walk)
                is List<*> -> {
                    if (value.firstOrNull() == "lit" && value.getOrNull(1) in setOf("function-addr", "data-addr")) {
                        val proof = (value[3] as Map<*, *>)["rep"] as Map<*, *>
                        assertEquals("address", proof["kind"])
                        assertEquals(listOf("AddrRep"), proof["primReps"])
                        labels.add(value[1] as String to value[2] as String)
                    }
                    value.forEach(::walk)
                }
            }
        }
        walk(Json.parse(File(root, "$prefix/foreign-labels.json").readText()))
        assertEquals(listOf("data-addr" to "enabled_capabilities", "function-addr" to "backtraceFree",
            "function-addr" to "libdwPoolRelease"), labels.sortedWith(compareBy({ it.first }, { it.second })))
    }

    @Test fun declarationAndStoredOperandProofsCannotBeForged() {
        for (declaration in declarations()) {
            val output = declaration.getValue("resultRep")
            val args = declaration.getValue("argumentReps") as List<Map<String, Any?>>
            val flags = args.map { false }
            val operation = CoreLibdwForeign.validate(mapOf("rep" to output, "foreignCall" to declaration), args, flags, output)!!
            assertEquals(symbol(declaration), operation.symbol)
            val target = declaration.getValue("target") as Map<String, Any?>
            val bad = listOf(declaration + ("schema" to 1.0), declaration + ("safety" to "safe"),
                declaration + ("arity" to 0), declaration + ("convention" to "capi"),
                declaration + ("target" to (target + ("unit" to "main"))),
                declaration + ("target" to (target + ("isFunction" to false))),
                declaration + ("resultRep" to long))
            for (incorrect in bad) assertThrows(RuntimeFault::class.java) {
                CoreLibdwForeign.validate(mapOf("rep" to output, "foreignCall" to incorrect), args, flags, output)
            }
            operation.arguments.forEachIndexed { index, primitive ->
                val proof = CoreRepresentation(if (primitive == null) CoreKind.VOID else CoreKind.ADDRESS, true, true, listOfNotNull(primitive))
                CoreLibdwForeign.validateOperand(operation, index, proof, proof)
                assertThrows(RuntimeFault::class.java) {
                    CoreLibdwForeign.validateOperand(operation, index, proof, proof.copy(kind = CoreKind.LONG, primReps = listOf("IntRep")))
                }
            }
        }
        for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val bad = module { binding ->
                    val lambda = (binding["expr"] as List<Any?>).toMutableList()
                    val formals = (lambda[1] as List<Map<String, Any?>>).toMutableList()
                    formals[0] = formals[0] + ("rep" to long)
                    lambda[1] = formals; binding["expr"] = lambda
                }
                assertThrows(RuntimeFault::class.java) { load(language, backend, bad) }
                released(language)
            } finally { context.leave() }
        }
    }
}
