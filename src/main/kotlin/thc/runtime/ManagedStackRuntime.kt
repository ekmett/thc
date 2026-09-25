// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import thc.Language
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/** Original stack/IPE service boundary. Images describe managed diagnostics, not continuations. */
internal object ManagedStackRuntime {
    @JvmStatic @TruffleBoundary
    fun stackInfo(snapshot: Any?, layout: TargetLayout): ManagedAddress =
        Language.currentState().stackSnapshots.stackInfo(snapshot, layout)

    @JvmStatic @TruffleBoundary
    fun frameInfo(snapshot: Any?, wordOffset: Long, layout: TargetLayout): Pair<ManagedAddress, ManagedAddress> =
        Language.currentState().stackSnapshots.frameInfo(snapshot, wordOffset, layout)

    @JvmStatic @TruffleBoundary
    fun stackFields(snapshot: Any?, layout: TargetLayout): Long =
        Language.currentState().stackSnapshots.stackFields(snapshot, layout)

    @JvmStatic @TruffleBoundary
    fun smallBitmap(snapshot: Any?, wordOffset: Long, layout: TargetLayout): ManagedStackBitmap =
        Language.currentState().stackSnapshots.smallBitmap(snapshot, wordOffset, layout)

    @JvmStatic @TruffleBoundary
    fun word(snapshot: Any?, wordOffset: Long, layout: TargetLayout): Long =
        Language.currentState().stackSnapshots.word(snapshot, wordOffset, layout)

    @JvmStatic @TruffleBoundary
    fun advance(snapshot: Any?, wordOffset: Long, layout: TargetLayout): ManagedStackAdvance =
        Language.currentState().stackSnapshots.advance(snapshot, wordOffset, layout)

    @JvmStatic @TruffleBoundary
    fun incompatibleGetter(operation: OriginalStackInfoOp, snapshot: Any?, wordOffset: Long, layout: TargetLayout): Nothing =
        Language.currentState().stackSnapshots.incompatibleGetter(operation, snapshot, wordOffset, layout)

    @JvmStatic @TruffleBoundary
    fun lookupIpe(key: ManagedAddress, destination: ManagedAddress, layout: TargetLayout): Long =
        Language.currentState().stackSnapshots.lookupIpe(key, destination, layout)
}

internal class ManagedStackBitmap(val bitmap: Long, val size: Long)
internal class ManagedStackAdvance(val snapshot: ManagedStackSnapshot?, val wordOffset: Long, val hasNext: Long)

/** Context owns registrations; snapshots carry only this registry's empty identity token. */
internal class ManagedStackRegistry {
    val token = Any()
    private var closed = false
    private class Provenance(val frame: ManagedStackFrame, val layout: TargetLayout) {
        // Contains stable literal strings but never the registered info-table key.
        var ipeTemplate: ManagedAllocation? = null
    }
    private class FrameInfo(val standard: ManagedAddress, val key: ManagedAddress, val provenance: Provenance)
    private data class Images(val layout: TargetLayout, val stack: ManagedAddress, val frames: List<FrameInfo>)
    // ManagedStackSnapshot has identity equality; values never refer to their snapshot.
    private val snapshots = WeakHashMap<ManagedStackSnapshot, Images>()
    private val entries = ManagedAddress.WeakLocations<Provenance>()
    private val layouts = HashSet<TargetLayout>()
    private val textFields = listOf("Name", "TyDesc", "Label", "Unit", "Module", "File", "Span")

