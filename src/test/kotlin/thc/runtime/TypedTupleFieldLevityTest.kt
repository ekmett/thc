// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import thc.Language

private typealias LevityCore = List<Any?>
private typealias LevityRep = Map<String, Any?>

/** Malformed Core boundary controls, paired across the two lowering backends. */
class TypedTupleFieldLevityTest {
    private fun scalar(kind: String, rep: String): LevityRep =
        mapOf("kind" to kind, "primReps" to listOf(rep), "evaluated" to true)
    private val integer = scalar("long", "IntRep")
    private val int32 = scalar("long", "Int32Rep")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private val state: LevityRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val vector: LevityRep = mapOf("kind" to "vector", "evaluated" to true,
        "primReps" to listOf("VecRep 4 Int32ElemRep"),
        "vector" to mapOf("lanes" to 4, "element" to "Int32ElemRep"))
    private val sum: LevityRep = mapOf("kind" to "unknown", "evaluated" to true,
        "aggregate" to "unboxed-sum", "alternatives" to List(4) { integer },
        "primReps" to listOf("WordRep", "WordRep"), "tagSlot" to 0,
        "alternativeSlots" to List(4) { listOf(1) })
    private fun tuple(vararg fields: LevityRep): LevityRep = mapOf("kind" to "unknown", "evaluated" to true,
        "aggregate" to "unboxed-tuple", "components" to fields.toList(),
        "primReps" to fields.flatMap { it["primReps"] as List<String> })
    private fun variable(id: String, rep: LevityRep = integer): LevityCore = listOf("var", id, mapOf("rep" to rep))
    private fun parameter(id: String, rep: LevityRep = integer) =
        mapOf("id" to id, "name" to id, "lifted" to false, "rep" to rep)
    private fun number(x: Long): LevityCore = listOf("lit", "int", x.toString(), mapOf("rep" to integer))
    private fun application(fn: LevityCore, args: List<LevityCore>, rep: LevityRep,
        flags: List<Any?> = List(args.size) { false }): LevityCore =
        listOf("app", fn, args, flags, false, false, mapOf("rep" to rep))
    private fun primitive(id: String, args: List<LevityCore>, rep: LevityRep = integer) =
        application(listOf("prim", id), args, rep)
    private fun pack(rep: LevityRep, vararg fields: LevityCore): LevityCore =
        if (fields.isEmpty()) listOf("con", "T0", 0, mapOf("rep" to rep))
        else application(listOf("con", "T${fields.size}", fields.size), fields.toList(), rep)
    private fun unpack(value: LevityCore, rep: LevityRep, ids: List<String>, body: LevityCore): LevityCore =
        listOf("case", value, "whole${ids.first()}", listOf(
            listOf("data", "T${ids.size}", ids, body, mapOf("binders" to
                ids.zip(rep["components"] as List<LevityRep>).map { parameter(it.first, it.second) }))),
            mapOf("rep" to integer, "binder" to parameter("whole${ids.first()}", rep)))
    private fun consumeSum(value: LevityCore): LevityCore = listOf("case", value, "wholeSum", listOf(
        listOf("data", "S4", listOf("sumPayload"), variable("sumPayload"),
            mapOf("binders" to listOf(parameter("sumPayload"))))),
        mapOf("rep" to integer, "binder" to parameter("wholeSum", sum)))

    private fun fixture(kind: String, flag: Any?): Map<String, Any?> {
        val sumValue = application(listOf("con", "S4", 1), listOf(number(17)), sum)
        val nested = tuple(sum, state, tuple())
        val proof: LevityRep
        val payload: LevityCore
        val consumed: LevityCore
        when (kind) {
            "vector" -> {
                proof = vector
                payload = primitive("broadcastInt32X4#", listOf(
                    primitive("intToInt32#", listOf(number(17)), int32)), vector)
                val lanes = tuple(int32, int32, int32, int32)
                consumed = unpack(primitive("unpackInt32X4#", listOf(variable("payload", vector)), lanes), lanes,
                    listOf("lane0", "lane1", "lane2", "lane3"),
                    primitive("int32ToInt#", listOf(variable("lane0", int32))))
            }
            "sum" -> {
                proof = sum
                payload = sumValue
                consumed = consumeSum(variable("payload", sum))
            }
            "nestedTuple" -> {
                proof = nested
                payload = pack(nested, sumValue, listOf("void", mapOf("rep" to state)), pack(tuple()))
                consumed = unpack(variable("payload", nested), nested, listOf("innerSum", "state", "empty"),
                    consumeSum(variable("innerSum", sum)))
            }
            else -> error("Unknown test shape: $kind")
        }
        val pair = tuple(proof, integer)
        val value = application(listOf("con", "T2", 2), listOf(payload, variable("input")), pair,
            listOf(flag, false))
        val body = unpack(value, pair, listOf("payload", "neighbour"),
            primitive("+#", listOf(consumed, variable("neighbour"))))
        val lambda: LevityCore = listOf("lam", listOf(parameter("input")), body,
            mapOf("rep" to closure, "resultRep" to integer, "entryStrict" to listOf(false)))
        val constructors = (0..4).map {
            mapOf("id" to "T$it", "name" to "T$it", "kind" to "unboxed-tuple", "arity" to it)
        } + (1..4).map {
            mapOf("id" to "S$it", "name" to "S$it", "kind" to "unboxed-sum", "arity" to 1,
                "sumArity" to 4, "tag" to it)
        }
        return mapOf("constructors" to constructors, "bindings" to listOf(
            mapOf("id" to "entry", "name" to "entry", "lifted" to true, "rep" to closure, "expr" to lambda)))
    }

    @TestFactory fun typedTupleFieldsRequireBooleanFalse(): List<DynamicTest> = buildList {
        for (backend in listOf("ast", "bytecode")) for (kind in listOf("vector", "sum", "nestedTuple")) {
            for ((name, flag) in listOf("false" to false, "true" to true, "nonBoolean" to "false")) {
                add(DynamicTest.dynamicTest("$backend/$kind/$name") {
                    Context.newBuilder("thc").build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            fun execute() {
                                val module = fixture(kind, flag)
                                val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                                // Resolve and execute the entry: deferred loading must not hide malformed bodies.
                                for (input in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) {
                                    assertEquals(input + 17L, Calls.target(program.hostEntryTarget(1),
                                        arrayOf(program.entryValue("entry"), arrayOf(input))))
                                }
                            }
                            if (flag == false) execute()
                            else {
                                val fault = assertThrows(RuntimeFault::class.java) { execute() }
                                assertEquals("Typed tuple field cannot be lifted", fault.message)
                            }
                        } finally { context.leave() }
                    }
                })
            }
        }
    }
}
