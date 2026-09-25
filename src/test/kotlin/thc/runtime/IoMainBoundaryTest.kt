// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.executionContext

/** Synthetic boundary adversaries; genuine optimized Core has a separate native fixture. */
class IoMainBoundaryTest {
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val unit = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val closure = unit + ("kind" to "closure")
    private val unitId = "ghc-internal:GHC.Internal.Tuple.()"
    private fun tuple(vararg fields: Map<String, Any?>): Map<String, Any?> = mapOf(
        "kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to fields.toList(),
        "primReps" to fields.flatMap { it["primReps"] as List<String> }, "evaluated" to true)
    private val result = tuple(state, unit)
    private fun v(id: String) = listOf("var", id, mapOf("rep" to closure))
    private fun n(value: Int) = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun app(function: List<Any?>, args: List<List<Any?>>) =
        listOf("app", function, args, List(args.size) { false }, false, false, mapOf("rep" to closure))
    private fun binding(id: String, expression: List<Any?>) = mapOf<String, Any?>(
        "id" to id, "name" to id, "type" to "IO ()", "arity" to 0,
        "lifted" to true, "rep" to closure, "expr" to expression)
    private fun fixture(prefix: Int = 2, type: String? = "State# RealWorld",
                        input: Map<String, Any?> = state, output: Map<String, Any?> = result,
                        supplied: Int = prefix, returnedConstructor: String = unitId): Map<String, Any?> {
        val arguments = (0 until prefix).map { mapOf<String, Any?>(
            "id" to "x$it", "name" to "x$it", "type" to "Int#", "lifted" to false, "rep" to integer) } +
            mapOf("id" to "s", "name" to "s", "type" to type, "lifted" to false, "rep" to input)
        val body = listOf("app", listOf("con", "StateUnit", 2, mapOf("rep" to closure)),
            listOf(listOf("void", mapOf("rep" to state)), listOf("con", returnedConstructor, 0, mapOf("rep" to unit))),
            listOf(false, true), true, true, mapOf("rep" to result))
        val worker = binding("worker", listOf("lam", arguments, body, mapOf("rep" to closure, "resultRep" to output)))
        val expression = if (prefix == 0 && supplied == 0) v("worker") else app(v("worker"), (0 until supplied).map(::n))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "bindings" to listOf(binding("main", expression), worker),
            "constructors" to listOf(
                mapOf("id" to "StateUnit", "name" to "StateUnit", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to returnedConstructor, "name" to "()", "kind" to "boxed", "arity" to 0,
                    "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>(), "fieldReps" to emptyList<String>())))
    }
    private fun load(context: Context, backend: String, module: Map<String, Any?>, diagnostic: Boolean = false,
                     shutdown: String? = null): Value = context.eval("thc", Json.stringify(
        mapOf("entry" to "main", "ioMain" to true, "backend" to backend,
            "diagnosticUnsupported" to diagnostic, "modules" to listOf(module)) +
            (if (shutdown == null) emptyMap() else mapOf("shutdownEntry" to shutdown))))
    private fun bindings(module: Map<String, Any?>) = module["bindings"] as List<Map<String, Any?>>
    private fun mainExpression(module: Map<String, Any?>, expression: List<Any?>) =
        module + ("bindings" to bindings(module).map { if (it["id"] == "main") it + ("expr" to expression) else it })
    private fun rejects(module: Map<String, Any?>) {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val error = assertThrows(PolyglotException::class.java) { load(context, backend, module) }
            assertTrue(error.message.orEmpty().contains("IO main"), "$backend: ${error.message}")
        }
    }

    @Test fun exactStateLambdaAndPapAliasesRunThroughBothBackends() {
        for (backend in listOf("ast", "bytecode")) for (prefix in listOf(0, 1, 3)) executionContext().use { context ->
            val direct = fixture(prefix)
            val alias = bindings(direct).first() + mapOf("id" to "alias", "name" to "alias")
            val main = bindings(direct).first() + ("expr" to v("alias"))
            val module = direct + ("bindings" to (listOf(main) + bindings(direct).drop(1) + alias))
            val action = load(context, backend, module)
            assertFalse(action.canExecute())
            repeat(2) { assertTrue(action.invokeMember("runIO").asBoolean()) }
            val diagnostics = Json.parse(action.getMember("diagnostics").asString()) as Map<String, Any?>
            assertEquals(0L, (diagnostics["unsupportedTraps"] as Number).toLong())
        }
    }

    @Test fun executableShutdownKeepsBothIoRootsAndRunsOnlyOnce() {
        val original = fixture()
        val shutdown = binding("shutdown", v("worker"))
        val module = original + ("bindings" to (bindings(original) + shutdown))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val action = load(context, backend, module, shutdown = "shutdown")
            assertTrue(action.invokeMember("runIO").asBoolean())
            val repeated = assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
            assertTrue(repeated.message.orEmpty().contains("already started"))
            // A raw --run-io action remains reusable for low-level fixtures.
            val raw = load(context, backend, module)
            repeat(2) { assertTrue(raw.invokeMember("runIO").asBoolean()) }
            val missing = assertThrows(PolyglotException::class.java) {
                load(context, backend, module, shutdown = "missing")
            }
            assertTrue(missing.message.orEmpty().contains("Missing or ambiguous entry"))
            val wrong = module + ("bindings" to (bindings(original) + (shutdown + ("type" to "IO Int"))))
            val invalid = assertThrows(PolyglotException::class.java) {
                load(context, backend, wrong, shutdown = "shutdown")
            }
            assertTrue(invalid.message.orEmpty().contains("IO ()"))
        }
    }

