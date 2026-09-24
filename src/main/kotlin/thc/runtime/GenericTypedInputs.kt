// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node

/** Megamorphic transport reads fixed caller locals in the compiled node. Only
 * storage, primitive values and metadata cross these rare dynamic-layout helpers. */
@CompilerDirectives.TruffleBoundary
internal fun validateGenericInput(root: GuestRoot, prefix: Int, source: ArgumentLayout?, offset: Int, count: Int,
    destination: TupleDestination?, exact: Boolean, under: Boolean) {
    ArgumentLayout.validate(root.inputLayout, prefix, source, offset, count)
    if (!under) checkInputResult(root, destination, exact)
}

@CompilerDirectives.TruffleBoundary
private fun putLong(storage: HandoffStorage, field: Int, value: Long) {
    val shape = storage.layout
    if (shape.isLong(field)) shape.setLong(storage, field, value) else {
        check(shape.isObject(field)); shape.setObject(storage, field, value)
    }
}
@CompilerDirectives.TruffleBoundary
private fun putFloat(storage: HandoffStorage, field: Int, value: Float) {
    val shape = storage.layout
    if (shape.isFloat(field)) shape.setFloat(storage, field, value) else {
        check(shape.isObject(field)); shape.setObject(storage, field, value)
    }
}
@CompilerDirectives.TruffleBoundary
private fun putDouble(storage: HandoffStorage, field: Int, value: Double) {
    val shape = storage.layout
    if (shape.isDouble(field)) shape.setDouble(storage, field, value) else {
        check(shape.isObject(field)); shape.setObject(storage, field, value)
    }
}
@CompilerDirectives.TruffleBoundary
private fun putScalar(storage: HandoffStorage, field: Int, value: Any?) {
    val shape = storage.layout
    when {
        shape.isLong(field) -> shape.setLong(storage, field, value as? Long ?: fault("Expected primitive Long input"))
        shape.isFloat(field) -> shape.setFloat(storage, field, value as? Float ?: fault("Expected primitive Float input"))
        shape.isDouble(field) -> shape.setDouble(storage, field, value as? Double ?: fault("Expected primitive Double input"))
        else -> shape.setObject(storage, field, value)
    }
}

@ExplodeLoop
private fun InputSource.copyGeneric(frame: VirtualFrame, node: Node, values: Array<Any?>?, maximum: Int,
    from: Int, width: Int, storage: HandoffStorage, to: Int) {
    // The limit and source accessor indices are call-site constants even when a
    // prior overapplication determines which contiguous slice is selected.
    for (i in 0 until ArgumentLayout.offset(layout, maximum)) if (i >= from && i < from + width) {
        val field = to + i - from
        val proof = physicalProofs?.get(i)
        when {
            proof?.isLong == true -> putLong(storage, field, long(frame, node, values, i))
            proof?.isFloat == true -> putFloat(storage, field, float(frame, node, values, i))
            proof?.isDouble == true -> putDouble(storage, field, double(frame, node, values, i))
            else -> putScalar(storage, field, reference(frame, node, values, i))
        }
    }
}

@CompilerDirectives.TruffleBoundary
private fun prefixValue(function: Closure, input: TypedInputLayout, physical: Int): Any? {
    val prefix = function.typedSupplied
    return if (prefix != null) input.prefix(function.suppliedCount).getObject(prefix, physical) else function.supplied[physical]
}

@CompilerDirectives.TruffleBoundary
private fun prefixStorage(input: TypedInputLayout, count: Int): HandoffStorage = input.prefix(count).create()

@CompilerDirectives.TruffleBoundary
private fun copyPrefix(function: Closure, storage: HandoffStorage, offset: Int, width: Int) {
    val typed = function.typedSupplied
    if (typed != null) copyInputFields(typed, storage, 0, offset, width)
    else {
        check(function.supplied.size == width)
        for (i in 0 until width) putScalar(storage, offset + i, function.supplied[i])
    }
}

internal fun genericTypedPap(function: Closure, input: TypedInputLayout, source: InputSource,
    frame: VirtualFrame, node: Node, values: Array<Any?>?, maximum: Int, offset: Int, count: Int): Closure {
    val oldCount = function.suppliedCount
    val prefixWidth = input.logical.offset(oldCount)
    val from = ArgumentLayout.offset(source.layout, offset)
    val width = ArgumentLayout.offset(source.layout, offset + count) - from
    val storage = prefixStorage(input, oldCount + count)
    copyPrefix(function, storage, 0, prefixWidth)
    source.copyGeneric(frame, node, values, maximum, from, width, storage, prefixWidth)
    return Closure(function.environment, NO_PAP_ARGUMENTS, function.arity - count, function.target, oldCount + count, storage)
}

