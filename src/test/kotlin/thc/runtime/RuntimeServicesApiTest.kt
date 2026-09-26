// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.Json
import thc.Language
import java.io.ByteArrayOutputStream

/** Retained genuine GHC declarations; synthetic callers isolate the versioned host ABI. */
@Timeout(60)
class RuntimeServicesApiTest {
    private val state = scalar("void")
    private val integer = scalar("long", "IntRep")
    private val cInt = scalar("long", "Int32Rep")
    private val cLong = scalar("long", "Int64Rep")
    private val address = scalar("address", "AddrRep")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private val result get() = tuple(state, cLong)
    private val arguments get() = linkedMapOf(
        "query" to listOf("selector" to cInt, "index" to cLong, "detail" to cLong, "s" to state),
        "control" to listOf("selector" to cInt, "setting" to cLong, "s" to state),
        "trace" to listOf("operation" to cInt, "token" to cLong, "bytes" to address, "length" to cLong, "s" to state))

    private fun scalar(kind: String, rep: String? = null): Map<String, Any?> = mapOf(
        "kind" to kind, "primReps" to if (rep == null) emptyList<String>() else listOf(rep), "evaluated" to true)
    private fun tuple(vararg fields: Map<String, Any?>): Map<String, Any?> = mapOf(
        "kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to fields.toList(),
        "primReps" to fields.flatMap { it["primReps"] as List<*> }, "evaluated" to true)
    private fun variable(name: String, rep: Map<String, Any?>): List<Any?> =
        listOf("var", name, mapOf("rep" to rep))
    private fun literal(value: Long, rep: Map<String, Any?>): List<Any?> =
        listOf("lit", "int", value.toString(), mapOf("rep" to rep))
    private fun binder(name: String, rep: Map<String, Any?>) =
        mapOf("id" to name, "lifted" to (rep == closure), "rep" to rep)
    private fun descriptor(name: String): Map<String, Any?> =
        (Json.parse(javaClass.getResource("/core/runtime-services-descriptors.json")!!.readText()) as Map<String, Any?>)
            .getValue("thc_runtime_v1_$name") as Map<String, Any?>
    private fun foreign(name: String, declaration: Map<String, Any?> = descriptor(name),
        operands: List<List<Any?>> = arguments.getValue(name).map { variable(it.first, it.second) }): List<Any?> =
        listOf("app", variable("foreign-$name", closure), operands, List(operands.size) { false },
            false, false, mapOf("rep" to result, "foreignCall" to declaration))
    private fun unpack(value: List<Any?>, next: String, answer: String, body: List<Any?> = variable(answer, cLong)): List<Any?> =
        listOf("case", value, "pair-$next", listOf(listOf("data", "tuple2", listOf(next, answer), body,
            mapOf("binders" to listOf(binder(next, state), binder(answer, cLong))))),
            mapOf("rep" to cLong, "binder" to binder("pair-$next", result)))
    private fun binding(name: String, parameters: List<Pair<String, Map<String, Any?>>>, body: List<Any?>): Map<String, Any?> =
        mapOf("id" to name, "name" to name, "arity" to parameters.size, "lifted" to true, "rep" to closure,
            "expr" to listOf("lam", parameters.map { binder(it.first, it.second) }, body,
                mapOf("rep" to closure, "resultRep" to cLong)))
    private fun module(bindings: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "unit" to "test", "module" to "RuntimeServicesSynthetic",
        "instrument" to true, "bindings" to bindings,
        "constructors" to listOf(mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    private fun module(): Map<String, Any?> {
        val entries = arguments.map { (name, parameters) -> binding(name, parameters, unpack(foreign(name), "next", "answer")) }
        val enable = foreign("control", operands = listOf(literal(500, cInt), literal(1, cLong), variable("s", state)))
        val emit = foreign("trace", operands = arguments.getValue("trace").map { (name, rep) ->
            variable(if (name == "s") "enabled" else name, rep) })
        return module(entries + binding("enableThenTrace", arguments.getValue("trace"),
            unpack(enable, "enabled", "enabled-result", unpack(emit, "done", "answer"))))
    }
    private fun context(output: ByteArrayOutputStream = ByteArrayOutputStream(), native: Boolean = false,
        threads: Boolean = false) = Context.newBuilder("thc").err(output).allowNativeAccess(native)
        .allowCreateThread(threads).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun program(language: Language, backend: String, source: Map<String, Any?> = module()): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source, true)
    private fun call(guest: ExecutableProgram, name: String, vararg values: Any?): Long =
        Calls.target(guest.entryTarget(name), arrayOf(0L, *values, Unit)) as Long
    private fun query(guest: ExecutableProgram, selector: Long, index: Long = 0, detail: Long = 0) =
        call(guest, "query", selector, index, detail)
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
        assertNull(handoff.pending)
    }
    private fun valid(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun records(output: ByteArrayOutputStream): List<Map<String, Any?>> =
        output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map { Json.parse(it) as Map<String, Any?> }.toList()

    @Test fun queriesReportExecutingBackendPermissionsAndActualProvidersOnFirstInstalledCalls() {
        for (backend in listOf("ast", "bytecode")) for (permissions in listOf(false, true))
            context(native = permissions, threads = permissions).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val guest = program(language, backend)
                    val target = guest.entryTarget("query")
                    fun exercise(compiled: Boolean) {
                        fun measured(selector: Long, index: Long = 0, detail: Long = 0): Long {
                            val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val answer = query(guest, selector, index, detail)
                            if (compiled) {
                                assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(target)
                            }
                            released(language)
                            return answer
                        }
                        assertEquals(1L, measured(0))
                        assertEquals(if (backend == "ast") 1L else 2L, measured(1))
                        assertEquals(if (permissions) 1L else 0L, measured(2))
                        assertEquals(if (permissions) 1L else 0L, measured(3))
                        assertTrue(measured(4) >= 1)
                        for (selector in listOf(5L, 6L, 7L)) {
                            val length = measured(selector, detail = -1)
                            assertTrue(length > 0)
                            assertTrue(Character.isValidCodePoint(measured(selector, detail = 0).toInt()))
                        }
                        assertTrue(measured(200) >= 0, "JVM heap used bytes")
                        assertTrue(measured(201) >= 0, "JVM heap committed bytes")
                        assertEquals(0L, measured(208), "No context-owned libc allocations")
                        assertEquals(0L, measured(209), "No context-owned libc allocations")
                        val collectors = measured(300)
                        assertTrue(collectors >= 0)
                        if (collectors > 0) {
                            assertTrue(measured(301, 0, -1) > 0)
                            val count = measured(302)
                            assertTrue(count >= 0 || count == RuntimeServiceStatus.UNAVAILABLE)
                        }
                    }
                    exercise(false)
                    compile(target)
                    exercise(true)
                    assertThrows(RuntimeFault::class.java) { query(guest, 9999) }
                    assertThrows(RuntimeFault::class.java) { query(guest, 0, 1) }
                    assertThrows(RuntimeFault::class.java) { query(guest, 5, detail = -2) }
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(target, arrayOf(0L, 0L, 0L, 0L, 7L))
                    }
                    released(language)
                } finally { context.leave() }
            }
    }

    @Test fun traceControlAndBytesExecuteInStateOrderAndRetainTheirFirstCompiledEntries() {
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            context(output).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val guest = program(language, backend)
                    val text = "runtime λ\uD83D\uDE00\n\"event\"\u0000tail"
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val pointer = ManagedAddress.fromByteArray(bytes)
                    val targets = listOf("control", "trace", "enableThenTrace").associateWith(guest::entryTarget)
                    assertEquals(0L, query(guest, 500))
                    assertEquals(RuntimeServiceStatus.DISABLED, call(guest, "trace", 0L, 0L, pointer, bytes.size.toLong()))
                    assertEquals(0, output.size())
                    fun exercise(compiled: Boolean) {
                        fun checked(name: String, vararg values: Any?): Long {
                            val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val answer = call(guest, name, *values)
                            if (compiled) {
                                assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong(), name)
                                valid(targets.getValue(name))
                            }
                            released(language)
                            return answer
                        }
                        assertEquals(0L, checked("control", 500L, 0L))
                        output.reset()
                        assertEquals(0L, checked("enableThenTrace", 0L, 0L, pointer, bytes.size.toLong()))
                        assertEquals(1L, query(guest, 500))
                        val token = checked("trace", 1L, 0L, pointer, bytes.size.toLong())
                        assertTrue(token > 0)
                        assertEquals(0L, checked("trace", 2L, token, ManagedAddress.nullAddress(), 0L))
                        val actual = records(output)
                        assertEquals(listOf("event", "begin", "end"), actual.map { it["phase"] })
                        assertEquals(listOf(0L, token, token), actual.map { it["span"] })
                        assertEquals(text, actual[0]["name"])
                        assertEquals(text, actual[1]["name"])
                        assertEquals(1, actual.map { it["context"] }.distinct().size)
                        assertTrue((actual[2]["elapsedNanos"] as Long) >= 0)
                    }
                    exercise(false)
                    targets.values.forEach(::compile)
                    exercise(true)
                    // Invalid State must be checked before either control or trace has an effect.
                    assertEquals(0L, call(guest, "control", 500L, 0L))
                    output.reset()
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(guest.entryTarget("enableThenTrace"), arrayOf(0L, 0L, 0L, pointer, bytes.size.toLong(), 7L))
                    }
                    assertEquals(0L, query(guest, 500))
                    assertEquals(0, output.size())
                    assertEquals(0L, call(guest, "control", 500L, 1L))
                    for ((location, length) in listOf(
                        pointer to -1L, pointer to (bytes.size + 1L),
                        ManagedAddress.fromByteArray(byteArrayOf(0xc3.toByte(), 0x28)) to 2L)) {
                        assertThrows(RuntimeFault::class.java) { call(guest, "trace", 0L, 0L, location, length) }
                        released(language)
                    }
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(guest.entryTarget("trace"), arrayOf(0L, 0L, 0L, pointer, bytes.size.toLong(), 7L))
                    }
                    assertEquals(0, output.size(), "Malformed payloads and State cannot publish partial records")
                    released(language)
                } finally { context.leave() }
            }
        }
    }

    @Test fun traceSinksAndLiveSpanOwnershipAreContextLocalThroughBothLoaders() {
        for (backend in listOf("ast", "bytecode")) {
            val left = ByteArrayOutputStream(); val right = ByteArrayOutputStream()
            context(left).use { first -> context(right).use { second ->
                first.initialize("thc"); second.initialize("thc")
                val bytes = "context-local".toByteArray(Charsets.UTF_8)
                val pointer = ManagedAddress.fromByteArray(bytes)
                lateinit var owner: ExecutableProgram
                var token = 0L
                first.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    owner = program(language, backend)
                    assertEquals(0L, call(owner, "control", 500L, 1L))
                    token = call(owner, "trace", 1L, 0L, pointer, bytes.size.toLong())
                    assertTrue(token > 0)
                    released(language)
                } finally { first.leave() }
                second.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val guest = program(language, backend)
                    assertEquals(0L, query(guest, 500))
                    assertEquals(RuntimeServiceStatus.DISABLED, call(guest, "trace", 0L, 0L, pointer, bytes.size.toLong()))
                    assertEquals(0L, call(guest, "control", 500L, 1L))
                    assertThrows(RuntimeFault::class.java) { call(guest, "trace", 2L, token, ManagedAddress.nullAddress(), 0L) }
                    assertEquals(0, right.size())
                    released(language)
                } finally { second.leave() }
                first.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    assertEquals(1L, query(owner, 500))
                    assertEquals(0L, call(owner, "trace", 3L, token, ManagedAddress.nullAddress(), 0L))
                    assertThrows(RuntimeFault::class.java) { call(owner, "trace", 2L, token, ManagedAddress.nullAddress(), 0L) }
                    assertEquals(listOf("begin", "exception"), records(left).map { it["phase"] })
                    released(language)
                } finally { first.leave() }
            } }
        }
    }

    @Test fun jitTelemetryIsExplicitlyEnabledAndControlsDoNotFabricateDisabledCounters() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val guest = program(language, backend)
                assertEquals(0L, query(guest, 400))
                for (selector in 401L..406L) assertEquals(RuntimeServiceStatus.DISABLED, query(guest, selector))
                assertEquals(0L, call(guest, "control", 400L, 1L))
                assertEquals(1L, query(guest, 400))
                for (selector in 401L..406L) assertTrue(query(guest, selector) >= 0)
                val target = guest.entryTarget("query")
                compile(target)
                val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                assertEquals(1L, query(guest, 0))
                assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                valid(target)
                val completed = query(guest, 402)
                assertTrue(completed >= 1, "The installed guest compilation is attributed to its own context")
                assertEquals(0L, call(guest, "control", 400L, 0L))
                assertEquals(0L, query(guest, 400))
                for (selector in 401L..406L) assertEquals(RuntimeServiceStatus.DISABLED, query(guest, selector))
                assertEquals(0L, call(guest, "control", 400L, 1L))
                assertEquals(completed, query(guest, 402), "Disabling observation does not silently reset its history")
                assertThrows(RuntimeFault::class.java) { call(guest, "control", 400L, 2L) }
                assertThrows(RuntimeFault::class.java) { query(guest, 400, 1) }
                released(language)
            } finally { context.leave() }
        }
    }

    @Test fun foreignDeclarationsRequireExactShapeWidthsConventionSafetyAndUnresolvedHead() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((name, parameters) in arguments) {
                    val valid = descriptor(name)
                    val declared = valid["argumentReps"] as List<Map<String, Any?>>
                    val badDeclarations = listOf(
                        valid + ("schema" to 2), valid + ("safety" to "safe"), valid + ("convention" to "prim"),
                        valid + ("arity" to parameters.size + 1), valid + ("suppliedArity" to parameters.size - 1),
                        valid + ("argumentReps" to (listOf(integer) + declared.drop(1))),
                        valid + ("resultRep" to tuple(state, integer)), valid + ("resultRep" to cLong),
                        valid + ("target" to ((valid["target"] as Map<String, Any?>) + ("isFunction" to false))))
                    for ((index, bad) in badDeclarations.withIndex()) {
                        val source = module(listOf(binding(name, parameters, unpack(foreign(name, bad), "next", "answer"))))
                        assertThrows(RuntimeFault::class.java, { program(language, backend, source) }, "$backend/$name declaration $index")
                    }
                    val call = foreign(name)
                    val operation = CoreRuntimeServices.validate(call, false)
                    assertNotNull(operation)
                    assertThrows(RuntimeFault::class.java) { CoreRuntimeServices.validate(call, true) }
                    for (bad in listOf(
                        call.toMutableList().also { it[3] = List(parameters.size) { true } },
                        call.toMutableList().also { it[2] = (it[2] as List<*>).dropLast(1) },
                        call.toMutableList().also { it[6] = (it[6] as Map<String, Any?>) + ("rep" to tuple(state, cInt)) })) {
                        val source = module(listOf(binding(name, parameters, unpack(bad, "next", "answer"))))
                        assertThrows(RuntimeFault::class.java, { program(language, backend, source) }, "$backend/$name call shape")
                    }
                    // A valid annotation on the occurrence cannot overrule its differently typed binder.
                    for (index in parameters.indices) {
                        val changed = parameters.mapIndexed { position, parameter ->
                            if (position == index) parameter.first to integer else parameter }
                        val source = module(listOf(binding(name, changed, unpack(call, "next", "answer"))))
                        assertThrows(RuntimeFault::class.java, { program(language, backend, source) }, "$backend/$name contradictory binder $index")
                    }
                    val shadowed = parameters + ("foreign-$name" to closure)
                    val source = module(listOf(binding(name, shadowed, unpack(call, "next", "answer"))))
                    assertThrows(RuntimeFault::class.java, { program(language, backend, source) }, "$backend/$name defined foreign identifier")
                    released(language)
                }
            } finally { context.leave() }
        }
    }
}
