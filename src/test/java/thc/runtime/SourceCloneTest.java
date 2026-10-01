// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

/** Target splitting must retain the original source provenance on the cloned executable tree. */
class SourceCloneTest {
    private final String text = "entry x = x + 1\n";
    private final int innerStart = text.indexOf("x + 1");
    private Map<String, Object> metadata(String id) { return Map.of("source", id, "sourceNotes", new ArrayList<>(new LinkedHashSet<>(List.of("outer", id)))); }
    private Map<String, Object> span(String id, int start, int length) {
        return Map.of("id", id, "file", "fixture", "startLine", 1, "startColumn", start + 1,
            "endLine", 1, "endColumn", start + length + 1, "charIndex", start, "charLength", length,
            "label", id.equals("outer") ? "entry" : "addition");
    }
    private Map<String, Object> module() {
        var body = List.of("app", List.of("prim", "+#"),
            List.of(List.of("var", "input", metadata("inner")), List.of("lit", "int", "1", metadata("inner"))),
            List.of(false, false), false, false, metadata("inner"));
        var function = List.of("lam", List.of(Map.of("id", "input", "name", "input", "lifted", false)), body, metadata("outer"));
        return Map.of("sourceNotesEnabled", true, "instrument", true,
            "sourceFiles", List.of(Map.of("id", "fixture", "path", "SourceClone.hs", "content", text)),
            "sourceSpans", List.of(span("outer", 0, text.length() - 1), span("inner", innerStart, 5)),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true, "source", "outer", "expr", function)));
    }
    private static final class CloneCaller extends RootNode {
        @Child private DirectCallNode call;
        CloneCaller(RootCallTarget target) { super(null); call = DirectCallNode.create(target); }
        @Override public Object execute(VirtualFrame frame) { return Calls.direct(call, frame.getArguments()); }
        RootCallTarget cloneTarget() {
            getCallTarget(); // Adopt the direct call before requesting an actual Truffle split.
            assertTrue(call.isCallTargetCloningAllowed());
            assertTrue(call.cloneCallTarget(), "The test must exercise a real cloned target");
            return (RootCallTarget) call.getClonedCallTarget();
        }
    }
    private record Position(int index, List<SourceSection> locations) {}
    private List<Position> locations(BytecodeRoot root) {
        var result = new ArrayList<Position>();
        for (var instruction : root.getBytecodeNode().getInstructions()) {
            var source = root.getBytecodeNode().getSourceLocations(instruction.getBytecodeIndex());
            result.add(new Position(instruction.getBytecodeIndex(), source == null ? List.of() : Arrays.asList(source)));
        }
        return result;
    }
    private void assertLocations(RootNode original, RootNode cloned) {
        assertNotNull(original.getSourceSection());
        assertEquals(original.getSourceSection(), cloned.getSourceSection());
        assertEquals("x + 1", cloned.getSourceSection().getCharacters().toString());
        if (original instanceof FunctionRoot originalFunction && cloned instanceof FunctionRoot clonedFunction) {
            var ids = new ArrayList<String>(); for (var note : clonedFunction.getCoreSourceNotes()) ids.add(note.getId());
            assertEquals(List.of("outer", "inner"), ids);
            assertEquals(originalFunction.getCoreSourceNotes(), clonedFunction.getCoreSourceNotes());
            var before = NodeUtil.findAllNodeInstances(original, Expr.class);
            var after = NodeUtil.findAllNodeInstances(cloned, Expr.class);
            assertEquals(before.size(), after.size()); assertTrue(!after.isEmpty());
            for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
                var a = before.get(i); var b = after.get(i);
                assertNotSame(a, b, "The child nodes must belong to the cloned tree");
                assertEquals(a.getClass(), b.getClass()); assertNotNull(b.getSourceSection());
                assertEquals(a.getSourceSection(), b.getSourceSection());
                assertEquals(a.getCoreSourceLocation() == null ? null : a.getCoreSourceLocation().getNotes(),
                    b.getCoreSourceLocation() == null ? null : b.getCoreSourceLocation().getNotes());
            }
        } else {
            assertTrue(original instanceof BytecodeRoot && cloned instanceof BytecodeRoot);
            var originalPositions = locations((BytecodeRoot) original); var clonedPositions = locations((BytecodeRoot) cloned);
            assertTrue(((BytecodeRoot) cloned).getBytecodeNode().hasSourceInformation());
            boolean any = false; for (var position : clonedPositions) if (!position.locations.isEmpty()) { any = true; break; }
            assertTrue(any);
            assertEquals(originalPositions, clonedPositions, "Every cloned bytecode location must retain its source stack");
        }
    }
    private void check(long input, RootCallTarget original, CloneCaller caller, String backend) {
        assertEquals(input + 1, Calls.target(original, new Object[]{0L, input}), backend + " original");
        assertEquals(input + 1, Calls.target(caller.getCallTarget(), new Object[]{0L, input}), backend + " clone");
    }
    @Test void actualClonedTargetsRetainRootAndChildLocationsBeforeAndAfterCompilation() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                var original = program.entryTarget("entry"); var caller = new CloneCaller(original); var cloned = caller.cloneTarget();
                assertNotSame(original, cloned); assertNotSame(original.getRootNode(), cloned.getRootNode());
                assertTrue(((GuestRoot) cloned.getRootNode()).isSelf(original));
                assertLocations(original.getRootNode(), cloned.getRootNode());
                for (int repeat = 0; repeat < 20; repeat++) check(repeat, original, caller, backend);
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                type.getMethod("compile", boolean.class).invoke(cloned, true);
                type.getMethod("waitForCompilation").invoke(cloned);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(cloned));
                for (long input : List.of(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L)) check(input, original, caller, backend);
                assertLocations(original.getRootNode(), cloned.getRootNode());
                assertTrue((Long) program.diagnostics().get("compiledEntries") > 0);
            } finally { context.leave(); }
        }
    }
}
