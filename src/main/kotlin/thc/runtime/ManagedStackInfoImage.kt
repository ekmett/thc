// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Standard StgInfoTable bytes only: no executable entry code or resumable stack. */
internal class ManagedStackInfoImage private constructor(private val bytes: ByteArray) {
    val byteSize: Int get() = bytes.size
    fun copyBytes(): ByteArray = bytes.copyOf()

    companion object {
        fun stack(layout: TargetLayout): ManagedStackInfoImage = create(layout, layout.offset("closureStack"))

        /** Diagnostic RET_SMALL with a zero bitmap: no pointer/nonpointer payload or SRT. */
        fun frame(layout: TargetLayout): ManagedStackInfoImage = create(layout, layout.offset("closureRetSmall"))

        private fun create(layout: TargetLayout, closureType: Int): ManagedStackInfoImage {
            require(layout.tablesNextToCode) { "Managed stack info images require tables-next-to-code" }
            val fields = listOf("Ptrs", "Nptrs", "Type", "Srt").map { name ->
                val offset = layout.offset("infoTable${name}Offset")
                val width = layout.offset("infoTable${name}Bytes")
                require(width in setOf(1, 2, 4, 8)) { "Unsupported StgInfoTable $name width: $width" }
                offset until offset + width
            }
            // TargetLayout already proves positive, in-bounds extents and target
            // word/order/ordinal identity. Disjointness is additional here.
            for (i in fields.indices) for (j in i + 1 until fields.size)
                require(fields[i].last < fields[j].first || fields[j].last < fields[i].first) {
                    "Overlapping StgInfoTable fields"
                }
            val width = layout.offset("infoTableTypeBytes")
            require(closureType >= 0 && (width == 8 || closureType.toLong() < (1L shl (width * 8)))) {
                "StgInfoTable closure type does not fit its target field"
            }
            val order = when (layout.endianness) {
                "little" -> ByteOrder.LITTLE_ENDIAN
                "big" -> ByteOrder.BIG_ENDIAN
                else -> error("Unsupported StgInfoTable byte order: ${layout.endianness}")
            }
            // Zero initialization supplies ptrs/nptrs (or the RET_SMALL bitmap),
            // SRT and padding. The target type offset is relative to these bytes,
            // not to a code/entry pointer; consumers own any pointer adjustment.
            val bytes = ByteArray(layout.offset("infoTableBytes"))
            val buffer = ByteBuffer.wrap(bytes).order(order)
            val offset = layout.offset("infoTableTypeOffset")
            when (width) {
                1 -> buffer.put(offset, closureType.toByte())
                2 -> buffer.putShort(offset, closureType.toShort())
                4 -> buffer.putInt(offset, closureType)
                8 -> buffer.putLong(offset, closureType.toLong())
            }
            return ManagedStackInfoImage(bytes)
        }
    }
}
