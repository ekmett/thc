// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import thc.Language;

/** Detached inspection data, newest first, not an executable continuation or native stack.
 * Frame metadata retains no targets, nodes, frames, arguments or Source objects.
 * Explicit annotation payloads are retained separately and remain lazy. */
public final class ManagedStackSnapshot {
    private final List<ManagedStackFrame> frames;
    private final Object ownerToken;
    private final List<Object> annotations;
    private ManagedStackSnapshot(List<ManagedStackFrame> frames, Object ownerToken, List<Object> annotations) {
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames)); this.ownerToken = ownerToken;
        this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations));
    }
    public List<ManagedStackFrame> getFrames() { return frames; }
    public Object getOwnerToken() { return ownerToken; }
    public List<Object> getAnnotations() { return annotations; }
    public List<String> renderLines() {
        List<String> lines = new ArrayList<>(frames.size());
        for (ManagedStackFrame frame : frames) {
            ManagedStackSource location = frame.getLocation();
            String position = "<source unavailable>";
            if (location != null && location.available()) {
                position = location.path() == null ? location.name() : location.path();
                if (location.startLine() != null) position += ":" + location.startLine();
                if (location.startColumn() != null) position += ":" + location.startColumn();
            }
            lines.add(frame.getFunctionName() + " (" + position + ")");
        }
        return Collections.unmodifiableList(lines);
    }

    public static ManagedStackSnapshot capture(Node current) { return capture(current, null); }
    /** The node belongs to the newest live guest root; bytecode supplies its current frame. */
    public static ManagedStackSnapshot capture(Node current, Frame currentBytecodeFrame) {
        if (!(current.getRootNode() instanceof GuestRoot currentRoot))
            throw new RuntimeFault("Stack capture requires an adopted current guest node");
        // Frames may not cross the boundary, even temporarily for read-only inspection.
        BytecodeLocation currentLocation = null;
        if (currentRoot instanceof BytecodeRoot) {
            if (currentBytecodeFrame == null) throw new RuntimeFault("Top bytecode stack capture requires its current execution frame");
            BytecodeNode node = BytecodeNode.get(current);
            if (node == null) throw new RuntimeFault("Top bytecode stack capture requires an adopted operation node");
            currentLocation = node.getBytecodeLocation(currentBytecodeFrame, current);
        }
        return captureFrames(current, currentRoot, currentLocation);
    }
    @TruffleBoundary
    private static ManagedStackSnapshot captureFrames(Node current, GuestRoot currentRoot, BytecodeLocation currentLocation) {
        List<ManagedStackFrame> captured = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(frame -> {
            if (frame.getCallTarget() instanceof RootCallTarget target && target.getRootNode() instanceof GuestRoot root) {
                boolean top = captured.isEmpty();
                if (top && root != currentRoot) throw new RuntimeFault("Stack capture node does not belong to the current guest frame");
                // callNode belongs to THIS frame; only the top uses the explicit current node.
                Node node = top ? current : frame.getCallNode();
                BytecodeLocation bytecode = root instanceof BytecodeRoot ? (top ? currentLocation : BytecodeLocation.get(frame)) : null;
                List<SourceSection> bytecodeSections = bytecode == null ? List.of() :
                    Arrays.asList(bytecode.ensureSourceInformation().getSourceLocations());
                CoreSourceLocation core = coreLocation(node);
                SourceSection local = core == null ? nodeSection(node, root) : core.getSection();
                SourceSection section = !bytecodeSections.isEmpty() ? bytecodeSections.getFirst() :
                    local != null ? local : root.getSourceSection();
                ManagedStackLocationKind kind = !bytecodeSections.isEmpty() ? ManagedStackLocationKind.BYTECODE :
                    section == null ? ManagedStackLocationKind.UNAVAILABLE :
                    local != null ? (top ? ManagedStackLocationKind.CURRENT_NODE : ManagedStackLocationKind.CALL_NODE) : ManagedStackLocationKind.ROOT;
                List<CoreSourceNote> notes = core != null ? core.getNotes() :
                    root instanceof FunctionRoot function ? function.getCoreSourceNotes() : List.of();
                List<ManagedStackSource> sections = new ArrayList<>();
                if (!bytecodeSections.isEmpty()) for (SourceSection item : bytecodeSections) sections.add(copySection(item));
                else if (section != null) sections.add(copySection(section));
                List<ManagedStackNote> copiedNotes = new ArrayList<>(notes.size());
                for (CoreSourceNote note : notes) copiedNotes.add(new ManagedStackNote(note.getId(), note.getLabel(), copySection(note.getSection()),
                    note.getStartLine(), note.getStartColumn(), note.getEndLine(), note.getEndColumn()));
                var identity = root.getCoreIdentity();
                captured.add(new ManagedStackFrame(root.getName() == null ? "<unnamed guest>" : root.getName(),
                    section == null ? null : copySection(section), kind, sections, copiedNotes,
                    identity == null ? null : new ManagedStackFunctionIdentity(identity.bindingId(), identity.unitId(),
                        identity.moduleName(), identity.occurrence())));
            }
            return null;
        });
        if (captured.isEmpty()) throw new RuntimeFault("Stack capture found no live guest frames");
        Object owner = currentRoot.getLanguageInfo() == null ? null : Language.currentState(current).getStackSnapshots().getToken();
        List<Object> annotations = owner == null ? List.of() : StackAnnotations.current(current).values();
        return new ManagedStackSnapshot(captured, owner, annotations);
    }
    private static CoreSourceLocation coreLocation(Node node) {
        for (Node cursor = node; cursor != null; cursor = cursor.getParent())
            if (cursor instanceof Expr expression && expression.getCoreSourceLocation() != null) return expression.getCoreSourceLocation();
        return null;
    }
    private static SourceSection nodeSection(Node node, GuestRoot root) {
        for (Node cursor = node; cursor != null && cursor != root; cursor = cursor.getParent()) {
            // Expr inherits its parent's section: use only its own metadata for provenance.
            SourceSection section = cursor instanceof Expr expression ?
                expression.getCoreSourceLocation() == null ? null : expression.getCoreSourceLocation().getSection() : cursor.getSourceSection();
            if (section != null) return section;
        }
        return null;
    }
    private static ManagedStackSource copySection(SourceSection section) {
        return new ManagedStackSource(section.getSource().getName(), section.getSource().getPath(), section.getSource().getURI().toString(),
            section.isAvailable(), section.hasLines() ? section.getStartLine() : null, section.hasColumns() ? section.getStartColumn() : null,
            section.hasLines() ? section.getEndLine() : null, section.hasColumns() ? section.getEndColumn() : null);
    }
}
