// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Language;
import thc.Json;
import thc.Main;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class CoreSourceTest {
    private final String text = "entry x = x + 1\n-- λ 😀\n";
    private final int innerStart = text.indexOf("x + 1");
    private Map<String, Object> span(String id, int start, int length) {
        return Map.of("id", id, "file", "Example.hs", "startLine", 1, "startColumn", start + 1,
            "endLine", 1, "endColumn", start + length + 1, "charIndex", start,
            "charLength", length, "label", id.equals("outer") ? "entry" : "addition");
    }
    private Map<String, Object> module(boolean enabled) {
        var metadata = Map.of("source", "inner", "sourceNotes", List.of("outer", "inner"));
        var body = List.of("app", List.of("prim", "+#"), List.of(List.of("var", "input"), List.of("lit", "int", "1")),
            List.of(false, false), false, false, metadata);
        var function = List.of("lam", List.of(Map.of("id", "input", "name", "input", "lifted", false)), body,
            Map.of("source", "outer", "sourceNotes", List.of("outer")));
        return Map.of("sourceNotesEnabled", enabled,
            "sourceFiles", List.of(Map.of("id", "Example.hs", "path", "Example.hs", "content", text)),
            "sourceSpans", List.of(span("outer", 0, text.indexOf('\n')), span("inner", innerStart, 5)),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "lifted", true,
                "source", "outer", "expr", function)));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void entered(Action action) throws Exception {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private static Object run(Program program, long input) {
        return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{input}});
    }
    @Test void sourceNotesAttachToTypedAstAndRootsWithoutAddingExecutionNodes() throws Exception {
        entered(language -> {
            var enabled = new Program(language, module(true));
            var disabled = new Program(language, module(false));
            var root = (FunctionRoot) enabled.entryTarget("entry").getRootNode();
            var noSource = (FunctionRoot) disabled.entryTarget("entry").getRootNode();
            assertEquals("x + 1", Objects.requireNonNull(root.getSourceSection()).getCharacters().toString());
            var ids = new ArrayList<String>();
            for (var note : root.getCoreSourceNotes()) ids.add(note.getId());
            assertEquals(List.of("outer", "inner"), ids);
            var nodes = NodeUtil.findAllNodeInstances(root, Expr.class);
            var plainNodes = NodeUtil.findAllNodeInstances(noSource, Expr.class);
            assertTrue(!nodes.isEmpty());
            var plainClasses = new ArrayList<Class<?>>();
            var classes = new ArrayList<Class<?>>();
            for (var node : plainNodes) plainClasses.add(node.getClass());
            for (var node : nodes) classes.add(node.getClass());
            assertEquals(plainClasses, classes, "Source metadata must not insert executable wrappers");
            boolean located = true;
            for (var node : nodes) if (node.getSourceSection() == null) { located = false; break; }
            assertTrue(located, "Synthetic typed nodes inherit the nearest enclosing span");
            assertNull(noSource.getSourceSection());
            boolean unlocated = true;
            for (var node : plainNodes) if (node.getSourceSection() != null) { unlocated = false; break; }
            assertTrue(unlocated);
            assertEquals(2, enabled.diagnostics().get("sourceSpanCount"));
            assertTrue(((Number) enabled.diagnostics().get("sourceRootCount")).intValue() > 0);
            assertEquals(0, disabled.diagnostics().get("sourceSpanCount"));
            assertEquals(0, disabled.diagnostics().get("sourceRootCount"));
            for (int i = 0; i < 20; i++) { assertEquals((long) i + 1, run(enabled, i)); assertEquals((long) i + 1, run(disabled, i)); }
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var program : List.of(enabled, disabled)) {
                type.getMethod("compile", boolean.class).invoke(program.entryTarget("entry"), true);
                type.getMethod("waitForCompilation").invoke(program.entryTarget("entry"));
                assertEquals(true, type.getMethod("isValidLastTier").invoke(program.entryTarget("entry")));
                for (long input : List.of(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L)) assertEquals(input + 1, run(program, input));
            }
        });
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> exported(Path project, String name) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(project.resolve("build/source-core/" + name + ".json")));
    }
    private static long invoke(Program program, String name, long n) {
        return (Long) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue(name), new Object[]{n}});
    }
    private record Row(String name, long increment) {}
    @Test void realGhcSourceNotesReachTypedRootsAndLocalJoinNodes() throws Exception {
        entered(language -> {
            var project = Path.of(System.getProperty("thc.projectRoot"));
            var sourceModule = exported(project, "SourceNotes");
            var compiler = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var row : List.of(new Row("unicode", 1L), new Row("tabbed", 2L), new Row("missing", 3L))) {
                var name = row.name(); long increment = row.increment();
                var sources = new Program(language, CoreModules.reachable(sourceModule, name));
                var target = sources.entryTarget(name);
                var root = (FunctionRoot) target.getRootNode();
                var section = root.getSourceSection();
                if (section == null) throw new IllegalArgumentException(name + " must retain real GHC source notes");
                assertTrue(!root.getCoreSourceNotes().isEmpty());
                boolean located = true;
                for (var node : NodeUtil.findAllNodeInstances(root, Expr.class)) if (node.getSourceSection() == null) { located = false; break; }
                assertTrue(located);
                if (name.equals("missing")) {
                    assertTrue(section.getSource().getName().endsWith("missing-original-source.hs"));
                    assertFalse(section.getSource().hasCharacters());
                    assertTrue(section.getStartLine() >= 200);
                } else {
                    assertTrue(section.getSource().hasCharacters());
                    assertTrue(section.getSource().getCharacters().toString().contains("😀"));
                }
                for (int i = 0; i < 20; i++) assertEquals((long) i + increment, invoke(sources, name, i));
                compiler.getMethod("compile", boolean.class).invoke(target, true);
                compiler.getMethod("waitForCompilation").invoke(target);
                assertEquals(true, compiler.getMethod("isValidLastTier").invoke(target));
                assertEquals(Long.MAX_VALUE + increment, invoke(sources, name, Long.MAX_VALUE));
            }
            var joins = new Program(language, CoreModules.reachable(exported(project, "RepresentationAudit"), "joinLoop"));
            var root = joins.entryTarget("joinLoop").getRootNode();
            var regions = NodeUtil.findAllNodeInstances(root, LocalJoinRegion.class);
            assertTrue(!regions.isEmpty(), "The fixture must contain an actual GHC join");
            boolean located = true;
            for (var region : regions) if (region.getSourceSection() == null) { located = false; break; }
            assertTrue(located, "Local control-flow nodes inherit GHC source locations");
            assertEquals(2_001_000L, invoke(joins, "joinLoop", 2_000));
            var offModule = new LinkedHashMap<>(CoreModules.reachable(sourceModule, "unicode"));
            offModule.put("sourceNotesEnabled", false);
            var off = new Program(language, offModule);
            assertNull(off.entryTarget("unicode").getRootNode().getSourceSection());
            assertEquals(0, off.diagnostics().get("sourceSpanCount"));
            assertEquals(0, off.diagnostics().get("sourceRootCount"));
        });
    }
    private static Map<String, Object> map(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
    private static <T> T single(List<T> values) {
        if (values.isEmpty()) throw new NoSuchElementException("List is empty.");
        if (values.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    @Test void missingSourceContentRetainsLocationsAndOriginalExclusiveProvenance() {
        var module = map("sourceFiles", List.of(map("id", "gone", "path", "Missing.hs", "content", null)),
            "sourceSpans", List.of(map("id", "span", "file", "gone", "startLine", 2, "startColumn", 4,
                "endLine", 3, "endColumn", 1, "charIndex", null, "charLength", null, "label", "missing")));
        var source = new CoreSources(module);
        var location = Objects.requireNonNull(source.expression(List.of("var", "x", Map.of("source", "span"))));
        var section = Objects.requireNonNull(location.getSection());
        assertFalse(section.getSource().hasCharacters());
        assertTrue(section.isAvailable());
        assertEquals(2, section.getStartLine());
        assertEquals(2, section.getEndLine(), "Without the preceding line's text only the exact start point is representable");
        assertEquals(4, section.getStartColumn()); assertEquals(4, section.getEndColumn());
        assertEquals(3, single(location.getNotes()).getEndLine());
        assertEquals(1, single(location.getNotes()).getEndColumn(), "Original exclusive end must not be rewritten");
        var off = new LinkedHashMap<>(module); off.put("sourceNotesEnabled", false);
        assertNull(new CoreSources(off).expression(List.of("var", "x", Map.of("source", "span"))));
    }
    private static CoreSourceLocation location(Map<String, Object> file, int startLine, int startColumn, int endLine, int endColumn) {
        var span = map("id", "span", "file", "available", "startLine", startLine, "startColumn", startColumn,
            "endLine", endLine, "endColumn", endColumn, "charIndex", null, "charLength", null);
        return Objects.requireNonNull(new CoreSources(Map.of("sourceFiles", List.of(file), "sourceSpans", List.of(span)))
            .expression(List.of("var", "x", Map.of("source", "span"))));
    }
    @Test void unverifiedCoordinatesNeverIndexAvailableSourceText() {
        Map<String, Object> file = Map.of("id", "available", "path", "Available.hs", "content", "short\n");
        for (var position : List.of(location(file, 1000, 80, 1001, 1), location(file, 1, 1, 1, 1))) {
            var section = Objects.requireNonNull(position.getSection());
            assertFalse(section.getSource().hasCharacters(), "Unverified GHC columns must use location-only source");
            assertTrue(section.isAvailable());
        }
        assertEquals(1000, Objects.requireNonNull(location(file, 1000, 80, 1001, 1).getSection()).getStartLine());
        var bad = Map.of("id", "bad", "file", "available", "startLine", 1, "startColumn", 1,
            "endLine", 1, "endColumn", 2, "charIndex", 0.5, "charLength", 1);
        assertThrows(RuntimeFault.class, () -> new CoreSources(Map.of("sourceFiles", List.of(file), "sourceSpans", List.of(bad))));
    }
    @Test void characterOffsetsUseUtf16WithoutReencodingUnicodeSource() {
        var content = "λ😀x\n";
        var source = new CoreSources(Map.of("sourceFiles", List.of(Map.of("id", "unicode", "path", "Unicode.hs", "content", content)),
            "sourceSpans", List.of(Map.of("id", "emoji", "file", "unicode", "startLine", 1, "startColumn", 2,
                "endLine", 1, "endColumn", 3, "charIndex", 1, "charLength", 2))));
        var location = Objects.requireNonNull(source.expression(List.of("var", "x", Map.of("source", "emoji"))));
        var section = Objects.requireNonNull(location.getSection());
        assertEquals("😀", section.getCharacters().toString());
        assertEquals(2, section.getCharLength());
        assertEquals(content, section.getSource().getCharacters().toString());
    }
}
