// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode

/** The calling convention is independent of the interpreter's frame layout. */
abstract class GuestRoot(language: TruffleLanguage<*>?, descriptor: FrameDescriptor) : RootNode(language, descriptor) {
    @field:CompilationFinal internal var coreIdentity: CoreFunctionIdentity? = null
        private set
    internal fun configureCoreIdentity(identity: CoreFunctionIdentity?) { coreIdentity = identity }
    @field:CompilationFinal(dimensions = 1) internal var entryStrict: BooleanArray = booleanArrayOf()
        private set
    @field:CompilationFinal internal var entryArgumentOffset: Int = 1
        private set

    @field:CompilationFinal internal var inputLayout: ArgumentLayout? = null
        private set
    @field:CompilationFinal(dimensions = 1) internal var strictArgumentPositions: IntArray = intArrayOf()
        private set
    internal fun configureInput(layout: ArgumentLayout?) { inputLayout = layout; configureStrictPositions() }
    @field:CompilationFinal internal var typedInput: TypedInputLayout? = null
        private set
    internal fun configureTypedInput(layout: TypedInputLayout?) { typedInput = layout }
    private fun configureStrictPositions() {
        strictArgumentPositions = entryStrict.indices.filter { entryStrict[it] && inputLayout?.isTuple(it) != true }
            .map { ArgumentLayout.offset(inputLayout, it) + entryArgumentOffset }.toIntArray()
    }

    /** Fixed before publishing the target; all saturated application paths enforce these marks. */
    internal fun configureEntry(strict: BooleanArray, hasEnvironment: Boolean) {
        entryStrict = strict.copyOf()
        entryArgumentOffset = if (hasEnvironment) 2 else 1
        configureStrictPositions()
    }
    @field:CompilationFinal internal var leadingCaseReturn: LeadingCaseReturn? = null
        private set
    internal fun configureLeadingCaseReturn(recipe: LeadingCaseReturn?) { leadingCaseReturn = recipe }
    @field:CompilationFinal internal var tupleResult: TupleShape? = null
        private set
    internal fun configureTupleResult(shape: TupleShape?) { tupleResult = shape }
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
