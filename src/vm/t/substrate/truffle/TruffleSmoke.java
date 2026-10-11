// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.lang.ref.Reference;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import jam.vm.Weak;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.RuntimeOptions;

/** Captured guest objects and generalized weak associations in runtime-compiled native code. */
public final class TruffleSmoke {
    private static final int[] finalized = new int[2];
    private static volatile Object sink;
    private static boolean deoptimizeNextBoundary;
    private static int deoptimized;

    private static final class Guest {
        final int id;
        final byte[] payload = new byte[1024];

        Guest(int id) {
            this.id = id;
            payload[1023] = (byte) (id + 41);
        }
    }

    private static final class Conditional {
        final Guest key;
        final byte[] payload = new byte[2048];

        Conditional(Guest key) {
            this.key = key;
            payload[2047] = (byte) (key.id + 71);
        }
    }

    private static final class Finalizer implements Runnable {
        final Conditional value;

        Finalizer(Conditional value) { this.value = value; }

        @Override public void run() {
            int id = value.key.id;
            verifyValue(value, id);
            collect();
            verifyValue(value, id);
            check(++finalized[id] == 1, "finalizer runs once");
        }
    }

    private record Result(Guest key, Conditional value, int checksum, boolean compiled) { }

    private static final class GuestRoot extends RootNode {
        @CompilationFinal private Guest captured;
        @CompilationFinal private long token;

        GuestRoot(int id) {
            super(null);
            install(id);
        }

        void install(int id) {
            Guest key = new Guest(id);
            Conditional value = new Conditional(key);
            // Both conditional roots return to the key; they cannot keep the association alive.
            token = Weak.create(key, value, new Finalizer(value));
            captured = key;
        }

        void clear() {
            captured = null;
            token = 0;
        }

        @Override public String getName() { return "jam-native-guest"; }

        @Override public Object execute(VirtualFrame frame) {
            boolean enteredCompiled = CompilerDirectives.inCompiledCode();
            Guest key = captured;
            Conditional value = deref(token);
            guestBoundary(this, (boolean) frame.getArguments()[0], enteredCompiled);
            int checksum = key.payload[1023] + value.payload[2047];
            return new Result(key, value, checksum, enteredCompiled && CompilerDirectives.inCompiledCode());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyValue(Conditional value, int id) {
        check(value != null && value.key.id == id, "conditional value and return edge survive");
        check(value.key.payload[1023] == id + 41 && value.payload[2047] == id + 71, "captured payloads survive");
    }

    @TruffleBoundary
    private static Conditional deref(long token) {
        Conditional value = (Conditional) Weak.deref(token);
        check(value != null, "captured key retains its conditional value");
        return value;
    }

    @TruffleBoundary
    private static void guestBoundary(GuestRoot root, boolean collect, boolean enteredCompiled) {
        if (deoptimizeNextBoundary) {
            deoptimizeNextBoundary = false;
            check(enteredCompiled, "deoptimization boundary was entered from compiled code");
            OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
            Boolean found = Truffle.getRuntime().iterateFrames(frame -> {
                if (frame.getCallTarget() != target) return null;
                check(frame.isVirtualFrame(), "guest frame starts virtual");
                // Materialization forces an eager deoptimization snapshot, including its pin.
                frame.getFrame(FrameInstance.FrameAccess.MATERIALIZE);
                check(!frame.isVirtualFrame(), "guest frame was materialized");
                return Boolean.TRUE;
            });
            check(Boolean.TRUE.equals(found), "active guest frame is visible to stack introspection");
            invalidate(target);
            root.clear();
            long beforeMajor = majorCollections();
            collect();
            check(majorCollections() > beforeMajor, "major collection runs with the deoptimization snapshot pinned");
            check(Weak.pump() == 0, "live deoptimized caller retains its conditional graph");
            deoptimized++;
            return;
        }
        if (collect) collect();
    }

    private static void collect() {
        for (int i = 0; i < 8192; i++) sink = new byte[1024 + (i & 127)];
        System.gc();
    }

    private static void compile(OptimizedCallTarget target) {
        target.compile(true);
        target.waitForCompilation();
        check(target.isValid() && target.isValidLastTier() && target.getCodeAddress() != 0,
                "last-tier runtime machine code is installed");
    }

    private static void call(OptimizedCallTarget target, int id, boolean collect, boolean compiled) {
        Result result = (Result) target.call(collect);
        verifyValue(result.value(), id);
        check(result.key() == result.value().key, "compiled capture and weak value agree after GC");
        check(result.checksum() == 2 * id + 112, "compiled code reads relocated payloads");
        if (compiled) {
            check(result.compiled(), "guest executes in compiled code before and after its GC boundary");
            check(target.isValidLastTier(), "guest code remains installed after GC");
        }
    }

    private static void exercise(OptimizedCallTarget target, int id) {
        for (int round = 0; round < 3; round++) call(target, id, true, true);
        check(finalized[id] == 0 && Weak.pump() == 0, "live captured key does not finalize");
    }

    private static void invalidate(OptimizedCallTarget target) {
        target.invalidate("Jam native guest capture changed");
        check(!target.isValid(), "invalidation removes the installed entry point");
    }

    private static long majorCollections() {
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getName().equals("Jam major")) return collector.getCollectionCount();
        }
        throw new AssertionError("Missing Jam major collector");
    }

