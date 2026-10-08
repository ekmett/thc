// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Cold graph traversal with an explicit work stack. Cyclic shells stay private
 * until their final fields are initialized and the entire operation succeeds. */
public final class CompactCopyNode extends Node {
    @Children private Expr[] failures;
    @Child private Force force;
    private record Copy(Object value, Consumer<Object> store) {}
    private static final class Traversal {
        final ManagedCompact region;
        final boolean sharing;
        final IdentityHashMap<Object, Object> known = new IdentityHashMap<>();
        final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        final ArrayList<ManagedCompact.Allocation> allocated = new ArrayList<>();
        final ArrayDeque<Object> pending = new ArrayDeque<>();
        Object result;
        Traversal(ManagedCompact region, Object root, boolean sharing) {
            this.region = region; this.sharing = sharing;
            pending.addLast(new Copy(root, value -> result = value));
        }
    }
    public CompactCopyNode(Metrics metrics, GlobalBinding[] failures) { this(metrics, failures, false); }
    public CompactCopyNode(Metrics metrics, GlobalBinding[] failures, boolean async) {
        this(metrics, java.util.Arrays.stream(failures).map(GlobalRead::new).toArray(Expr[]::new), async);
    }
    public CompactCopyNode(Metrics metrics, Expr[] failures, boolean async) {
        force = new Force(metrics, async); this.failures = failures;
    }
    public Object execute(VirtualFrame frame, ManagedCompact region, Object root, boolean sharing) {
        CompilerDirectives.transferToInterpreter();
        return traverse(frame, new Traversal(region, root, sharing), null, null, null);
    }
    private Object traverse(VirtualFrame frame, Traversal saved, Copy interrupted, List<AstResumeStep> steps, Object input) {
        var region = saved.region;
        boolean sharing = saved.sharing;
        var registry = region.getOwner();
        var known = saved.known;
        var path = saved.path;
        var allocated = saved.allocated;
        var pending = saved.pending;
        Copy task = interrupted;
        // A parked/abandoned copy owns only private shells. Release the region
        // on every cut and reacquire it for the next one-shot resume segment.
        region.begin();
        try {
            // A payload read can suspend after its rejected Copy was consumed.
            // Its remaining throw must run even when no traversal work remains.
            if (task == null && steps != null) {
                AstContinuations.resumeAstSteps(frame, steps, input);
                throw fault("Compact failure continuation returned");
            }
            while (task != null || !pending.isEmpty()) {
                TruffleSafepoint.poll(this);
                if (task == null) {
                    var next = pending.removeLast();
                    if (!(next instanceof Copy copy)) { path.remove(next); continue; }
                    task = copy;
                }
                var value = steps == null ? AstControl.forceCallback(frame, this, force, task.value())
                    : AstContinuations.resumeAstSteps(frame, steps, input);
                steps = null;
                Copy current = task;
                task = null;
                if (value == null) throw fault("Null guest value in compact region");
                if (registry.contains(region, value)) { current.store().accept(value); continue; }
                if (sharing && known.containsKey(value)) { current.store().accept(known.get(value)); continue; }
                if (!sharing && path.containsKey(value)) throw fault("Cyclic data requires compactAddWithSharing#");
                if (value instanceof DataValue data) {
                    var layout = data.getLayout();
                    // Shared nullary constructors are static values, not region allocations.
                    if (layout.getArity() == 0) { current.store().accept(value); continue; }
                    var copy = layout.allocate();
                    publish(current, value, copy, layout.compactBytes(), sharing, known, path, allocated, pending);
                    for (int index = layout.getArity() - 1; index >= 0; index--) {
                        int field = index;
                        if (layout.inactiveSumReference(data, index)) layout.initialize(copy, index, null);
                        else if (layout.compactPointer(index)) pending.addLast(new Copy(layout.read(data, index),
                            child -> layout.initialize(copy, field, child)));
                        else layout.copyCompactScalar(data, copy, index);
                    }
                } else if (value instanceof ManagedAllocation allocation) {
                    if (allocation.isPinned()) throw failure(frame, 1);
                    var copy = ManagedAllocation.immutableGuest(allocation.copyBytesOut(0, allocation.getSize()), allocation.getAddressWidth());
                    publish(current, value, copy, 16L + copy.getSize(), sharing, known, path, allocated, pending);
                } else if (value instanceof byte[] bytes) {
                    Object copy = thc.Language.currentState(this).getNativeByteArrays()
                        ? ManagedAllocation.immutableGuest(bytes, (int) java.lang.foreign.ValueLayout.ADDRESS.byteSize()) : bytes.clone();
                    publish(current, value, copy, 16L + bytes.length, sharing, known, path, allocated, pending);
                } else if (value instanceof Object[] array) {
                    if (!ManagedArray.isFrozen(array)) throw failure(frame, 2);
                    var copy = ManagedArray.freeze(new Object[array.length]);
                    publish(current, value, copy, 24L + 8L * copy.length, sharing, known, path, allocated, pending);
                    for (int index = array.length - 1; index >= 0; index--) {
                        int field = index;
                        pending.addLast(new Copy(array[index], child -> copy[field] = child));
                    }
                } else if (value instanceof SmallArrayStorage array) {
                    if (!array.getFrozen()) throw failure(frame, 2);
                    var copy = ManagedSmallArray.freeze(new SmallArrayStorage(new Object[array.getLogicalSize()]));
                    publish(current, value, copy, 16L + 8L * copy.getLogicalSize(), sharing, known, path, allocated, pending);
                    int length = array.getLogicalSize();
                    for (int index = 0; index < length; index++) {
                        int field = index;
                        pending.addLast(new Copy(array.getElements()[index], child -> copy.getElements()[field] = child));
                    }
                } else if (value instanceof Closure) throw failure(frame, 0);
                else throw failure(frame, 2);
            }
            region.finish(allocated);
            registry.record(region, allocated);
            return saved.result;
        } catch (AstCapture cut) {
            Copy current = task;
            throw cut.enclose(remaining -> (resumed, value) -> traverse(resumed, saved, current, remaining, value));
        } finally { region.end(); }
    }
    private static void publish(Copy task, Object value, Object copy, long bytes, boolean sharing,
            IdentityHashMap<Object, Object> known, IdentityHashMap<Object, Boolean> path,
            ArrayList<ManagedCompact.Allocation> allocated, ArrayDeque<Object> pending) {
        if (sharing) known.put(value, copy);
        else { path.put(value, true); pending.addLast(value); }
        allocated.add(new ManagedCompact.Allocation(copy, bytes));
        task.store().accept(copy);
    }
    private GuestException failure(VirtualFrame frame, int index) {
        Object payload;
        try { payload = failures[index].execute(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> { throw new GuestException(input, this); }); }
        return new GuestException(payload, this);
    }
}
