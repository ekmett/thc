// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.nodes.Node.Child
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import thc.Language

/** A completed update is an indirection, not a distinct CAS payload. The volatile
 * state publishes immutable WHNF; all other states remain opaque, with no forcing,
 * waiting or guest work. Force's state-2 invariant excludes another Thunk answer.
 * This is CAS-specific: reallyUnsafePtrEquality# keeps its raw-reference contract. */
internal fun completedBoxedIdentity(value: Any?): Any? =
    if (value is Thunk && value.state == 2) value.value else value

/** One mutable guest reference. Reads return the stored value without entering a thunk.
 * Volatile access publishes stored values between threads without serializing
 * independent cells. The field remains mutable and is never CompilationFinal.
 */
internal class ManagedMutVar(@Volatile var value: Any?) {
    /** One JVM atomic exchange on the same volatile field used by read/write. */
    fun exchange(replacement: Any?): Any? = VALUE_HANDLE.getAndSet(this, replacement)
    /** Return expected on success, the atomic observation on failure. */
    fun compareExchange(expected: Any?, replacement: Any?): Any? {
        var witness = VALUE_HANDLE.compareAndExchange(this, expected, replacement)
        if (witness === expected) return expected
        while (completedBoxedIdentity(witness) === completedBoxedIdentity(expected)) {
            val prior = witness
            witness = VALUE_HANDLE.compareAndExchange(this, prior, replacement)
            if (witness === prior) return expected
        }
        return witness
    }

    /** Publish one shared lazy application, or its selector for the pair-returning primitive. */
    @CompilerDirectives.TruffleBoundary
    fun modify(function: Any?, site: MutVarModifySite): ModifiedMutVar {
        while (true) {
            val old = value
            val result = site.application(function, old)
            val selected = site.stored(result)
            if (VALUE_HANDLE.compareAndSet(this, old, selected)) return ModifiedMutVar(old, result)
        }
    }

    companion object {
        private val VALUE_HANDLE: VarHandle = MethodHandles.privateLookupIn(
            ManagedMutVar::class.java, MethodHandles.lookup()
        ).findVarHandle(ManagedMutVar::class.java, "value", Any::class.java)

        @JvmStatic fun require(value: Any?): ManagedMutVar = value as? ManagedMutVar
            ?: fault("Expected a managed MutVar#")
    }
}

internal class ModifiedMutVar(val old: Any?, val result: Thunk)

/** One reusable application target and optional selector per site; retries allocate captures and thunks. */
internal class MutVarModifySite(language: Language, metrics: Metrics, async: Boolean, selectFirst: Boolean = true) {
    private val applicationLayout = CaptureLayout(language, booleanArrayOf(false, false))
    private val selectorLayout = if (selectFirst) CaptureLayout(language, booleanArrayOf(false),
        exactReference = arrayOf<Class<*>?>(Thunk::class.java)) else null
    private val applicationTarget = ModifyApplicationRoot(language, applicationLayout, metrics, async).callTarget
    private val selectorTarget = selectorLayout?.let { ModifySelectorRoot(language, it, metrics, async).callTarget }

    fun application(function: Any?, old: Any?): Thunk =
        Thunk(applicationTarget, applicationLayout.captureValues(arrayOf(function, old)))
    fun selector(result: Thunk): Thunk =
        Thunk(selectorTarget!!, selectorLayout!!.captureValues(arrayOf(result)))
    fun stored(result: Thunk): Thunk = if (selectorTarget == null) result else selector(result)

    companion object {
        @JvmStatic fun create(language: Language, metrics: Metrics, async: Boolean, selectFirst: Boolean) =
            MutVarModifySite(language, metrics, async, selectFirst)
    }
}

