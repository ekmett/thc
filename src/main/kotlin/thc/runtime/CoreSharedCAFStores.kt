// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The two selected GHC 9.14.1 RTS slots holding shared-CAF StablePtr values. */
internal enum class SharedCAFStore(val symbol: String) {
    EVENT_MANAGER("getOrSetSystemEventThreadEventManagerStore"),
    SIGNAL_HANDLER("getOrSetGHCConcSignalSignalHandlerStore");

    companion object { fun named(symbol: Any?): SharedCAFStore? = entries.firstOrNull { it.symbol == symbol } }
}

internal object CoreSharedCAFStores {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original RTS shared-CAF call: $detail")
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
            proof["primReps"] == listOf("AddrRep") && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == 2 &&
            scalar(fields[0], "void", emptyList()) && (fields[0] as Map<*, *>)["evaluated"] == true &&
            scalar(fields[1], "address", listOf("AddrRep")) && (fields[1] as Map<*, *>)["evaluated"] == true
    }

    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): SharedCAFStore? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val store = SharedCAFStore.named(target["symbol"]) ?: return null
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "exact installed GHC target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe" &&
            exactInteger(descriptor["arity"], 2) && exactInteger(descriptor["suppliedArity"], 2),
            "convention, safety or arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared?.size == 2 && scalar(declared[0], "address", listOf("AddrRep"), true) &&
            scalar(declared[1], "void", emptyList(), true) && arguments.size == 2 &&
            scalar(arguments[0], "address", listOf("AddrRep")) && scalar(arguments[1], "void", emptyList()) &&
            flags == listOf(false, false), "Addr# and State# arguments")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultProof),
            "State# and Addr# tuple result")
        return store
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep")
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(proof, "closure", listOf("BoxedRep (Just Lifted)")) &&
            (proof as Map<*, *>)["evaluated"] == true, "unresolved declared foreign variable required")
    }

    fun validateOperand(index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val kind = if (index == 0) CoreKind.ADDRESS else CoreKind.VOID
        val reps = if (index == 0) listOf("AddrRep") else emptyList()
        requireProof(index in 0..1 && lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored operand $index")
    }
}

internal class SharedCAFStoreExpression(
    private val store: SharedCAFStore,
    @field:Child private var pointer: Expr,
    @field:Child private var state: Expr
) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("RTS shared-CAF tuple requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val candidate = pointer.executeRequiredAddress(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], StablePointers.current(this).getOrSetSharedCAF(store, candidate))
        return null
    }
}
