// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.source.SourceSection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.BytecodeRoot;
import thc.runtime.CoreSourceLocation;
import thc.runtime.CoreSources;
import thc.runtime.Expr;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine producer bytes and unchanged original JSON, not model debug tables. */
@SuppressWarnings("unchecked")
public class CoreCompactDebugInteropTest {
    private final Path manifest = Path.of(System.getProperty("thc.compactInteropDebugManifest"));
    private CoreUnitDirectory directory(Path path) throws Exception {
        return Objects.requireNonNull(CoreUnitDirectory.read((Map<?, ?>) Json.parse(Files.readString(path))));
    }
    private List<Object> section(SourceSection value) {
        if (value == null) return null;
        return Arrays.asList(value.getSource().getName(), value.isAvailable(), value.hasLines() ? value.getStartLine() : null,
            value.hasColumns() ? value.getStartColumn() : null, value.hasLines() ? value.getEndLine() : null,
            value.hasColumns() ? value.getEndColumn() : null, value.hasCharIndex() ? value.getCharIndex() : null,
            value.hasCharIndex() ? value.getCharLength() : null,
            value.getSource().hasCharacters() && value.isAvailable() ? value.getCharacters().toString() : null);
    }
    private List<List<Object>> notes(CoreSourceLocation value) {
        var notes = new ArrayList<List<Object>>();
        if (value != null) for (var note : value.getNotes())
            notes.add(Arrays.asList(note.getId(), note.getLabel(), note.getStartLine(), note.getStartColumn(), note.getEndLine(), note.getEndColumn(), section(note.getSection())));
        return notes;
    }
    private void location(CoreSourceLocation expected, CoreSourceLocation actual) {
        assertEquals(section(expected == null ? null : expected.getSection()), section(actual == null ? null : actual.getSection()));
        assertEquals(notes(expected), notes(actual));
    }
    private CoreUnitDirectory.ModuleRecord sourceNotes(CoreUnitDirectory directory) {
        CoreUnitDirectory.ModuleRecord found = null;
        for (var module : directory.getModules()) if (module.getName().equals("SourceNotes")) {
            assertNull(found); found = module;
        }
        return Objects.requireNonNull(found);
    }
    private final class Compare {
        private final CoreSources source, decoded = new CoreSources(Map.of());
        Compare(CoreSources source) { this.source = source; }
        CoreSourceLocation compareBinding(Map<String, Object> old, Map<String, Object> value, CoreSourceLocation inherited) {
            var current = source.binding(old, inherited); location(current, decoded.binding(value, null)); return current;
        }
        void binding(Map<String, Object> old, Map<String, Object> value, CoreSourceLocation inherited) {
            var current = compareBinding(old, value, inherited);
            var origin = (CoreCompactRecords.Origin) value.get("compactOrigin");
            String id = (String) value.get("id");
            long slot = id.startsWith("\u0000compact-local:") ? Long.parseLong(id.substring(id.indexOf(':') + 1)) + 1 : 0;
            assertEquals(old.get("name"), Objects.requireNonNull(origin.getDebug()).name(origin.getBindingOffset(), slot));
            expression((List<Object>) old.get("expr"), (List<Object>) value.get("expr"), current);
        }
        void expression(List<Object> old, List<Object> value, CoreSourceLocation inherited) {
            assertEquals(old.getFirst(), value.getFirst());
            var current = source.expression(old, inherited); var actual = decoded.expression(value, null);
            assertNotNull(actual == null ? null : actual.getCompactOrigin()); location(current, actual);
            switch ((String) old.getFirst()) {
                case "lam" -> {
                    var oldParameters = (List<Map<String, Object>>) old.get(1); var parameters = (List<Map<String, Object>>) value.get(1);
                    for (int i = 0; i < Math.min(oldParameters.size(), parameters.size()); i++) {
                        var a = oldParameters.get(i); var b = parameters.get(i); compareBinding(a, b, current);
                        var origin = (CoreCompactRecords.Origin) b.get("compactOrigin");
                        String id = (String) b.get("id"); long slot = Long.parseLong(id.substring(id.indexOf(':') + 1)) + 1;
                        assertEquals(a.get("name"), Objects.requireNonNull(origin.getDebug()).name(origin.getBindingOffset(), slot));
                    }
                    expression((List<Object>) old.get(2), (List<Object>) value.get(2), current);
                }
                case "app" -> {
                    expression((List<Object>) old.get(1), (List<Object>) value.get(1), current);
                    var oldArguments = (List<List<Object>>) old.get(2); var arguments = (List<List<Object>>) value.get(2);
                    for (int i = 0; i < Math.min(oldArguments.size(), arguments.size()); i++) expression(oldArguments.get(i), arguments.get(i), current);
                }
                case "let" -> {
                    var oldBindings = (List<Map<String, Object>>) old.get(2); var bindings = (List<Map<String, Object>>) value.get(2);
                    for (int i = 0; i < Math.min(oldBindings.size(), bindings.size()); i++) binding(oldBindings.get(i), bindings.get(i), current);
                    expression((List<Object>) old.get(3), (List<Object>) value.get(3), current);
                }
                case "case" -> {
                    expression((List<Object>) old.get(1), (List<Object>) value.get(1), current);
                    var oldArms = (List<List<Object>>) old.get(3); var arms = (List<List<Object>>) value.get(3);
                    for (int i = 0; i < Math.min(oldArms.size(), arms.size()); i++) expression((List<Object>) oldArms.get(i).get(3), (List<Object>) arms.get(i).get(3), current);
                }
            }
        }
    }
    @Test public void selectedGenuineOccurrencesKeepOriginalOrderedNotesAndDisplayNames() throws Exception {
        var compact = directory(manifest); var reference = directory(Path.of(System.getProperty("thc.compactInteropReference")));
        var artifact = sourceNotes(compact).getArtifact();
        try (var originals = reference.open(false); var file = new CoreCompactFile(artifact.getPath(), artifact.getSha256())) {
            var compare = new Compare(new CoreSources(originals.metadata(sourceNotes(reference))));
            var records = new CoreCompactRecords(file, artifact.getSha256());
            for (String name : List.of("unicode", "tabbed", "missing")) {
                String id = "main:SourceNotes." + name;
                var value = records.binding(Objects.requireNonNull(file.lookup(id)));
                if (name.equals("unicode")) assertEquals(0L, file.getCounters().statistics().debugBytesRead());
                compare.binding(Objects.requireNonNull(originals.binding(id)), value, null);
            }
            assertTrue(file.getCounters().statistics().debugBytesRead() > 0);
            assertEquals(0L, file.getCounters().statistics().hashBytesRead());
        }
    }
    private List<List<Object>> instructions(BytecodeRoot root) {
        var result = new ArrayList<List<Object>>();
        for (var instruction : root.getBytecodeNode().getInstructions()) {
            var arguments = new ArrayList<String>();
            for (var argument : instruction.getArguments()) arguments.add(argument.toString());
            result.add(List.of(instruction.getName(), arguments));
        }
        return result;
    }
    private long reads(CoreUnitProgram program) { return ((Number) program.diagnostics().get("coreCompactDebugBytesRead")).longValue(); }
    @Test public void publicDebugRequestStaysColdUntilExplicitSourceDemandAndRetainsOrigins() {
        String[] names = {"unicode", "tabbed", "missing"};
        for (String backend : List.of("ast", "bytecode")) for (int i = 0; i < names.length; i++)
            try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
                String name = names[i], id = "main:SourceNotes." + name; long delta = i + 1L;
                var entry = context.eval("thc", CoreModules.request(List.of("@" + manifest), id, true, false, backend, true, false, null, false));
                context.enter();
                try {
                    var programs = Language.currentState().getCoreUnitPrograms(); assertEquals(1, programs.size()); var program = programs.getFirst();
                    var target = program.entryTarget(id);
                    assertEquals(0L, reads(program), "Default construction must not resolve a debug argument");
                    assertEquals(40 + delta, entry.execute(40).asLong());
                    assertEquals(0L, reads(program), "Ordinary execution must leave debug tables cold");
                    var root = target.getRootNode();
                    if (root instanceof BytecodeRoot bytecode) {
                        var code = instructions(bytecode);
                        assertFalse(bytecode.getBytecodeNode().hasSourceInformation());
                        bytecode.getBytecodeNode().ensureSourceInformation();
                        assertTrue(bytecode.getBytecodeNode().hasSourceInformation()); assertEquals(code, instructions(bytecode));
                    } else {
                        Expr node = null;
                        for (var candidate : NodeUtil.findAllNodeInstances(root, Expr.class))
                            if (candidate.getCoreSourceLocation() != null && candidate.getCoreSourceLocation().getCompactOrigin() != null) { node = candidate; break; }
                        Objects.requireNonNull(node); var copied = NodeUtil.cloneNode(node);
                        assertSame(node.getCoreSourceLocation().getCompactOrigin(), copied.getCoreSourceLocation().getCompactOrigin());
                        assertEquals(section(node.getSourceSection()), section(copied.getSourceSection()));
                    }
                    assertNotNull(root.getSourceSection()); assertTrue(reads(program) > 0); long after = reads(program);
                    assertSame(target, program.entryTarget(id)); assertEquals(77 + delta, entry.execute(77).asLong());
                    if (root instanceof BytecodeRoot bytecode) bytecode.getBytecodeNode().ensureSourceInformation();
                    assertEquals(after, reads(program), "Resolved debug data and replay must be reused");
                } finally { context.leave(); }
            }
    }
}
