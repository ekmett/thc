// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Standard StgInfoTable bytes only: no entry code or resumable stack. */
public final class ManagedStackInfoImage {
    private final byte[] bytes;
    private ManagedStackInfoImage(byte[] bytes) { this.bytes = bytes; }
    public int getByteSize() { return bytes.length; }
    public byte[] copyBytes() { return bytes.clone(); }
    public static ManagedStackInfoImage stack(TargetLayout layout) { return create(layout, layout.offset("closureStack")); }
    /** Diagnostic RET_SMALL has zero bitmap, payload and SRT. */
    public static ManagedStackInfoImage frame(TargetLayout layout) { return create(layout, layout.offset("closureRetSmall")); }

    private static ManagedStackInfoImage create(TargetLayout layout, int closureType) {
        if (!layout.getTablesNextToCode()) throw new IllegalArgumentException("Managed stack info images require tables-next-to-code");
        String[] names = {"Ptrs", "Nptrs", "Type", "Srt"};
        int[] starts = new int[names.length], ends = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            int offset = layout.offset("infoTable" + names[i] + "Offset");
            int width = layout.offset("infoTable" + names[i] + "Bytes");
            if (width != 1 && width != 2 && width != 4 && width != 8)
                throw new IllegalArgumentException("Unsupported StgInfoTable " + names[i] + " width: " + width);
            starts[i] = offset; ends[i] = offset + width - 1;
        }
        // TargetLayout already proves positive in-bounds extents; require disjointness too.
        for (int i = 0; i < names.length; i++) for (int j = i + 1; j < names.length; j++)
            if (!(ends[i] < starts[j] || ends[j] < starts[i]))
                throw new IllegalArgumentException("Overlapping StgInfoTable fields");
        int width = layout.offset("infoTableTypeBytes");
        if (closureType < 0 || (width != 8 && closureType >= (1L << (width * 8))))
            throw new IllegalArgumentException("StgInfoTable closure type does not fit its target field");
        ByteOrder order = switch (layout.getEndianness()) {
            case "little" -> ByteOrder.LITTLE_ENDIAN;
            case "big" -> ByteOrder.BIG_ENDIAN;
            default -> throw new IllegalStateException("Unsupported StgInfoTable byte order: " + layout.getEndianness());
        };
        // Zero bytes supply ptrs/nptrs, bitmap, SRT and padding; offsets exclude entry code.
        byte[] bytes = new byte[layout.offset("infoTableBytes")];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        int offset = layout.offset("infoTableTypeOffset");
        switch (width) {
            case 1 -> buffer.put(offset, (byte) closureType);
            case 2 -> buffer.putShort(offset, (short) closureType);
            case 4 -> buffer.putInt(offset, closureType);
            case 8 -> buffer.putLong(offset, closureType);
        }
        return new ManagedStackInfoImage(bytes);
    }
}
