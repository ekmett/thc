// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** A context snapshots capacity before guest pinning. Dense logical indices
 * remain independent of sparse OS CPU identities. */
public final class CpuAffinity {
    private final NativeCpuAffinity nativeAffinity;
    private final int count;
    public CpuAffinity(NativeCpuAffinity nativeAffinity, int availableProcessors) {
        this.nativeAffinity = nativeAffinity;
        count = Math.min(nativeAffinity == null ? Integer.MAX_VALUE : nativeAffinity.getCount(), Math.max(1, availableProcessors));
    }
    public int getCount() { return count; }
    public CpuAffinityMode getMode() { return nativeAffinity == null ? CpuAffinityMode.UNAVAILABLE : nativeAffinity.getMode(); }
    public CpuCoordinate coordinate(int index) {
        return index >= 0 && index < count && nativeAffinity != null ? nativeAffinity.coordinate(index) : null;
    }
    @TruffleBoundary public AutoCloseable bindCurrent(long capability) {
        return nativeAffinity == null ? null : nativeAffinity.bindCurrent((int) Math.floorMod(capability, (long) count));
    }
    @TruffleBoundary public AutoCloseable resetCurrent() { return nativeAffinity == null ? null : nativeAffinity.resetCurrent(); }
    public static CpuAffinity discover(boolean nativeAccess) {
        int available = Runtime.getRuntime().availableProcessors();
        NativeCpuAffinity nativeAffinity = null;
        if (nativeAccess && !Thread.currentThread().isVirtual()) {
            String os = System.getProperty("os.name");
            if (os.startsWith("Linux")) nativeAffinity = LinuxCpuAffinity.discover();
            else if (os.startsWith("Windows")) nativeAffinity = WindowsCpuAffinity.discover();
        }
        if (nativeAffinity != null) {
            try { CompilerCpuAffinity.install(nativeAffinity::resetCurrent); }
            catch (Exception | LinkageError unavailable) { }
        }
        return new CpuAffinity(nativeAffinity, available);
    }
}
