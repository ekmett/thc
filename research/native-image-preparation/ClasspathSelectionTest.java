// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs each recipe's actual classpath loop, without compiling the project or building an image. */
public final class ClasspathSelectionTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("REPO OUTPUT required");
        Path repo = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        Path lib = output.resolve("build/install/thc/lib");
        Files.createDirectories(lib);
        var retained = Set.of("thc-0.1-experiment.jar", "polyglot-25.3.4.1.jar",
            "thc-truffle-runtime-25.3.4.1-return1-budget1.jar", "thc-llvm-helper.jar");
        for (String name : retained) Files.createFile(lib.resolve(name));
        for (String name : List.of("llvm-language-25.3.4.1.jar", "llvm-api-25.3.4.1.jar",
                "thc-llvm-language-25.3.4.1-globals1.jar",
                "thc-llvm-language-25.3.4.1-globals1-windows1.jar",
                "antlr4-25.3.4.1.jar", "truffle-nfi-25.3.4.1.jar")) {
            Files.createFile(lib.resolve(name));
        }
        int failures = 0;
        for (String[] recipe : List.of(
                new String[]{"bin/native-image-pure.sh", "image_jar", "image_classpath"},
                new String[]{"research/native-image-preparation/prepared-image.sh", "jar", "classpath"})) {
            String source = Files.readString(repo.resolve(recipe[0]));
            int start = source.indexOf("for " + recipe[1] + " in ");
            int end = source.indexOf("\ndone", start);
            if (start < 0 || end < start) throw new AssertionError("Missing classpath loop: " + recipe[0]);
            // Execute the source loop verbatim; do not duplicate its filename rules.
            String script = "set -euo pipefail\n" + recipe[2] + "=\n"
                + source.substring(start, end + "\ndone".length())
                + "\nprintf '%s' \"$" + recipe[2] + "\"\n";
            var command = new ProcessBuilder("bash", "-c", script).directory(output.toFile());
            command.environment().put("lib_dir", lib.toString());
            command.environment().put("repo_dir", output.toString());
            command.redirectErrorStream(true);
            var process = command.start();
            String selected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            var names = Arrays.stream(selected.split(":"))
                .map(name -> Path.of(name).getFileName().toString()).collect(Collectors.toSet());
            boolean pass = status == 0 && names.equals(retained);
            Files.writeString(output.resolve(recipe[1] + "-classpath.txt"), selected + "\n");
            System.out.println((pass ? "PASS " : "FAIL ") + recipe[0] + ": " + names);
            if (!pass) failures++;
        }
        if (failures != 0) throw new AssertionError(failures + " classpath selections retain unexpected jars");
        System.out.println("PASS both classpath selections; no image builder invoked");
    }
}
