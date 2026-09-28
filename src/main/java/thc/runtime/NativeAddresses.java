// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.WeakReference;
import java.util.TreeMap;
import java.util.WeakHashMap;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Native immutable images retain no managed backing through their weak keys. */
public final class NativeAddresses {
    private final TruffleLanguage.Env env;
    private final WeakHashMap<Object, NativeReadOnlyImage> images = new WeakHashMap<>();
    private final TreeMap<Long, WeakReference<NativeReadOnlyImage>> ranges = new TreeMap<>(Long::compareUnsigned);
    private final TreeMap<Long, WeakReference<ManagedAllocation>> pinned = new TreeMap<>(Long::compareUnsigned);
    private boolean closed;
    public NativeAddresses(TruffleLanguage.Env env) { this.env = env; }
    private void requireOpen() { if (closed) throw fault("Native address registry is closed"); }
    private void reap() {
        images.size(); // Drain backing keys before resolving numeric ranges.
        ranges.entrySet().removeIf(entry -> { var image = entry.getValue().get(); return image == null || !image.hasSource(); });
        pinned.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }
    @TruffleBoundary public synchronized long project(ManagedAddress address) {
        requireOpen();
        if (!env.isNativeAccessAllowed()) throw fault("Native address projection requires native access");
        reap();
        var owner = address.cbitsOwner();
        var segment = owner == null ? null : owner.nativeSegment();
        if (segment != null) {
            address.cbitsSegment();
            pinned.put(segment.address(), new WeakReference<>(owner));
            return segment.address() + address.cbitsOffset();
        }
        Object key = address.nativeImageKey();
        if (key == null) throw fault("Numeric projection requires pinned storage or a static literal; moving heap arrays cannot be projected");
        var image = images.get(key);
        if (image == null) {
            image = new NativeReadOnlyImage(key, address.nativeImageBytes());
            images.put(key, image);
            ranges.put(image.getBase(), new WeakReference<>(image));
        }
        image.requireLive();
        return image.getBase() + address.cbitsOffset();
    }
    @TruffleBoundary public synchronized ManagedAddress recover(long bits) {
        requireOpen();
        if (bits == 0) return ManagedAddress.nullAddress();
        var stable = StablePointers.current(null).recoverToken(bits);
        if (stable != null) return stable;
        reap();
        var entry = pinned.floorEntry(bits);
        if (entry != null) {
            var allocation = entry.getValue().get();
            if (allocation != null) {
                long displacement = bits - entry.getKey();
                if (Long.compareUnsigned(displacement, allocation.getSize()) <= 0)
                    return ManagedAddress.fromGuestByteArray(allocation).plus(displacement);
            }
        }
        var range = ranges.floorEntry(bits);
        var image = range == null ? null : range.getValue().get();
        if (image != null) {
            long displacement = bits - image.getBase();
            if (Long.compareUnsigned(displacement, image.getSize()) <= 0) {
                image.requireLive();
                Object source = image.source();
                if (source != null) return ManagedAddress.fromNativeImageSource(source, displacement);
            }
        }
        return ManagedAddress.unownedNumeric(bits);
    }
    @TruffleBoundary public synchronized NativeReadOnlyPointer transport(ManagedAddress address) {
        requireOpen();
        Object key = address.nativeImageKey();
        if (key == null) return null;
        var image = images.get(key);
        if (image == null) return null;
        image.requireLive();
        return new NativeReadOnlyPointer(image, key);
    }
    @TruffleBoundary public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var reference : ranges.values()) { var image = reference.get(); if (image != null) image.close(); }
        images.clear(); ranges.clear(); pinned.clear();
    }
    public static NativeAddresses current(Node node) { return Language.currentState(node).getNativeAddresses(); }
}
