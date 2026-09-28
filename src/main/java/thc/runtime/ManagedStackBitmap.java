// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Original small-bitmap carriers. */
public final class ManagedStackBitmap {
    private final long bitmap, size;
    public ManagedStackBitmap(long bitmap, long size) { this.bitmap = bitmap; this.size = size; }
    public long getBitmap() { return bitmap; }
    public long getSize() { return size; }
}
