// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
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

    private fun originalLabel(symbol: String): List<Any?> {
        val root = File(System.getProperty("thc.projectRoot"))
        val source = Json.parse(File(root, "build/libdw-unavailable/foreign-labels.json").readText())
        fun find(value: Any?): List<Any?>? = when (value) {
            is List<*> -> if (value.take(3) == listOf("lit", "function-addr", symbol))
                value as List<Any?> else value.firstNotNullOfOrNull(::find)
            is Map<*, *> -> value.values.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(source) ?: error("Missing genuine GHC $symbol label")
    }

    private fun cFinalizerConsumer(label: List<Any?>): Map<String, Any?> {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
        val weak = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val flag = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val result = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(state, flag),
            "primReps" to listOf("IntRep"), "evaluated" to true)
        val formals = listOf(mapOf("id" to "pointer", "lifted" to false, "rep" to address),
            mapOf("id" to "weak", "lifted" to false, "rep" to weak))
        val operands = listOf(label, listOf("var", "pointer", mapOf("rep" to address)),
            listOf("lit", "int", "0", mapOf("rep" to flag)),
            listOf("lit", "null-addr", "0", mapOf("rep" to address)),
            listOf("var", "weak", mapOf("rep" to weak)), listOf("void", mapOf("rep" to state)))
        val call = listOf("app", listOf("prim", "addCFinalizerToWeak#"), operands,
            List(6) { false }, false, false, mapOf("rep" to result))
        val fields = listOf(mapOf("id" to "outState", "lifted" to false, "rep" to state),
            mapOf("id" to "outFlag", "lifted" to false, "rep" to flag))
        val body = listOf("case", call, "outTuple", listOf(listOf("data", "tuple2",
            listOf("outState", "outFlag"), listOf("var", "outFlag", mapOf("rep" to flag)),
            mapOf("binders" to fields))),
            mapOf("rep" to flag, "binder" to mapOf("id" to "outTuple", "lifted" to false, "rep" to result)))
        return mapOf("schema" to 1, "module" to "SyntheticCFinalizerConsumer", "unit" to "test", "ghc" to "9.14.1",
            "instrument" to true, "bindings" to listOf(mapOf("id" to "attach", "name" to "attach", "arity" to 2,
                "lifted" to true, "rep" to closure, "expr" to listOf("lam", formals, body,
                    mapOf("rep" to closure, "resultRep" to flag)))),
            "constructors" to listOf(mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple",
                "arity" to 2, "tag" to 1)))
    }

    @Test fun actualGhcFunctionLabelAndTypedWeakRegistrationCompileOnBothBackends() {
        val module = cFinalizerConsumer(originalLabel("libdwPoolRelease"))
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = load(language, backend, module)
                    val target = program.entryTarget("attach")
                    val pointer = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
                    fun call(): Long {
                        val weak = Language.currentState().weaks.make(Any(), Any(), null)
                        val added = Calls.target(target, arrayOf(0L, pointer, weak)) as Long
                        assertEquals(1L, added)
                        assertEquals(0L, Language.currentState().weaks.finalize(weak).flag)
                        released(language)
                        return added
                    }
                    repeat(3) { call() }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(1L, call())
                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    valid(target)
                } finally { context.leave() }
            }
    }

    @Test fun originalFreeLabelFinalizesOnlyOwnedMallocBasesOnBothBackends() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        val module = cFinalizerConsumer(originalLabel("free"))
        val proof = CoreRepresentation(CoreKind.ADDRESS, evaluated = true, present = true, primReps = listOf("AddrRep"))
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = load(language, backend, module)
                    val target = program.entryTarget("attach")
                    val state = Language.currentState()
                    val function = CFinalizerLabels.fromCore("free", proof)
                    assertTrue(function.sameLocation(CFinalizerLabels.fromCore("free", proof)))
                    assertThrows(RuntimeFault::class.java) { function.toNativeBits() }
                    fun call(): Long {
                        val base = state.nativeAllocations.malloc(16)
                        val weak = state.weaks.make(Any(), Any(), null)
                        assertThrows(RuntimeFault::class.java) { state.weaks.addCFinalizer(function,
                            base.plus(1), 0, weak, state.cbits()) }
                        assertThrows(RuntimeFault::class.java) { state.weaks.addCFinalizer(function,
                            ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)), 0, weak, state.cbits()) }
                        val borrow = base.nativeAllocation()!!.borrow()
                        try {
                            assertThrows(RuntimeFault::class.java) { state.weaks.addCFinalizer(function,
                                base, 0, weak, state.cbits()) }
                        } finally { borrow.close() }
                        assertEquals(1L, Calls.target(target, arrayOf(0L, base, weak)) as Long)
                        assertEquals(1, state.nativeAllocations.liveCount())
                        assertEquals(0L, state.weaks.finalize(weak).flag)
                        assertEquals(0, state.nativeAllocations.liveCount())
                        assertThrows(RuntimeFault::class.java) { base.readWord8(0) }
                        assertEquals(0L, state.weaks.finalize(weak).flag)
                        assertEquals(0L, Calls.target(target, arrayOf(0L, base, weak)) as Long,
                            "A finalized Weak# rejects registration before inspecting its freed base")
                        assertThrows(RuntimeFault::class.java) {
                            state.weaks.addCFinalizer(function, base, 1, weak, state.cbits())
                        }
                        released(language)
                        return 1L
                    }
                    repeat(3) { call() }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(1L, call())
                    assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    valid(target)
                } finally { context.leave() }
            }
    }

    @Test fun certifiedLabelsUseContextOwnedSulongCallablesOnlyOnExplicitFinalization() {
        val proof = CoreRepresentation(CoreKind.ADDRESS, evaluated = true, present = true, primReps = listOf("AddrRep"))
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val state = Language.currentState()
                val first = CFinalizerLabels.fromCore("libdwPoolRelease", proof)
                val second = CFinalizerLabels.fromCore("backtraceFree", proof)
                assertTrue(first.sameLocation(CFinalizerLabels.fromCore("libdwPoolRelease", proof)))
                assertFalse(first.sameLocation(second))
                assertFalse(first.sameLocation(ManagedAddress.nullAddress()))
                assertThrows(RuntimeFault::class.java) { first.toNativeBits() }
                assertThrows(RuntimeFault::class.java) { first.plus(0) }
                assertThrows(RuntimeFault::class.java) { first.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { CFinalizerLabels.fromCore("enabled_capabilities", proof) }
                assertThrows(RuntimeFault::class.java) { CFinalizerLabels.fromCore("libdwPoolRelease", proof.copy(primReps = listOf("WordRep"))) }
                val bytes = ManagedAllocation.mutable(16, 8)
                for (index in 0L until 16L) bytes.writeByte(index, index + 17)
                val pointer = ManagedAddress.fromAllocation(bytes)
                val interop = InteropLibrary.getUncached()
                val shifted = state.cbits().pointerTransport(pointer.plus(5))
                assertEquals(22.toByte(), interop.readBufferByte(shifted, 0))
                assertEquals(11L, interop.getBufferSize(shifted))
                val immutable = ManagedAddress.fromHex("0112233445").plus(2)
                val projected = state.cbits().pointerTransport(immutable)
                assertEquals(0x23.toByte(), interop.readBufferByte(projected, 0))
                interop.toNative(projected)
                assertEquals(immutable.toNativeBits(), interop.asPointer(projected))
                val weak = state.weaks.make(Any(), Any(), null)
                assertEquals(1L, state.weaks.addCFinalizer(first, pointer.plus(5), 0, weak, state.cbits()))
                assertEquals(1L, state.weaks.addCFinalizer(second, pointer.plus(5), 0, weak, state.cbits()))
                assertThrows(RuntimeFault::class.java) { state.weaks.addCFinalizer(first, pointer, 1, weak, state.cbits()) }
                assertEquals(0L, state.weaks.finalize(weak).flag)
                assertEquals(List(16) { it.toLong() + 17 }, (0L until 16L).map(bytes::readByte))
                assertEquals(0L, state.weaks.addCFinalizer(first, pointer, 0, weak, state.cbits()))
                assertEquals(0L, state.weaks.finalize(weak).flag)
            } finally { context.leave() }
        }
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
        assertEquals(List(18) { true }, oracle["cFinalizerObservations"])
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
            "function-addr" to "free", "function-addr" to "libdwPoolRelease"),
            labels.distinct().sortedWith(compareBy({ it.first }, { it.second })))
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
