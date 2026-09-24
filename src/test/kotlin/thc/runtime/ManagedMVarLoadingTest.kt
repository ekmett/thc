// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias MVarProof = Map<String, Any?>

/** Synthetic load-time contracts only: no MVar operation is executed here. */
class ManagedMVarLoadingTest {
    private val liftedBox = "BoxedRep (Just Lifted)"
    private val unliftedBox = "BoxedRep (Just Unlifted)"
    private fun leaf(kind: String, vararg registers: String): MVarProof =
        mapOf("kind" to kind, "primReps" to registers.toList(), "evaluated" to true)
    private val state = leaf("void")
    private val integer = leaf("long", "IntRep")
    private val closure = leaf("closure", liftedBox)
    private fun tuple(fields: List<MVarProof>): MVarProof = mapOf(
        "kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to fields,
        "primReps" to fields.flatMap { it["primReps"] as List<*> }, "evaluated" to true)
    private fun role(role: String, lifted: Boolean, kind: String): MVarProof = when (role) {
        "state" -> state
        "mvar" -> leaf("object", unliftedBox)
        "flag" -> integer
        else -> leaf(kind, if (lifted) liftedBox else unliftedBox)
    }
    private data class Contract(val name: String, val arguments: List<String>, val results: List<String>)
    // Independent of MVarOp's private role tables, including logical zero-width State#.
    private val contracts = listOf(
        Contract("newMVar#", listOf("state"), listOf("state", "mvar")),
        Contract("takeMVar#", listOf("mvar", "state"), listOf("state", "boxed")),
        Contract("putMVar#", listOf("mvar", "boxed", "state"), listOf("state")),
        Contract("readMVar#", listOf("mvar", "state"), listOf("state", "boxed")),
        Contract("tryTakeMVar#", listOf("mvar", "state"), listOf("state", "flag", "boxed")),
        Contract("tryPutMVar#", listOf("mvar", "boxed", "state"), listOf("state", "flag")),
        Contract("tryReadMVar#", listOf("mvar", "state"), listOf("state", "flag", "boxed")),
        Contract("isEmptyMVar#", listOf("mvar", "state"), listOf("state", "flag")))

    private inner class Fixture(val contract: Contract, lifted: Boolean = true, kind: String = "object") {
        val parameters = contract.arguments.mapIndexed { index, role -> mutableMapOf<String, Any?>(
            "id" to "x$index", "lifted" to (role == "boxed" && lifted), "rep" to role(role, lifted, kind))
        }.toMutableList()
        val arguments = parameters.map { variable(it["id"] as String, it["rep"] as MVarProof) }.toMutableList()
        val flags = parameters.map { it["lifted"] }.toMutableList()
        val resultFields = contract.results.map { role(it, lifted, kind) }
        val result = if (contract.name == "putMVar#") state else tuple(resultFields)
        val metadata = mutableMapOf<String, Any?>("rep" to result)
        val application = listOf("app", listOf("prim", contract.name), arguments, flags, false, false, metadata)
        val binding = mutableMapOf<String, Any?>("id" to "root", "name" to "root", "lifted" to true,
            "arity" to parameters.size, "rep" to closure,
            "expr" to listOf("lam", parameters, application, mapOf("rep" to closure, "resultRep" to result)))
        val bindings = mutableListOf<Map<String, Any?>>(binding)
        val module: Map<String, Any?> = mapOf("bindings" to bindings, "constructors" to listOf(mapOf(
            "id" to "Carrier", "name" to "Carrier", "arity" to 0, "kind" to "boxed",
            "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>(),
            "fieldTypes" to emptyList<Any?>(), "fieldReps" to emptyList<Any?>())))

        fun stored(index: Int, proof: MVarProof, global: Boolean) {
            val parameter = parameters.single { it["id"] == "x$index" }
            if (!global) parameter["rep"] = proof else {
                parameters.remove(parameter)
                // Keep an ordinary lambda even when newMVar#'s sole operand becomes global.
                if (parameters.isEmpty()) parameters.add(mutableMapOf(
                    "id" to "unused", "lifted" to false, "rep" to integer))
                binding["arity"] = parameters.size
                bindings.add(global("x$index", proof))
            }
        }
    }