    @Test fun nestedPapsConsumeLogicalZeroWidthPrefixWithoutLosingRealStateBinder() {
        val original = fixture()
        val worker = bindings(original)[1]
        val expression = worker["expr"] as List<Any?>
        val formals = expression[1] as List<Map<String, Any?>>
        val newFormals = listOf(formals[0] + mapOf("type" to "State# OtherWorld", "rep" to state)) + formals.drop(1)
        val newWorker = worker + ("expr" to (expression.toMutableList().also { it[1] = newFormals }))
        val partial = binding("partial", app(v("worker"), listOf(listOf("void", mapOf("rep" to state)))))
        val module = original + ("bindings" to listOf(binding("main", app(v("partial"), listOf(n(1)))), newWorker, partial))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            assertTrue(load(context, backend, module).invokeMember("runIO").asBoolean())
        }
    }

    @Test fun zeroWidthImpostorsAndWrongStateRepresentationsRemainRejected() {
        for (type in listOf(null, "Proxy# a", "State# s", "(# #)", "Coercion#")) rejects(fixture(type = type))
        rejects(fixture(input = tuple()))
        rejects(fixture(input = integer))
    }

    @Test fun saturatedOversaturatedUnknownAndCyclicHeadsCannotInventRemainingFormals() {
        for (supplied in listOf(0, 1, 3, 4)) rejects(fixture(supplied = supplied))
        rejects(mainExpression(fixture(), app(v("unknown"), listOf(n(1), n(2)))))
        val original = fixture()
        val worker = bindings(original)[1] + ("expr" to v("main"))
        rejects(original + ("bindings" to listOf(bindings(original)[0], worker)))
    }

    @Test fun exactDeclaredIoAndStateUnitResultChecksRemainInForce() {
        for (output in listOf(unit, tuple(unit), tuple(integer, unit), tuple(state, integer), tuple(state, state, unit)))
            rejects(fixture(output = output))
        val original = fixture()
        val wrongMain = bindings(original)[0] + ("type" to "IO Int")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            assertThrows(PolyglotException::class.java) {
                load(context, backend, original + ("bindings" to listOf(wrongMain, bindings(original)[1])))
            }
            assertThrows(PolyglotException::class.java) { load(context, backend, original, diagnostic = true) }
        }
    }

    @Test fun aBoxedResultImpostorStillFailsTheRuntimeUnitConstructorCheck() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val action = load(context, backend, fixture(returnedConstructor = "NotUnit"))
            val error = assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
            assertTrue(error.message.orEmpty().contains("did not return boxed unit"))
        }
    }

    @Test fun consumedPrefixProofsStillReceiveStrictWholeProgramValidation() {
        val original = fixture()
        val worker = bindings(original)[1]
        val expression = worker["expr"] as List<Any?>
        val formals = expression[1] as List<Map<String, Any?>>
        val corrupt = formals[0] + ("rep" to (integer + ("primReps" to listOf("FloatRep"))))
        val changed = worker + ("expr" to expression.toMutableList().also { it[1] = listOf(corrupt) + formals.drop(1) })
        val module = original + ("bindings" to listOf(bindings(original)[0], changed))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            assertThrows(PolyglotException::class.java) { load(context, backend, module) }
        }
    }
}
