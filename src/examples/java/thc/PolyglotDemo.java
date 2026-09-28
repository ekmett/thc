// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotAccess;
import thc.runtime.BytecodeProgram;
import thc.runtime.Calls;
import thc.runtime.CoreRepresentations;
import thc.runtime.ExecutableProgram;
import thc.runtime.IoMainRoot;
import thc.runtime.Program;

/** Runs actual exported IO, including its result check, with an optional language. */
@SuppressWarnings("unchecked")
public final class PolyglotDemo {
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static void main(String[] arguments) throws Exception {
        File directory = new File(arguments.length > 0 ? arguments[0] : "build/polyglot");
        String entry = arguments.length > 1 ? arguments[1] : "main:THC.PolyglotDemo.main";
        List<String> moduleNames = arguments.length > 2 ? Arrays.asList(arguments).subList(2, arguments.length)
            : List.of("THC.Polyglot.json", "THC.PolyglotDemo.json", "THC.InterfaceClosure.json");
        for (String stage : List.of("pre-core", "post-core")) for (String backend : List.of("ast", "bytecode")) {
            List<Map<String, Object>> modules = new ArrayList<>();
            for (String name : moduleNames) {
                File file = new File(new File(directory, stage), name);
                if (!file.isFile()) throw new IllegalArgumentException("Missing " + file + "; export the demo first: scripts/polyglot-demo.sh");
                modules.add((Map<String, Object>) Json.INSTANCE.parse(Files.readString(file.toPath())));
            }
            var output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder("thc", "js")
                    .allowExperimentalOptions(true)
                    .allowPolyglotAccess(PolyglotAccess.ALL)
                    .option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false")
                    .option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
                    .option("engine.SingleTierCompilationThreshold", "10000000")
                    .option("engine.CompilationFailureAction", "Throw")
                    .out(output).build()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(CoreModules.INSTANCE.merge(modules), entry, false));
                    linked.put("instrument", true);
                    var bindings = (List<Map<String, Object>>) linked.get("bindings");
                    Map<String, Object> selectedBinding = null;
                    for (var binding : bindings) if (entry.equals(binding.get("id"))) {
                        if (selectedBinding != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
                        selectedBinding = binding;
                    }
                    if (selectedBinding == null) throw new NoSuchElementException("Collection contains no element matching the predicate.");
                    var result = CoreRepresentations.ioUnitMainResult(selectedBinding, bindings);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, false, false) : new BytecodeProgram(language, linked, false);
                    var target = new IoMainRoot(language, result).getCallTarget();
                    var action = program.entryValue(entry);
                    for (int index = 0; index < 3; index++) run(output, target, action, program);
                    var original = program.entryTarget(entry);
                    List<RootCallTarget> active = NodeUtil.findAllNodeInstances(target.getRootNode(), DirectCallNode.class).stream()
                        .filter(node -> node.getCallTarget() == original).map(node -> (RootCallTarget) node.getCurrentCallTarget()).toList();
                    if (active.isEmpty()) active = List.of(original);
                    for (var selected : active.stream().distinct().toList()) compile(selected);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    run(output, target, action, program);
                    check(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "The demo did not enter compiled Haskell code");
                    System.out.println(stage + " / " + backend + ": Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)");
                } finally { context.leave(); }
            }
        }
    }

    private static void run(ByteArrayOutputStream output, RootCallTarget target, Object action, ExecutableProgram program) {
        output.reset();
        Calls.target(target, new Object[] {action});
        check(output.toString(StandardCharsets.UTF_8).equals("THC polyglot result: 42\n"), "Unexpected guest output: " + output);
        check(((Number) program.diagnostics().get("unsupportedTraps")).longValue() == 0, "Unsupported guest trap");
    }

    private static void compile(RootCallTarget target) throws ReflectiveOperationException {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        check(type.isInstance(target), "The demo needs the optimizing Truffle runtime");
        // GraalJS may load a node subclass during its first compilation. Retry
        // explicit compilation after that hierarchy bailout, never guest effects.
        for (int attempt = 0; attempt < 3; attempt++) {
            type.getMethod("compile", boolean.class).invoke(target, true);
            if (Boolean.TRUE.equals(type.getMethod("isValidLastTier").invoke(target))) return;
        }
        throw new IllegalStateException("No compiled demo target was installed: " + target.getRootNode().getName());
    }
}
