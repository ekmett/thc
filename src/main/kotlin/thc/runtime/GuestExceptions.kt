package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node

/** A Haskell raise# payload, kept separate from unsupported-runtime diagnostics. */
class GuestException(val payload: Any?, location: Node) :
    AbstractTruffleException("Haskell exception raised by raise# (payload retained lazily)", location)

/**
 * GHC 9.14.1 rts/Exception.cmm: stg_raisezh passes its exception closure unchanged
 * to the handler; it does not enter that closure. In particular, a handler that
 * ignores the payload can catch a raise# whose payload is itself bottom.
 */
internal class RaiseException(@field:Child private var exception: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing {
        val payload = exception.execute(frame)
        CompilerDirectives.transferToInterpreterAndInvalidate()
        raise(payload, this)
    }

    companion object {
        @TruffleBoundary
        private fun raise(payload: Any?, location: Node): Nothing = throw GuestException(payload, location)
    }
}