/** GHC's application thunk r = f old. The modifier is not called by the atomic operation. */
private class ModifyApplicationRoot(language: TruffleLanguage<*>, private val layout: CaptureLayout,
                                    metrics: Metrics, async: Boolean) : GuestRoot(language, FrameLayout().build()) {
    @Child private var dispatch = Dispatch.create(1, false, metrics)
    @Child private var force = Force(metrics, async)
    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long or mask
    private inner class ResumeDispatch(private val old: Any?) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = invoke(frame, resumed(input), old)
    }
    private inner class ResumeCall(private val child: AstContinuation) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = completeCall(frame, child.continueWith(input))
    }
    private fun resumed(input: Any?): Any? = when (input) {
        is ChildResume -> { input.failure?.let { throw it }; input.value }
        is Throwable -> throw input
        else -> fault("Invalid atomic MutVar modifier resume")
    }
    private fun completeCall(frame: VirtualFrame, result: Any?): Any? = when (result) {
        is AstContinuation -> {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            AstCapture(result.yielded, SynchronousMasking.current(this))
                .append(ResumeCall(result)).freeze(this, frame.materialize())
        }
        else -> result
    }
    private fun invoke(frame: VirtualFrame, value: Any?, old: Any?): Any? {
        val function = value as? Closure ?: fault("atomic MutVar modifier is not a function")
        val result = dispatch.execute(frame, function, arrayOf(old))
        return if (result is ContinuationResult) TailYield(result, function.target)
            else completeCall(frame, result)
    }
    override fun execute(frame: VirtualFrame): Any? {
        val environment = frame.arguments[1] as? CapturedFrame ?: fault("Missing atomic MutVar application capture")
        val old = layout.read(environment, 1)
        val function = try { force.execute(frame, layout.read(environment, 0)) }
        catch (signal: ThunkSuspended) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            return AstCapture(signal, SynchronousMasking.current(this))
                .append(ResumeDispatch(old)).freeze(this, frame.materialize())
        }
        return invoke(frame, function, old)
    }
}

/** GHC's selector thunk fst r. Both application and selected field enter at most once. */
private class ModifySelectorRoot(language: TruffleLanguage<*>, private val layout: CaptureLayout,
                                 metrics: Metrics, async: Boolean) : GuestRoot(language, FrameLayout().build()) {
    @Child private var force = Force(metrics, async)
    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long or mask

    private fun suspended(frame: VirtualFrame, signal: ThunkSuspended, next: AstResumeStep): AstContinuation {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        return AstCapture(signal, SynchronousMasking.current(this)).append(next).freeze(this, frame.materialize())
    }
    private fun completed(input: Any?): Any? = when (input) {
        is ChildResume -> { input.failure?.let { throw it }; input.value }
        is Throwable -> throw input
        else -> fault("Invalid atomic MutVar selector resume")
    }
    private inner class ResumeRecord : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = select(frame, completed(input))
    }
    private inner class ResumeField : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = completed(input)
    }
    private fun select(frame: VirtualFrame, record: Any?): Any? {
        val data = record as? DataValue ?: fault("atomicModifyMutVar2# modifier did not return a data record")
        val field = data.layout.readFirstLifted(data)
        return try { force.execute(frame, field) }
        catch (signal: ThunkSuspended) { suspended(frame, signal, ResumeField()) }
    }
    override fun execute(frame: VirtualFrame): Any? {
        val environment = frame.arguments[1] as? CapturedFrame ?: fault("Missing atomic MutVar selector capture")
        val result = layout.read(environment, 0)
        val record = try { force.execute(frame, result) }
        catch (signal: ThunkSuspended) { return suspended(frame, signal, ResumeRecord()) }
        return select(frame, record)
    }
}

private const val MUTVAR_REP = "BoxedRep (Just Unlifted)"
private const val LIFTED_REP = "BoxedRep (Just Lifted)"

