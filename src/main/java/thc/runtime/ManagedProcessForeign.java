// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** ABI marshalling and declared activation. Interpreter hooks own the completed
 * result/errno continuation and delivery cut; no process effect is retried here. */
public final class ManagedProcessForeign {
    private static final Object OUTPUT_LOCK_ORDER = new Object();
    private static final class InterruptedWait extends RuntimeException {
        InterruptedWait() { super(null, null, false, false); }
    }
    private final Language.State context = Language.currentState(null);
    private final EnumMap<ProcessFailureStage, ManagedAddress> failures = new EnumMap<>(ProcessFailureStage.class);

    public ManagedProcessForeign() {
        for (var stage : ProcessFailureStage.values()) {
            ManagedAddress address;
            if (stage == ProcessFailureStage.NONE) address = ManagedAddress.Companion.nullAddress();
            else {
                byte[] bytes = stage.getOperation().getBytes(StandardCharsets.US_ASCII);
                address = ManagedAddress.Companion.fromHex(HexFormat.of().formatHex(Arrays.copyOf(bytes, bytes.length + 1)));
                address.toNativeBits(); // Stage immutable native images before any launch effect.
            }
            failures.put(stage, address);
        }
    }

    public static ManagedProcessForeign current(Node node) {
        return Language.currentState(node).getFiles().getProcessForeign();
    }

    /** Cancellation observes pending work; the interpreter claims it after saving the scalar/errno. */
    @TruffleBoundary
    public long invoke(ProcessOp operation, Object[] arguments, Node node) {
        var threads = context.getThreads();
        var previous = threads.enterForeign(operation == ProcessOp.WAIT ? ForeignSafety.INTERRUPTIBLE : ForeignSafety.UNSAFE);
        try {
            try {
                return execute(operation, arguments, node, operation == ProcessOp.WAIT ? () -> {
                    if (threads.interruptibleForeignPending$org_intelligence_thc()) throw new InterruptedWait();
                } : null);
            } catch (InterruptedWait ignored) {
                // No waitpid/reap occurred and the caller's output is untouched.
                context.getStdio().captureForeignErrno(4);
                return -1;
            }
        } finally { threads.leaveForeign(previous); }
    }

    private static int cint(Object value) {
        if (!(value instanceof Long number)) throw fault("Original process call requires a Long scalar carrier");
        if (number.longValue() != (long) number.intValue()) throw fault("Original process call requires a signed CInt");
        return number.intValue();
    }

    private static ManagedAddress pointer(Object value) {
        if (value instanceof ManagedAddress address) return address;
        throw fault("Original process call requires an address carrier");
    }