    private fun validate(layout: TargetLayout) {
        if (closed) fault("Managed stack registry is closed")
        snapshots.size // Drain dead snapshots, releasing their image addresses.
        entries.size // Reap dead allocation registrations even on a cached getter.
        if (layout in layouts) return
        try {
            // The only constructible managed snapshot is a nonempty sequence of
            // one-word RET_SMALL headers. Every getter shares this closed domain;
            // no payload, alternative frame kind or underflow chunk is hidden in it.
            require(layout.offset("stackHeaderBytes") == layout.wordBytes &&
                layout.offset("stackClosurePayloadBytes") == layout.wordBytes) {
                "Managed diagnostic frames require a one-word nonprofiling header"
            }
            ManagedStackInfoImage.stack(layout)
            ManagedStackInfoImage.frame(layout)
            require(layout.offset("infoProvDescBytes") == 4) { "InfoProv closure_desc must be Word32" }
            val base = layout.offset("infoProvEntProvOffset").toLong()
            val ranges = listOf(layout.offset("infoProvEntInfoOffset").toLong() to layout.wordBytes) +
                textFields.map { base + layout.offset("infoProv${it}Offset") to layout.wordBytes } +
                listOf(base + layout.offset("infoProvDescOffset") to 4)
            for ((start, width) in ranges)
                require(start >= 0 && start + width <= layout.offset("infoProvEntBytes")) { "InfoProv field outside entry" }
            for (i in ranges.indices) for (j in i + 1 until ranges.size) {
                val (a, aw) = ranges[i]; val (b, bw) = ranges[j]
                require(a + aw <= b || b + bw <= a) { "Overlapping InfoProv fields" }
            }
        } catch (invalid: IllegalArgumentException) {
            fault("Invalid managed stack target layout: ${invalid.message}")
        }
        layouts += layout
    }

    private fun images(value: Any?, layout: TargetLayout): Images {
        validate(layout)
        val snapshot = value as? ManagedStackSnapshot ?: fault("Expected a managed StackSnapshot#")
        if (snapshot.ownerToken !== token) fault("StackSnapshot# belongs to another context or is diagnostic-only")
        snapshots[snapshot]?.let {
            if (it.layout != layout) fault("StackSnapshot# target layout changed")
            return it
        }
        fun address(image: ManagedStackInfoImage) =
            ManagedAddress.fromAllocation(ManagedAllocation.immutable(image.copyBytes(), layout.wordBytes))
        val frames = snapshot.frames.map { frame ->
            val standard = address(ManagedStackInfoImage.frame(layout))
            FrameInfo(standard, standard.plus(layout.offset("infoTableBytes").toLong()), Provenance(frame, layout))
        }
        return Images(layout, address(ManagedStackInfoImage.stack(layout)), frames).also {
            snapshots[snapshot] = it
            frames.forEach { frame -> entries[frame.key] = frame.provenance }
        }
    }

    @Synchronized fun stackInfo(snapshot: Any?, layout: TargetLayout): ManagedAddress = images(snapshot, layout).stack

    /** Each captured guest frame occupies one virtual word, with no payload. */
    @Synchronized fun frameInfo(snapshot: Any?, wordOffset: Long, layout: TargetLayout): Pair<ManagedAddress, ManagedAddress> {
        val frames = images(snapshot, layout).frames
        if (wordOffset < 0 || wordOffset >= frames.size.toLong()) fault("Managed stack frame offset outside snapshot")
        val frame = frames[wordOffset.toInt()]
        return frame.standard to frame.key
    }

    private fun diagnosticFrames(snapshot: Any?, layout: TargetLayout): Images {
        return images(snapshot, layout)
    }

    private fun diagnosticFrame(snapshot: Any?, wordOffset: Long, layout: TargetLayout): Images {
        val images = diagnosticFrames(snapshot, layout)
        if (wordOffset < 0 || wordOffset >= images.frames.size.toLong())
            fault("Managed stack frame offset outside snapshot")
        return images
    }

    /** Exact capacity of the zero-slack virtual image, not the native stack's capacity. */
    @Synchronized fun stackFields(snapshot: Any?, layout: TargetLayout): Long =
        diagnosticFrames(snapshot, layout).frames.size.toLong()

    @Synchronized fun smallBitmap(snapshot: Any?, wordOffset: Long, layout: TargetLayout): ManagedStackBitmap {
        diagnosticFrame(snapshot, wordOffset, layout)
        return emptyBitmap
    }

    /** Stack.cmm reads the word itself, including an info-pointer header. Our
     * zero-payload format has exactly one such word per frame. Materialize the
     * same owned immutable info image used by addr2Int#, never a numeric token.
     * The snapshot keeps its image alive; a returned integer alone does not. */
    @Synchronized fun word(snapshot: Any?, wordOffset: Long, layout: TargetLayout): Long {
        val images = diagnosticFrame(snapshot, wordOffset, layout)
        return images.frames[wordOffset.toInt()].key.toNativeBits()
    }

