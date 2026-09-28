// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Consumers release results before evaluating any guest continuation. */
public final class TupleResultPool {
    private final HandoffPool pool = new HandoffPool();
    private HandoffStorage current;
    public int getDepth() { return pool.getDepth(); }
    public long getAllocations() { return pool.getAllocations(); }
    public HandoffStorage acquire(HandoffLayout layout) {
        if (current != null) throw new IllegalStateException("Check failed.");
        HandoffStorage result = pool.acquire(layout); current = result; return result;
    }
    public Object complete(HandoffStorage storage) {
        if (current != storage || !storage.getLive()) throw new IllegalStateException("Check failed.");
        storage.setCompletedGeneration(storage.getGeneration());
        return TupleComplete.INSTANCE;
    }
    public HandoffStorage completed() {
        HandoffStorage storage = current;
        if (storage == null) throw fault("Missing completed tuple result");
        if (!storage.getLive() || storage.getCompletedGeneration() != storage.getGeneration()) throw new IllegalStateException("Check failed.");
        return storage;
    }
    public void release(HandoffStorage storage, HandoffLayout layout) {
        if (current != storage) throw new IllegalStateException("Check failed.");
        pool.release(storage, layout); current = null;
    }
    public void releaseChecked(HandoffStorage storage, HandoffLayout expected) {
        if (storage.getLayout() == expected) release(storage, expected); else releaseMismatched(storage);
    }
    @TruffleBoundary private void releaseMismatched(HandoffStorage storage) { release(storage, storage.getLayout()); }
    public int retainedReferences() { return pool.retainedReferences(); }
}
