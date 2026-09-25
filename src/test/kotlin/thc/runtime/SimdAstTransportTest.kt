// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.EntryValue
import thc.Json
import thc.Language

/** Small exact-Core controls; native SIMD call coverage uses the separate exported fixture. */
class SimdAstTransportTest {
    private val int = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val int16 = mapOf("kind" to "long", "primReps" to listOf("Int16Rep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val vector = mapOf("kind" to "vector", "primReps" to listOf("VecRep 8 Int16ElemRep"),
        "vector" to mapOf("lanes" to 8L, "element" to "Int16ElemRep"), "evaluated" to true)
    private val unpacked = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to List(8) { "Int16Rep" }, "components" to List(8) { int16 }, "evaluated" to true)
    private val nested = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to listOf("VecRep 8 Int16ElemRep", "IntRep"),
        "components" to listOf(vector, int), "evaluated" to true)
    private fun variable(id: String, rep: Map<String, Any?> = int): List<Any?> = listOf("var", id, mapOf("rep" to rep))
    private fun literal(value: Long): List<Any?> = listOf("lit", "int", value.toString(), mapOf("rep" to int))
    private fun formal(id: String, rep: Map<String, Any?> = int) = mapOf("id" to id, "name" to id,
        "lifted" to false, "rep" to rep)
    private fun app(fn: List<Any?>, args: List<List<Any?>>, result: Map<String, Any?>,
                    flags: List<Boolean> = List(args.size) { false }): List<Any?> =
        listOf("app", fn, args, flags, false, false, mapOf("rep" to result))
    private fun call(id: String, args: List<List<Any?>>, result: Map<String, Any?> = int): List<Any?> =
        app(variable(id, closure), args, result)
    private fun prim(name: String, args: List<List<Any?>>, result: Map<String, Any?>): List<Any?> =
        app(listOf("prim", name, mapOf("rep" to closure)), args, result)
    private fun broadcast(x: List<Any?>) = prim("broadcastInt16X8#",
        listOf(prim("intToInt16#", listOf(x), int16)), vector)
    private fun unpack(value: List<Any?>, body: List<Any?>): List<Any?> {
        val ids = (0 until 8).map { "lane$it" }
        return listOf("case", prim("unpackInt16X8#", listOf(value), unpacked), "whole",
            listOf(listOf("data", "T8", ids, body,
                mapOf("binders" to ids.map(::formal16)))),
            mapOf("rep" to int, "binder" to formal("whole", unpacked)))
    }
    private fun formal16(id: String) = formal(id, int16)
    private fun lam(args: List<Map<String, Any?>>, body: List<Any?>,
                    result: Map<String, Any?> = int): List<Any?> =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result,
            "entryStrict" to List(args.size) { false }))
    private fun binding(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id,
        "lifted" to true, "rep" to closure, "expr" to body)
    private fun module(): Map<String, Any?> {
        val v = variable("v", vector)
        val first = unpack(v, prim("int16ToInt#", listOf(variable("lane0", int16)), int))
        val nestedValue = app(listOf("con", "T2", 2), listOf(broadcast(variable("x")), literal(13)), nested)
        val nestedCase = listOf("case", nestedValue, "pair", listOf(listOf("data", "T2", listOf("v", "z"),
            call("score", listOf(variable("v", vector), variable("z"))),
            mapOf("binders" to listOf(formal("v", vector), formal("z"))))),
            mapOf("rep" to int, "binder" to formal("pair", nested)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "instrument" to true,
            "constructors" to listOf(mapOf("id" to "T8", "name" to "T8", "kind" to "unboxed-tuple", "arity" to 8),
                mapOf("id" to "T2", "name" to "T2", "kind" to "unboxed-tuple", "arity" to 2)),
            "bindings" to listOf(
                binding("identity", lam(listOf(formal("v", vector)), v, vector)),
                binding("returnVector", lam(listOf(formal("x")), broadcast(variable("x")), vector)),
                binding("score", lam(listOf(formal("v", vector), formal("z")),
                    prim("+#", listOf(first, variable("z")), int))),
                binding("direct", lam(listOf(formal("x")), call("score", listOf(
                    call("identity", listOf(broadcast(variable("x"))), vector), literal(13))))),
                binding("pap", lam(listOf(formal("x")), app(
                    call("score", listOf(broadcast(variable("x"))), closure), listOf(literal(13)), int))),
                binding("nested", lam(listOf(formal("x")), nestedCase))))
    }
    private fun heapModule(): Map<String, Any?> {
        val boxed = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val heap = mapOf("id" to "Heap", "name" to "Heap", "kind" to "boxed", "arity" to 2,
            "fieldReps" to listOf(listOf("VecRep 8 Int16ElemRep"), listOf("IntRep")),
            "fieldTypes" to listOf(vector, int), "fieldLifted" to listOf(false, false),
            "strictFields" to listOf(false, false))
        val boxedInt = mapOf("id" to "BoxedInt", "name" to "BoxedInt", "kind" to "boxed", "arity" to 1,
            "fieldReps" to listOf(listOf("IntRep")), "fieldTypes" to listOf(int),
            "fieldLifted" to listOf(false), "strictFields" to listOf(false))
        fun saved() = variable("saved", vector)
        fun scoreSaved() = call("score", listOf(saved(), literal(13)))
        fun vectorLet(body: List<Any?>): List<Any?> = listOf("let", false,
            listOf(mapOf("id" to "saved", "name" to "saved", "lifted" to false,
                "rep" to vector, "expr" to broadcast(variable("x")))), body, mapOf("rep" to int))
        fun selectHeap(value: List<Any?>): List<Any?> = listOf("case", value, "whole",
            listOf(listOf("data", "Heap", listOf("v", "bias"),
                call("score", listOf(variable("v", vector), variable("bias"))),
                mapOf("binders" to listOf(formal("v", vector), formal("bias"))))),
            mapOf("rep" to int, "binder" to formal("whole", boxed)))
        val heapDirect = app(listOf("con", "Heap", 2), listOf(broadcast(variable("x")), literal(13)), boxed)
        val heapPap = app(app(listOf("con", "Heap", 2), listOf(broadcast(variable("x"))), closure),
            listOf(literal(13)), boxed)
        val captured = vectorLet(app(lam(listOf(formal("bias")),
            call("score", listOf(saved(), variable("bias")))), listOf(literal(13)), int))
        val thunk = vectorLet(listOf("let", false,
            listOf(mapOf("id" to "suspended", "name" to "suspended", "lifted" to true,
                "rep" to (boxed + ("evaluated" to false)),
                "expr" to app(listOf("con", "BoxedInt", 1), listOf(scoreSaved()), boxed))),
            listOf("case", variable("suspended", boxed + ("evaluated" to false)), "whole",
                listOf(listOf("data", "BoxedInt", listOf("answer"), variable("answer"),
                    mapOf("binders" to listOf(formal("answer"))))),
                mapOf("rep" to int, "binder" to formal("whole", boxed))), mapOf("rep" to int)))
        val base = module()
        return base + mapOf("constructors" to (base["constructors"] as List<*>) + listOf(heap, boxedInt),
            "bindings" to (base["bindings"] as List<*>) + listOf(
                binding("heapDirect", lam(listOf(formal("x")), selectHeap(heapDirect))),
                binding("heapPap", lam(listOf(formal("x")), selectHeap(heapPap))),
                binding("captured", lam(listOf(formal("x")), captured)),
                binding("thunk", lam(listOf(formal("x")), thunk))))
    }
    @Test fun publicHostStillRejectsVectorIngressAndResult() {
        for (backend in listOf("ast", "bytecode")) for ((entry, boundary) in
            listOf("identity" to "host argument", "returnVector" to "host result"))
            Context.newBuilder("thc").allowExperimentalOptions(true).build().use { context ->
                val request = Json.stringify(mapOf("entry" to entry, "backend" to backend,
                    "modules" to listOf(module())))
                val failure = assertThrows(PolyglotException::class.java) { context.eval("thc", request) }
                assertTrue(failure.message.orEmpty().contains("Unsupported Core vector boundary: $boundary"),
                    "$backend/$entry: ${failure.message}")
            }
    }

    @Test fun vectorArgumentsResultsPapAndNestedTupleKeepExactLanesAfterCompilation() {
        for (inlining in listOf(true, false)) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = Program(language, module())
                    for (entry in listOf("direct", "pap", "nested")) {
                        val function = context.asValue(EntryValue(program, entry, 1))
                        for (x in listOf(-32768L, -1L, 0L, 32767L))
                            assertEquals(x.toShort().toLong() + 13L, function.execute(x).asLong(), "$entry/$x interpreted")
                        assertTrue(function.invokeMember("compile").asBoolean(), "$entry first installed compilation")
                        val target = program.entryTarget(entry)
                        for (x in listOf(32767L, 0L, -1L, -32768L)) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(x.toShort().toLong() + 13L, function.execute(x).asLong(), "$entry/$x compiled")
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                    }
                } finally { context.leave() }
            }
    }

    @Test fun astHeapVectorsOwnLanesAcrossClosureThunkConstructorAndPap() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = Program(language, heapModule())
                    for (entry in listOf("heapDirect", "heapPap", "captured", "thunk")) {
                        val function = context.asValue(EntryValue(program, entry, 1))
                        for (x in listOf(-32768L, -1L, 0L, 32767L))
                            assertEquals(x.toShort().toLong() + 13L, function.execute(x).asLong(), "$entry/$x interpreted")
                        assertTrue(function.invokeMember("compile").asBoolean(), "$entry first installed compilation")
                        val target = program.entryTarget(entry)
                        for (x in listOf(32767L, 0L, -1L, -32768L)) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(x.toShort().toLong() + 13L, function.execute(x).asLong(), "$entry/$x compiled")
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                    }
                } finally { context.leave() }
            }
    }

    @Test fun bytecodeHeapConstructorFieldsAndPapRetainPrimitiveLanes() {
        val constructorModule = heapModule().let { source ->
            source + ("bindings" to (source["bindings"] as List<Map<String, Any?>>)
                .filterNot { it["id"] in setOf("captured", "thunk") })
        }
        for (inlining in listOf(true, false)) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", inlining.toString())
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = BytecodeProgram(language, constructorModule)
                    for (entry in listOf("heapDirect", "heapPap")) {
                        val function = context.asValue(EntryValue(program, entry, 1))
                        for (x in listOf(-32768L, -1L, 0L, 32767L))
                            assertEquals(x.toShort().toLong() + 13L, function.execute(x).asLong(), "$entry/$x interpreted")
                        assertTrue(function.invokeMember("compile").asBoolean(), "$entry first installed compilation")
                        val target = program.entryTarget(entry)
                        for (x in listOf(32767L, 0L, -1L, -32768L)) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(x.toShort().toLong() + 13L, function.execute(x).asLong(), "$entry/$x compiled")
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                            assertEquals(0, language.handoffState.get().arguments.depth)
                            assertEquals(0, language.handoffState.get().results.depth)
                        }
                    }
                } finally { context.leave() }
            }
    }
}
