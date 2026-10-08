// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotAccess;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.runtime.Calls;
import thc.runtime.CoreRepresentations;
import thc.runtime.IoMainRoot;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Compiled-entry control kept separate from the public embedding example. */
@SuppressWarnings("unchecked")
class AcquiredPolyglotDemoTest {
    @ParameterizedTest
    @CsvSource({"javascript,JavaScriptDemo", "polyglot,PolyglotDemo"})
    void genuineNamedMainWrapperSurvivesAcquiredCompactModule(String demo, String module) throws Exception {
        Path manifest = Path.of("build", demo, "packages.json").toAbsolutePath();
        assumeTrue(Files.isRegularFile(manifest), "Run bin/acquire-polyglot-demo.sh " + demo + " first");
        var input = (Map<String,Object>) Json.INSTANCE.parse(CoreModules.request(
            List.of("@" + manifest), "main::Main.main", true, false, "ast", true, true));
        var directory = CoreModules.unitDirectory(input);
        var original = directory.owner("main::Main.main");
        assertNotNull(original);
        assertEquals(module, original.name());
        try (var source = directory.open(true);
             var reader = new CoreCompactModule(original, directory.getTargetLayout(), true)) {
            assertEquals(module, reader.metadata().get("module"));
            var expected = source.binding("main::Main.main");
            var actual = reader.binding("main::Main.main");
            // CBD keeps display names/source locations in lazy debug tables.
            for (String field : List.of("id", "type", "rep", "arity", "lifted", "info", "entryStrict", "entryStrictSource"))
                assertEquals(expected.get(field), actual.get(field), field);
            assertEquals(((List<?>) expected.get("expr")).subList(0, 2),
                ((List<?>) actual.get("expr")).subList(0, 2), "The wrapper retains its actual Core reference");
            assertNull(reader.binding("main::" + module + ".main"));
            reader.verify();
        }
    }

    @ParameterizedTest
    @CsvSource({"javascript,JavaScriptDemo,ast", "javascript,JavaScriptDemo,bytecode",
                "polyglot,PolyglotDemo,ast", "polyglot,PolyglotDemo,bytecode"})
    void acquiredDemoEntersCompiledHaskell(String demo, String module, String backend) throws Exception {
        Path manifest = Path.of("build", demo, "packages.json").toAbsolutePath();
        assumeTrue(Files.isRegularFile(manifest), "Run bin/acquire-polyglot-demo.sh " + demo + " first");
        var output = new ByteArrayOutputStream();
        try (Context context = Main.withContextProfile(Context.newBuilder("thc", "js")
                .allowNativeAccess(true).allowPolyglotAccess(PolyglotAccess.ALL).out(output),
                ContextProfile.SYNCHRONOUS_TEST)
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var owner = Language.currentState(null);
                var input = (Map<String,Object>) Json.INSTANCE.parse(CoreModules.request(
                    List.of("@" + manifest), "main::Main.main", true, false, backend, true, true));
                var directory = CoreModules.unitDirectory(input);
                var candidates = directory.getModules().stream().filter(item -> item.name().equals(module)).toList();
                assertEquals(1, candidates.size());
                assertTrue(candidates.getFirst().mainAlias(), "The genuine named-module CLI wrapper needs an alias summary");
                assertSame(candidates.getFirst(), directory.owner("main::Main.main"));
                assertNull(directory.owner("main::" + module + ".main"), "Do not invent a module-relative CLI wrapper");
                try (var sources = directory.open(true)) {
                    assertEquals("main::Main.main", sources.binding("main::Main.main").get("id"));
                }
                String entry = candidates.getFirst().unit() + ":" + module + ".main";
                Main.loadEntry(context, List.of("@" + manifest), entry, true, backend, true);
                assertEquals(1, owner.getCoreUnitPrograms().size());
                var program = owner.getCoreUnitPrograms().getFirst();
                var bindings = program.signatureBindings(entry);
                var selected = bindings.stream().filter(binding -> entry.equals(binding.get("id"))).findFirst().orElseThrow();
                var result = CoreRepresentations.ioMainResult(selected, bindings);
                var target = new IoMainRoot(language, result).getCallTarget();
                var action = program.entryValue(entry);
                for (int index = 0; index < 3; index++) run(output, target, action, program, owner);
                var original = program.entryTarget(entry);
                List<RootCallTarget> active = NodeUtil.findAllNodeInstances(target.getRootNode(), DirectCallNode.class).stream()
                    .filter(node -> node.getCallTarget() == original)
                    .map(node -> (RootCallTarget) node.getCurrentCallTarget()).toList();
                if (active.isEmpty()) active = List.of(original);
                for (var selectedTarget : active.stream().distinct().toList()) compile(selectedTarget);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                run(output, target, action, program, owner);
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                    "The demo did not enter compiled Haskell code");
            } finally { context.leave(); }
        }
    }

    private static void run(ByteArrayOutputStream output, RootCallTarget target, Object action,
                            CoreUnitProgram program, Language.State owner) {
        output.reset();
        owner.getThreads().enterCurrent(null, false, program.getAsynchronousExceptions(), null);
        try { Calls.target(target, new Object[]{action}); }
        finally { owner.getThreads().leaveCurrent(); }
        assertEquals("THC polyglot result: 42\n", output.toString(StandardCharsets.UTF_8));
        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
    }

    private static void compile(RootCallTarget target) throws ReflectiveOperationException {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        assertTrue(type.isInstance(target), "The test needs the optimizing Truffle runtime");
        // GraalJS can load a node subclass during its first compilation. Retain
        // the existing bounded hierarchy-bailout retry without replaying IO.
        for (int attempt = 0; attempt < 3; attempt++) {
            type.getMethod("compile", boolean.class).invoke(target, true);
            if (Boolean.TRUE.equals(type.getMethod("isValidLastTier").invoke(target))) return;
        }
        fail("No compiled demo target was installed: " + target.getRootNode().getName());
    }
}