    @Synchronized fun advance(snapshot: Any?, wordOffset: Long, layout: TargetLayout): ManagedStackAdvance {
        val images = diagnosticFrame(snapshot, wordOffset, layout)
        val next = wordOffset + 1
        // Original Stack.cmm returns three null/zero carriers at the end, not
        // the old snapshot or a one-past frame location. Null is not a snapshot.
        return if (next < images.frames.size.toLong()) ManagedStackAdvance(snapshot as ManagedStackSnapshot, next, 1)
            else endOfStack
    }

    @Synchronized fun incompatibleGetter(operation: OriginalStackInfoOp, snapshot: Any?, wordOffset: Long,
        layout: TargetLayout): Nothing {
        diagnosticFrame(snapshot, wordOffset, layout)
        require(operation in setOf(OriginalStackInfoOp.CLOSURE, OriginalStackInfoOp.LARGE_BITMAP,
            OriginalStackInfoOp.BCO_LARGE_BITMAP, OriginalStackInfoOp.RET_FUN_LARGE_BITMAP,
            OriginalStackInfoOp.RET_FUN_SMALL_BITMAP, OriginalStackInfoOp.RET_FUN_BIG, OriginalStackInfoOp.UNDERFLOW))
        fault("${operation.symbol} cannot read a payload, bitmap kind or chunk absent from a managed diagnostic RET_SMALL frame")
    }

    @Synchronized fun lookupIpe(key: ManagedAddress, destination: ManagedAddress, layout: TargetLayout): Long {
        validate(layout)
        val entry = entries[key] ?: return 0L
        if (entry.layout != layout) fault("IPE key target layout changed")
        val count = layout.offset("infoProvEntBytes").toLong()
        destination.requireRange(0, count, writable = true)
        val template = entry.ipeTemplate ?: buildIpeTemplate(entry).also { entry.ipeTemplate = it }
        // A cached image containing key would strongly retain the weak index's owner.
        val scratch = ManagedAllocation.mutable(count, layout.wordBytes)
        scratch.copyFrom(template, 0, 0, count)
        scratch.writeAddressByteOffset(layout.offset("infoProvEntInfoOffset").toLong(), key)
        // The only destination mutation: all ranges/cells/aliases are validated by the owner.
        destination.copyFromAllocationBytes(scratch, 0, count)
        return 1L
    }

    private fun buildIpeTemplate(entry: Provenance): ManagedAllocation {
        val layout = entry.layout
        val count = layout.offset("infoProvEntBytes").toLong()
        val scratch = ManagedAllocation.mutable(count, layout.wordBytes)
        val frame = entry.frame
        val identity = frame.coreIdentity
        val source = frame.location?.takeIf { it.available }
        val note = frame.notes.lastOrNull { source != null && it.source.uri == source.uri &&
            it.startLine == source.startLine && it.startColumn == source.startColumn }
        val span = if (note != null) "${note.startLine}:${note.startColumn}-${note.endLine}:${note.endColumn} (end exclusive)"
            else if (source?.startLine != null) buildString {
                append(source.startLine)
                source.startColumn?.let { append(':'); append(it) }
                source.endLine?.let { append('-'); append(it); source.endColumn?.let { column -> append(':'); append(column) } }
            } else ""
        val values = listOf("THC managed diagnostic frame", "", identity?.occurrence ?: note?.label.orEmpty(),
            identity?.unitId.orEmpty(), identity?.moduleName.orEmpty(), source?.let { it.path ?: it.name }.orEmpty(), span)
        val base = layout.offset("infoProvEntProvOffset").toLong()
        for ((field, value) in textFields.zip(values)) {
            if ('\u0000' in value) fault("NUL in managed stack provenance")
            val bytes = value.toByteArray(Charsets.UTF_8)
            val text = ManagedAddress.fromHex(bytes.joinToString("") { "%02x".format(it) })
            scratch.writeAddressByteOffset(base + layout.offset("infoProv${field}Offset"), text)
        }
        val desc = ByteBuffer.allocate(4).order(if (layout.endianness == "little") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .putInt(layout.offset("closureRetSmall")).array()
        scratch.copyBytesIn(desc, 0, base + layout.offset("infoProvDescOffset"), 4)
        return scratch
    }

    @Synchronized fun dispose() { closed = true; snapshots.clear(); entries.clear(); layouts.clear() }

    companion object {
        private val emptyBitmap = ManagedStackBitmap(0, 0)
        private val endOfStack = ManagedStackAdvance(null, 0, 0)
    }
}
