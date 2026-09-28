// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Detached word image with unforced pointer fields kept separately in payload order. */
public final class ClosureImage {
    private final String descriptor;
    private final byte[] bytes;
    private final Object[] pointers;
    public ClosureImage(String descriptor, byte[] bytes, Object[] pointers) { this.descriptor = descriptor; this.bytes = bytes; this.pointers = pointers; }
    public String getDescriptor() { return descriptor; }
    public byte[] getBytes() { return bytes; }
    public Object[] getPointers() { return pointers; }
}
