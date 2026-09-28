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
import java.util.function.Consumer;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Cold graph traversal with an explicit work stack. Cyclic shells stay private
 * until their final fields are initialized and the entire operation succeeds. */
public final class CompactCopyNode extends Node {
    private final GlobalBinding[] failures;
    @Child private Force force;
    private record Copy(Object value, Consumer<Object> store) {}
    public CompactCopyNode(Metrics metrics, GlobalBinding[] failures) { force = new Force(metrics); this.failures = failures; }
    public Object execute(VirtualFrame frame, ManagedCompact region, Object root, boolean sharing) {
        CompilerDirectives.transferToInterpreter();
        var registry = region.getOwner();
        var known = new IdentityHashMap<Object, Object>();
        var path = new IdentityHashMap<Object, Boolean>();
        var allocated = new ArrayList<ManagedCompact.Allocation>();
        var pending = new ArrayDeque<Object>();
        var result = new Object[1];
        pending.addLast(new Copy(root, value -> result[0] = value));
        region.begin();
        try {
            while (!pending.isEmpty()) {
                TruffleSafepoint.poll(this);
                var next = pending.removeLast();
                if (!(next instanceof Copy task)) { path.remove(next); continue; }
                var value = force.execute(frame, task.value());
                if (value == null) throw fault("Null guest value in compact region");
                if (registry.contains(region, value)) { task.store().accept(value); continue; }
                if (sharing && known.containsKey(value)) { task.store().accept(known.get(value)); continue; }
                if (!sharing && path.containsKey(value)) throw fault("Cyclic data requires compactAddWithSharing#");
                if (value instanceof DataValue data) {
                    var layout = data.getLayout();
                    // Shared nullary constructors are static values, not region allocations.
                    if (layout.getArity() == 0) { task.store().accept(value); continue; }
                    var copy = layout.allocate();
                    publish(task, value, copy, layout.compactBytes(), sharing, known, path, allocated, pending);
                    for (int index = layout.getArity() - 1; index >= 0; index--) {
                        int field = index;
                        if (layout.inactiveSumReference(data, index)) layout.initialize(copy, index, null);
                        else if (layout.compactPointer(index)) pending.addLast(new Copy(layout.read(data, index),
                            child -> layout.initialize(copy, field, child)));
                        else layout.copyCompactScalar(data, copy, index);
                    }
                } else if (value instanceof ManagedAllocation allocation) {
                    if (allocation.isPinned()) throw failure(1);
                    var copy = ManagedAllocation.immutable(allocation.copyBytesOut(0, allocation.getSize()), allocation.getAddressWidth());
                    publish(task, value, copy, 16L + copy.getSize(), sharing, known, path, allocated, pending);
                } else if (value instanceof byte[] bytes) {
                    publish(task, value, bytes.clone(), 16L + bytes.length, sharing, known, path, allocated, pending);
                } else if (value instanceof Object[] array) {
                    if (!ManagedArray.isFrozen(array)) throw failure(2);
                    var copy = ManagedArray.freeze(new Object[array.length]);
                    publish(task, value, copy, 24L + 8L * copy.length, sharing, known, path, allocated, pending);
                    for (int index = array.length - 1; index >= 0; index--) {
                        int field = index;
                        pending.addLast(new Copy(array[index], child -> copy[field] = child));
                    }
                } else if (value instanceof SmallArrayStorage array) {
                    if (!array.getFrozen()) throw failure(2);
                    var copy = ManagedSmallArray.freeze(new SmallArrayStorage(new Object[array.getLogicalSize()]));
                    publish(task, value, copy, 16L + 8L * copy.getLogicalSize(), sharing, known, path, allocated, pending);
                    int length = array.getLogicalSize();
                    for (int index = 0; index < length; index++) {
                        int field = index;
                        pending.addLast(new Copy(array.getElements()[index], child -> copy.getElements()[field] = child));
                    }
                } else if (value instanceof Closure) throw failure(0);
                else throw failure(2);
            }
            region.finish(allocated);
            registry.record(region, allocated);
            return result[0];
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
    private GuestException failure(int index) { return new GuestException(failures[index].read(), this); }
}