    private fun variable(id: String, proof: MVarProof): List<Any?> = listOf("var", id, mapOf("rep" to proof))
    private fun global(id: String, proof: MVarProof): Map<String, Any?> {
        val registers = proof["primReps"] as List<*>
        // Initializers are inert scalar/constructor/closure values, never MVar calls.
        // An object proof does not assert a particular runtime reference class.
        val expression = when {
            registers.isEmpty() -> listOf("void", mapOf("rep" to state))
            registers == listOf("IntRep") -> listOf("lit", "int", "0", mapOf("rep" to integer))
            proof["kind"] == "closure" -> listOf("lam", listOf(mapOf(
                "id" to "ignored", "lifted" to false, "rep" to state)),
                listOf("void", mapOf("rep" to state)), mapOf("rep" to proof, "resultRep" to state))
            else -> listOf("con", "Carrier", 0, mapOf("rep" to (proof + ("kind" to "data"))))
        }
        return mapOf("id" to id, "name" to id,
            "lifted" to (registers.singleOrNull() in setOf(liftedBox, "BoxedRep Nothing")),
            "rep" to proof, "expr" to expression)
    }

    private fun forBackends(action: (Language, String) -> Unit) {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), backend) }
            finally { context.leave() }
        }
    }
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun accepts(language: Language, backend: String, fixture: Fixture, label: String) {
        assertDoesNotThrow({ load(language, backend, fixture.module) }, "$backend/$label")
    }
    private fun rejects(language: Language, backend: String, fixture: Fixture, label: String,
                        message: String? = null, diagnostic: Boolean = false) {
        val failure = assertThrows(RuntimeFault::class.java, {
            load(language, backend, fixture.module + ("diagnosticUnsupported" to diagnostic))
        }, "$backend/$label/diagnostic=$diagnostic")
        if (message != null) assertTrue(failure.message.orEmpty().contains(message),
            "$backend/$label: expected '$message', got '${failure.message}'")
    }

    @Test fun everyPrimitiveLoadsWithBothPayloadLevitiesAndAllBoxedKinds() = forBackends { language, backend ->
        for (contract in contracts) for (lifted in listOf(false, true)) for (kind in listOf("data", "closure", "object"))
            accepts(language, backend, Fixture(contract, lifted, kind), "${contract.name}/$lifted/$kind")
    }

    @Test fun localAndGlobalDataOrClosureCannotBeRelabelledAsMVarObjects() = forBackends { language, backend ->
        for (contract in contracts.filter { "mvar" in it.arguments }) for (kind in listOf("data", "closure"))
            for (global in listOf(false, true)) {
                val fixture = Fixture(contract)
                fixture.stored(contract.arguments.indexOf("mvar"), leaf(kind, unliftedBox), global)
                rejects(language, backend, fixture, "${contract.name}/$kind/global=$global",
                    "MVar argument contradicts its binding proof")
            }
    }

    @Test fun objectPayloadOccurrencesMayRetainStoredDataOrClosureKinds() = forBackends { language, backend ->
        for (contract in contracts.filter { "boxed" in it.arguments }) for (kind in listOf("data", "closure"))
            for (lifted in listOf(false, true)) for (global in listOf(false, true)) {
                val fixture = Fixture(contract, lifted)
                fixture.stored(contract.arguments.indexOf("boxed"), leaf(kind, if (lifted) liftedBox else unliftedBox), global)
                accepts(language, backend, fixture, "${contract.name}/$kind/$lifted/global=$global")
            }
    }

    @Test fun unknownKindDoesNotEraseConcreteScalarRegistersAtAnyOperand() = forBackends { language, backend ->
        for (contract in contracts) for (index in contract.arguments.indices) for (global in listOf(false, true)) {
            val fixture = Fixture(contract)
            fixture.stored(index, leaf("unknown", "IntRep"), global)
            rejects(language, backend, fixture, "${contract.name}/operand$index/global=$global",
                "MVar argument contradicts its binding proof")
        }
    }

    @Test fun unknownBoxedLevityCannotRefineToZeroWidthState() = forBackends { language, backend ->
        for (kind in listOf("unknown", "object", "data", "closure")) for (global in listOf(false, true)) {
            val fixture = Fixture(contracts.first())
            fixture.stored(0, leaf(kind, "BoxedRep Nothing"), global)
            rejects(language, backend, fixture, "$kind/global=$global", "MVar argument contradicts its binding proof")
        }
    }

    @Test fun unknownBoxedLevityMayRefineToExactBoxedPayloads() = forBackends { language, backend ->
        for (contract in contracts.filter { "boxed" in it.arguments }) for (kind in listOf("unknown", "object", "data", "closure"))
            for (lifted in listOf(false, true)) for (global in listOf(false, true)) {
                val fixture = Fixture(contract, lifted)
                fixture.stored(contract.arguments.indexOf("boxed"), leaf(kind, "BoxedRep Nothing"), global)
                accepts(language, backend, fixture, "${contract.name}/$kind/$lifted/global=$global")
            }
    }

    @Test fun lexicalLocalsShadowGlobalBindingProofsInBothDirections() = forBackends { language, backend ->
        val contract = contracts.single { it.name == "isEmptyMVar#" }
        val positive = Fixture(contract)
        positive.bindings.add(global("x0", integer))
        accepts(language, backend, positive, "local MVar shadows global IntRep")
        val negative = Fixture(contract)
        negative.stored(0, leaf("data", unliftedBox), false)
        negative.bindings.add(global("x0", leaf("object", unliftedBox)))
        rejects(language, backend, negative, "local data shadows global object", "MVar argument contradicts its binding proof")
        val stateShadow = Fixture(contracts.first())
        stateShadow.bindings.add(global("x0", integer))
        accepts(language, backend, stateShadow, "local State shadows global IntRep")
    }

    @Test fun loweredCompositeOperandsAreRevalidatedAfterOccurrenceChecks() = forBackends { language, backend ->
        for (kind in listOf("data", "closure")) {
            val fixture = Fixture(contracts.single { it.name == "isEmptyMVar#" })
            val stored = leaf(kind, unliftedBox)
            fixture.stored(0, stored, false)
            // A non-var operand bypasses validateBindings; lowering retains its
            // more precise data/closure kind despite the outer object occurrence.
            fixture.arguments[0] = listOf("let", false, emptyList<Any?>(), variable("x0", stored),
                mapOf("rep" to leaf("object", unliftedBox)))
            rejects(language, backend, fixture, kind, "MVar primitive argument representation mismatch")
        }
    }

    @Test fun argumentRolesFlagsAndLogicalArityRemainExactInBothLoadModes() = forBackends { language, backend ->
        for (contract in contracts) for (diagnostic in listOf(false, true)) {
            for (index in contract.arguments.indices) {
                for (badFlag in listOf(null, 0L, "false", contract.arguments[index] != "boxed")) {
                    val fixture = Fixture(contract)
                    fixture.flags[index] = badFlag
                    rejects(language, backend, fixture, "${contract.name}/flag$index=$badFlag", diagnostic = diagnostic)
                }
                for (bad in listOf(leaf("unknown"), leaf("object", "BoxedRep Nothing"),
                    leaf("address", "AddrRep"), integer, tuple(emptyList()))) {
                    val fixture = Fixture(contract)
                    fixture.arguments[index] = variable("x$index", bad)
                    rejects(language, backend, fixture, "${contract.name}/operand$index=$bad", diagnostic = diagnostic)
                }
            }
            for (mutation in 0..3) {
                val fixture = Fixture(contract)
                when (mutation) {
                    0 -> { fixture.arguments.removeLast(); fixture.flags.removeLast() }
                    1 -> { fixture.arguments.add(fixture.arguments.first()); fixture.flags.add(false) }
                    2 -> fixture.flags.removeLast()
                    else -> fixture.flags.add(false)
                }
                rejects(language, backend, fixture, "${contract.name}/arity$mutation", diagnostic = diagnostic)
            }
        }
    }

    @Test fun resultsRetainLogicalStateExactFlagsAndFlattenedTupleProofs() = forBackends { language, backend ->
        for (contract in contracts) for (diagnostic in listOf(false, true)) {
            val template = Fixture(contract)
            val malformed = mutableListOf<MVarProof?>(null, integer, tuple(emptyList()))
            if (contract.name != "putMVar#") {
                malformed.add(tuple(template.resultFields.drop(1))) // State# cannot disappear from the logical tuple.
                malformed.add(tuple(listOf(tuple(emptyList())) + template.resultFields.drop(1)))
                malformed.add(template.result + ("primReps" to emptyList<String>()))
                // A generic unsupported tuple leaf is deliberately deferred by
                // diagnostic mode; strict mode must still reject it at load time.
                if (!diagnostic) malformed.add(tuple(listOf(leaf("unknown")) + template.resultFields.drop(1)))
                for (index in contract.results.indices) {
                    val fields = template.resultFields.toMutableList()
                    fields[index] = when (contract.results[index]) {
                        "state" -> leaf("object", unliftedBox)
                        "flag" -> leaf("long", "WordRep")
                        "mvar" -> leaf("object", liftedBox)
                        else -> integer
                    }
                    malformed.add(tuple(fields))
                }
            }
            for ((index, proof) in malformed.withIndex()) {
                val fixture = Fixture(contract)
                fixture.metadata["rep"] = proof
                rejects(language, backend, fixture, "${contract.name}/result$index", diagnostic = diagnostic)
            }
        }
    }
}
