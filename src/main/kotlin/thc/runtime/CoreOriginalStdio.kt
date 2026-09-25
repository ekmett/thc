// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Exact pinned GHC declarations, not aliases for arbitrary POSIX imports. */
internal enum class OriginalStdioOp(val symbol: String, val convention: String, val safety: String,
    val arguments: List<String?>, val result: String?) {
    GET_SAVED_TERMIOS("__hscore_get_saved_termios", "ccall", "unsafe", listOf("Int32Rep", null), "AddrRep"),
    SET_SAVED_TERMIOS("__hscore_set_saved_termios", "ccall", "unsafe", listOf("Int32Rep", "AddrRep", null), null),
    SIGPROCMASK("ghczuwrapperZC11ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigprocmask", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "AddrRep", null), "Int32Rep"),
    TCGETATTR("ghczuwrapperZC10ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcgetattr", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", null), "Int32Rep"),
    LFLAG("__hscore_lflag", "ccall", "unsafe", listOf("AddrRep", null), "Word32Rep"),
    POKE_LFLAG("__hscore_poke_lflag", "ccall", "unsafe", listOf("AddrRep", "Word32Rep", null), null),
    PTR_C_CC("__hscore_ptr_c_cc", "ccall", "unsafe", listOf("AddrRep", null), "AddrRep"),
    SIZEOF_TERMIOS("__hscore_sizeof_termios", "ccall", "unsafe", listOf(null), "IntRep"),
    ECHO("__hscore_echo", "ccall", "unsafe", listOf(null), "Int32Rep"),
    ICANON("__hscore_icanon", "ccall", "unsafe", listOf(null), "Int32Rep"),
    VMIN("__hscore_vmin", "ccall", "unsafe", listOf(null), "Int32Rep"),
    VTIME("__hscore_vtime", "ccall", "unsafe", listOf(null), "Int32Rep"),
    TCSANOW("__hscore_tcsanow", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIZEOF_SIGSET("__hscore_sizeof_sigset_t", "ccall", "unsafe", listOf(null), "IntRep"),
    SIGTTOU("__hscore_sigttou", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIG_BLOCK("__hscore_sig_block", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIG_SETMASK("__hscore_sig_setmask", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIGEMPTYSET("ghczuwrapperZC13ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigemptyset", "capi", "unsafe",
        listOf("AddrRep", null), "Int32Rep"),
    SIGADDSET("ghczuwrapperZC12ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigaddset", "capi", "unsafe",
        listOf("AddrRep", "Int32Rep", null), "Int32Rep"),
    READ_SAFE("ghczuwrapperZC22ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "safe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    READ_UNSAFE("ghczuwrapperZC23ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_SAFE("ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "safe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_UNSAFE("ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    ERRNO("__hscore_get_errno", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SEEK_SET("ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET", "capi", "unsafe", listOf(null), "Int32Rep"),
    SEEK_CUR("ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR", "capi", "unsafe", listOf(null), "Int32Rep"),
    SEEK_END("ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND", "capi", "unsafe", listOf(null), "Int32Rep"),
    CLOSE("close", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    OPEN("__hscore_open", "ccall", "unsafe", listOf("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    DUP("dup", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    DUP2("dup2", "ccall", "unsafe", listOf("Int32Rep", "Int32Rep", null), "Int32Rep"),
    SEEK("ghczuwrapperZC19ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZClseek", "capi", "unsafe",
        listOf("Int32Rep", "Int64Rep", "Int32Rep", null), "Int64Rep"),
    TRUNCATE("__hscore_ftruncate", "ccall", "unsafe", listOf("Int32Rep", "Int64Rep", null), "Int32Rep"),
    FSTAT("__hscore_fstat", "ccall", "unsafe", listOf("Int32Rep", "AddrRep", null), "Int32Rep"),
    LOCK("lockFile", "ccall", "unsafe", listOf("Word64Rep", "Word64Rep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    UNLOCK("unlockFile", "ccall", "unsafe", listOf("Word64Rep", null), "Int32Rep"),
    SIZEOF_STAT("__hscore_sizeof_stat", "ccall", "unsafe", listOf(null), "IntRep"),
    ST_DEV("__hscore_st_dev", "ccall", "unsafe", listOf("AddrRep", null), "Word64Rep"),
    ST_INO("__hscore_st_ino", "ccall", "unsafe", listOf("AddrRep", null), "Word64Rep"),
    ST_MODE("__hscore_st_mode", "ccall", "unsafe", listOf("AddrRep", null), "Word32Rep"),
    ST_SIZE("__hscore_st_size", "ccall", "unsafe", listOf("AddrRep", null), "Int64Rep"),
    IS_REG("ghczuwrapperZC8ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISREG", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_CHR("ghczuwrapperZC7ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISCHR", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_BLK("ghczuwrapperZC6ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISBLK", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_DIR("ghczuwrapperZC5ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISDIR", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_FIFO("ghczuwrapperZC4ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISFIFO", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_SOCK("ghczuwrapperZC3ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISSOCK", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    ISATTY("isatty", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    READY_SAFE("fdReady", "ccall", "safe", listOf("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    READY_UNSAFE("fdReady", "ccall", "unsafe", listOf("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    LOCALE("localeEncoding", "ccall", "unsafe", listOf(null), "AddrRep"),
    ICONV_OPEN("hs_iconv_open", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "Int64Rep"),
    ICONV_CLOSE("hs_iconv_close", "ccall", "unsafe", listOf("Int64Rep", null), "Int32Rep"),
    ICONV("hs_iconv", "ccall", "unsafe", listOf("Int64Rep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", null), "Word64Rep"),
    STRERROR("base_strerror_r", "ccall", "safe", listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int32Rep");

    val readiness: Boolean get() = this == READY_SAFE || this == READY_UNSAFE
    val duplication: Boolean get() = this == DUP || this == DUP2
    val locking: Boolean get() = this == LOCK || this == UNLOCK
    val seekConstant: Boolean get() = this == SEEK_SET || this == SEEK_CUR || this == SEEK_END
    val stat: Boolean get() = this == SIZEOF_STAT || statField ||
        this == IS_REG || this == IS_CHR || this == IS_BLK || this == IS_DIR || this == IS_FIFO || this == IS_SOCK
    val statField: Boolean get() = this == ST_DEV || this == ST_INO || this == ST_MODE || this == ST_SIZE
    val readImage: Boolean get() = this == FSTAT || this == TCGETATTR
    val iconv: Boolean get() = this == LOCALE || this == ICONV_OPEN || this == ICONV_CLOSE || this == ICONV
    val strerror: Boolean get() = this == STRERROR
    val termios: Boolean get() = this == LFLAG || this == POKE_LFLAG || this == PTR_C_CC ||
        this == SIZEOF_TERMIOS || this == ECHO || this == ICANON || this == VMIN || this == VTIME || this == TCSANOW ||
        this == SIZEOF_SIGSET || this == SIGTTOU || this == SIG_BLOCK || this == SIG_SETMASK
    val termiosAddress: Boolean get() = this == LFLAG || this == POKE_LFLAG || this == PTR_C_CC
    val sigset: Boolean get() = this == SIGEMPTYSET || this == SIGADDSET
    val savedTermios: Boolean get() = this == GET_SAVED_TERMIOS || this == SET_SAVED_TERMIOS
}

internal object CoreOriginalStdio {
    @JvmStatic fun current(node: Node): ManagedStdio = Language.currentState(node).stdio
    @JvmStatic fun locks(node: Node): RtsFileLocks = Language.currentState(node).rtsFileLocks
    @JvmStatic fun iconv(node: Node): ManagedIconv = Language.currentState(node).iconv
    @JvmStatic fun strerror(node: Node): ManagedStrerror = Language.currentState(node).strerror

    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid original stdio call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()

    /** An occurrence certificate cannot relabel a stored foreign operand. */
    fun validateScalarOperand(operation: OriginalStdioOp, index: Int,
        lowered: CoreRepresentation, stored: CoreRepresentation?) {
        requireProof(operation == OriginalStdioOp.SIGPROCMASK || operation.readiness || operation.seekConstant || operation.stat || operation.termios || operation.sigset || operation.savedTermios || operation.readImage || operation == OriginalStdioOp.OPEN || operation.iconv || operation.strerror || operation.duplication || operation.locking,
            "strict operand operation")
        val primitive = operation.arguments[index]
        val kind = when (primitive) { null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS; else -> CoreKind.LONG }
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered foreign operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored foreign operand $index")
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved declared foreign variable required")
    }

    /** Reject malformed heads before generic call/capture analysis casts their IDs. */
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val meta = value.getOrNull(6) as? Map<*, *>
                    val descriptor = meta?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (OriginalStdioOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid original stdio call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    private fun scalar(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val kind = when (primitive) { null -> "void"; "AddrRep" -> "address"; else -> "long" }
        return value.keys == scalarKeys && value["kind"] == kind &&
            value["primReps"] == (primitive?.let { listOf(it) } ?: emptyList<String>()) &&
            value["evaluated"] is Boolean && (!declared || value["evaluated"] == false)
    }

    private fun result(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        val expected = if (primitive == null) listOf(null) else listOf(null, primitive)
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && components.size == expected.size &&
            expected.indices.all { scalar(components[it], expected[it]) && (components[it] as Map<*, *>)["evaluated"] == true }
    }

    /** Caller binding names are irrelevant; raw FCallId proof must match exactly.
     * Unrecognized symbols retain ordinary unsupported-foreign handling. */
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): OriginalStdioOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val candidates = OriginalStdioOp.entries.filter { it.symbol == symbol }
        if (candidates.isEmpty()) return null
        val operation = candidates.firstOrNull { it.convention == descriptor["convention"] && it.safety == descriptor["safety"] }
            ?: throw RuntimeFault("Invalid original stdio call: calling convention/safety")
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == operation.convention && descriptor["safety"] == operation.safety,
            "calling convention/safety")
        val expected = operation.arguments
        requireProof(exactInteger(descriptor["arity"], expected.size) &&
            exactInteger(descriptor["suppliedArity"], expected.size), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == expected.size &&
            expected.indices.all { scalar(declared[it], expected[it], true) }, "declared argument representations")
        requireProof(argumentReps.size == expected.size && expected.indices.all { scalar(argumentReps[it], expected[it]) },
            "actual argument representations")
        requireProof(flags.size == expected.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], operation.result, true) && result(meta["rep"], operation.result) &&
            result(resultRep, operation.result), "exact State/result tuple")
        return operation
    }
}