    private static byte[] string(ManagedAddress address) {
        var owner = address.nativeAllocation$org_intelligence_thc();
        try (var ignored = owner == null ? null : owner.borrow()) {
            long length = address.cStringLength();
            if (length >= Integer.MAX_VALUE) throw fault("Process string exceeds managed byte capacity");
            byte[] bytes = new byte[(int) length];
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) address.readWord8(index);
            return bytes;
        }
    }

    private static List<byte[]> vector(ManagedAddress address) {
        var owner = address.nativeAllocation$org_intelligence_thc();
        try (var ignored = owner == null ? null : owner.borrow()) {
            var values = new ArrayList<byte[]>();
            long index = 0;
            while (true) {
                var entry = address.readAddressElementIndex(index++);
                if (entry == ManagedAddress.Companion.nullAddress()) break;
                values.add(string(entry));
            }
            return values;
        }
    }

    private static void pointerCell(ManagedAddress address) {
        address.requireRange(0, 8, true);
        if (address.nativeAllocation$org_intelligence_thc() == null) {
            var owner = address.cbitsOwner$org_intelligence_thc();
            if (owner == null || owner.getAddressWidth() != 8 || address.cbitsOffset$org_intelligence_thc() % 8 != 0)
                throw fault("Process failure pointer requires aligned LP64 pointer storage");
        }
    }

    private record Region(ManagedAddress address, long size) {}

    private static long outputs(List<Region> regions, LongSupplier action) {
        var addresses = new ArrayList<ManagedAddress>(regions.size());
        for (var region : regions) addresses.add(region.address());
        return ManagedAddress.Companion.withNativeBorrows$org_intelligence_thc(addresses, () -> {
            for (int i = 0; i < regions.size(); i++) for (int j = 0; j < i; j++) {
                var first = regions.get(i);
                var second = regions.get(j);
                if (first.address().overlaps(0, first.size(), second.address(), 0, second.size()))
                    throw fault("Process output cells must be disjoint");
            }
            // Managed allocations cannot resize during publication; native owners are already borrowed.
            var owners = new ArrayList<ManagedAllocation>();
            for (var region : regions) {
                var owner = region.address().cbitsOwner$org_intelligence_thc();
                if (owner != null && !owners.contains(owner)) owners.add(owner);
            }
            owners.sort((a, b) -> Integer.compareUnsigned(System.identityHashCode(a), System.identityHashCode(b)));
            boolean collision = false;
            for (int index = 1; index < owners.size(); index++)
                if (System.identityHashCode(owners.get(index - 1)) == System.identityHashCode(owners.get(index))) collision = true;
            if (collision) synchronized (OUTPUT_LOCK_ORDER) { return locked(owners, 0, action); }
            return locked(owners, 0, action);
        });
    }

    private static long locked(List<ManagedAllocation> owners, int index, LongSupplier action) {
        if (index == owners.size()) return action.getAsLong();
        synchronized (owners.get(index)) { return locked(owners, index + 1, action); }
    }

    public long execute(ProcessOp operation, Object[] arguments) { return execute(operation, arguments, null, null); }
    public long execute(ProcessOp operation, Object[] arguments, Node node) { return execute(operation, arguments, node, null); }

    @TruffleBoundary
    public long execute(ProcessOp operation, Object[] arguments, Node node, Runnable beforeBlock) {
        if (Language.currentState(node) != context) throw fault("Process ABI adapter belongs to another context");
        if (arguments.length != operation.getArguments().size()) throw fault("Original process call arity mismatch");
        TupleResultsKt.requireVoidCarrier(arguments[arguments.length - 1]);
        if (operation == ProcessOp.CREATE) return create(arguments);
        int pid = cint(arguments[0]);
        if (operation == ProcessOp.TERMINATE) {
            var result = context.getFiles().processOperation(operation, pid, node, beforeBlock);
            context.getStdio().nativeError(result.getErrno());
            return result.getStatus();
        }
        var destination = pointer(arguments[1]);
        return outputs(List.of(new Region(destination, 4)), () -> {
            destination.requireByteRegion$org_intelligence_thc(4, true);
            var result = context.getFiles().processOperation(operation, pid, node, beforeBlock);
            if (result.getExitCode() != null) destination.writeNativeScalar(0, 4, result.getExitCode().longValue());
            context.getStdio().nativeError(result.getErrno());
            return result.getStatus();
        });
    }

    private static Long credential(Object value) {
        var address = pointer(value);
        if (address == ManagedAddress.Companion.nullAddress()) return null;
        var owner = address.nativeAllocation$org_intelligence_thc();
        try (var ignored = owner == null ? null : owner.borrow()) {
            address.requireByteRegion$org_intelligence_thc(4, false);
            return Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(address, 0));
        }
    }

    private long rejected(ManagedAddress failure, int error, ProcessFailureStage stage) {
        failure.writeAddressElementIndex(0, failures.get(stage));
        context.getStdio().nativeError(error);
        return -1;
    }

    private long create(Object[] values) {
        var arguments = vector(pointer(values[0]));
        var cwdPointer = pointer(values[1]);
        byte[] cwd = cwdPointer == ManagedAddress.Companion.nullAddress() ? null : string(cwdPointer);
        var parentEnvironment = context.getEnvironment().snapshotForProcess();
        var environmentPointer = pointer(values[2]);
        var environment = environmentPointer == ManagedAddress.Companion.nullAddress() ? parentEnvironment.getEntries() : vector(environmentPointer);
        int[] streams = new int[3];
        for (int index = 0; index < streams.length; index++) streams[index] = cint(values[index + 3]);
        ManagedAddress[] destinations = new ManagedAddress[3];
        for (int index = 0; index < destinations.length; index++) destinations[index] = pointer(values[index + 6]);
        Long group = credential(values[9]);
        Long user = credential(values[10]);
        int flags = cint(values[11]);
        var failure = pointer(values[12]);
        var regions = new ArrayList<Region>();
        regions.add(new Region(failure, 8));
        for (int index = 0; index < destinations.length; index++)
            if (streams[index] == -1) regions.add(new Region(destinations[index], 4));
        return outputs(regions, () -> {
            pointerCell(failure);
            for (int index = 0; index < streams.length; index++)
                if (streams[index] == -1) destinations[index].requireByteRegion$org_intelligence_thc(4, true);
            failure.writeAddressElementIndex(0, ManagedAddress.Companion.nullAddress());
            try {
                return context.getFiles().launchProcess(
                    arguments, environment, cwd, streams, flags, group, user, parentEnvironment.getSearchPath(), (pid, returned) -> {
                        for (int index = 0; index < streams.length; index++)
                            if (streams[index] == -1) destinations[index].writeNativeScalar(0, 4, returned[index]);
                    });
            } catch (Throwable error) {
                if (error instanceof ProcessSpawnException spawn) return rejected(failure, spawn.getErrno(), spawn.getStage());
                if (error instanceof NativeFileException file) return rejected(failure, file.getErrno(), ProcessFailureStage.ARGUMENTS);
                if (error instanceof ClosedChannelException) return rejected(failure, 9, ProcessFailureStage.ARGUMENTS);
                if (error instanceof UnsupportedOperationException) return rejected(failure, 95, ProcessFailureStage.ARGUMENTS);
                throw propagate(error);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
