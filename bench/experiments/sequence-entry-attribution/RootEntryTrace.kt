package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.nodes.RootNode

/** Build-only diagnostic. No formatting, reflection, or guest calls while capturing. */
object RootEntryTrace {
    private const val capacity = 4096
    private val subjects = arrayOfNulls<Any>(capacity)
    private val kinds = IntArray(capacity)
    private val compiled = BooleanArray(capacity)
    @Volatile private var armed = false
    private var count = 0L

    @JvmStatic @TruffleBoundary
    fun arm() {
        subjects.fill(null)
        count = 0
        armed = true
    }

    @JvmStatic @TruffleBoundary
    fun disarm() { armed = false }

    // Mode is sampled at the guest/root entry site, BEFORE this boundary call.
    // Keeping the armed branch here avoids introducing a newly-taken branch in
    // installed guest code when capture is enabled after compilation.
    @JvmStatic @TruffleBoundary
    fun record(kind: Int, subject: Any, inCompiledCode: Boolean) {
        if (!armed) return
        val slot = (count % capacity).toInt()
        subjects[slot] = subject
        kinds[slot] = kind
        compiled[slot] = inCompiledCode
        count++
    }

    private fun identity(value: Any) = value.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(value))

    @JvmStatic @TruffleBoundary
    fun dump(backend: String, phase: String, name: String, input: Long) {
        armed = false
        println("ROOT_TRACE_FAILURE backend=$backend phase=$phase entry=$name input=$input events=$count capacity=$capacity overwritten=${maxOf(0L, count - capacity)}")
        for (sequence in maxOf(0L, count - capacity) until count) {
            val slot = (sequence % capacity).toInt()
            val subject = subjects[slot]!!
            val root = when (subject) {
                is RootNode -> subject
                is RootCallTarget -> subject.rootNode
                else -> error("Unexpected diagnostic subject: ${identity(subject)}")
            }
            val target = root.callTarget
            val targetClass = target.javaClass
            val kind = when (kinds[slot]) { 0 -> "host-entry"; 1 -> "selected-guest"; else -> "guest-entry" }
            println("ROOT_TRACE seq=$sequence kind=$kind mode=${if (compiled[slot]) "compiled" else "interpreter"} root=${identity(root)} target=${identity(target)} name=${root.name} valid=${targetClass.getMethod("isValid").invoke(target)} lastTier=${targetClass.getMethod("isValidLastTier").invoke(target)} code=${targetClass.getMethod("getCodeAddress").invoke(target)}")
        }
    }
}
