package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node

/** Isolated experiment: no ordinary thunk, Force, layout, or compiler lowering changes. */
internal enum class IntThunkMode(val cli: String) {
    ORDINARY("ordinary"), INLINE_COPY("inline-copy"), INLINE_DIRECT("inline-direct");
    companion object {
        fun parse(value: String): IntThunkMode = entries.singleOrNull { it.cli == value }
            ?: error("Unknown mode: $value")
    }
}

/** Exactly two reference fields, one state tag and one full-width primitive payload.
 * The entry slot becomes a memoized failure or an existing shared I# result. No layout field.
 * Single guest thread, matching the current ordinary THC thunk protocol.
 */
internal class ExperimentalIntThunk(target: RootCallTarget, environment: CapturedFrame?) {
    @JvmField var entryOrFailure: Any? = target
    @JvmField var environment: CapturedFrame? = environment
    @JvmField var state: Int = NEW
    @JvmField var payload: Long = 0L

    /** Private producer ABI: stage a result; the forcing node owns final publication. */
    fun writeLongWhileEvaluating(value: Long) {
        if (state != BUSY) fault("IntThunk producer wrote outside evaluation")
        payload = value
        entryOrFailure = null
    }
    fun writeReferenceWhileEvaluating(value: DataValue) {
        if (state != BUSY) fault("IntThunk producer wrote outside evaluation")
        entryOrFailure = value
    }

    companion object {
        const val NEW = 0
        const val BUSY = 1
        const val READY_INT = 2
        const val FAILURE = 3
        const val REF_RESULT = 4
    }
}

/** Experimental mixed Int consumer. It preserves general Force's existing WHNF contract.
 * INLINE_COPY uses the exact boxed producer ABI/cache used by ordinary Thunk.
 * INLINE_DIRECT uses [0L, environment, destination] and a Unit result: no Long result box.
 */
internal class ExperimentalForceInt(private val layout: DataLayout, private val mode: IntThunkMode) : Node() {
    @Child private var ordinary = Force(Metrics(false))
    @Child private var boxedCalls = ThunkTargetCache(Metrics(false))
    @Child private var directCalls = TargetCache(Metrics(false))

    fun executeLong(frame: VirtualFrame, original: Any?): Long {
        if (original is ExperimentalIntThunk) {
            forceCandidate(original)
            return if (original.state == ExperimentalIntThunk.READY_INT) original.payload
            else layout.readLong(original.entryOrFailure as DataValue, 0)
        }
        val value = ordinary.execute(frame, original)
        if (value !is DataValue || !layout.matches(value)) fault("Expected experiment I# value")
        return layout.readLong(value, 0)
    }

    /** Broadened experimental WHNF adapter; a finalized primitive cell is itself an Int value.
     * Existing shared/cached I# values preserve exact identity through REF_RESULT.
     */
    fun forceValue(frame: VirtualFrame, original: Any?): Any? {
        if (original !is ExperimentalIntThunk) return ordinary.execute(frame, original)
        forceCandidate(original)
        return if (original.state == ExperimentalIntThunk.READY_INT) original else original.entryOrFailure
    }

    private fun forceCandidate(cell: ExperimentalIntThunk) {
        when (cell.state) {
            ExperimentalIntThunk.READY_INT, ExperimentalIntThunk.REF_RESULT -> return
            ExperimentalIntThunk.FAILURE -> {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                throw (cell.entryOrFailure as? Throwable ?: fault("Invalid failed IntThunk"))
            }
            ExperimentalIntThunk.BUSY -> fault("Blackhole: cyclic IntThunk entered while evaluating")
        }
        val target = cell.entryOrFailure as? RootCallTarget ?: fault("Unevaluated IntThunk has no body")
        val environment = cell.environment
        cell.state = ExperimentalIntThunk.BUSY
        try {
            if (mode == IntThunkMode.INLINE_COPY) {
                val boxed = boxedCalls.call(target, environment)
                if (boxed !is DataValue || !layout.matches(boxed)) fault("IntThunk copy producer did not return I#")
                cell.payload = layout.readLong(boxed, 0)
                cell.entryOrFailure = null
                cell.environment = null
                cell.state = ExperimentalIntThunk.READY_INT
            } else {
                val result = directCalls.call(target, arrayOf(0L, environment, cell))
                if (result !== Unit) fault("IntThunk direct producer violated private Unit ABI")
                val staged = cell.entryOrFailure
                val nextState = when {
                    staged == null -> ExperimentalIntThunk.READY_INT
                    staged is DataValue && layout.matches(staged) -> ExperimentalIntThunk.REF_RESULT
                    else -> fault("IntThunk direct producer failed to stage its result")
                }
                cell.environment = null
                cell.state = nextState
            }
        } catch (failure: GuestException) {
            memoizeFailure(cell, failure)
            throw failure
        } catch (failure: RuntimeFault) {
            memoizeFailure(cell, failure)
            throw failure
        } catch (failure: Throwable) {
            // A producer can stage a result before an interruption: restore BOTH suspension refs.
            cell.entryOrFailure = target
            cell.environment = environment
            cell.payload = 0L
            cell.state = ExperimentalIntThunk.NEW
            throw failure
        }
    }

    private fun memoizeFailure(cell: ExperimentalIntThunk, failure: Throwable) {
        cell.entryOrFailure = failure
        cell.environment = null
        cell.payload = 0L
        cell.state = ExperimentalIntThunk.FAILURE
    }
}