    private static void deoptimize(GuestRoot root, OptimizedCallTarget target, int id) {
        long token = root.token;
        deoptimizeNextBoundary = true;
        Result result = (Result) target.call(true);
        check(!deoptimizeNextBoundary && deoptimized == 1, "active-frame boundary completed once");
        check(!result.compiled() && !target.isValid(), "guest resumes in the interpreter");
        verifyValue(result.value(), id);
        check(result.key() == result.value().key && result.checksum() == 2 * id + 112,
                "deoptimized locals retain identity and payload after GC");
        check(Weak.deref(token) == result.value(), "deoptimized caller retains the weak association");
        root.captured = result.key();
        root.token = token;
        // The snapshot's pin has closed; subsequent collection must still preserve its returned values.
        long beforeMajor = majorCollections();
        collect();
        check(majorCollections() > beforeMajor, "major collection still runs after the deoptimized frame returns");
        compile(target);
        exercise(target, id);
    }

    private static void awaitFinalizer(long token, int id) {
        for (int attempt = 0; attempt < 6 && finalized[id] == 0; attempt++) {
            collect();
            Weak.pump();
        }
        check(finalized[id] == 1, "discarded capture becomes eligible for its finalizer");
        check(Weak.deref(token) == null, "finalized association is retired");
    }

    public static void main(String[] args) throws Exception {
        check(ImageInfo.inImageRuntimeCode(), "run this probe as a native executable");
        if (args.length > 0 && args[0].equals("check-masking")) {
            try {
                RuntimeOptions.set("MemoryMaskingAndFencing", true);
                throw new AssertionError("Jam must reject enabling memory masking after startup");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("not supported when using Jam"), "unsupported mitigation is diagnosed");
            }
            check(Boolean.FALSE.equals(RuntimeOptions.get("MemoryMaskingAndFencing")), "rejected update preserves the disabled option");
            RuntimeOptions.set("MemoryMaskingAndFencing", false);
        }
        Weak.checkAvailable();
        GuestRoot root = new GuestRoot(0);
        check(root.getCallTarget() instanceof OptimizedCallTarget, "optimized Truffle runtime is available");
        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        long first = root.token;

        call(target, 0, false, false);
        compile(target);
        exercise(target, 0);
        deoptimize(root, target, 0);

        invalidate(target);
        collect();
        check(Weak.pump() == 0, "invalidation preserves the still-captured weak key");
        compile(target);
        exercise(target, 0);

        invalidate(target);
        root.install(1);
        long second = root.token;
        call(target, 1, false, false);
        compile(target);
        // The first key is now reachable only through its weak value/finalizer and obsolete code.
        awaitFinalizer(first, 0);
        exercise(target, 1);

        invalidate(target);
        root.clear();
        awaitFinalizer(second, 1);
        check(Weak.pump() == 0, "completed finalizers leave no pending claims");
        Reference.reachabilityFence(target);
        System.out.println("Jam Native Image compiled Truffle consumer passed");
        if (Boolean.getBoolean("jam.runtime.audit")) {
            System.out.println("jam-runtime-audit-ready");
            System.out.flush();
            if (System.in.read() != '\n') throw new AssertionError("runtime audit did not resume");
        }
    }
}
