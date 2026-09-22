package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode

/** The calling convention is independent of the interpreter's frame layout. */
abstract class GuestRoot(language: TruffleLanguage<*>?, descriptor: FrameDescriptor) : RootNode(language, descriptor) {
    // Clones retain identity, so self calls through a cloned target still loop.
    private val bodyIdentity = Any()
    @JvmField val mask = System.identityHashCode(bodyIdentity).let { h ->
        (1L shl (h and 63)) or (1L shl ((h ushr 6) and 63)) or (1L shl ((h ushr 12) and 63)) or
            (1L shl ((h ushr 18) and 63)) or (1L shl ((h ushr 24) and 63))
    }
    fun isSelf(target: RootCallTarget): Boolean = (target.rootNode as? GuestRoot)?.bodyIdentity === bodyIdentity
    abstract fun bloom(frame: VirtualFrame): Long
}

/** Host entry and diagnostics are shared across executable Core backends. */
interface ExecutableProgram {
    fun hostEntryTarget(arity: Int = 0): RootCallTarget
    fun entryValue(name: String): Any?
    fun entryTarget(name: String): RootCallTarget
    fun diagnostics(): Map<String, Any>
}
