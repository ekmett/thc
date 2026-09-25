// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import thc.ForeignBitcode
import thc.Language
import com.oracle.truffle.api.nodes.Node

internal data class CapiCall(val unit: String, val symbol: String, val zeroArgument: Boolean)

/** Exact CAPI descriptors of verified, context-loaded bitcode exports. */
internal object CoreCapiForeign {
    @JvmStatic fun zero(node: Node, call: CapiCall): Long {
        val state = Language.currentState(node)
        val previous = state.threads.enterForeign()
        try { return state.cbits().capiZero(call.unit, call.symbol) }
        finally { state.threads.leaveForeign(previous) }
    }

    @JvmStatic fun wordAddress(node: Node, call: CapiCall, word: Long, address: ManagedAddress): Long {
        val state = Language.currentState(node)
        val previous = state.threads.enterForeign()
        try {
            val result = state.cbits().capiWordAddress(call.unit, call.symbol, word, address)
            if (result.value < 0) state.stdio.captureForeignErrno(result.errno)
            return result.value
        } finally { state.threads.leaveForeign(previous) }
    }
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity",
        "suppliedArity", "argumentReps", "resultRep")

    private fun failProof(message: String): Nothing = throw RuntimeFault("Invalid linked CAPI call: $message")
    private fun integer(value: Any?, number: Int): Boolean =
        (value is Int && value == number) || (value is Long && value == number.toLong())
    private fun scalar(value: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val map = value as? Map<*, *> ?: return false
        val kind = when (primitive) { null -> "void"; "AddrRep" -> "address"; else -> "long" }
        return map.keys == scalarKeys && map["kind"] == kind &&
            map["primReps"] == (primitive?.let { listOf(it) } ?: emptyList<String>()) &&
            map["evaluated"] is Boolean && (!declared || map["evaluated"] == false)
    }
    private fun result(value: Any?, primitive: String, declared: Boolean): Boolean {
        val map = value as? Map<*, *> ?: return false
        val parts = map["components"] as? List<*> ?: return false
        return map.keys == tupleKeys && map["kind"] == "unknown" &&
            map["aggregate"] == "unboxed-tuple" && map["primReps"] == listOf(primitive) &&
            map["evaluated"] is Boolean && (!declared || map["evaluated"] == false) &&
            parts.size == 2 && scalar(parts[0], null) && (parts[0] as Map<*, *>)["evaluated"] == true &&
            scalar(parts[1], primitive) && (parts[1] as Map<*, *>)["evaluated"] == true
    }

    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?,
        links: List<ForeignBitcode>): CapiCall? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val unit = target["unit"] as? String ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val link = links.firstOrNull { it.unit == unit && symbol in it.symbols } ?: return null
        if (link.module != "System.CPUTime.Posix.ClockGetTime") failProof("unsupported module")
        if (descriptor.keys != descriptorKeys || !integer(descriptor["schema"], 1) ||
            target.keys != setOf("kind", "symbol", "unit", "isFunction") ||
            target["kind"] != "static" || target["isFunction"] != true ||
            descriptor["convention"] != "capi" || descriptor["safety"] != "unsafe")
            failProof("static target, calling convention or safety")
        val declared = descriptor["argumentReps"] as? List<*> ?: failProof("argument representations")
        val zero = declared.size == 1
        val expected = if (zero) listOf<String?>(null) else listOf("Word64Rep", "AddrRep", null)
        val output = if (zero) "Word64Rep" else "Int32Rep"
        if (!integer(descriptor["arity"], expected.size) ||
            !integer(descriptor["suppliedArity"], expected.size) ||
            declared.size != expected.size || argumentReps.size != expected.size ||
            flags.size != expected.size || flags.any { it != false } ||
            expected.indices.any { !scalar(declared[it], expected[it], true) ||
                !scalar(argumentReps[it], expected[it]) } ||
            !result(descriptor["resultRep"], output, true) ||
            !result(meta["rep"], output, false) || !result(resultRep, output, false))
            failProof("actual or declared CAPI argument/result representation")
        return CapiCall(unit, symbol, zero)
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        if (function.size != 3 || function[0] != "var" || function[1] !is String ||
            (function[1] as String).isEmpty() || defined || proof?.keys != scalarKeys ||
            proof["kind"] != "closure" || proof["primReps"] != listOf("BoxedRep (Just Lifted)") ||
            proof["evaluated"] != true) failProof("unresolved declared foreign head")
    }
}
