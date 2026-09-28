// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public interface LimbProvider {
    long add(LimbRegion output, LimbRegion left, LimbRegion right);
    long addWord(LimbRegion output, LimbRegion input, long word);
    long subtract(LimbRegion output, LimbRegion left, LimbRegion right);
    long compare(LimbRegion left, LimbRegion right);
    long multiply(LimbRegion output, LimbRegion left, LimbRegion right);
    long multiplyWord(LimbRegion output, LimbRegion input, long word);
    long divideWord(LimbRegion output, long fractionalLimbs, LimbRegion input, long divisor);
    long moduloWord(LimbRegion input, long divisor);
    long shiftRight(LimbRegion output, LimbRegion input, long count, boolean negative);
    double toDouble(LimbRegion input, boolean negative, long exponent);
    long gcdWords(long left, long right);
    long gcdWord(LimbRegion input, long word);
    long gcd(LimbRegion output, LimbRegion left, LimbRegion right);
    long shiftLeft(LimbRegion output, LimbRegion input, long count);
    void bitwise(LimbRegion output, LimbRegion left, LimbRegion right, LimbBitwise operation);
    long populationCount(LimbRegion input);
    void divide(LimbRegion quotient, LimbRegion remainder, long fractionalLimbs,
        LimbRegion numerator, LimbRegion divisor);
    void quotient(LimbRegion output, LimbRegion numerator, LimbRegion divisor);
    void remainder(LimbRegion output, LimbRegion numerator, LimbRegion divisor);
}
