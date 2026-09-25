// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Typed normalized operand lanes; no boxed numeric packet or native addresses.
 * Original evaluation order is retained by each lowering before this boundary. */
internal object ManagedGmp {
    @JvmStatic @TruffleBoundary fun invoke(node: Node, operation: GmpForeignOp,
        first: Any?, second: Any?, third: Any?, fourth: Any?, a: Long, b: Long, c: Long): Long {
        val provider = Language.currentState(node).limbs()
        return when (operation) {
            GmpForeignOp.ADD -> provider.add(LimbRegion.write(first, a), LimbRegion.read(second, a), LimbRegion.read(third, b))
            GmpForeignOp.SUBTRACT -> provider.subtract(LimbRegion.write(first, a), LimbRegion.read(second, a), LimbRegion.read(third, b))
            GmpForeignOp.MULTIPLY -> {
                val left = LimbRegion.read(second, a); val right = LimbRegion.read(third, b)
                provider.multiply(LimbRegion.write(first, left.limbs + right.limbs), left, right)
            }
            GmpForeignOp.ADD_WORD -> provider.addWord(LimbRegion.write(first, a), LimbRegion.read(second, a), b)
            GmpForeignOp.MULTIPLY_WORD -> provider.multiplyWord(LimbRegion.write(first, a), LimbRegion.read(second, a), b)
            // GHC's original import declares Int# for GMP's C int return. On
            // the supported x86_64 ABI its raw negative result is zero-extended
            // (native oracle: -1 becomes 4294967295). The original bignat_compare
            // caller applies narrowCInt# itself. Keep that real call boundary
            // separate from the provider's signed comparison contract.
            GmpForeignOp.COMPARE -> provider.compare(LimbRegion.read(first, a), LimbRegion.read(second, a)) and 0xffff_ffffL
            GmpForeignOp.DIVIDE_WORD -> {
                val input = LimbRegion.read(second, b, true)
                if (a < 0 || a > Int.MAX_VALUE.toLong() / 8) fault("Invalid fractional limb count")
                provider.divideWord(LimbRegion.write(first, input.limbs + a, true), a, input, c)
            }
            GmpForeignOp.MODULO_WORD -> provider.moduloWord(LimbRegion.read(first, a, true), b)
            GmpForeignOp.DIVIDE -> {
                val numerator = LimbRegion.read(third, b); val divisor = LimbRegion.read(fourth, c)
                provider.divide(LimbRegion.write(first, b - c + 1), LimbRegion.write(second, c), a, numerator, divisor)
                0L // Internal unused lane only; the guest result is singleton State.
            }
            GmpForeignOp.QUOTIENT -> {
                val numerator = LimbRegion.read(second, a); val divisor = LimbRegion.read(third, b)
                provider.quotient(LimbRegion.write(first, a - b + 1), numerator, divisor)
                0L
            }
            GmpForeignOp.REMAINDER -> {
                val numerator = LimbRegion.read(second, a); val divisor = LimbRegion.read(third, b)
                provider.remainder(LimbRegion.write(first, b), numerator, divisor)
                0L
            }
        }
    }
}
