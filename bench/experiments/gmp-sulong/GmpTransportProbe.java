// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.io.IOAccess;
import thc.runtime.CbitsBuffer;

/** Proves real external GMP over owned native copies of THC byte-buffer views.
 * This does not claim native-oracle, compiled-Core, or original-FFI coverage. */
public final class GmpTransportProbe {
    private record Example(long[] input, long word, long[] output, long carry) {}
    private static byte[] storage(long[] values) {
        byte[] bytes = new byte[16 + values.length * 8];
        Arrays.fill(bytes, (byte)0x5a);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < values.length; ++i) buffer.putLong(8 + i * 8, values[i]);
        return bytes;
    }
    public static void main(String[] arguments) throws Exception {
        var examples = new Example[] {
            new Example(new long[]{0}, 0, new long[]{0}, 0),
            new Example(new long[]{-1}, 1, new long[]{0}, 1),
            new Example(new long[]{-1, -1}, 1, new long[]{0, 0}, 1),
            new Example(new long[]{-1, 5}, 1, new long[]{0, 6}, 0),
            new Example(new long[]{123, 456}, -1, new long[]{122, 457}, 0)
        };
        try (var context = Context.newBuilder("llvm").allowNativeAccess(true)
                .allowIO(IOAccess.ALL).allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            var library = context.eval(Source.newBuilder("llvm", ByteSequence.create(
                Files.readAllBytes(Path.of(arguments[0]))), "gmp-transport.so").build());
            var call = library.getMember("thc_probe_gmp_add_1");
            int count = 0;
            for (var example : examples) for (boolean alias : new boolean[]{false, true}) {
                var input = storage(example.input());
                var before = input.clone();
                var output = alias ? input : storage(new long[example.input().length]);
                var source = new CbitsBuffer(input, alias);
                var destination = alias ? source : new CbitsBuffer(output, true);
                long carry = call.execute(destination, 8L, source, 8L,
                    (long)example.input().length, example.word()).asLong();
                if (carry != example.carry() || !Arrays.equals(output, storage(example.output())))
                    throw new AssertionError("Limb/carry/canary mismatch, alias=" + alias);
                if (!alias && !Arrays.equals(before, input))
                    throw new AssertionError("Read-only input was modified");
                ++count;
            }
            System.out.println("PASS: " + count + " external GMP managed/native copy cases");
        }
    }
}
