// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import thc.Json;

/** Hosted Linux selection checked against a constructor-observed native control. */
@SuppressWarnings("unchecked")
public final class NativeLibraryCaptureTest {
    private static int checks, commands;
    private static Path output;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static String command(String... arguments) throws Exception {
        var log = output.resolve("command-" + commands++ + ".log");
        Files.writeString(log.resolveSibling(log.getFileName() + ".args"), String.join("\n", arguments) + "\n");
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            check(process.waitFor(15, TimeUnit.SECONDS), "Native control timed out: " + log);
            String text = Files.readString(log);
            check(process.exitValue() == 0, "Native control failed: " + log + "\n" + text);
            return text;
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Map<String,Object> companion(Path file) throws Exception {
        var bytes = Files.readAllBytes(file);
        return Map.of("nativeLibrary", Map.of("sha256", digest(bytes), "hex", HexFormat.of().formatHex(bytes)));
    }
    private static Map<String,Object> input(Map<String,Object>... companions) {
        var modules = new ArrayList<Map<String,Object>>();
        for (var companion : companions) modules.add(Map.of("packageNativeLink", companion));
        return Map.of("modules", List.copyOf(modules));
    }
    private static void rejected(Map<String,Object> input, String reason, String name) {
        Path receipt = output.resolve(name + ".json");
        try {
            new NativeLibraryCapture(receipt).capture(input);
            throw new AssertionError("Accepted " + reason);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(reason), expected.toString());
            check(!Files.exists(receipt), "Failed selection must not publish a receipt");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("OUTPUT required");
        if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64"))
            throw new IllegalArgumentException("Native capture regression requires Linux x86-64");
        output = Path.of(args[0]).toAbsolutePath().resolve("native-capture");
        Files.createDirectory(output);
        var compiler = System.getenv().getOrDefault("THC_CLANG", "clang");
        var marker = output.resolve("constructors.txt");
        var markerLiteral = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String recording = "#include <stdio.h>\n#include <stdlib.h>\nstatic void record(const char *value) { FILE *f = fopen(\"" +
            markerLiteral + "\", \"a\"); if (!f) abort(); fputs(value, f); fclose(f); }\n";
        var leafSource = output.resolve("leaf.c"); var rootSource = output.resolve("root.c");
        var leaf = output.resolve("libcapture_leaf.so.1"); var root = output.resolve("libcapture_root.so.1");
        Files.writeString(leafSource, recording + "__attribute__((constructor)) static void initialize(void) { record(\"leaf\\n\"); }\nint capture_leaf(void) { return 40; }\n");
        Files.writeString(rootSource, recording + "extern int capture_leaf(void); static int state;\n__attribute__((constructor)) static void initialize(void) { state = capture_leaf() + 2; record(\"root\\n\"); }\nint capture_root(void) { return state; }\n");
        command(compiler, "-shared", "-fPIC", leafSource.toString(), "-Wl,-soname," + leaf.getFileName(), "-o", leaf.toString());
        command(compiler, "-shared", "-fPIC", rootSource.toString(), "-L" + output, "-l:" + leaf.getFileName(),
            "-Wl,-soname," + root.getFileName(), "-Wl,-rpath,$ORIGIN", "-o", root.toString());
        var source = output.resolve("companion.c"); var nativeCompanion = output.resolve("companion.so");
        Files.writeString(source, "int capture_witness;\n");
        command(compiler, "-shared", "-fPIC", source.toString(), "-Wl,--no-as-needed", "-L" + output,
            "-l:" + root.getFileName(), "-Wl,-rpath," + output, "-o", nativeCompanion.toString());
        var original = companion(nativeCompanion); var originalInput = input(original);
        var receipt = output.resolve("captured.json");
        var captured = new NativeLibraryCapture(receipt).capture(originalInput);
        var module = ((List<Map<String,Object>>) captured.get("modules")).getFirst();
        var library = (Map<String,Object>) ((Map<String,Object>) module.get("packageNativeLink")).get("nativeLibrary");
        var expected = new ArrayList<Map<String,Object>>();
        for (var file : List.of(leaf, root)) {
            var bytes = Files.readAllBytes(file);
            expected.add(Map.of("name", file.getFileName().toString(), "sha256", digest(bytes), "hex", HexFormat.of().formatHex(bytes)));
        }
        check(expected.equals(library.get("bundledLibraries")), "Capture must retain exact provider bytes in dependency order");
        var originalLibrary = (Map<?,?>) original.get("nativeLibrary");
        check(library.get("sha256").equals(originalLibrary.get("sha256")) && library.get("hex").equals(originalLibrary.get("hex")),
            "Companion bytes or identity changed");
        check(!((Map<?,?>) original.get("nativeLibrary")).containsKey("bundledLibraries"), "Capture mutated original metadata");
        var observations = (List<Map<String,Object>>) ((Map<String,Object>) Json.parse(Files.readString(receipt))).get("providers");
        check(observations.stream().map(value -> value.get("path")).toList().equals(List.of(leaf.toRealPath().toString(), root.toRealPath().toString())),
            "Receipt must identify the selected source providers");
        check(!Files.exists(marker), "Capture executed native constructors");

        var origin = output.resolve("origin.so");
        command(compiler, "-shared", "-fPIC", source.toString(), "-Wl,--no-as-needed", "-L" + output,
            "-l:" + root.getFileName(), "-Wl,-rpath,$ORIGIN", "-o", origin.toString());
        rejected(input(companion(origin)), "Native companion requires its original $ORIGIN directory", "origin-rejected");
        var alternative = output.resolve("alternative"); Files.createDirectory(alternative);
        var changed = alternative.resolve("leaf.c");
        Files.writeString(changed, Files.readString(leafSource).replace("return 40;", "return 41;"));
        command(compiler, "-shared", "-fPIC", changed.toString(), "-Wl,-soname," + leaf.getFileName(), "-o", alternative.resolve(leaf.getFileName()).toString());
        Files.copy(root, alternative.resolve(root.getFileName()));
        var conflict = output.resolve("conflict.so");
        command(compiler, "-shared", "-fPIC", source.toString(), "-Wl,--no-as-needed", "-L" + alternative,
            "-l:" + root.getFileName(), "-Wl,-rpath," + alternative, "-o", conflict.toString());
        rejected(input(original, companion(conflict)), "Conflicting native provider: " + leaf.getFileName(), "conflict-rejected");
        check(!Files.exists(marker), "Rejected captures executed native constructors");

        // Prove that the constructor marker was live, rather than an unused fixture.
        var oracleSource = output.resolve("oracle.c"); var oracle = output.resolve("oracle");
        Files.writeString(oracleSource, "#include <stdio.h>\nextern int capture_root(void);\nint main(void) { printf(\"%d\\n\", capture_root()); }\n");
        command(compiler, oracleSource.toString(), "-L" + output, "-l:" + root.getFileName(), "-Wl,-rpath," + output, "-o", oracle.toString());
        check(command(oracle.toString()).equals("42\n"), "Native constructor result differs");
        check(Files.readString(marker).equals("leaf\nroot\n"), "Native control must execute both constructors in dependency order");
        System.out.println("PASS " + checks + " native-library capture checks; constructors execute only in the native control");
    }
}
