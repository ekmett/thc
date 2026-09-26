// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence

/** Bounded synchronous native GMP provider. Guest storage remains ordinary
 * checked limb regions. This provider does not recognize/admit any Core FFI. */
internal class SulongLimbProvider(env: TruffleLanguage.Env) : LimbProvider {
    private val interop = InteropLibrary.getUncached()
    private val library: Any
    init {
        if (!env.isNativeAccessAllowed) fault("Native limb arithmetic requires native access")
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
            fault("Native GMP limb transport is currently verified only on Linux x86_64")
        val bytes = javaClass.getResourceAsStream("/thc/cbits/gmp-api.so")?.use { it.readBytes() }
            ?: fault("Missing compiled native GMP adapter")
        library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "gmp-api.so").build()).call()
        if (interop.asInt(interop.execute(interop.readMember(library, "thc_gmp_limb_bits"))) != 64)
            fault("Native GMP library does not use 64-bit limbs")
    }
    private val add = member("add")
    private val addWord = member("add_word")
    private val subtract = member("subtract")
    private val compare = member("compare")
    private val multiply = member("multiply")
    private val multiplyWord = member("multiply_word")
    private val divideWord = member("divide_word")
    private val moduloWord = member("modulo_word")
    private val divide = member("divide")
    private val quotient = member("quotient")
    private val remainder = member("remainder")
    private fun member(name: String): Any = interop.readMember(library, "thc_gmp_$name")

    // Native transport belongs to this provider, not to the shared limb-region
    // representation or the contract a future native-limb provider implements.
    private fun LimbRegion.preflight() {
        validate()
        address.cbitsSegment() // Reject pointer-bearing storage before native execution.
    }
    private fun LimbRegion.snapshot(scope: NativeLimbScope): NativeLimbScope.Pointer =
        synchronized(address.cbitsOwner() ?: address.cbitsStorageKey()) {
            address.requireRange(0, byteSize)
            if (address.cbitsOwner()?.isPinned == true) scope.borrow(address, byteSize)
            else scope.snapshot(address.cbitsBacking(), address.cbitsOffset().toInt(), byteSize.toInt())
        }
    private fun LimbRegion.destination(scope: NativeLimbScope): NativeLimbScope.Pointer =
        if (address.cbitsOwner()?.isPinned == true) scope.borrow(address, byteSize) else scope.allocate(byteSize)
    private fun LimbRegion.copyFrom(pointer: NativeLimbScope.Pointer) {
        synchronized(address.cbitsOwner() ?: address.cbitsStorageKey()) {
            requireOutput(limbs)
            if (!pointer.aliases(address)) pointer.copyTo(address.cbitsSegment(), address.cbitsOffset(), byteSize)
        }
    }

    private inline fun <T> owned(vararg regions: LimbRegion, action: (NativeLimbScope) -> T): T {
        regions.forEach { it.preflight() }
        val threads = thc.Language.currentState().threads
        val previous = threads.enterForeign()
        try { NativeLimbScope().use { return action(it) } }
        finally { threads.leaveForeign(previous) }
    }
    private fun exactAlias(output: LimbRegion, input: LimbRegion) {
        if (output.overlaps(input) && !output.sameStart(input)) fault("Partial limb overlap is not allowed")
    }
    private fun disjoint(first: LimbRegion, second: LimbRegion) {
        if (first.overlaps(second)) fault("This limb operation requires disjoint regions")
    }
    private fun ordered(left: LimbRegion, right: LimbRegion) {
        left.requirePositive(); right.requirePositive()
        if (left.limbs < right.limbs) fault("Left limb count is smaller than right limb count")
    }
    private fun nonzero(divisor: Long) { if (divisor == 0L) fault("Native limb division by zero") }
    private fun normalized(divisor: LimbRegion, native: NativeLimbScope.Pointer) {
        if (native.readWord(divisor.limbs - 1) == 0L) fault("Divisor most significant limb is zero")
    }
    private fun addSubtract(output: LimbRegion, left: LimbRegion, right: LimbRegion, operation: Any): Long {
        ordered(left, right); output.requireOutput(left.limbs)
        exactAlias(output, left); exactAlias(output, right)
        return owned(output, left, right) { scope ->
            val first = left.snapshot(scope); val second = right.snapshot(scope)
            val destination = output.destination(scope)
            val result = interop.asLong(interop.execute(operation, destination, first, left.limbs, second, right.limbs))
            output.copyFrom(destination)
            result
        }
    }
    @TruffleBoundary override fun add(output: LimbRegion, left: LimbRegion, right: LimbRegion): Long =
        addSubtract(output, left, right, add)
    @TruffleBoundary override fun subtract(output: LimbRegion, left: LimbRegion, right: LimbRegion): Long =
        addSubtract(output, left, right, subtract)
    private fun wordOperation(output: LimbRegion, input: LimbRegion, value: Long, operation: Any): Long {
        input.requirePositive(); output.requireOutput(input.limbs)
        return owned(output, input) { scope ->
            val source = input.snapshot(scope); val destination = output.destination(scope)
            val result = interop.asLong(interop.execute(operation, destination, source, input.limbs, value))
            output.copyFrom(destination)
            result
        }
    }
    @TruffleBoundary override fun addWord(output: LimbRegion, input: LimbRegion, word: Long): Long {
        exactAlias(output, input)
        return wordOperation(output, input, word, addWord)
    }
    @TruffleBoundary override fun compare(left: LimbRegion, right: LimbRegion): Long {
        ordered(left, right)
        if (left.limbs != right.limbs) fault("Limb compare requires equal counts")
        return owned(left, right) { scope ->
            interop.asLong(interop.execute(compare, left.snapshot(scope), right.snapshot(scope), left.limbs))
        }
    }
    @TruffleBoundary override fun multiply(output: LimbRegion, left: LimbRegion, right: LimbRegion): Long {
        ordered(left, right); output.requireOutput(left.limbs + right.limbs)
        disjoint(output, left); disjoint(output, right)
        return owned(output, left, right) { scope ->
            val first = left.snapshot(scope); val second = right.snapshot(scope)
            val destination = output.destination(scope)
            val result = interop.asLong(interop.execute(multiply, destination, first, left.limbs, second, right.limbs))
            output.copyFrom(destination)
            result
        }
    }
    @TruffleBoundary override fun multiplyWord(output: LimbRegion, input: LimbRegion, word: Long): Long {
        if (output.overlaps(input) && output.address.cbitsOffset() > input.address.cbitsOffset())
            fault("Multiply-word destination overlaps above input")
        return wordOperation(output, input, word, multiplyWord)
    }
    @TruffleBoundary override fun divideWord(output: LimbRegion, fractionalLimbs: Long, input: LimbRegion, divisor: Long): Long {
        nonzero(divisor)
        if (fractionalLimbs < 0 || fractionalLimbs > Int.MAX_VALUE.toLong() / 8)
            fault("Invalid fractional limb count")
        output.requireOutput(input.limbs + fractionalLimbs)
        exactAlias(output, input)
        return owned(output, input) { scope ->
            val source = input.snapshot(scope); val destination = output.destination(scope)
            val result = interop.asLong(interop.execute(divideWord, destination, fractionalLimbs, source, input.limbs, divisor))
            output.copyFrom(destination)
            result
        }
    }
    @TruffleBoundary override fun moduloWord(input: LimbRegion, divisor: Long): Long {
        nonzero(divisor)
        return owned(input) { scope -> interop.asLong(interop.execute(moduloWord, input.snapshot(scope), input.limbs, divisor)) }
    }
    private fun divisionInputs(numerator: LimbRegion, divisor: LimbRegion) {
        ordered(numerator, divisor)
        disjoint(numerator, divisor)
    }
    @TruffleBoundary override fun divide(quotient: LimbRegion, remainder: LimbRegion, fractionalLimbs: Long,
        numerator: LimbRegion, divisor: LimbRegion) {
        if (fractionalLimbs != 0L) fault("Multi-limb division requires zero fractional limbs")
        divisionInputs(numerator, divisor)
        quotient.requireOutput(numerator.limbs - divisor.limbs + 1)
        remainder.requireOutput(divisor.limbs)
        disjoint(quotient, remainder); disjoint(quotient, numerator); disjoint(quotient, divisor)
        disjoint(remainder, divisor); exactAlias(remainder, numerator)
        owned(quotient, remainder, numerator, divisor) { scope ->
            val first = numerator.snapshot(scope); val second = divisor.snapshot(scope)
            normalized(divisor, second)
            val q = quotient.destination(scope); val r = remainder.destination(scope)
            interop.execute(divide, q, r, fractionalLimbs, first, numerator.limbs, second, divisor.limbs)
            // Both capacities were preflighted before native execution. No
            // native pointer writes into guest storage, even on bad input.
            quotient.copyFrom(q); remainder.copyFrom(r)
        }
    }
    @TruffleBoundary override fun quotient(output: LimbRegion, numerator: LimbRegion, divisor: LimbRegion) {
        divisionInputs(numerator, divisor)
        output.requireOutput(numerator.limbs - divisor.limbs + 1)
        disjoint(output, numerator); disjoint(output, divisor)
        owned(output, numerator, divisor) { scope ->
            val first = numerator.snapshot(scope); val second = divisor.snapshot(scope)
            normalized(divisor, second)
            val destination = output.destination(scope); val scratch = scope.allocate(divisor.byteSize)
            interop.execute(quotient, destination, scratch, first, numerator.limbs, second, divisor.limbs)
            output.copyFrom(destination)
        }
    }
    @TruffleBoundary override fun remainder(output: LimbRegion, numerator: LimbRegion, divisor: LimbRegion) {
        divisionInputs(numerator, divisor)
        output.requireOutput(divisor.limbs)
        disjoint(output, divisor); exactAlias(output, numerator)
        owned(output, numerator, divisor) { scope ->
            val first = numerator.snapshot(scope); val second = divisor.snapshot(scope)
            normalized(divisor, second)
            val destination = output.destination(scope)
            val scratch = scope.allocate((numerator.limbs - divisor.limbs + 1) * 8)
            interop.execute(remainder, destination, scratch, first, numerator.limbs, second, divisor.limbs)
            output.copyFrom(destination)
        }
    }
}
