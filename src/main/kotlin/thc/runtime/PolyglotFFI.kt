// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.InteropException
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.source.Source
import thc.Language

/** The handle owns a managed foreign value, never an address into either heap. */
internal class ForeignValue(val owner: Language.State, val receiver: Any)

internal enum class PolyglotOp(val symbol: String, val arguments: List<String>, val result: String) {
    EVAL("thc_polyglot_v1_eval", listOf("AddrRep", "AddrRep", "AddrRep", STATE), BOXED),
    READ_MEMBER("thc_polyglot_v1_read_member", listOf(BOXED, "AddrRep", STATE), BOXED),
    EXECUTE_INT("thc_polyglot_v1_execute_int", listOf(BOXED, "IntRep", STATE), "IntRep");
}

private const val STATE = "State# RealWorld"
private const val BOXED = "BoxedRep (Just Lifted)"

/** Only the explicitly declared, saturated versioned ABI crosses this boundary. */
internal object CorePolyglot {
    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid polyglot foreign call: $detail")
    }

    private fun matches(proof: CoreRepresentation, rep: String): Boolean = !proof.isAggregate && !proof.isVector &&
        proof.present && proof.primReps == (if (rep == STATE) emptyList<String>() else listOf(rep)) &&
        proof.kind == when (rep) {
            STATE -> CoreKind.VOID
            "AddrRep" -> CoreKind.ADDRESS
            "IntRep" -> CoreKind.LONG
            else -> CoreKind.OBJECT
        }

    private fun result(proof: CoreRepresentation, rep: String): Boolean = proof.isTuple &&
        proof.components?.size == 2 && matches(proof.components[0], STATE) && matches(proof.components[1], rep) &&
        proof.primReps == listOf(rep)

    fun validate(expr: List<Any?>, defined: Boolean): PolyglotOp? {
        val metadata = CoreRepresentations.metadata(expr) ?: return null
        val descriptor = metadata["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val op = PolyglotOp.entries.firstOrNull { it.symbol == symbol }
            ?: throw UnsupportedCore("Unsupported foreign call: $symbol")
        val function = expr.getOrNull(1) as? List<*> ?: throw RuntimeFault("Missing foreign function")
        val arguments = expr.getOrNull(2) as? List<List<Any?>> ?: throw RuntimeFault("Missing foreign arguments")
        val flags = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Missing foreign representation flags")
        fun exact(value: Any?, number: Int) = (value is Long || value is Int) && (value as Number).toLong() == number.toLong()
        requireProof(expr.firstOrNull() == "app" && function.firstOrNull() == "var" && function.getOrNull(1) is String && !defined,
            "expected an unresolved foreign identifier")
        requireProof(exact(descriptor["schema"], 1) && target["kind"] == "static" && target["isFunction"] == true,
            "expected a static version 1 function declaration")
        requireProof(descriptor["convention"] == "prim" && descriptor["safety"] == "safe", "calling convention")
        requireProof(exact(descriptor["arity"], op.arguments.size) && exact(descriptor["suppliedArity"], op.arguments.size) &&
            arguments.size == op.arguments.size && flags.size == arguments.size, "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*> ?: throw RuntimeFault("Missing foreign argument declarations")
        requireProof(declared.size == op.arguments.size && op.arguments.indices.all { i ->
            matches(CoreRepresentations.parse(declared[i]), op.arguments[i]) &&
                matches(CoreRepresentations.expression(arguments[i]), op.arguments[i]) && flags[i] == (op.arguments[i] == BOXED)
        }, "argument representations")
        requireProof(result(CoreRepresentations.parse(descriptor["resultRep"]), op.result) &&
            result(CoreRepresentations.expression(expr), op.result), "state/result tuple")
        return op
    }
}

/** Cached interop messages can specialize on the foreign language's actual objects. */
internal class PolyglotAccess : Node() {
    @Child private var force = Force(Metrics(false))
    @Child private var evalCall = IndirectCallNode.create()
    @Child private var members = InteropLibrary.getFactory().createDispatched(3)
    @Child private var functions = InteropLibrary.getFactory().createDispatched(3)
    @Child private var numbers = InteropLibrary.getFactory().createDispatched(3)

    @TruffleBoundary
    private fun parse(language: LiteralAddress, source: LiteralAddress, name: LiteralAddress) =
        Language.currentState(this).env.parsePublic(Source.newBuilder(language.utf8(), source.utf8(), name.utf8()).build())

    fun eval(language: LiteralAddress, source: LiteralAddress, name: LiteralAddress, state: Any?): ForeignValue {
        requireVoidCarrier(state)
        val owner = Language.currentState(this)
        val value = evalCall.call(parse(language, source, name))
        return ForeignValue(owner, value ?: fault("Foreign evaluation returned a host null"))
    }

    private fun receiver(frame: VirtualFrame, value: Any?): Any {
        val handle = force.execute(frame, value) as? ForeignValue ?: fault("Expected THC.Polyglot.Value")
        if (handle.owner !== Language.currentState(this)) fault("Polyglot value belongs to a different context")
        return handle.receiver
    }

    fun readMember(frame: VirtualFrame, value: Any?, name: LiteralAddress, state: Any?): ForeignValue {
        requireVoidCarrier(state)
        val receiver = receiver(frame, value)
        return try {
            ForeignValue(Language.currentState(this), members.readMember(receiver, name.utf8()))
        } catch (error: InteropException) { interopFailure("readMember", error) }
    }

    fun executeInt(frame: VirtualFrame, value: Any?, argument: Long, state: Any?): Long {
        requireVoidCarrier(state)
        val receiver = receiver(frame, value)
        // This first scalar bridge uses the integer range shared by Int# and JS
        // Number. A separate BigInt conversion is needed for all 64-bit inputs.
        if (argument !in -9_007_199_254_740_991L..9_007_199_254_740_991L)
            fault("Polyglot executeInt input exceeds the exact Number integer range")
        return try {
            val answer = functions.execute(receiver, argument)
            if (!numbers.fitsInLong(answer)) fault("Polyglot executeInt result is not an exact Int#")
            numbers.asLong(answer)
        } catch (error: InteropException) { interopFailure("executeInt", error) }
    }

    @TruffleBoundary
    private fun interopFailure(operation: String, error: InteropException): Nothing =
        throw RuntimeFault("Polyglot $operation: ${error.message}")
}

internal class PolyglotExpression(private val operation: PolyglotOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    @Child private var access = PolyglotAccess()
    override fun execute(frame: VirtualFrame): Nothing = fault("Polyglot IO requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        when (operation) {
            PolyglotOp.EVAL -> {
                val language = arguments[0].executeRequiredAddress(frame)
                val source = arguments[1].executeRequiredAddress(frame)
                val name = arguments[2].executeRequiredAddress(frame)
                val state = arguments[3].execute(frame)
                FrameAccess.write(frame, slots[offset], access.eval(language, source, name, state))
            }
            PolyglotOp.READ_MEMBER -> {
                val value = arguments[0].execute(frame)
                val name = arguments[1].executeRequiredAddress(frame)
                val state = arguments[2].execute(frame)
                FrameAccess.write(frame, slots[offset], access.readMember(frame, value, name, state))
            }
            PolyglotOp.EXECUTE_INT -> {
                val value = arguments[0].execute(frame)
                val input = arguments[1].executeRequiredLong(frame)
                val state = arguments[2].execute(frame)
                FrameAccess.writeLong(frame, slots[offset], access.executeInt(frame, value, input, state))
            }
        }
        return null
    }
}