@ExplodeLoop
private fun forceActuals(frame: VirtualFrame, node: Node, function: Closure, source: InputSource,
    values: Array<Any?>?, maximum: Int, offset: Int, count: Int, strict: IntArray, force: Force) {
    for (i in 0 until maximum) if (i >= offset && i < offset + count) {
        val proof = source.layout?.proof(i)
        // Tuple WHNF says nothing about its lifted leaves; primitive scalars
        // also need no force even if a less precise formal uses a reference slot.
        if (proof?.isTuple == true || proof?.isLong == true || proof?.isFloat == true || proof?.isDouble == true) continue
        if (strict.contains(function.suppliedCount + i - offset)) {
            val physical = ArgumentLayout.offset(source.layout, i)
            source.setReference(frame, node, values, physical, force.execute(frame, source.reference(frame, node, values, physical)))
        }
    }
}

@CompilerDirectives.TruffleBoundary
private fun acquireGenericInput(input: TypedInputLayout, compiled: Boolean): HandoffStorage =
    if (compiled) input.packet.create().also { it.inputMode = 3 }
    else input.state().arguments.acquire(input.packet).also { it.inputMode = 1 }

@CompilerDirectives.TruffleBoundary
internal fun releaseGenericInput(input: TypedInputLayout, storage: HandoffStorage, generation: Long) =
    input.releaseIfOwned(storage, generation)

internal fun prepareGenericInput(frame: VirtualFrame, node: Node, function: Closure, input: TypedInputLayout,
    source: InputSource, values: Array<Any?>?, maximum: Int, offset: Int, count: Int, force: Force): HandoffStorage {
    val strict = strictInputPositions(function.target.rootNode as GuestRoot, input)
    val prefixCount = function.suppliedCount
    val prefixWidth = input.logical.offset(prefixCount)
    val overrides = if (strict.any { it < prefixCount }) arrayOfNulls<Any>(prefixWidth) else null
    for (i in strict) if (i < prefixCount) {
        val physical = input.logical.offset(i)
        overrides!![physical] = force.execute(frame, prefixValue(function, input, physical))
    }
    forceActuals(frame, node, function, source, values, maximum, offset, count, strict, force)
    val storage = acquireGenericInput(input, CompilerDirectives.inCompiledCode())
    try {
        putLong(storage, 0, 0L)
        if (input.hasEnvironment) putScalar(storage, 1, function.environment)
        copyPrefix(function, storage, input.header, prefixWidth)
        val from = ArgumentLayout.offset(source.layout, offset)
        val width = ArgumentLayout.offset(source.layout, offset + count) - from
        source.copyGeneric(frame, node, values, maximum, from, width, storage, input.header + prefixWidth)
        if (overrides != null) for (i in strict) if (i < prefixCount) {
            val physical = input.logical.offset(i)
            putScalar(storage, input.header + physical, overrides[physical])
        }
        return storage
    } catch (failure: Throwable) {
        releaseGenericInput(input, storage, storage.generation)
        throw failure
    }
}

/** Only logical scalar arguments may cross back into the legacy scalar ABI. */
@ExplodeLoop
internal fun genericScalarValues(frame: VirtualFrame, node: Node, source: InputSource, values: Array<Any?>?,
    maximum: Int, offset: Int, count: Int): Array<Any?> {
    val from = ArgumentLayout.offset(source.layout, offset)
    val result = arrayOfNulls<Any>(ArgumentLayout.offset(source.layout, offset + count) - from)
    for (i in 0 until maximum) if (i >= offset && i < offset + count) {
        val proof = source.layout?.proof(i)
        if (proof?.isEmptyTuple == true) continue
        if (proof?.isTuple == true) fault("Tuple input cannot enter a scalar packet")
        val physical = ArgumentLayout.offset(source.layout, i)
        result[physical - from] = when {
            proof?.isLong == true -> source.long(frame, node, values, physical)
            proof?.isFloat == true -> source.float(frame, node, values, physical)
            proof?.isDouble == true -> source.double(frame, node, values, physical)
            else -> source.reference(frame, node, values, physical)
        }
    }
    return result
}
