// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Narrow unsigned words compute in Int carriers and expose unsigned public Long values. */
class NarrowWordPrimitiveTest {
    private BigInteger mask(int width) { return BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE); }
    private long arithmetic(long input, int width) {
        var modulus = BigInteger.ONE.shiftLeft(width); var a = BigInteger.valueOf(input).mod(modulus); var b = BigInteger.valueOf(input / 257 + 11).mod(modulus);
        var wrapped = a.add(BigInteger.valueOf(17)).multiply(b.add(BigInteger.valueOf(3))).subtract(BigInteger.valueOf(29)).mod(modulus);
        int flags = (a.compareTo(b) < 0 ? 1 : 0) + (a.equals(b) ? 2 : 0) + (wrapped.compareTo(a) <= 0 ? 4 : 0);
        return wrapped.add(modulus.add(BigInteger.ONE).multiply(BigInteger.valueOf(flags))).longValue();
    }
    private BigInteger weighted(List<List<BigInteger>> items, long multiplier, long[] weights) {
        var acc = BigInteger.ZERO;
        for (var fields : items) {
            var sum = BigInteger.ZERO; for (int i = 0; i < fields.size(); i++) sum = sum.add(fields.get(i).multiply(BigInteger.valueOf(weights[i])));
            acc = acc.multiply(BigInteger.valueOf(multiplier)).add(sum);
        }
        return acc;
    }
    private long records(long input) {
        var value = BigInteger.valueOf(input); var samples = new ArrayList<List<BigInteger>>();
        for (int i = 0; i < 8; i++) {
            samples.add(list(value.mod(BigInteger.ONE.shiftLeft(8)), value.add(BigInteger.valueOf(129)).mod(BigInteger.ONE.shiftLeft(16)),
                value.multiply(BigInteger.valueOf(65537)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32))));
            value = BigInteger.valueOf(value.multiply(BigInteger.valueOf(257)).add(BigInteger.valueOf(129)).longValue());
        }
        return weighted(samples, 17, new long[]{3, 5, 7}).add(weighted(samples.reversed(), 33, new long[]{1, 11, 13})).longValue();
    }
    @Test void ordinaryNativeExamplesAgreeWithIndependentUnsignedArithmeticModel() throws Exception {
        var rows = Files.readAllLines(Path.of(System.getProperty("thc.projectRoot"), "build/corpus/oracle.tsv")).stream().map(line -> line.split("\t", -1)).filter(row -> row[0].startsWith("narrow-words/")).toList();
        var names = new HashSet<String>(); for (var row : rows) names.add(row[0].substring(row[0].indexOf('/') + 1));
        assertEquals(Set.of("word8Arithmetic", "word16Arithmetic", "word32Arithmetic", "narrowWordRecordChecksum"), names);
        for (var row : rows) {
            long input = Long.parseLong(row[1]); long expected = switch (row[0].substring(row[0].indexOf('/') + 1)) {
                case "word8Arithmetic" -> arithmetic(input, 8); case "word16Arithmetic" -> arithmetic(input, 16); case "word32Arithmetic" -> arithmetic(input, 32); default -> records(input);
            };
            assertEquals(expected, Long.parseLong(row[2]), "Independent model " + row[0] + "(" + input + ")");
        }
    }

}
