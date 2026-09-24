// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.runtime.BytecodeProgram;
import thc.runtime.ExecutableProgram;
import thc.runtime.Program;
import java.nio.file.*;
import java.util.*;

/** Native-row correctness and graph control for actual exported Core. No timing or retries. */
public final class Word32X4RuntimeGraphProbe {
    static boolean valid(RootCallTarget target) throws Exception {
        return (boolean)target.getClass().getMethod("isValidLastTier").invoke(target);
    }

    static void check(ExecutableProgram program, String entry, List<long[]> rows,
                      RootCallTarget compiled) throws Exception {
        RootCallTarget host = program.hostEntryTarget(2);
        Object closure = program.entryValue(entry);
        for (long[] row : rows) {
            if (compiled != null && !valid(compiled))
                throw new AssertionError("Entry invalid before input: " + entry);
            Object actual = host.call(closure, new Object[]{row[0], row[1]});
            if (!(actual instanceof Long) || ((Long)actual).longValue() != row[2])
                throw new AssertionError(entry + " " + Arrays.toString(row) + ": THC=" + actual);
            if (compiled != null) {
                List<DirectCallNode> calls = NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)
                    .stream().filter(call -> call.getCallTarget() == compiled).toList();
                if (calls.isEmpty() || calls.stream().anyMatch(call -> call.getCurrentCallTarget() != compiled))
                    throw new AssertionError("Changed active guest target: " + entry);
                if (!valid(compiled))
                    throw new AssertionError("Entry invalidated on " + entry + " " + Arrays.toString(row));
            }
        }
    }

    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        if (args.length != 6 || !Set.of("ast", "bytecode").contains(args[3])
                || !args[4].equals("inline") || !args[5].equals("native")
                || !Set.of("plusCase", "minusCase", "timesCase").contains(args[2]))
            throw new IllegalArgumentException("module.json oracle.tsv plusCase|minusCase|timesCase ast|bytecode inline native");
        String entry = args[2], backend = args[3];
        Map<String,Object> module = (Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(args[0])));
        Map<String,Object> linked = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(module, entry));
        linked.put("instrument", false);
        linked.put("diagnosticUnsupported", false);
        List<Map<String,Object>> bindings = (List<Map<String,Object>>)linked.get("bindings");
        List<Map<String,Object>> selected = bindings.stream()
            .filter(binding -> entry.equals(binding.get("name")) || entry.equals(binding.get("id"))).toList();
        if (selected.size() != 1 || ((Number)selected.getFirst().get("arity")).intValue() != 2)
            throw new AssertionError("Expected one arity-two scalar entry: " + entry);
        List<long[]> rows = new ArrayList<>();
        Set<String> inputs = new HashSet<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] fields = line.split("\t", -1);
            if (!fields[0].equals(entry)) continue;
            if (fields.length != 4 || !inputs.add(fields[1] + "\t" + fields[2]))
                throw new AssertionError("Malformed or duplicate oracle row: " + line);
            rows.add(new long[]{Long.parseLong(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3])});
        }
        if (rows.isEmpty()) throw new AssertionError("No native oracle rows: " + entry);
        Context.Builder builder = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000");
        try (Context context = builder.build()) {
            context.initialize("thc");
            context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast")
                    ? new Program(language, linked) : new BytecodeProgram(language, linked);
                // The existing vector controls use exactly forty interpreted corpus passes.
                for (int i = 0; i < 40; i++) check(program, entry, rows, null);
                RootCallTarget target = program.entryTarget((String)selected.getFirst().get("id"));
                if (valid(target)) throw new AssertionError("Unexpected automatic entry compilation: " + entry);
                System.out.println("GRAPH_TARGET=" + Json.INSTANCE.stringify(Map.of(
                    "entry", entry, "backend", backend, "root", target.getRootNode().getName())));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                if (!valid(target)) throw new AssertionError("No installed code: " + target);
                check(program, entry, rows, target);
                check(program, entry, rows, target);
                System.out.println("PASS entry=" + entry + " backend=" + backend
                    + " mode=inline oracleOrigin=native oracleRows=" + rows.size()
                    + " arity=2 validAfterExecution=true");
                System.out.println("diagnostics=" + Json.INSTANCE.stringify(program.diagnostics()));
            } finally {
                context.leave();
            }
        }
    }
}
