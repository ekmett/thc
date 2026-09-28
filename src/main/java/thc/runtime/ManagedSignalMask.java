// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.io.ByteSequence;
import thc.Language;
import thc.NativeIO;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import static thc.runtime.RuntimeFault.fault;

/** Partial original sigprocmask: query or effective SIGTTOU-only changes on a
 * platform thread with native authority. The real OS thread owns the mask;
 * original callers must restore on that same thread. */
public final class ManagedSignalMask {
    private final Language.State owner;
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final AtomicReference<FutureTask<Object>> libraryTask = new AtomicReference<>();
    public ManagedSignalMask(Language.State owner) { this.owner = owner; }
    private void authority() {
        if (Language.currentState(null) != owner) throw fault("Signal-mask service belongs to another context");
        if (!owner.getEnv().isNativeAccessAllowed()) throw fault("Original sigprocmask requires native access");
        if (Thread.currentThread().isVirtual()) throw fault("Original sigprocmask requires a platform thread");
        if (!NativeIO.INSTANCE.supportedHost$org_intelligence_thc()) throw fault("Original sigprocmask requires Linux x86_64 glibc");
    }
    private Object library() {
        var task = libraryTask.get();
        if (task == null) {
            var candidate = new FutureTask<Object>(() -> {
                byte[] bytes;
                try (var input = getClass().getResourceAsStream("/thc/native/native-signal-api.so")) {
                    if (input == null) throw fault("Missing native signal-mask bridge");
                    bytes = input.readAllBytes();
                }
                var result = owner.getEnv().parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "native-signal-api.so").build()).call();
                long size = interop.asLong(interop.execute(interop.readMember(result, "thc_signal_size")));
                if (size != imageSize()) throw fault("Native signal-mask/image ABI mismatch");
                return result;
            });
            if (libraryTask.compareAndSet(null, candidate)) { task = candidate; candidate.run(); }
            else task = libraryTask.get();
        }
        try {
            var selected = task;
            if (selected.isDone()) return selected.get();
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<FutureTask<Object>, Object>) pending -> {
                    try { return pending.get(); } catch (ExecutionException failure) { throw propagate(failure); }
                }, selected);
        } catch (ExecutionException failure) {
            libraryTask.compareAndSet(task, null);
            throw propagate(failure.getCause() == null ? failure : failure.getCause());
        } catch (InterruptedException failure) { throw propagate(failure); }
    }
    private long imageSize() { return TermiosImage.scalar(OriginalStdioOp.SIZEOF_SIGSET, ManagedAddress.Companion.nullAddress(), 0); }
    @TruffleBoundary public long call(long how, ManagedAddress set, ManagedAddress oldset) {
        authority(); // Before native loading, buffer access, or mask effects.
        if (how != (long) (int) how) throw fault("Original sigprocmask requires a canonical signed CInt");
        long size = imageSize();
        var nil = ManagedAddress.Companion.nullAddress();
        if (set != nil) set.requireByteRegion$org_intelligence_thc(size, false);
        if (oldset != nil) oldset.requireByteRegion$org_intelligence_thc(size, true);
        if (set != nil && oldset != nil && set.overlaps(0, size, oldset, 0, size))
            throw fault("Original sigprocmask requires disjoint restrict-qualified input/output images");
        Object function;
        try { function = interop.readMember(library(), "thc_signal_mask"); }
        catch (InteropException failure) { throw propagate(failure); }
        // Resolve LLVM before borrowing; retain both owners through snapshot/effect/copyback.
        return ManagedAddress.Companion.withNativeBorrows$org_intelligence_thc(List.of(set, oldset),
            () -> borrowedCall(function, how, set, oldset, size));
    }
    private byte[] snapshot(ManagedAddress set, long size) {
        set.requireByteRegion$org_intelligence_thc(size, false);
        var bytes = new byte[(int) size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) set.readWord8(i);
        return bytes;
    }
    private long borrowedCall(Object function, long how, ManagedAddress set, ManagedAddress oldset, long size) {
        var nil = ManagedAddress.Companion.nullAddress();
        byte[] input = null;
        if (set != nil) {
            var allocation = set.cbitsOwner$org_intelligence_thc();
            if (allocation == null) input = snapshot(set, size);
            else synchronized (allocation) { input = snapshot(set, size); }
        }
        if (input != null && oldset != nil && set.overlaps(0, size, oldset, 0, size))
            throw fault("Original sigprocmask requires disjoint restrict-qualified input/output images");
        if (oldset != nil) oldset.requireByteRegion$org_intelligence_thc(size, true);
        // Snapshot input before locking output: no inverse two-allocation lock order.
        var allocation = oldset.cbitsOwner$org_intelligence_thc();
        if (allocation == null) return invoke(function, how, oldset, size, input);
        synchronized (allocation) { return invoke(function, how, oldset, size, input); }
    }
    private long invoke(Object function, long how, ManagedAddress oldset, long size, byte[] input) {
        var nil = ManagedAddress.Companion.nullAddress();
        if (oldset != nil) oldset.requireByteRegion$org_intelligence_thc(size, true);
        try (var scope = new NativeLimbScope()) {
            byte[] output = null;
            if (oldset != nil) {
                output = new byte[(int) size];
                for (int i = 0; i < output.length; i++) output[i] = (byte) oldset.readWord8(i);
            }
            var nativeInput = input == null ? scope.allocate(0) : scope.snapshot(input, 0, input.length);
            var nativeOutput = output == null ? scope.allocate(0) : scope.snapshot(output, 0, output.length);
            var error = scope.allocate(8);
            var previous = owner.getThreads().enterForeign();
            try {
                long result = interop.asLong(interop.execute(function, (int) how, nativeInput, nativeOutput,
                    input == null ? 0 : 1, output == null ? 0 : 1, error));
                long errno = error.readWord(0);
                if (result == -2 && errno == 0) throw fault("Original sigprocmask supports only effective SIGTTOU mask changes");
                if (result < -1 || result > 0 || (result == -1 && (errno < 1 || errno > Integer.MAX_VALUE)) || (result == 0 && errno != 0))
                    throw fault("Invalid native signal-mask result");
                if (output != null) {
                    nativeOutput.copyTo(output, 0, output.length);
                    for (int i = 0; i < output.length; i++) oldset.writeWord8(i, output[i]);
                }
                if (result == -1) owner.getStdio().nativeError$org_intelligence_thc(errno);
                return result;
            } finally { owner.getThreads().leaveForeign(previous); }
        } catch (InteropException failure) { throw propagate(failure); }
    }
    public static long execute(Node node, long how, ManagedAddress set, ManagedAddress oldset) {
        return Language.currentState(node).getSignalMask().call(how, set, oldset);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
