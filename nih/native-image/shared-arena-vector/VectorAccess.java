/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */
package com.oracle.svm.core.foreign;

import java.lang.ref.Reference;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetElement;
import com.oracle.svm.shared.AlwaysInline;
import jdk.internal.foreign.AbstractMemorySegmentImpl;
import jdk.internal.foreign.MemorySessionImpl;
import jdk.internal.misc.ScopedMemoryAccess.ScopedAccessError;
import jdk.internal.vm.vector.VectorSupport;

/** Owned lifetime boundary transplanted into the pinned provider's substitution class. */
public final class VectorAccess {
    @Substitute
    @TargetElement(onlyWith = ForeignAPIPredicates.SharedArenasEnabled.class)
    @AlwaysInline("Keep the direct VectorSupport call visible to intrinsic lowering")
    public static <V extends VectorSupport.Vector<E>, E, S extends VectorSupport.VectorSpecies<E>>
    V loadFromMemorySegment(Class<? extends V> vectorClass, Class<E> elementClass, int length,
                    AbstractMemorySegmentImpl segment, long offset, S species,
                    VectorSupport.LoadOperation<AbstractMemorySegmentImpl, V, S> fallback) {
        MemorySessionImpl session = segment.sessionImpl();
        session.acquire0();
        try {
            return VectorSupport.load(vectorClass, elementClass, length,
                    segment.unsafeGetBase(), segment.unsafeGetOffset() + offset, true,
                    segment, offset, species, fallback);
        } catch (ScopedAccessError failure) {
            throw failure.newRuntimeException();
        } finally {
            session.release0();
            Reference.reachabilityFence(session);
        }
    }

    @Substitute
    @TargetElement(onlyWith = ForeignAPIPredicates.SharedArenasEnabled.class)
    @AlwaysInline("Keep the direct VectorSupport call visible to intrinsic lowering")
    public static <V extends VectorSupport.Vector<E>, E, S extends VectorSupport.VectorSpecies<E>,
                    M extends VectorSupport.VectorMask<E>>
    V loadFromMemorySegmentMasked(Class<? extends V> vectorClass, Class<M> maskClass,
                    Class<E> elementClass, int length, AbstractMemorySegmentImpl segment,
                    long offset, M mask, S species, int offsetInRange,
                    VectorSupport.LoadVectorMaskedOperation<AbstractMemorySegmentImpl, V, S, M> fallback) {
        MemorySessionImpl session = segment.sessionImpl();
        session.acquire0();
        try {
            return VectorSupport.loadMasked(vectorClass, maskClass, elementClass, length,
                    segment.unsafeGetBase(), segment.unsafeGetOffset() + offset, true, mask, offsetInRange,
                    segment, offset, species, fallback);
        } catch (ScopedAccessError failure) {
            throw failure.newRuntimeException();
        } finally {
            session.release0();
            Reference.reachabilityFence(session);
        }
    }

    @Substitute
    @TargetElement(onlyWith = ForeignAPIPredicates.SharedArenasEnabled.class)
    @AlwaysInline("Keep the direct VectorSupport call visible to intrinsic lowering")
    public static <V extends VectorSupport.Vector<E>, E>
    void storeIntoMemorySegment(Class<? extends V> vectorClass, Class<E> elementClass, int length,
                    V value, AbstractMemorySegmentImpl segment, long offset,
                    VectorSupport.StoreVectorOperation<AbstractMemorySegmentImpl, V> fallback) {
        MemorySessionImpl session = segment.sessionImpl();
        session.acquire0();
        try {
            VectorSupport.store(vectorClass, elementClass, length,
                    segment.unsafeGetBase(), segment.unsafeGetOffset() + offset, true,
                    value, segment, offset, fallback);
        } catch (ScopedAccessError failure) {
            throw failure.newRuntimeException();
        } finally {
            session.release0();
            Reference.reachabilityFence(session);
        }
    }

    @Substitute
    @TargetElement(onlyWith = ForeignAPIPredicates.SharedArenasEnabled.class)
    @AlwaysInline("Keep the direct VectorSupport call visible to intrinsic lowering")
    public static <V extends VectorSupport.Vector<E>, E, M extends VectorSupport.VectorMask<E>>
    void storeIntoMemorySegmentMasked(Class<? extends V> vectorClass, Class<M> maskClass,
                    Class<E> elementClass, int length, V value, M mask,
                    AbstractMemorySegmentImpl segment, long offset,
                    VectorSupport.StoreVectorMaskedOperation<AbstractMemorySegmentImpl, V, M> fallback) {
        MemorySessionImpl session = segment.sessionImpl();
        session.acquire0();
        try {
            VectorSupport.storeMasked(vectorClass, maskClass, elementClass, length,
                    segment.unsafeGetBase(), segment.unsafeGetOffset() + offset, true,
                    value, mask, segment, offset, fallback);
        } catch (ScopedAccessError failure) {
            throw failure.newRuntimeException();
        } finally {
            session.release0();
            Reference.reachabilityFence(session);
        }
    }
}
