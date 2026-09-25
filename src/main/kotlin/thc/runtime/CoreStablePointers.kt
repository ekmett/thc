// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

private const val LIFTED = "BoxedRep (Just Lifted)"

internal enum class StablePointerOp(val primitive: String, val tuple: Boolean) {
    MAKE("makeStablePtr#", true), DEREFERENCE("deRefStablePtr#", true), EQUAL("eqStablePtr#", false);

    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun address(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.ADDRESS && proof.primReps == listOf("AddrRep")
        fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
        fun lifted(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) && proof.primReps == listOf(LIFTED)
        val inputs = when (this) {
            MAKE -> arguments.size == 2 && lifted(arguments[0]) && state(arguments[1]) && flags == listOf(true, false)
            DEREFERENCE -> arguments.size == 2 && address(arguments[0]) && state(arguments[1]) && flags == listOf(false, false)
            EQUAL -> arguments.size == 2 && address(arguments[0]) && address(arguments[1]) && flags == listOf(false, false)
        }
        if (!inputs) fault("StablePtr# primitive argument representation mismatch: $primitive")
        val output = if (this == EQUAL) !result.isAggregate && !result.isVector &&
            result.kind == CoreKind.LONG && result.primReps == listOf("IntRep")
        else result.isTuple && result.components?.size == 2 && state(result.components[0]) &&
            (if (this == MAKE) address(result.components[1]) else lifted(result.components[1])) &&
            result.primReps == result.components[1].primReps
        if (!output) fault("StablePtr# primitive result representation mismatch: $primitive")
    }

    companion object { fun named(name: String): StablePointerOp? = entries.firstOrNull { it.primitive == name } }
}

internal object CoreStablePointers {
    private const val SYMBOL = "hs_free_stable_ptr"
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original StablePtr free call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun scalar(value: Any?, kind: String, reps: List<String>, declared: Boolean = false): Boolean {
        val proof = value as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind && proof["primReps"] == reps &&
            proof["evaluated"] is Boolean && (!declared || proof["evaluated"] == false)
    }
    private fun result(value: Any?, declared: Boolean = false): Boolean {
        val proof = value as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == emptyList<String>() && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == 1 &&
            scalar(fields[0], "void", emptyList()) && (fields[0] as Map<*, *>)["evaluated"] == true
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep")
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(proof, "closure", listOf(LIFTED)) && (proof as Map<*, *>)["evaluated"] == true,
            "unresolved declared foreign variable required")
    }
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val target = ((value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>)?.get("target") as? Map<*, *>
                    if (target?.get("symbol") == SYMBOL) validateHead(value.getOrNull(1) as? List<Any?>
                        ?: fault("Invalid original StablePtr free call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): Boolean {
        val meta = metadata as? Map<*, *> ?: return false
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return false
        val target = descriptor["target"] as? Map<*, *> ?: return false
        if (target["symbol"] != SYMBOL) return false
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") && target["kind"] == "static" &&
            target["unit"] == "ghc-internal" && target["isFunction"] == true, "exact installed GHC target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe" &&
            exactInteger(descriptor["arity"], 2) && exactInteger(descriptor["suppliedArity"], 2),
            "convention, safety or arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == 2 && scalar(declared[0], "address", listOf("AddrRep"), true) &&
            scalar(declared[1], "void", emptyList(), true) && arguments.size == 2 &&
            scalar(arguments[0], "address", listOf("AddrRep")) && scalar(arguments[1], "void", emptyList()) &&
            flags == listOf(false, false), "address and State# arguments")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultProof),
            "single-State# tuple result")
        return true
    }
}

internal class MakeStablePointer(@field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("StablePtr# tuple requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], StablePointers.current(this).make(stored))
        return null
    }
}

internal class DereferenceStablePointer(@field:Child private var address: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("StablePtr# tuple requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val handle = address.executeRequiredAddress(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], StablePointers.current(this).dereference(handle))
        return null
    }
}

internal class EqualStablePointers(@field:Child private var left: Expr, @field:Child private var right: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long =
        if (StablePointers.current(this).equal(left.executeRequiredAddress(frame), right.executeRequiredAddress(frame))) 1L else 0L
}

internal class FreeStablePointer(@field:Child private var address: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("StablePtr# free requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val handle = address.executeRequiredAddress(frame)
        requireVoidCarrier(state.execute(frame))
        StablePointers.current(this).free(handle)
        return null
    }
}
