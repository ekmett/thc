// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.io.IOAccess;
import thc.runtime.NativeLimbScope;

/** Native-pointer and cleanup proof only; not original-Core FFI admission. */
public final class GmpOwnershipProbe {
    private static byte[] limbs(long... values) {
        var result = ByteBuffer.allocate(values.length * 8).order(ByteOrder.nativeOrder());
        for (long value : values) result.putLong(value);
        return result.array();
    }
    private static void equal(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError("Limb/canary mismatch");
    }
    private static void expired(NativeLimbScope.Pointer pointer) throws Exception {
        var interop = InteropLibrary.getUncached();
        if (interop.isPointer(pointer)) throw new AssertionError("Dead native pointer still exposed");
        try { interop.asPointer(pointer); throw new AssertionError("Dead address escaped"); }
        catch (UnsupportedMessageException expected) { }
        try { pointer.copyTo(new byte[16], 0, 16); throw new AssertionError("Dead allocation read"); }
        catch (IllegalStateException expected) { }
    }
    public static void main(String[] arguments) throws Exception {
        NativeLimbScope.Pointer cancelled;
        var context = Context.newBuilder("llvm").allowNativeAccess(true).allowIO(IOAccess.ALL)
                .allowExperimentalOptions(true).option("engine.Compilation", "false").build();
        try {
            var library = context.eval(Source.newBuilder("llvm", ByteSequence.create(
                Files.readAllBytes(Path.of(arguments[0]))), "gmp-api.so").build());
            if (library.getMember("thc_gmp_limb_bits").execute().asInt() != 64)
                throw new AssertionError("Native GMP limb ABI mismatch");
            var add = library.getMember("thc_gmp_add_word");
            for (boolean alias : new boolean[]{false, true}) {
                byte[] source = limbs(-1, -1), before = source.clone();
                byte[] destination = alias ? source : new byte[16];
                NativeLimbScope.Pointer output;
                try (var scope = new NativeLimbScope()) {
                    var input = scope.snapshot(source, 0, 16);
                    output = scope.allocate(16);
                    if (add.execute(output, input, 2L, 1L).asLong() != 1L)
                        throw new AssertionError("Wrong carry");
                    output.copyTo(destination, 0, 16);
                    equal(limbs(0, 0), destination);
                    if (!alias) equal(before, source);
                    byte[] canaries = limbs(0x5a5a, 7, 7, 0x5a5a);
                    output.copyTo(canaries, 8, 16);
                    equal(limbs(0x5a5a, 0, 0, 0x5a5a), canaries);
                }
                expired(output);
            }
            NativeLimbScope.Pointer failed;
            try (var scope = new NativeLimbScope()) {
                failed = scope.allocate(16);
                // A real failed Sulong conversion: cleanup is outside LLVM.
                try { add.execute(failed, new Object(), 2L, 1L); throw new AssertionError("Invalid pointer accepted"); }
                catch (IllegalArgumentException | org.graalvm.polyglot.PolyglotException expected) { }
            }
            expired(failed);
            try (var scope = new NativeLimbScope()) {
                var input = scope.snapshot(limbs(1, 2), 0, 16);
                byte[] destination = limbs(7, 8);
                try { input.copyTo(destination, 1, 16); throw new AssertionError("Overflow accepted"); }
                catch (IndexOutOfBoundsException expected) { }
                equal(limbs(7, 8), destination);
                try (var pool = Executors.newSingleThreadExecutor()) {
                    if (pool.submit(() -> InteropLibrary.getUncached().isPointer(input)).get())
                        throw new AssertionError("Confined pointer escaped its thread");
                }
            }
            try (var scope = new NativeLimbScope()) {
                cancelled = scope.allocate(16);
                context.close(true);
                // Closing the host scope must remain valid after guest disposal.
            }
        } finally {
            try { context.close(); }
            catch (org.graalvm.polyglot.PolyglotException expected) {
                if (!expected.isCancelled()) throw expected;
            }
        }
        expired(cancelled);
        System.out.println("PASS: actual GMP arena pointers, carry, aliases, canaries, failed interop, bounds, confinement, disposal cleanup");
    }
}
