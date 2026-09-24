// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/** A Sulong ABI probe, independent of THC's still-unimplemented C foreign-call lowering. */
public final class SulongCbitsProbe {
    private static final String[] FUNCTIONS = {
        "thc_add", "thc_alloc", "thc_store", "thc_load", "thc_offset", "thc_release"
    };

    public static void main(String[] arguments) throws Exception {
        boolean compiled = switch (arguments[2]) {
            case "compiled" -> true;
            case "interpreted" -> false;
            default -> throw new IllegalArgumentException("Expected interpreted or compiled");
        };
        var expected = Files.readAllLines(Path.of(arguments[1]));
        if (expected.size() != 8) throw new AssertionError("Incomplete native oracle");
        var log = new ByteArrayOutputStream();
        var builder = Context.newBuilder("llvm").allowNativeAccess(true)
            .allowExperimentalOptions(true).logHandler(log);
        if (compiled) {
            // Compile the functions under test, not one-shot Sulong module loaders.
            builder.option("engine.CompileOnly", "thc_")
                .option("engine.CompileImmediately", "true")
                .option("engine.BackgroundCompilation", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.TraceCompilation", "true");
        } else {
            builder.option("engine.Compilation", "false");
        }
        try (var context = builder.build()) {
            var library = context.eval(Source.newBuilder("llvm", Path.of(arguments[0]).toFile()).build());
            for (int row = 0; row < expected.size(); ++row) {
                long sum = library.getMember("thc_add").execute((1L << 40) + row, 42L - row).asLong();
                var pointer = library.getMember("thc_alloc").execute(24L);
                if (pointer.isNull()) throw new AssertionError("C allocation failed");
                try {
                    library.getMember("thc_store").execute(pointer, (long)row, (byte)(42 + row));
                    library.getMember("thc_store").execute(pointer, 16L, (byte)(254 - row));
                    var advanced = library.getMember("thc_offset").execute(pointer, (long)row);
                    long first = library.getMember("thc_load").execute(advanced, 0L).asLong();
                    long second = library.getMember("thc_load").execute(pointer, 16L).asLong();
                    String actual = sum + "\t" + first + "\t" + second;
                    if (!actual.equals(expected.get(row)))
                        throw new AssertionError("Native/Sulong mismatch at row " + row + ": " + actual);
                    if (sum != (1L << 40) + 42 || first != 42 + row || second != 254 - row)
                        throw new AssertionError("Unexpected C result at row " + row);
                } finally {
                    // The owner outlives its offset pointers; release only the allocation base.
                    library.getMember("thc_release").execute(pointer);
                }
            }
        } finally {
            Files.writeString(Path.of(arguments[3], arguments[2] + ".log"), log.toString(StandardCharsets.UTF_8));
        }
        if (compiled) requireCompiledFunctions(log.toString(StandardCharsets.UTF_8));
        System.out.println("Sulong " + arguments[2] + ": 8 native-matched scalar/pointer rows");
    }

    private static void requireCompiledFunctions(String log) {
        Map<String, Boolean> installed = new LinkedHashMap<>();
        for (String function : FUNCTIONS) installed.put(function, false);
        var root = Pattern.compile("\\bLLVM:(thc_\\w+)\\s+\\|");
        var ir = Pattern.compile("\\|IR\\s+(\\d+)/");
        for (String line : log.lines().toList()) {
            var name = root.matcher(line);
            if (!name.find() || !installed.containsKey(name.group(1))) continue;
            if (line.contains("opt done") && line.contains("|Tier 2|")) {
                var nodes = ir.matcher(line);
                // First-call compilations may consist only of a deoptimization stub.
                installed.put(name.group(1), nodes.find() && Integer.parseInt(nodes.group(1)) > 5);
            } else if (line.contains("opt deopt") || line.contains("opt failed")) {
                installed.put(name.group(1), false);
            }
        }
        if (installed.containsValue(false)) throw new AssertionError("Missing profiled compiled C functions: " + installed);
    }
}
