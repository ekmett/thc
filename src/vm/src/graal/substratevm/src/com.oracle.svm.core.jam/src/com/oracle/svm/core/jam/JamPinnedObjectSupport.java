// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.heap.AbstractPinnedObjectSupport;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/** Graal owns the Java root; Jam owns its stable physical-page alias. */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class)
final class JamPinnedObjectSupport extends AbstractPinnedObjectSupport {
    @Override
    public PinnedObject create(Object object) {
        Alias result = new Alias((PinnedObjectImpl) super.create(object));
        result.acquire();
        return result;
    }

    @Override
    @Uninterruptible(reason = "Alias.acquire registers the side mapping before exposing its address.", callerMustBe = true)
    protected void pinObject(Object object) { }

    @Override
    @Uninterruptible(reason = "Alias.close releases the side mapping under the heap lock.", callerMustBe = true)
    protected void unpinObject(Object object) { }

    private static final class Alias implements PinnedObject {
        private final PinnedObjectImpl root;
        private Pointer registration = Word.nullPointer();
        private Pointer address = Word.nullPointer();
        private boolean open = true;

        Alias(PinnedObjectImpl root) { this.root = root; }

        @Uninterruptible(reason = "Register the current object without an intervening safepoint.")
        void acquire() {
            Object object = root.getObject();
            if (!needsPinning(object)) {
                address = Word.objectToUntrackedPointer(object);
                return;
            }
            JamHeap heap = JamHeap.get();
            heap.lock().lockNoTransition();
            try {
                registration = JamNative.pinObject(heap.nativeHeap(), heap.encode(object),
                                LayoutEncoding.getSizeFromObjectInGC(object).unsignedShiftRight(3));
                address = JamNative.pinAddress(registration);
            } finally { heap.lock().unlock(); }
        }

        @Override
        @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
        public Object getObject() {
            VMError.guarantee(open, "Closed Jam pin");
            return root.getObject();
        }

        @Override
        @Uninterruptible(reason = "Release the alias and its Java root together outside GC.")
        public void close() {
            VMError.guarantee(open, "Closed Jam pin");
            JamHeap heap = JamHeap.get();
            heap.lock().lockNoTransition();
            try {
                if (registration.isNonNull()) JamNative.unpin(heap.nativeHeap(), registration);
                registration = Word.nullPointer();
                address = Word.nullPointer();
                root.close();
                open = false;
            } finally { heap.lock().unlock(); }
        }

        @Override
        public Pointer addressOfObject() {
            if (!SubstrateOptions.PinnedObjectAddressing.getValue()) {
                throw new UnsupportedOperationException("Pinned object addressing has been disabled.");
            }
            VMError.guarantee(open, "Closed Jam pin");
            return address;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends PointerBase> T addressOfArrayElement(int index) {
            Object object = getObject();
            if (object == null) throw new NullPointerException("PinnedObject is missing a referent");
            return (T) addressOfObject().add(LayoutEncoding.getArrayElementOffset(
                            ObjectHeader.readDynamicHubFromObject(object).getLayoutEncoding(), index));
        }
    }
}
