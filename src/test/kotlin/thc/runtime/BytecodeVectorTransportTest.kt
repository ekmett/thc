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

private typealias VectorCore = List<Any?>
private typealias VectorRep = Map<String, Any?>

/** Bytecode transport controls; these do not replace exported-Core/native SIMD evidence. */
class BytecodeVectorTransportTest {
    private val integer = scalar("long", "IntRep")
    private val word = scalar("long", "WordRep")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private fun scalar(kind: String, rep: String): VectorRep =
        mapOf("kind" to kind, "primReps" to listOf(rep), "evaluated" to true)
    private fun tuple(fields: List<VectorRep>): VectorRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "components" to fields, "primReps" to fields.flatMap { it["primReps"] as List<String> }, "evaluated" to true)
    private data class Family(val name: String, val element: String, val lanes: Int) {
        val laneName = element.removeSuffix("ElemRep")
        val lane: VectorRep = mapOf("kind" to when (laneName) { "Float" -> "float"; "Double" -> "double"; else -> "long" },
            "primReps" to listOf("${laneName}Rep"), "evaluated" to true)
        val vector: VectorRep = mapOf("kind" to "vector", "primReps" to listOf("VecRep $lanes $element"),
            "vector" to mapOf("lanes" to lanes, "element" to element), "evaluated" to true)
    }
    private fun families(): List<Family> {
        val table = Json.parse(File(System.getProperty("thc.projectRoot"), "scripts/simd-families.json").readText()) as Map<String, Any?>
        return (table["families"] as List<Map<String, Any?>>).map {
            Family(it["name"] as String, it["element"] as String, (it["lanes"] as Number).toInt())
        }.also { assertEquals(24, it.size) }
    }
    private fun variable(id: String, proof: VectorRep = integer): VectorCore = listOf("var", id, mapOf("rep" to proof))
    private fun number(value: Long): VectorCore = listOf("lit", "int", value.toString(), mapOf("rep" to integer))
    private fun parameter(id: String, proof: VectorRep = integer) =
        mapOf("id" to id, "name" to id, "lifted" to (proof == closure), "rep" to proof)
    private fun application(fn: VectorCore, args: List<VectorCore>, result: VectorRep): VectorCore =
        listOf("app", fn, args, List(args.size) { false }, false, false, mapOf("rep" to result))
    private fun call(id: String, args: List<VectorCore>, result: VectorRep) = application(variable(id, closure), args, result)
    private fun primitive(id: String, args: List<VectorCore>, result: VectorRep = integer) = application(listOf("prim", id), args, result)
    private fun lambda(args: List<Map<String, Any?>>, body: VectorCore, result: VectorRep): VectorCore =
        listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to result, "entryStrict" to List(args.size) { false }))
    private fun binding(id: String, body: VectorCore) = mapOf("id" to id, "name" to id, "lifted" to true, "rep" to closure, "expr" to body)
    private fun vectorLet(id: String, proof: VectorRep, rhs: VectorCore, body: VectorCore,
        recursive: Boolean = false, lifted: Boolean = false): VectorCore = listOf("let", recursive,
        listOf(mapOf("id" to id, "name" to id, "lifted" to lifted, "rep" to proof, "expr" to rhs)), body)
    private fun localLets(vector: VectorRep, payload: VectorCore): VectorCore =
        vectorLet("x", vector, call("identity", listOf(payload), vector),
            vectorLet("x", vector, call("identity", listOf(variable("x", vector)), vector),
                vectorLet("copied", vector, variable("x", vector), variable("copied", vector))))
    private fun choice(condition: VectorCore, yes: VectorCore, no: VectorCore, result: VectorRep): VectorCore =
        listOf("case", condition, "condition", listOf(
            listOf("lit", listOf("int", "1"), emptyList<String>(), yes),
            listOf("default", null, emptyList<String>(), no)), mapOf("rep" to result, "binder" to parameter("condition")))
    private fun laneValue(family: Family, value: VectorCore): VectorCore {
        val operation = when (family.laneName) {
            "Float" -> "int2Float#"
            "Double" -> "int2Double#"
            else -> if (family.laneName.startsWith("Word")) "wordTo${family.laneName}#" else "intTo${family.laneName}#"
        }
        return primitive(operation, listOf(if (family.laneName.startsWith("Word")) primitive("int2Word#", listOf(value), word) else value), family.lane)
    }
    private fun lanes(family: Family, input: VectorCore): VectorCore {
        val values = (0 until family.lanes).map { index ->
            laneValue(family, primitive("+#", listOf(input, number(index.toLong()))))
        }
        val fields = tuple(List(family.lanes) { family.lane })
        return primitive("pack${family.name}#", listOf(application(listOf("con", "T${family.lanes}", family.lanes), values, fields)), family.vector)
    }
    private fun fixture(family: Family): Map<String, Any?> {
        val vector = family.vector
        fun function(id: String, next: String) = binding(id, lambda(listOf(parameter("v", vector), parameter("n")),
            choice(primitive("<=#", listOf(variable("n"), number(0))), variable("v", vector),
                call(next, listOf(variable("v", vector), primitive("-#", listOf(variable("n"), number(1)))), vector), vector), vector))
        val payload = lanes(family, variable("x"))
        val pair = tuple(listOf(vector, integer))
        val pairValue = application(listOf("con", "T2", 2), listOf(payload, number(19)), pair)
        val pairResult: VectorCore = listOf("case", call("pairIdentity", listOf(pairValue), pair), "whole", listOf(
            listOf("data", "T2", listOf("projected", "unused"), variable("projected", vector),
                mapOf("binders" to listOf(parameter("projected", vector), parameter("unused"))))),
            mapOf("rep" to vector, "binder" to parameter("whole", pair)))
        val join = binding("swap", lambda(listOf(parameter("left", vector), parameter("right", vector), parameter("n")),
            choice(primitive("<=#", listOf(variable("n"), number(0))), variable("left", vector),
                call("swap", listOf(variable("right", vector), variable("left", vector),
                    primitive("-#", listOf(variable("n"), number(1)))), vector), vector), vector)) +
            mapOf("joinValueArity" to 3, "joinResultRep" to vector)
        val joinValue: VectorCore = listOf("let", true, listOf(join),
            call("swap", listOf(lanes(family, number(91)), payload, number(101)), vector), mapOf("rep" to vector))
        fun entry(name: String, body: VectorCore) = binding(name, lambda(listOf(parameter("x")), body, vector))
        return mapOf("instrument" to true, "constructors" to listOf(2, family.lanes).distinct().map {
            mapOf("id" to "T$it", "name" to "T$it", "kind" to "unboxed-tuple", "arity" to it)
        }, "bindings" to listOf(
            binding("identity", lambda(listOf(parameter("v", vector)), variable("v", vector), vector)),
            binding("worker", lambda(listOf(parameter("v", vector), parameter("ignored")), variable("v", vector), vector)),
            binding("pairIdentity", lambda(listOf(parameter("p", pair)), variable("p", pair), pair)),
            binding("make", lambda(listOf(parameter("outer")), lambda(listOf(parameter("v", vector)),
                choice(primitive("==#", listOf(variable("outer"), number(19))), variable("v", vector), lanes(family, number(0)), vector), vector), closure)),
            function("self", "self"), function("mutualA", "mutualB"), function("mutualB", "mutualA"),
            binding("prefix", lambda(listOf(parameter("x")), call("worker", listOf(payload), closure), closure)),
            entry("exact", call("identity", listOf(payload), vector)),
            entry("pap", application(call("worker", listOf(payload), closure), listOf(number(19)), vector)),
            entry("roundTrip", call("identity", listOf(call("identity", listOf(payload), vector)), vector)),
            entry("arithmetic", primitive("plus${family.name}#", listOf(call("identity", listOf(payload), vector),
                primitive("broadcast${family.name}#", listOf(laneValue(family, number(0))), vector)), vector)),
            entry("local", application(lambda(listOf(parameter("v", vector)), variable("v", vector), vector), listOf(payload), vector)),
            entry("localLet", localLets(vector, payload)),
            entry("over", call("make", listOf(number(19), payload), vector)),
            entry("selfTail", call("self", listOf(payload, number(100)), vector)),
            entry("mutualTail", call("mutualA", listOf(payload, number(100)), vector)),
            entry("joinSwap", joinValue), entry("tupleField", pairResult)))
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun withLanguage(inlining: Boolean = true, action: (Language) -> Unit) = context(inlining).use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertNull(state.pending)
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    private fun check(program: BytecodeProgram, language: Language, family: Family, name: String, input: Long) {
        val root = program.entryTarget(name).rootNode as BytecodeRoot
        val shape = requireNotNull(root.tupleResult)
        assertEquals(family.lanes, shape.width)
        assertTrue(shape.proof.isVector); assertFalse(shape.proof.isTuple)
        val result = ownedTupleResult(Calls.target(root.callTarget, arrayOf(0L, input)), shape)
        for (lane in 0 until family.lanes) {
            val value = input + lane
            val label = "${family.name}/$name/$input/lane=$lane"
            when (family.laneName) {
                "Float" -> assertEquals(value.toFloat().toRawBits(), shape.layout.getFloat(result, lane).toRawBits(), label)
                "Double" -> assertEquals(value.toDouble().toRawBits(), shape.layout.getDouble(result, lane).toRawBits(), label)
                else -> {
                    val expected = when (family.laneName) {
                        "Int8" -> value.toByte().toLong(); "Word8" -> value and 255L
                        "Int16" -> value.toShort().toLong(); "Word16" -> value and 65535L
                        "Int32" -> value.toInt().toLong(); "Word32" -> value and 0xffff_ffffL
                        else -> value
                    }
                    assertEquals(expected, shape.layout.getLong(result, lane), label)
                }
            }
        }
        released(language)
    }
    private val paths = listOf("exact", "pap", "roundTrip", "arithmetic", "local", "localLet", "over", "selfTail", "mutualTail", "joinSwap", "tupleField")
    private val entryCounts = mapOf("exact" to 2L, "pap" to 2L, "roundTrip" to 3L, "arithmetic" to 2L,
        "local" to 2L, "localLet" to 3L, "over" to 3L, "joinSwap" to 1L, "tupleField" to 2L)
    private val values = listOf(Long.MIN_VALUE, -129L, -1L, 0L, 127L, Long.MAX_VALUE)

    @Test fun allExistingFamiliesCarryExactLanesThroughCallsAndJoins() = withLanguage { language ->
        for (family in families()) {
            val program = BytecodeProgram(language, fixture(family))
            for (name in paths) for (value in values) check(program, language, family, name, value)
            val prefix = Calls.target(program.entryTarget("prefix"), arrayOf(0L, -1L)) as Closure
            assertEquals(1, prefix.suppliedCount); assertEquals(1, prefix.arity); assertEquals(0, prefix.supplied.size)
            assertNotNull(prefix.typedSupplied)
            val input = (program.entryTarget("worker").rootNode as GuestRoot).typedInput!!
            assertEquals(2, input.logical.logicalArity); assertEquals(family.lanes + 1, input.logical.physicalArity)
            assertEquals(family.lanes, prefix.typedSupplied!!.layout.reps.size)
            assertTrue(prefix.typedSupplied!!.layout.reps.none { it == "reference" })
            released(language)
        }
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = arrayListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(root)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }.forEach {
                val callee = it.currentCallTarget as? RootCallTarget
                if (callee?.rootNode is GuestRoot) visit(callee)
            }
            targets += target
        }
        visit(entry)
        return targets
    }
    private fun valid(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())

    @Test fun compiledCallsRemainInstalledFromTheFirstEntryWithAndWithoutInlining() {
        for (inlining in listOf(true, false)) withLanguage(inlining) { language ->
            for (family in families().filter { it.name in setOf("Int32X4", "Word8X16", "FloatX4", "DoubleX2") }) {
                val program = BytecodeProgram(language, fixture(family))
                for (name in paths) {
                    for (value in values) check(program, language, family, name, value)
                    val entry = program.entryTarget(name)
                    val active = activeTargets(entry)
                    for (target in active) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                    }
                    for (value in values.asReversed()) {
                        val before = program.diagnostics()["compiledEntries"] as Long
                        check(program, language, family, name, value)
                        val entered = program.diagnostics()["compiledEntries"] as Long - before
                        entryCounts[name]?.let { assertEquals(it, entered, "${family.name}/$name exact compiled entries") }
                            ?: assertTrue(entered > 0, "${family.name}/$name compiled tail entry")
                        assertEquals(active, activeTargets(entry), "${family.name}/$name active targets")
                        active.forEach(::valid)
                    }
                }
            }
        }
    }

    @Test fun sameWidthVectorAndTupleInputsRemainLogicallyDistinct() = withLanguage { language ->
        val family = families().single { it.name == "Int32X4" }
        val program = BytecodeProgram(language, fixture(family))
        val closureValue = program.entryValue("identity") as Closure
        val wrong = ArgumentLayout.fromProofs(listOf(CoreRepresentations.parse(tuple(List(family.lanes) { family.lane }))))
        assertThrows(RuntimeFault::class.java) { ArgumentLayout.validate(closureValue, wrong, 0, 1) }
        val unsigned = families().single { it.name == "Word32X4" }
        val otherVector = ArgumentLayout.fromProofs(listOf(CoreRepresentations.parse(unsigned.vector)))
        assertThrows(RuntimeFault::class.java) { ArgumentLayout.validate(closureValue, otherVector, 0, 1) }
        released(language)
    }

    @Test fun vectorLetsPreserveFloatingBitsFromTheFirstCompiledEntry() {
        for (inlining in listOf(true, false)) withLanguage(inlining) { language ->
            for (family in families().filter { it.name in setOf("FloatX4", "DoubleX2") }) {
                val floating = family.laneName == "Float"
                val bitsProof = scalar("long", if (floating) "Word32Rep" else "Word64Rep")
                val bits = primitive(if (floating) "wordToWord32#" else "wordToWord64#",
                    listOf(primitive("int2Word#", listOf(variable("x")), word)), bitsProof)
                val lane = primitive(if (floating) "castWord32ToFloat#" else "castWord64ToDouble#", listOf(bits), family.lane)
                val body = localLets(family.vector, primitive("broadcast${family.name}#", listOf(lane), family.vector))
                val original = fixture(family)
                val module = original + ("bindings" to (original["bindings"] as List<Map<String, Any?>>) +
                    binding("bitsLet", lambda(listOf(parameter("x")), body, family.vector)))
                val program = BytecodeProgram(language, module)
                val inputs = if (floating) listOf(0L, 0x80000000L, 1L, 0x7fc01234L, 0x7f800000L, 0xff800000L)
                    else listOf(0L, Long.MIN_VALUE, 1L, 0x7ff8000000001234L, 0x7ff0000000000000L, -4503599627370496L)
                val entry = program.entryTarget("bitsLet")
                val shape = requireNotNull((entry.rootNode as BytecodeRoot).tupleResult)
                fun check(value: Long) {
                    val result = ownedTupleResult(Calls.target(entry, arrayOf(0L, value)), shape)
                    for (index in 0 until family.lanes) {
                        if (floating) assertEquals(value.toInt(), shape.layout.getFloat(result, index).toRawBits())
                        else assertEquals(value, shape.layout.getDouble(result, index).toRawBits())
                    }
                    released(language)
                }
                inputs.forEach(::check)
                val active = activeTargets(entry)
                active.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                for (value in inputs.asReversed()) {
                    val before = program.diagnostics()["compiledEntries"] as Long
                    check(value)
                    assertEquals(3L, program.diagnostics()["compiledEntries"] as Long - before)
                    assertEquals(active, activeTargets(entry))
                    active.forEach(::valid)
                }
            }
        }
    }

    @Test fun vectorLetsRejectRecursiveLiftedAndMismatchedValues() = withLanguage { language ->
        val family = families().single { it.name == "Int32X4" }
        val payload = lanes(family, variable("x"))
        fun reject(body: VectorCore, result: VectorRep = family.vector) {
            val original = fixture(family)
            val module = original + ("bindings" to (original["bindings"] as List<Map<String, Any?>>) +
                binding("badLet", lambda(listOf(parameter("x")), body, result)))
            assertThrows(RuntimeFault::class.java) { BytecodeProgram(language, module).entryTarget("badLet") }
            released(language)
        }
        reject(vectorLet("v", family.vector, payload, variable("v", family.vector), recursive = true))
        reject(vectorLet("v", family.vector, payload, variable("v", family.vector), lifted = true))
        val other = families().single { it.name == "Word32X4" }
        reject(vectorLet("v", family.vector, lanes(other, variable("x")), variable("v", family.vector)))
        reject(vectorLet("v", family.vector, number(0), variable("v", family.vector)))
        // Ordinary closures retaining a lexical vector now have owned lane
        // storage; the genuine capturedCase Core path is proved by SimdCallNativeTest.
        val fields = tuple(List(family.lanes) { family.lane })
        reject(vectorLet("v", fields,
            application(listOf("con", "T${family.lanes}", family.lanes),
                List(family.lanes) { laneValue(family, number(it.toLong())) }, fields), variable("v", fields)), fields)
    }
}
