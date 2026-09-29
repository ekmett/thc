// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import static thc.runtime.RuntimeFault.fault;

/** Byte-addressed interpreter stack. Managed words never acquire numeric addresses. */
final class GhcBCOStack {
    interface Marker {}
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private byte[] bytes = new byte[64];
    private Object[] references = new Object[8];
    private final BitSet managed = new BitSet();
    private int size;

    boolean isEmpty() { return size == 0; }
    int words() { aligned(); return size / 8; }
    private void aligned() { if ((size & 7) != 0) throw fault("BCO word operation on an unaligned stack"); }
    private void reserve(int width) {
        if (size > Integer.MAX_VALUE - width) throw fault("BCO stack exceeds managed bounds");
        int needed = size + width;
        if (needed > bytes.length) {
            int capacity = (int) Math.min(Integer.MAX_VALUE - 7L, Math.max((long) needed, bytes.length * 2L));
            if (capacity < needed) throw fault("BCO stack exceeds managed bounds");
            capacity = (capacity + 7) & ~7;
            bytes = Arrays.copyOf(bytes, capacity);
            references = Arrays.copyOf(references, capacity / 8);
        }
    }
    private int range(long offset, int width) {
        if (offset < 0 || offset > size || width > size - offset) throw fault("BCO stack offset outside live values");
        return size - (int) offset - width;
    }
    private int cell(long offset) {
        aligned();
        if (offset < 0 || offset >= size / 8) throw fault("BCO stack offset outside live values");
        return size / 8 - 1 - (int) offset;
    }
    boolean isReference(long offset) { return managed.get(cell(offset)); }
    Object peek(long offset) {
        int cell = cell(offset);
        return managed.get(cell) ? references[cell] : read(offset * 8, 8);
    }
    Object pointer(long offset) {
        Object value = peek(offset);
        if (!isReference(offset) || value instanceof Marker) throw fault("BCO pointer operation received raw bits or a stack marker");
        return value;
    }
    long read(long offset, int width) {
        int first = range(offset, width);
        int ref = managed.nextSetBit(first / 8);
        if (ref >= 0 && ref * 8 < first + width) throw fault("BCO word operation received a pointer or stack marker");
        long value = 0;
        for (int i = 0; i < width; i++) {
            int shift = (LITTLE_ENDIAN ? i : width - 1 - i) * 8;
            value |= (long) (bytes[size - 1 - (int) offset - i] & 255) << shift;
        }
        return value;
    }
    void push(long value, int width) {
        reserve(width);
        for (int i = 0; i < width; i++) {
            int shift = (LITTLE_ENDIAN ? i : width - 1 - i) * 8;
            bytes[size + width - 1 - i] = (byte) (value >>> shift);
        }
        size += width;
    }
    void pushReference(Object value) {
        aligned(); reserve(8);
        managed.set(size / 8); references[size / 8] = value; size += 8;
    }
    private void drop(int width) {
        range(0, width);
        int previous = size; size -= width;
        for (int i = (size + 7) / 8; i < (previous + 7) / 8; i++) references[i] = null;
        managed.clear((size + 7) / 8, (previous + 7) / 8);
    }
    Object popPointer() { Object value = pointer(0); drop(8); return value; }
    Object pop() { Object value = peek(0); drop(8); return value; }
    long popWord() { long value = read(0, 8); drop(8); return value; }
    void setWord(long offset, long value) {
        cell(offset);
        read(offset * 8, 8);
        int first = range(offset * 8, 8);
        for (int i = 0; i < 8; i++) bytes[first + 7 - i] = (byte) (value >>> ((LITTLE_ENDIAN ? i : 7 - i) * 8));
    }
    void copy(long[] offsets) {
        Object[] values = new Object[offsets.length]; boolean[] pointers = new boolean[offsets.length];
        for (int i = 0; i < offsets.length; i++) { values[i] = peek(offsets[i]); pointers[i] = isReference(offsets[i]); }
        for (int i = 0; i < offsets.length; i++) {
            if (pointers[i]) pushReference(values[i]); else push((Long) values[i], 8);
        }
    }
    void slide(long keep, long remove) {
        int words = words();
        if (keep < 0 || remove < 0 || keep > words || remove > words - keep) throw fault("BCO SLIDE outside live values");
        int from = words - (int) keep, into = from - (int) remove;
        System.arraycopy(bytes, from * 8, bytes, into * 8, (int) keep * 8);
        System.arraycopy(references, from, references, into, (int) keep);
        for (int i = 0; i < keep; i++) managed.set(into + i, managed.get(from + i));
        drop((int) remove * 8);
    }
    void validate(boolean[] nonPointers, int offset) {
        if (offset < 0 || nonPointers.length > words() - offset) throw fault("BCO bitmap exceeds its live stack");
        for (int i = 0; i < nonPointers.length; i++) {
            if (nonPointers[i]) read((long) (offset + i) * 8, 8);
            else pointer(offset + i);
        }
    }
}
