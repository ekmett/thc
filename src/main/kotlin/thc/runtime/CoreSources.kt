// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.source.Source
import com.oracle.truffle.api.source.SourceSection

/** Original GHC coordinates retain their exclusive end, even when source text is unavailable. */
internal data class CoreSourceNote(val id: String, val section: SourceSection, val label: String?,
    val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

/** A node has one debugger location and all source-note provenance from collapsed ticks. */
internal data class CoreSourceLocation(val section: SourceSection, val notes: List<CoreSourceNote>)

/** Parsing-only metadata. Constructing locations never reads files or adds executable nodes. */
internal class CoreSources(moduleData: Map<String, Any?>) {
    val enabled = moduleData["sourceNotesEnabled"] != false
    private val notes: Map<String, CoreSourceNote>
    val spanCount: Int get() = notes.size

    init {
        if (!enabled) notes = emptyMap()
        else {
            val files = (moduleData["sourceFiles"] as? List<Map<String, Any?>> ?: emptyList()).associate { file ->
                val id = file["id"] as? String ?: throw RuntimeFault("Source file lacks an id")
                val path = file["path"] as? String ?: throw RuntimeFault("Source file lacks a path")
                val content = file["content"] as? String
                val locationOnly = Source.newBuilder("thc", "", path).content(Source.CONTENT_NONE).build()
                val source = if (content == null) locationOnly else Source.newBuilder("thc", content, path).build()
                id to (source to locationOnly)
            }
            notes = (moduleData["sourceSpans"] as? List<Map<String, Any?>> ?: emptyList()).associate { span ->
                val id = span["id"] as? String ?: throw RuntimeFault("Source span lacks an id")
                val file = files[span["file"] as? String] ?: throw RuntimeFault("Source span references an unknown file")
                val source = file.first
                fun coordinate(name: String): Int {
                    val number = span[name] as? Number ?: throw RuntimeFault("Source span lacks $name")
                    val value = number.toInt()
                    if (value < 1 || value.toDouble() != number.toDouble()) throw RuntimeFault("Invalid source span $name")
                    return value
                }
                val startLine = coordinate("startLine"); val startColumn = coordinate("startColumn")
                val endLine = coordinate("endLine"); val endColumn = coordinate("endColumn")
                if (endLine < startLine || endLine == startLine && endColumn < startColumn)
                    throw RuntimeFault("Reversed source span")
                fun offset(name: String): Int? {
                    val raw = span[name] ?: return null
                    val number = raw as? Number ?: throw RuntimeFault("Invalid source span $name")
                    val value = number.toInt()
                    if (value < 0 || value.toDouble() != number.toDouble()) throw RuntimeFault("Invalid source span $name")
                    return value
                }
                val index = offset("charIndex")
                val length = offset("charLength")
                val section = if (source.hasCharacters() && index != null && length != null) {
                    if (index < 0 || length < 0 || index.toLong() + length > source.length)
                        throw RuntimeFault("Source span character range is out of bounds")
                    source.createSection(index, length)
                } else {
                    // GHC tab-expanded/remapped columns are not character offsets.
                    // Keep the exact exclusive range in CoreSourceNote. When its end
                    // is the next line's column 1, only the start point has a known
                    // inclusive column without reading the unavailable preceding line.
                    if (endColumn == 1 && endLine > startLine)
                        file.second.createSection(startLine, startColumn, startLine, startColumn)
                    else file.second.createSection(startLine, startColumn, endLine,
                        if (endLine == startLine && endColumn == startColumn) startColumn else endColumn - 1)
                }
                id to CoreSourceNote(id, section, span["label"] as? String, startLine, startColumn, endLine, endColumn)
            }
        }
    }

    fun expression(expr: List<Any?>, inherited: CoreSourceLocation? = null): CoreSourceLocation? =
        location(CoreRepresentations.metadata(expr), inherited)

    fun binding(binding: Map<String, Any?>, inherited: CoreSourceLocation? = null): CoreSourceLocation? =
        location(binding, inherited)

    private fun location(metadata: Map<String, Any?>?, inherited: CoreSourceLocation?): CoreSourceLocation? {
        if (!enabled) return null
        val primary = metadata?.get("source") as? String
        val ids = metadata?.get("sourceNotes") as? List<String> ?: emptyList()
        if (primary == null && ids.isEmpty()) return inherited
        val selected = (primary ?: ids.lastOrNull())?.let { notes[it] ?: throw RuntimeFault("Unknown source span $it") }
        val provenance = (inherited?.notes.orEmpty() + ids.map { notes[it] ?: throw RuntimeFault("Unknown source note $it") } +
            if (selected != null && selected.id !in ids) listOf(selected) else emptyList()).distinctBy { it.id }
        return CoreSourceLocation(selected?.section ?: inherited?.section ?: return null, provenance)
    }
}
