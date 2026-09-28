// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Debugger attribution must preserve the actual lazy program and instruction stream. */
class BytecodeSourceNotesTest {
    private final String text = "entry x =\n  let boxed = x + 1\n  in case boxed of y -> boxed + y\n";
    private Map<String, Object> metadata(String span) { return map("source", span, "sourceNotes", list("entry", span).stream().distinct().toList()); }
    private List<Object> variable(String name) { return list("var", name, metadata("demand")); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value), metadata("rhs")); }
    private List<Object> add(List<Object> a, List<Object> b) { return list("app", list("prim", "+#"), list(a, b), list(false, false), false, false, metadata("rhs")); }
    private Map<String, Object> binder(String id, boolean lifted) { return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "coercion", false); }
    private int line(int index) { return (int) text.substring(0, index).chars().filter(c -> c == '\n').count() + 1; }
    private int column(int index) { return index - text.lastIndexOf('\n', index - 1); }
    private Map<String, Object> span(String id, String substring) {
        int start = text.indexOf(substring), end = start + substring.length();
        return map("id", id, "file", "fixture", "startLine", line(start), "startColumn", column(start), "endLine", line(end), "endColumn", column(end),
            "charIndex", start, "charLength", substring.length(), "label", id);
    }
    private Map<String, Object> module(boolean enabled) { return module(enabled, true); }
    private Map<String, Object> module(boolean enabled, boolean content) {
        var rhs = list("case", variable("x"), "ignored", list(list("default", null, list(), add(variable("x"), integer(1)))), metadata("rhs"));
        var binding = with(binder("boxed", true), "expr", rhs, "arity", 0);
        var demand = list("case", variable("boxed"), "y", list(list("default", null, list(), add(variable("boxed"), variable("y")))), metadata("demand"));
        var body = list("let", false, list(binding), demand, metadata("entry"));
        var entry = with(binder("entry", true), "arity", 1, "source", "entry", "expr", list("lam", list(binder("x", false)), body, metadata("entry")));
        return map("bindings", list(entry), "constructors", list(), "sourceNotesEnabled", enabled,
            "sourceFiles", list(map("id", "fixture", "path", "Synthetic.SourceNotes.hs", "content", content ? text : null)),
            "sourceSpans", list(span("entry", text.substring(0, text.length() - 1)), span("rhs", "x + 1"), span("demand", "case boxed of y -> boxed + y")));
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withRuntime(Action action) throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private long execute(BytecodeProgram program, long input) { return (Long) Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{input}}); }
    private List<String> instructions(BytecodeRoot root) {
        var result = new ArrayList<String>(); for (var instruction : root.getBytecodeNode().getInstructions()) result.add(instruction.getName()); return result;
    }
    private EntryValue entry(BytecodeProgram program) { return new EntryValue(program, "entry", 1); }
    @Test void notesAttachToNativeBytecodeWithoutChangingInstructionsOrLocalWriteback() throws Exception {
        withRuntime(language -> {
            var off = new BytecodeProgram(language, module(false)); var on = new BytecodeProgram(language, module(true));
            var offRoot = (BytecodeRoot) off.entryTarget("entry").getRootNode(); var onRoot = (BytecodeRoot) on.entryTarget("entry").getRootNode();
            assertEquals(instructions(offRoot), instructions(onRoot), "Source notes must emit no executable opcode");
            assertTrue(instructions(onRoot).stream().anyMatch(name -> name.startsWith("c.ForceLocal")), "Decorated local forcing must still write back");
            assertFalse(offRoot.getBytecodeNode().hasSourceInformation()); assertNull(offRoot.getSourceSection());
            assertTrue(onRoot.getBytecodeNode().hasSourceInformation()); assertEquals(text.substring(0, text.length() - 1), onRoot.getSourceSection().getCharacters().toString());
            var locations = new ArrayList<com.oracle.truffle.api.source.SourceSection>();
            for (var instruction : onRoot.getBytecodeNode().getInstructions()) {
                var found = onRoot.getBytecodeNode().getSourceLocations(instruction.getBytecodeIndex());
                if (found != null) locations.addAll(Arrays.asList(found));
            }
            assertTrue(locations.stream().anyMatch(location -> location.getCharacters().toString().equals("case boxed of y -> boxed + y")));
            assertEquals(0, off.diagnostics().get("sourceRootCount")); assertTrue((Integer) on.diagnostics().get("sourceRootCount") > 0); assertEquals(3, on.diagnostics().get("sourceSpanCount"));
            for (long input : new long[]{0L, 3_000_000_000L, Long.MAX_VALUE}) { assertEquals((input + 1) * 2, execute(off, input)); assertEquals((input + 1) * 2, execute(on, input)); }
            assertEquals(true, InteropLibrary.getUncached().invokeMember(entry(on), "compile")); assertEquals(86L, execute(on, 42L));
            assertTrue((Long) on.diagnostics().get("compiledEntries") > 0);
            onRoot.getRootNodes().update(BytecodeConfig.WITH_SOURCE); assertEquals(86L, execute(on, 42L));
            assertEquals(text.substring(0, text.length() - 1), onRoot.getSourceSection().getCharacters().toString()); assertEquals(instructions(offRoot), instructions(onRoot));
        });
    }
    private List<Object> apply(List<Object> fn, List<List<Object>> args) { return list("app", fn, args, Collections.nCopies(args.size(), false), false, false, metadata("rhs")); }
    @Test void sourceSectionsPreserveLocalJoinBackedgesAndTheirOuterContinuation() throws Exception {
        withRuntime(language -> {
            var next = apply(variable("loop"), list(apply(list("prim", "-#"), list(variable("n"), integer(1))), add(variable("acc"), integer(1))));
            var worker = list("case", variable("n"), "choice", list(list("lit", list("int", "0"), list(), variable("acc")), list("default", null, list(), next)), metadata("demand"));
            var join = with(binder("loop", true), "arity", 2, "joinValueArity", 2, "source", "rhs", "expr", list("lam", list(binder("n", false), binder("acc", false)), worker, metadata("rhs")));
            var region = list("let", true, list(join), apply(variable("loop"), list(variable("x"), integer(0))), metadata("entry"));
            var entry = with(binder("entry", true), "arity", 1, "expr", list("lam", list(binder("x", false)), add(integer(17), region), metadata("entry")));
            var off = new BytecodeProgram(language, with(module(false), "bindings", list(entry))); var on = new BytecodeProgram(language, with(module(true), "bindings", list(entry)));
            var offRoot = (BytecodeRoot) off.entryTarget("entry").getRootNode(); var onRoot = (BytecodeRoot) on.entryTarget("entry").getRootNode();
            assertEquals(instructions(offRoot), instructions(onRoot)); assertEquals(100_017L, execute(off, 100_000L)); assertEquals(100_017L, execute(on, 100_000L));
            assertEquals(true, InteropLibrary.getUncached().invokeMember(entry(on), "compile")); assertEquals(100_018L, execute(on, 100_001L));
            assertEquals(0L, on.diagnostics().get("trampolineIterations")); assertEquals(1, on.diagnostics().get("localJoinCount")); assertNotNull(onRoot.getSourceSection());
        });
    }
    @Test void bytecodeKeepsSourceLocationsWhenOriginalFileContentIsUnavailable() throws Exception {
        withRuntime(language -> {
            var program = new BytecodeProgram(language, module(true, false)); var root = (BytecodeRoot) program.entryTarget("entry").getRootNode(); var section = root.getSourceSection();
            assertNotNull(section); assertFalse(section.getSource().hasCharacters()); assertEquals("Synthetic.SourceNotes.hs", section.getSource().getName());
            assertEquals(1, section.getStartLine()); assertEquals(3, section.getEndLine()); assertTrue(root.getBytecodeNode().hasSourceInformation()); assertEquals(10L, execute(program, 4L));
        });
    }
}
