// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import thc.CoreCompactFormat.Segment
import thc.runtime.CoreSourceLocation
import thc.runtime.CoreSources

/** Display-only selected lookups. No table index or source tree is built at load. */
internal class CoreCompactDebug(private val file: CoreCompactFile) {
    private data class Directory(val start: Long, val count: Long)
    private data class Selection(val directory: Directory, val payload: Long)
    private data class Notes(val primary: Int, val values: List<Map<String, Any?>>)

    private fun directory(segment: Segment, width: Int): Directory? {
        val length = file.header()[segment].length
        if (length == 0L) return null
        require(length >= 16) { "Truncated compact Core debug directory" }
        return file.debugAt(segment, length - 16, 16) { cursor ->
            val start = cursor.offset()
            val count = cursor.offset()
            require(start <= length - 16 && (length - 16 - start) % width == 0L &&
                count == (length - 16 - start) / width) { "Invalid compact Core debug directory extent" }
            Directory(start, count)
        }
    }

    private fun source(segment: Segment, offset: Long): Selection? {
        val directory = directory(segment, 24) ?: return null
        var low = 0L
        var high = directory.count
        while (low < high) {
            val mid = low + (high - low) / 2
            val start = file.debugAt(segment, directory.start + mid * 24, 8) { it.offset() }
            if (start <= offset) low = mid + 1 else high = mid
        }
        if (low == 0L) return null
        return file.debugAt(segment, directory.start + (low - 1) * 24, 24) { cursor ->
            val start = cursor.offset(); val end = cursor.offset(); val payload = cursor.offset()
            require(start < end && end <= file.header()[Segment.DATA].length && payload < directory.start) {
                "Invalid compact Core source interval"
            }
            if (offset >= end) null else Selection(directory, payload)
        }
    }

    private fun <T> payload(segment: Segment, selection: Selection?, read: (CoreCompactCursor) -> T): T? {
        if (selection == null) return null
        return file.debugAt(segment, selection.payload, selection.directory.start - selection.payload) { cursor ->
            when (val tag = cursor.byte()) {
                0 -> null
                1 -> read(cursor)
                else -> error("Invalid compact Core source payload tag: $tag")
            }
        }
    }

    private fun text(cursor: CoreCompactCursor): String = cursor.text(cursor.count())
    private fun common(cursor: CoreCompactCursor): String = file.string(cursor.unsigned(), cursor.unsigned())
    private fun <T> optional(cursor: CoreCompactCursor, read: () -> T): T? = when (val tag = cursor.byte()) {
        0, 1 -> null
        2 -> read()
        else -> error("Invalid compact Core debug presence tag: $tag")
    }

    /** Null is explicit absence, not permission to floor-search into another binding. */
    fun location(offset: Long): CoreSourceLocation? {
        require(offset >= 0 && offset < file.header()[Segment.DATA].length) { "Invalid compact Core source position" }
        val files = payload(Segment.FILENAMES, source(Segment.FILENAMES, offset)) { cursor ->
            List(cursor.count()) {
                mapOf("id" to common(cursor), "path" to common(cursor),
                    "content" to optional(cursor) { common(cursor) })
            }
        }
        val notes = payload(Segment.LINE_COLUMNS, source(Segment.LINE_COLUMNS, offset)) { cursor ->
            val primary = cursor.unsigned()
            val values = List(cursor.count()) {
                val note = linkedMapOf<String, Any?>("id" to text(cursor), "label" to optional(cursor) { text(cursor) })
                for (field in listOf("startLine", "startColumn", "endLine", "endColumn")) note[field] = cursor.unsigned()
                for (field in listOf("charIndex", "charLength")) note[field] = optional(cursor) { cursor.unsigned() }
                note
            }
            require(primary < values.size) { "Invalid compact Core primary source note" }
            Notes(primary.toInt(), values)
        }
        if (files == null && notes == null) return null
        require(files != null && notes != null && files.size == notes.values.size) { "Mismatched compact Core source tables" }
        val distinctFiles = linkedMapOf<String, Map<String, Any?>>()
        val ids = HashSet<String>()
        val spans = notes.values.mapIndexed { index, note ->
            require(ids.add(note["id"] as String)) { "Duplicate compact Core source note" }
            val file = files[index]
            val id = file["id"] as String
            val previous = distinctFiles.putIfAbsent(id, file)
            require(previous == null || previous == file) { "Inconsistent compact Core source file" }
            note + ("file" to id)
        }
        // Reuse the JSON route's exact coordinate, unavailable-text and tab rules.
        val sources = CoreSources(mapOf("sourceFiles" to distinctFiles.values.toList(), "sourceSpans" to spans))
        return sources.binding(mapOf("source" to spans[notes.primary]["id"], "sourceNotes" to spans.map { it["id"] }))
    }

    fun name(bindingOffset: Long, slot: Long): String? {
        require(bindingOffset >= 0 && bindingOffset < file.header()[Segment.DATA].length && slot >= 0) {
            "Invalid compact Core display-name key"
        }
        return lookupName(bindingOffset, slot)
    }

    fun constructorName(index: Int): String? {
        require(index >= 0) { "Invalid compact Core constructor display index" }
        return lookupName(-1L, index.toLong() + 1)
    }

    private fun lookupName(bindingOffset: Long, slot: Long): String? {
        val directory = directory(Segment.NAMES, 32) ?: return null
        var low = 0L
        var high = directory.count
        while (low < high) {
            val mid = low + (high - low) / 2
            val key = file.debugAt(Segment.NAMES, directory.start + mid * 32, 16) { it.fixedBits() to it.offset() }
            val comparison = java.lang.Long.compareUnsigned(key.first, bindingOffset).takeUnless { it == 0 } ?: key.second.compareTo(slot)
            when {
                comparison < 0 -> low = mid + 1
                comparison > 0 -> high = mid
                else -> return file.debugAt(Segment.NAMES, directory.start + mid * 32 + 16, 16) { cursor ->
                    val start = cursor.offset(); val length = cursor.offset()
                    require(start <= directory.start && length <= directory.start - start && length <= Int.MAX_VALUE) {
                        "Invalid compact Core display-name span"
                    }
                    file.debugAt(Segment.NAMES, start, length) { it.text(length.toInt()) }
                }
            }
        }
        return null
    }
}
