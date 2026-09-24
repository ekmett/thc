// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.bytecode.BytecodeLocation
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.source.SourceSection
import java.util.Collections

/** Detached SourceSection coordinates; end columns are inclusive, unlike Core source notes. */
@ConsistentCopyVisibility
data class ManagedStackSource internal constructor(
    val name: String, val path: String?, val uri: String, val available: Boolean,
    val startLine: Int?, val startColumn: Int?, val endLine: Int?, val endColumn: Int?
)

/** Original exported Core coordinates, retaining their exclusive end and optional label. */
@ConsistentCopyVisibility
data class ManagedStackNote internal constructor(
    val id: String, val label: String?, val source: ManagedStackSource,
    val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int
)

enum class ManagedStackLocationKind { CURRENT_NODE, CALL_NODE, BYTECODE, ROOT, UNAVAILABLE }

class ManagedStackFrame internal constructor(
    val functionName: String,
    val location: ManagedStackSource?,
    val locationKind: ManagedStackLocationKind,
    sections: List<ManagedStackSource>, notes: List<ManagedStackNote>
) {
    /** Bytecode sections are ordered most to least concrete; AST notes retain exporter order. */
    val sections: List<ManagedStackSource> = immutableStackList(sections)
    val notes: List<ManagedStackNote> = immutableStackList(notes)
}

/**
 * A local, immutable snapshot of live THC GuestRoots, newest first. This is not a native StgStack
 * layout or an IPE table. No call targets, nodes, frames, arguments or Source objects escape capture.
 */
class ManagedStackSnapshot private constructor(frames: List<ManagedStackFrame>) {
    val frames: List<ManagedStackFrame> = immutableStackList(frames)

    /** Source-only rendering for an explicit library adapter, not native frame decoding. */
    fun renderLines(): List<String> = immutableStackList(frames.map { frame ->
        val location = frame.location
        val position = if (location == null || !location.available) "<source unavailable>" else
            (location.path ?: location.name) + (location.startLine?.let { ":$it" } ?: "") +
                (location.startColumn?.let { ":$it" } ?: "")
        "${frame.functionName} ($position)"
    })

    companion object {
        /**
         * [current] must belong to the newest live GuestRoot, not a caller or an inactive tree.
         * Bytecode operations must also pass their actual execution frame, used only during capture.
         */
        @JvmStatic @JvmOverloads
        fun capture(current: Node, currentBytecodeFrame: Frame? = null): ManagedStackSnapshot {
            val currentRoot = current.rootNode as? GuestRoot
                ?: throw RuntimeFault("Stack capture requires an adopted current guest node")
            // Resolve the actual operation frame before crossing the boundary; Frames must not
            // escape compiled code into a TruffleBoundary, even for temporary read-only access.
            val currentBytecodeLocation = if (currentRoot is BytecodeRoot) {
                val executionFrame = currentBytecodeFrame
                    ?: throw RuntimeFault("Top bytecode stack capture requires its current execution frame")
                val bytecodeNode = BytecodeNode.get(current)
                    ?: throw RuntimeFault("Top bytecode stack capture requires an adopted operation node")
                bytecodeNode.getBytecodeLocation(executionFrame, current)
            } else null
            return captureFrames(current, currentRoot, currentBytecodeLocation)
        }

        @TruffleBoundary
        private fun captureFrames(current: Node, currentRoot: GuestRoot,
                                  currentBytecodeLocation: BytecodeLocation?): ManagedStackSnapshot {
            val captured = ArrayList<ManagedStackFrame>()
            Truffle.getRuntime().iterateFrames<Any?> { frame ->
                val root = (frame.callTarget as? RootCallTarget)?.rootNode as? GuestRoot
                if (root != null) {
                    val top = captured.isEmpty()
                    if (top && root !== currentRoot)
                        throw RuntimeFault("Stack capture node does not belong to the current guest frame")
                    // A FrameInstance's callNode belongs to THIS frame: it invokes the next newer
                    // target, not this target's caller. The top frame needs the explicit current node.
                    val node = if (top) current else frame.callNode
                    val bytecode = if (root is BytecodeRoot) {
                        if (top) currentBytecodeLocation
                        else BytecodeLocation.get(frame)
                    } else null
                    val bytecodeSections = bytecode?.ensureSourceInformation()?.sourceLocations?.toList().orEmpty()
                    val core = coreLocation(node)
                    val localSection = core?.section ?: nodeSection(node, root)
                    val section = bytecodeSections.firstOrNull() ?: localSection ?: root.sourceSection
                    val kind = when {
                        bytecodeSections.isNotEmpty() -> ManagedStackLocationKind.BYTECODE
                        section == null -> ManagedStackLocationKind.UNAVAILABLE
                        localSection != null -> if (top) ManagedStackLocationKind.CURRENT_NODE else ManagedStackLocationKind.CALL_NODE
                        else -> ManagedStackLocationKind.ROOT
                    }
                    val notes = core?.notes ?: (root as? FunctionRoot)?.getCoreSourceNotes().orEmpty()
                    val sections = if (bytecodeSections.isNotEmpty()) bytecodeSections
                        else listOfNotNull(section)
                    captured += ManagedStackFrame(root.name ?: "<unnamed guest>", section?.let(::copySection), kind,
                        sections.map(::copySection), notes.map { note ->
                            ManagedStackNote(note.id, note.label, copySection(note.section),
                                note.startLine, note.startColumn, note.endLine, note.endColumn)
                        })
                }
                null
            }
            if (captured.isEmpty()) throw RuntimeFault("Stack capture found no live guest frames")
            return ManagedStackSnapshot(captured)
        }

        private fun coreLocation(node: Node?): CoreSourceLocation? {
            var cursor = node
            while (cursor != null) {
                if (cursor is Expr && cursor.coreSourceLocation != null) return cursor.coreSourceLocation
                cursor = cursor.parent
            }
            return null
        }

        private fun nodeSection(node: Node?, root: GuestRoot): SourceSection? {
            var cursor = node
            while (cursor != null && cursor !== root) {
                // Expr.getSourceSection inherits its parent's encapsulating section, possibly the
                // root's. Inspect its own metadata so that root fallback keeps ROOT provenance.
                val section = if (cursor is Expr) cursor.coreSourceLocation?.section else cursor.sourceSection
                if (section != null) return section
                cursor = cursor.parent
            }
            return null
        }

        private fun copySection(section: SourceSection) = ManagedStackSource(
            section.source.name, section.source.path, section.source.uri.toString(), section.isAvailable,
            if (section.hasLines()) section.startLine else null,
            if (section.hasColumns()) section.startColumn else null,
            if (section.hasLines()) section.endLine else null,
            if (section.hasColumns()) section.endColumn else null)
    }
}

private fun <T> immutableStackList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