/** GHC's a_levpoly is a boxed value of either known levity, not an arbitrary RuntimeRep. */
internal enum class MutVarOp(val primitive: String, private val arguments: List<String>, val tuple: Boolean) {
    NEW("newMutVar#", listOf("boxed", "state"), true),
    READ("readMutVar#", listOf("mutvar", "state"), true),
    SWAP("atomicSwapMutVar#", listOf("mutvar", "boxed", "state"), true),
    CAS("casMutVar#", listOf("mutvar", "boxed", "boxed", "state"), true),
    MODIFY("atomicModifyMutVar_#", listOf("mutvar", "function", "state"), true),
    MODIFY2("atomicModifyMutVar2#", listOf("mutvar", "function", "state"), true),
    WRITE("writeMutVar#", listOf("mutvar", "boxed", "state"), false);

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun matches(proof: CoreRepresentation, role: String): Boolean = !proof.isTuple && !proof.isVector && when (role) {
            "state" -> proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
            "mutvar" -> proof.kind == CoreKind.OBJECT && proof.primReps == listOf(MUTVAR_REP)
            "flag" -> proof.kind == CoreKind.LONG
            "function" -> proof.kind == CoreKind.CLOSURE && proof.primReps == listOf(LIFTED_REP)
            "lifted" -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                proof.primReps == listOf(LIFTED_REP)
            else -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                proof.primReps?.singleOrNull() in setOf(LIFTED_REP, MUTVAR_REP)
        }
        if (actual.size != arguments.size || flags.size != arguments.size)
            throw RuntimeFault("Primitive arity mismatch: $primitive")
        if (actual.indices.any { !matches(actual[it], arguments[it]) ||
                flags[it] != (actual[it].primReps == listOf(LIFTED_REP)) })
            throw RuntimeFault("MutVar primitive argument representation mismatch: $primitive")
        val valid = if (this in setOf(MODIFY, MODIFY2)) result.isTuple && result.components!!.size == 3 &&
            matches(result.components[0], "state") && matches(result.components[1], "lifted") &&
            matches(result.components[2], "lifted") && result.primReps == listOf(LIFTED_REP, LIFTED_REP)
        else if (this == CAS) result.isTuple && result.components!!.size == 3 &&
            matches(result.components[0], "state") && matches(result.components[1], "flag") &&
            matches(result.components[2], "boxed") &&
            result.primReps == result.components.flatMap { it.primReps!! }
        else if (tuple) result.isTuple && result.components!!.size == 2 &&
            matches(result.components[0], "state") && matches(result.components[1], if (this == NEW) "mutvar" else "boxed") &&
            result.primReps == result.components[1].primReps
        else matches(result, "state")
        if (!valid) throw RuntimeFault("MutVar primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): MutVarOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal fun mutVarExpression(operation: MutVarOp, proof: CoreRepresentation, operands: Array<Expr>,
                              language: TruffleLanguage<*>? = null, metrics: Metrics? = null, async: Boolean = false): Expr =
    when (operation) {
        MutVarOp.NEW -> NewMutVarExpression(operands[0], operands[1])
        MutVarOp.READ -> ReadMutVarExpression(operands[0], operands[1])
        MutVarOp.SWAP -> SwapMutVarExpression(operands[0], operands[1], operands[2])
        MutVarOp.CAS -> CasMutVarExpression(operands[0], operands[1], operands[2], operands[3])
        MutVarOp.MODIFY, MutVarOp.MODIFY2 -> ModifyMutVar2Expression(operands[0], operands[1], operands[2],
            language as? Language ?: fault("Missing atomic MutVar guest language"),
            metrics ?: fault("Missing atomic MutVar metrics"), async, operation == MutVarOp.MODIFY2)
        MutVarOp.WRITE -> WriteMutVarExpression(operands[0], operands[1], operands[2])
    }.proven(proof.copy(evaluated = true))

private class NewMutVarExpression(@field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedMutVar(stored))
        return null
    }
}
private class ReadMutVarExpression(@field:Child private var cell: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMutVar.require(cell.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], reference.value)
        return null
    }
}
private class WriteMutVarExpression(@field:Child private var cell: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val reference = ManagedMutVar.require(cell.execute(frame))
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        reference.value = stored
        return Unit
    }
}

private class SwapMutVarExpression(@field:Child private var cell: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMutVar.require(cell.execute(frame))
        val replacement = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], reference.exchange(replacement))
        return null
    }
}

private class CasMutVarExpression(@field:Child private var cell: Expr,
    @field:Child private var expected: Expr, @field:Child private var replacement: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMutVar.require(cell.execute(frame))
        val old = expected.execute(frame)
        val new = replacement.execute(frame)
        requireVoidCarrier(state.execute(frame))
        val witness = reference.compareExchange(old, new)
        val success = witness === old
        FrameAccess.writeLong(frame, slots[offset], if (success) 0L else 1L)
        FrameAccess.write(frame, slots[offset + 1], if (success) new else witness)
        return null
    }
}

private class ModifyMutVar2Expression(@field:Child private var cell: Expr,
    @field:Child private var function: Expr, @field:Child private var state: Expr,
    language: Language, metrics: Metrics, async: Boolean, selectFirst: Boolean) : Expr() {
    private val site = MutVarModifySite(language, metrics, async, selectFirst)
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMutVar.require(cell.execute(frame))
        val modifier = function.execute(frame)
        requireVoidCarrier(state.execute(frame))
        val modified = reference.modify(modifier, site)
        FrameAccess.write(frame, slots[offset], modified.old)
        FrameAccess.write(frame, slots[offset + 1], modified.result)
        return null
    }
}
